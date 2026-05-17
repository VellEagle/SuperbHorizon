package com.atsuishio.superbhorizon.event;

import com.atsuishio.superbhorizon.SuperbHorizon;
import com.atsuishio.superbhorizon.SuperbHorizonConfig;
import com.atsuishio.superbhorizon.network.GhostNetwork;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * サーバー側でビークルの発生・消失・移動を監視し、付近のクライアントへ
 * 状態同期パケットを配信するイベントリスナークラス。
 */
@Mod.EventBusSubscriber(modid = SuperbHorizon.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GhostServerEvents {

    // ディメンションごとに、現在ワールドに実在してアクティブに動いているビークル実体の一覧を管理します
    private static final Map<ResourceKey<Level>, Map<UUID, VehicleEntity>> ACTIVE_VEHICLES = new HashMap<>();
    
    // 直前のTickにおいて各クライアントに送信したアニメーション状態のキャッシュ（冗長なパケット送信を抑止するため）
    private static final Map<ResourceKey<Level>, Map<UUID, GhostNetwork.GhostAnimationState>> LAST_ANIMATION_STATES = new HashMap<>();

    /**
     * プレイヤーがサーバーにログインした際、すでに存在するすべてのゴースト車両データを一括同期します。
     */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncPlayer(player);
        }
    }

    /**
     * プレイヤーが別のディメンション（ネザーやエンドなど）にテレポートした際、
     * 移動先のディメンションに存在するゴースト車両データを再同期します。
     */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncPlayer(player);
        }
    }

    /**
     * プレイヤーが死亡してリスポーンした際、周辺のゴースト車両の状態を再送信します。
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncPlayer(player);
        }
    }

    /**
     * サーバーワールドに車両エンティティ（VehicleEntity）がスポーンまたはロードされた際に呼び出されます。
     * アクティブリストに車両を追加し、付近のクライアントへロード用パケットを即座にブロードキャストします。
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof VehicleEntity vehicle)) return;

        ServerLevel serverLevel = (ServerLevel) event.getLevel();
        registerActiveVehicle(serverLevel, vehicle);

        String typeKey = typeKey(vehicle);
        GhostSavedData data = GhostSavedData.get(serverLevel);
        
        // ワールドの永続化データに新規登録
        data.vehicleMap.put(vehicle.getUUID(), new GhostSavedData.GhostEntry(
                typeKey, vehicle.getX(), vehicle.getY(), vehicle.getZ(), vehicle.getYRot(), vehicle.getXRot(), vehicle.getRoll()
        ));
        data.setDirty();
        
        // 現在のアニメーション状態を取得・記録
        GhostNetwork.GhostAnimationState animation = captureAnimationState(vehicle);
        rememberAnimationState(serverLevel, vehicle.getUUID(), animation);

        // 周辺のプレイヤーへ新車両ロードを通知
        GhostNetwork.sendToLevelNear(new GhostNetwork.LoadPacket(
                vehicle.getId(), vehicle.getUUID(), typeKey, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                vehicle.getYRot(), vehicle.getXRot(), vehicle.getRoll(), animation
        ), serverLevel, vehicle.getX(), vehicle.getY(), vehicle.getZ(), SuperbHorizonConfig.MAX_SYNC_DISTANCE.get());
    }

    /**
     * 車両エンティティがワールドから離脱（破壊、デスポーン、アンロード）した際に呼び出されます。
     * 実体が完全にゲームから削除（破壊・破棄）された場合は、ゴーストデータも削除しアンロード用パケットを通知します。
     */
    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof VehicleEntity vehicle)) return;

        ServerLevel serverLevel = (ServerLevel) event.getLevel();
        unregisterActiveVehicle(serverLevel, vehicle.getUUID());
        forgetAnimationState(serverLevel, vehicle.getUUID());

        Entity.RemovalReason reason = vehicle.getRemovalReason();
        // 車両が完全に破壊または破棄された場合のみデータを永続リストから削除し、ゴーストも消去します
        if (reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED) {
            GhostSavedData data = GhostSavedData.get(serverLevel);
            data.vehicleMap.remove(vehicle.getUUID());
            data.setDirty();

            GhostNetwork.sendToLevelNear(
                    new GhostNetwork.UnloadPacket(vehicle.getUUID()),
                    serverLevel,
                    vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                    SuperbHorizonConfig.MAX_SYNC_DISTANCE.get()
            );
        }
    }

    /**
     * サーバーワールドがティック進行するたびに呼び出されます。
     * 設定された TICK_INTERVAL 周期ごとに稼働中のすべての車両の座標・回転・アニメーションに「意味のある変化」があるかを走査し、
     * 変化があった場合または定期ハートビート時に最新の状態パケットを配信します。
     */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.level.isClientSide()) return;

        // 同期Tick周期チェック
        int tickInterval = SuperbHorizonConfig.TICK_INTERVAL.get();
        if (event.level.getGameTime() % tickInterval != 0) return;

        ServerLevel serverLevel = (ServerLevel) event.level;
        Map<UUID, VehicleEntity> active = ACTIVE_VEHICLES.get(serverLevel.dimension());
        if (active == null || active.isEmpty()) return;

        GhostSavedData data = GhostSavedData.get(serverLevel);
        boolean shouldSave = event.level.getGameTime() % SuperbHorizonConfig.SAVE_INTERVAL.get() == 0;
        int staleTicks = SuperbHorizonConfig.STALE_TICKS.get();
        int heartbeatInterval = staleTicks > 0 ? Math.max(SuperbHorizonConfig.TICK_INTERVAL.get(), staleTicks / 2) : 0;
        boolean shouldHeartbeat = heartbeatInterval > 0 && event.level.getGameTime() % heartbeatInterval == 0;

        Iterator<Map.Entry<UUID, VehicleEntity>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, VehicleEntity> activeEntry = iterator.next();
            VehicleEntity vehicle = activeEntry.getValue();
            
            // 使用不可能（削除済みなど）な実体はリストから除外
            if (!isUsableVehicle(serverLevel, vehicle)) {
                iterator.remove();
                continue;
            }

            String typeKey = typeKey(vehicle);
            GhostSavedData.GhostEntry entry = data.vehicleMap.get(vehicle.getUUID());
            if (entry == null) {
                entry = new GhostSavedData.GhostEntry(typeKey, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                        vehicle.getYRot(), vehicle.getXRot(), vehicle.getRoll());
                data.vehicleMap.put(vehicle.getUUID(), entry);
                data.setDirty();
            }

            // 位置・角度に目立った変更があるか検証
            boolean changed = hasMeaningfulChange(entry, vehicle);
            
            // アニメーション状態をキャプチャし変化があるか検証
            GhostNetwork.GhostAnimationState animation = captureAnimationState(vehicle);
            boolean animationChanged = hasAnimationChanged(serverLevel, vehicle.getUUID(), animation);
            
            // データを最新にアップデート
            entry.typeKey = typeKey;
            entry.x = vehicle.getX(); entry.y = vehicle.getY(); entry.z = vehicle.getZ();
            entry.yaw = vehicle.getYRot(); entry.pitch = vehicle.getXRot(); entry.roll = vehicle.getRoll();

            // 変化が発生したか、定期ハートビートのタイミングであればクライアントにTickUpdateパケットを送信
            if (changed || animationChanged || shouldHeartbeat) {
                GhostNetwork.sendToLevelNear(new GhostNetwork.TickPacket(
                        vehicle.getId(), vehicle.getUUID(), typeKey, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                        vehicle.getYRot(), vehicle.getXRot(), vehicle.getRoll(), animation
                ), serverLevel, vehicle.getX(), vehicle.getY(), vehicle.getZ(), SuperbHorizonConfig.MAX_SYNC_DISTANCE.get());
                rememberAnimationState(serverLevel, vehicle.getUUID(), animation);
            }

            // 変更があり、かつ保存周期であれば保存フラグを汚す
            if ((changed || animationChanged) && shouldSave) {
                data.setDirty();
            }
        }
    }

    /**
     * 新しくスポーン/ログインしたプレイヤーに対し、周囲にあるゴースト車両を一括初期化送信（BatchLoadPacket）します。
     */
    private static void syncPlayer(ServerPlayer player) {
        ServerLevel serverLevel = player.serverLevel();
        GhostSavedData data = GhostSavedData.get(serverLevel);
        List<GhostNetwork.GhostSnapshot> snapshots = new ArrayList<>();
        double maxDistance = SuperbHorizonConfig.MAX_SYNC_DISTANCE.get();
        double maxDistanceSq = maxDistance * maxDistance;

        data.vehicleMap.forEach((uuid, entry) -> {
            // 最大同期距離フィルター
            if (maxDistance > 0.0D && player.distanceToSqr(entry.x, entry.y, entry.z) > maxDistanceSq) {
                return;
            }

            VehicleEntity active = activeVehicle(serverLevel, uuid);
            int entityId = active != null ? active.getId() : -1;
            GhostNetwork.GhostAnimationState animation = active != null
                    ? captureAnimationState(active)
                    : GhostNetwork.GhostAnimationState.EMPTY;
            snapshots.add(new GhostNetwork.GhostSnapshot(
                    entityId, uuid, entry.typeKey, entry.x, entry.y, entry.z, entry.yaw, entry.pitch, entry.roll, animation
            ));
        });

        // プレイヤーにバッチロードメッセージを送信
        GhostNetwork.sendToPlayer(new GhostNetwork.BatchLoadPacket(true, snapshots), player);
    }

    private static void registerActiveVehicle(ServerLevel level, VehicleEntity vehicle) {
        ACTIVE_VEHICLES.computeIfAbsent(level.dimension(), key -> new HashMap<>()).put(vehicle.getUUID(), vehicle);
    }

    private static void unregisterActiveVehicle(ServerLevel level, UUID uuid) {
        Map<UUID, VehicleEntity> active = ACTIVE_VEHICLES.get(level.dimension());
        if (active != null) {
            active.remove(uuid);
            if (active.isEmpty()) {
                ACTIVE_VEHICLES.remove(level.dimension());
            }
        }
    }

    private static void rememberAnimationState(ServerLevel level, UUID uuid, GhostNetwork.GhostAnimationState state) {
        LAST_ANIMATION_STATES.computeIfAbsent(level.dimension(), key -> new HashMap<>()).put(uuid, state);
    }

    private static void forgetAnimationState(ServerLevel level, UUID uuid) {
        Map<UUID, GhostNetwork.GhostAnimationState> states = LAST_ANIMATION_STATES.get(level.dimension());
        if (states != null) {
            states.remove(uuid);
            if (states.isEmpty()) {
                LAST_ANIMATION_STATES.remove(level.dimension());
            }
        }
    }

    private static VehicleEntity activeVehicle(ServerLevel level, UUID uuid) {
        Map<UUID, VehicleEntity> active = ACTIVE_VEHICLES.get(level.dimension());
        return active != null ? active.get(uuid) : null;
    }

    private static boolean isUsableVehicle(ServerLevel level, VehicleEntity vehicle) {
        return vehicle != null
                && !vehicle.isRemoved()
                && vehicle.level() == level
                && vehicle.getRemovalReason() == null;
    }

    /**
     * 前回の記録データから、有意な位置または回転の変化（しきい値超え）が発生したかを判定します。
     */
    private static boolean hasMeaningfulChange(GhostSavedData.GhostEntry entry, VehicleEntity vehicle) {
        double positionEpsilonSq = SuperbHorizonConfig.POSITION_EPSILON.get() * SuperbHorizonConfig.POSITION_EPSILON.get();
        double dx = entry.x - vehicle.getX();
        double dy = entry.y - vehicle.getY();
        double dz = entry.z - vehicle.getZ();
        if (dx * dx + dy * dy + dz * dz > positionEpsilonSq) return true;

        double rotationEpsilon = SuperbHorizonConfig.ROTATION_EPSILON.get();
        return Math.abs(Mth.wrapDegrees(entry.yaw - vehicle.getYRot())) > rotationEpsilon
                || Math.abs(Mth.wrapDegrees(entry.pitch - vehicle.getXRot())) > rotationEpsilon
                || Math.abs(Mth.wrapDegrees(entry.roll - vehicle.getRoll())) > rotationEpsilon;
    }

    /**
     * 直前の同期タイミングから、アニメーションのプロパティ（速度、タイヤ回転、主砲角度など）に有意な変化が発生したかを検証します。
     */
    private static boolean hasAnimationChanged(ServerLevel level, UUID uuid, GhostNetwork.GhostAnimationState current) {
        Map<UUID, GhostNetwork.GhostAnimationState> states = LAST_ANIMATION_STATES.get(level.dimension());
        GhostNetwork.GhostAnimationState previous = states != null ? states.get(uuid) : null;
        if (previous == null) return true;

        double epsilon = SuperbHorizonConfig.ROTATION_EPSILON.get();
        return Math.abs(previous.absoluteSpeed - current.absoluteSpeed) > 0.01D
                || Math.abs(previous.targetSpeed - current.targetSpeed) > 0.01D
                || Math.abs(previous.power - current.power) > 0.01D
                || Math.abs(Mth.wrapDegrees(previous.turretYaw - current.turretYaw)) > epsilon
                || Math.abs(Mth.wrapDegrees(previous.turretPitch - current.turretPitch)) > epsilon
                || Math.abs(Mth.wrapDegrees(previous.gunYaw - current.gunYaw)) > epsilon
                || Math.abs(Mth.wrapDegrees(previous.gunPitch - current.gunPitch)) > epsilon
                || Math.abs(Mth.wrapDegrees(previous.leftWheelRot - current.leftWheelRot)) > epsilon
                || Math.abs(Mth.wrapDegrees(previous.rightWheelRot - current.rightWheelRot)) > epsilon
                || Math.abs(Mth.wrapDegrees(previous.leftTrack - current.leftTrack)) > epsilon
                || Math.abs(Mth.wrapDegrees(previous.rightTrack - current.rightTrack)) > epsilon
                || Math.abs(Mth.wrapDegrees(previous.propellerRot - current.propellerRot)) > epsilon
                || Math.abs(Mth.wrapDegrees(previous.gearRot - current.gearRot)) > epsilon
                || Math.abs(previous.planeBreak - current.planeBreak) > 0.01D
                || previous.cannonRecoilTime != current.cannonRecoilTime
                || Math.abs(previous.cannonRecoilForce - current.cannonRecoilForce) > 0.01D
                || previous.flags != current.flags;
    }

    /**
     * 車両エンティティから、現在の駆動スピード、アニメーションパラメータ、状態フラグを一括で抽出・キャプチャします。
     */
    private static GhostNetwork.GhostAnimationState captureAnimationState(VehicleEntity vehicle) {
        int flags = 0;
        if (vehicle.engineRunning()) flags |= 1;
        if (vehicle.getGearUp()) flags |= 2;
        if (vehicle.isFiring()) flags |= 4;
        if (vehicle.getHoverMode()) flags |= 8;
        if (vehicle.isWreck()) flags |= 16;

        return new GhostNetwork.GhostAnimationState(
                (float) vehicle.getAbsoluteSpeed(),
                (float) vehicle.getTargetSpeed(),
                vehicle.getPower(),
                vehicle.getTurretYRot(),
                vehicle.getTurretXRot(),
                vehicle.getGunYRot(),
                vehicle.getGunXRot(),
                vehicle.getLeftWheelRot(),
                vehicle.getRightWheelRot(),
                vehicle.getLeftTrack(),
                vehicle.getRightTrack(),
                vehicle.getPropellerRot(),
                vehicle.getSynchedGearRot(),
                vehicle.getPlaneBreak(),
                vehicle.getCannonRecoilTime(),
                vehicle.getCannonRecoilForce(),
                flags
        );
    }

    private static String typeKey(VehicleEntity vehicle) {
        return EntityType.getKey(vehicle.getType()).toString();
    }
}
