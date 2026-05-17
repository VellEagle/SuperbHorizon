package com.atsuishio.superbhorizon.network;

import com.atsuishio.superbhorizon.SuperbHorizon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * サーバー・クライアント間のゴースト車両情報のネットワーク通信を統括するクラス。
 * パケットの定義、エンコード、デコード、およびスレッドセーフなパケット処理（ハンドラー）を内包します。
 */
public class GhostNetwork {

    // ネットワークプロトコルのバージョン定義
    private static final String PROTOCOL = "4";

    // パケット配信用チャンネルの生成
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(SuperbHorizon.MODID, "ghost"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static int nextId = 0;

    /**
     * パケット一覧をネットワークチャンネルへ登録します。
     */
    public static void register() {
        CHANNEL.registerMessage(nextId++, LoadPacket.class,
                LoadPacket::encode, LoadPacket::decode, LoadPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, BatchLoadPacket.class,
                BatchLoadPacket::encode, BatchLoadPacket::decode, BatchLoadPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, UnloadPacket.class,
                UnloadPacket::encode, UnloadPacket::decode, UnloadPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, TickPacket.class,
                TickPacket::encode, TickPacket::decode, TickPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, ClearPacket.class,
                ClearPacket::encode, ClearPacket::decode, ClearPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /**
     * 指定ディメンション（ワールドレベル）のすべてのプレイヤーへパケットを一斉送信します。
     */
    public static void sendToLevel(Object packet, ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            sendToServerPlayer(packet, player);
        }
    }

    /**
     * 指定地点から一定距離内にいるプレイヤーに対してのみパケットを送信します（送信トラフィックの削減）。
     */
    public static void sendToLevelNear(Object packet, ServerLevel level, double x, double y, double z, double maxDistance) {
        if (maxDistance <= 0.0D) {
            sendToLevel(packet, level);
            return;
        }

        double maxDistanceSq = maxDistance * maxDistance;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) <= maxDistanceSq) {
                sendToServerPlayer(packet, player);
            }
        }
    }

    /**
     * 特定のプレイヤーへパケットを送信します。
     */
    public static void sendToPlayer(Object message, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            sendToServerPlayer(message, serverPlayer);
        }
    }

    private static void sendToServerPlayer(Object message, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /**
     * 車両の特定時点における位置・角度・アニメーション情報を保持するシリアライズ用スナップショットクラス。
     */
    public static class GhostSnapshot {
        public final int entityId;               // サーバー側の実EntityID
        public final UUID vehicleId;             // 車両の固有UUID
        public final String typeKey;             // 車両の種類 (RegistryName)
        public final double x, y, z;             // 絶対座標
        public final float yaw, pitch, roll;     // 車体の向き角度
        public final GhostAnimationState animation; // 車両の可動パーツのアニメーション情報

        public GhostSnapshot(int entityId, UUID vehicleId, String typeKey,
                             double x, double y, double z,
                             float yaw, float pitch, float roll) {
            this(entityId, vehicleId, typeKey, x, y, z, yaw, pitch, roll, GhostAnimationState.EMPTY);
        }

        public GhostSnapshot(int entityId, UUID vehicleId, String typeKey,
                             double x, double y, double z,
                             float yaw, float pitch, float roll,
                             GhostAnimationState animation) {
            this.entityId = entityId;
            this.vehicleId = vehicleId;
            this.typeKey = typeKey;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.animation = animation;
        }

        private static void encode(GhostSnapshot snapshot, FriendlyByteBuf buf) {
            buf.writeInt(snapshot.entityId);
            buf.writeUUID(snapshot.vehicleId);
            buf.writeUtf(snapshot.typeKey);
            buf.writeDouble(snapshot.x);
            buf.writeDouble(snapshot.y);
            buf.writeDouble(snapshot.z);
            buf.writeFloat(snapshot.yaw);
            buf.writeFloat(snapshot.pitch);
            buf.writeFloat(snapshot.roll);
            GhostAnimationState.encode(snapshot.animation, buf);
        }

        private static GhostSnapshot decode(FriendlyByteBuf buf) {
            return new GhostSnapshot(
                    buf.readInt(), buf.readUUID(), buf.readUtf(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    GhostAnimationState.decode(buf)
            );
        }
    }

    /**
     * 車両の各種可動部分（砲塔、ローター、ギア、主砲リコイル、スピード等）のアニメーションパラメータを束ねたクラス。
     */
    public static class GhostAnimationState {
        // 空のアニメーション定義（静止車両用など）
        public static final GhostAnimationState EMPTY = new GhostAnimationState(
                0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 0,
                0.0F, 0
        );

        public final float absoluteSpeed;     // 車体の実速度
        public final float targetSpeed;       // 車体の目標速度
        public final float power;             // エンジン出力
        public final float turretYaw;         // 砲塔の回転（ヨー）
        public final float turretPitch;       // 砲塔の上下（ピッチ）
        public final float gunYaw;             // 銃身の回転（ヨー）
        public final float gunPitch;           // 銃身の上下（ピッチ）
        public final float leftWheelRot;      // 左タイヤの回転角
        public final float rightWheelRot;     // 右タイヤの回転角
        public final float leftTrack;         // 左クローラー（無限軌道）の移動量
        public final float rightTrack;        // 右クローラー（無限軌道）の移動量
        public final float propellerRot;      // プロペラ/ローターの回転角
        public final float gearRot;           // 着陸ギアの昇降角度
        public final float planeBreak;        // フラップ/ブレードなどの制動変位
        public final int cannonRecoilTime;    // 主砲のリコイル残存時間（Tick）
        public final float cannonRecoilForce; // 主砲のリコイル力
        public final int flags;               // ブーリアン状態フラグのビット列

        public GhostAnimationState(
                float absoluteSpeed,
                float targetSpeed,
                float power,
                float turretYaw,
                float turretPitch,
                float gunYaw,
                float gunPitch,
                float leftWheelRot,
                float rightWheelRot,
                float leftTrack,
                float rightTrack,
                float propellerRot,
                float gearRot,
                float planeBreak,
                int cannonRecoilTime,
                float cannonRecoilForce,
                int flags) {
            this.absoluteSpeed = absoluteSpeed;
            this.targetSpeed = targetSpeed;
            this.power = power;
            this.turretYaw = turretYaw;
            this.turretPitch = turretPitch;
            this.gunYaw = gunYaw;
            this.gunPitch = gunPitch;
            this.leftWheelRot = leftWheelRot;
            this.rightWheelRot = rightWheelRot;
            this.leftTrack = leftTrack;
            this.rightTrack = rightTrack;
            this.propellerRot = propellerRot;
            this.gearRot = gearRot;
            this.planeBreak = planeBreak;
            this.cannonRecoilTime = cannonRecoilTime;
            this.cannonRecoilForce = cannonRecoilForce;
            this.flags = flags;
        }

        // --- フラグビットマスクに基づく状態確認用ヘルパーメソッド群 ---

        public boolean engineRunning() {
            return (flags & 1) != 0;
        }

        public boolean gearUp() {
            return (flags & 2) != 0;
        }

        public boolean firing() {
            return (flags & 4) != 0;
        }

        public boolean hoverMode() {
            return (flags & 8) != 0;
        }

        public boolean wreck() {
            return (flags & 16) != 0;
        }

        public static void encode(GhostAnimationState state, FriendlyByteBuf buf) {
            buf.writeFloat(state.absoluteSpeed);
            buf.writeFloat(state.targetSpeed);
            buf.writeFloat(state.power);
            buf.writeFloat(state.turretYaw);
            buf.writeFloat(state.turretPitch);
            buf.writeFloat(state.gunYaw);
            buf.writeFloat(state.gunPitch);
            buf.writeFloat(state.leftWheelRot);
            buf.writeFloat(state.rightWheelRot);
            buf.writeFloat(state.leftTrack);
            buf.writeFloat(state.rightTrack);
            buf.writeFloat(state.propellerRot);
            buf.writeFloat(state.gearRot);
            buf.writeFloat(state.planeBreak);
            buf.writeVarInt(state.cannonRecoilTime);
            buf.writeFloat(state.cannonRecoilForce);
            buf.writeVarInt(state.flags);
        }

        public static GhostAnimationState decode(FriendlyByteBuf buf) {
            return new GhostAnimationState(
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readVarInt(), buf.readFloat(), buf.readVarInt()
            );
        }
    }

    /**
     * クライアント側のゴーストキャッシュを一斉クリアするためのパケット。
     */
    public static class ClearPacket {
        public static void encode(ClearPacket pkt, FriendlyByteBuf buf) {
        }

        public static ClearPacket decode(FriendlyByteBuf buf) {
            return new ClearPacket();
        }

        public static void handle(ClearPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.atsuishio.superbhorizon.client.renderer.VehicleGhostRenderer.clearSnapshots()
                    )
            );
        }
    }

    /**
     * クライアント側に特定のゴースト車両を新規に「ロード」させるパケット。
     */
    public static class LoadPacket extends GhostSnapshot {
        public LoadPacket(int entityId, UUID vehicleId, String typeKey,
                          double x, double y, double z,
                          float yaw, float pitch, float roll) {
            super(entityId, vehicleId, typeKey, x, y, z, yaw, pitch, roll);
        }

        public LoadPacket(int entityId, UUID vehicleId, String typeKey,
                          double x, double y, double z,
                          float yaw, float pitch, float roll,
                          GhostAnimationState animation) {
            super(entityId, vehicleId, typeKey, x, y, z, yaw, pitch, roll, animation);
        }

        public static void encode(LoadPacket pkt, FriendlyByteBuf buf) {
            GhostSnapshot.encode(pkt, buf);
        }

        public static LoadPacket decode(FriendlyByteBuf buf) {
            GhostSnapshot snapshot = GhostSnapshot.decode(buf);
            return new LoadPacket(snapshot.entityId, snapshot.vehicleId, snapshot.typeKey,
                    snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch, snapshot.roll, snapshot.animation);
        }

        public static void handle(LoadPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.atsuishio.superbhorizon.client.renderer.VehicleGhostRenderer.onLoad(pkt)
                    )
            );
        }
    }

    /**
     * ディメンション移動時などに、現在ディメンション内のゴースト車両群を一斉に再ロードさせる一括同期パケット。
     */
    public static class BatchLoadPacket {
        public final boolean clearFirst;            // 読込前に既存キャッシュをクリアするか
        public final List<GhostSnapshot> snapshots; // ロードする全車両のデータリスト

        public BatchLoadPacket(boolean clearFirst, List<GhostSnapshot> snapshots) {
            this.clearFirst = clearFirst;
            this.snapshots = snapshots;
        }

        public static void encode(BatchLoadPacket pkt, FriendlyByteBuf buf) {
            buf.writeBoolean(pkt.clearFirst);
            buf.writeVarInt(pkt.snapshots.size());
            for (GhostSnapshot snapshot : pkt.snapshots) {
                GhostSnapshot.encode(snapshot, buf);
            }
        }

        public static BatchLoadPacket decode(FriendlyByteBuf buf) {
            boolean clearFirst = buf.readBoolean();
            int size = buf.readVarInt();
            List<GhostSnapshot> snapshots = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                snapshots.add(GhostSnapshot.decode(buf));
            }
            return new BatchLoadPacket(clearFirst, snapshots);
        }

        public static void handle(BatchLoadPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.atsuishio.superbhorizon.client.renderer.VehicleGhostRenderer.onBatchLoad(pkt)
                    )
            );
        }
    }

    /**
     * クライアント側に特定のゴースト車両を「アンロード（消去）」させるためのパケット。
     */
    public static class UnloadPacket {
        public final UUID vehicleId; // 消去対象の車両UUID

        public UnloadPacket(UUID vehicleId) {
            this.vehicleId = vehicleId;
        }

        public static void encode(UnloadPacket pkt, FriendlyByteBuf buf) {
            buf.writeUUID(pkt.vehicleId);
        }

        public static UnloadPacket decode(FriendlyByteBuf buf) {
            return new UnloadPacket(buf.readUUID());
        }

        public static void handle(UnloadPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.atsuishio.superbhorizon.client.renderer.VehicleGhostRenderer.onUnload(pkt.vehicleId)
                    )
            );
        }
    }

    /**
     * 稼働中のゴースト車両の位置、向き、可動パーツアニメーション状態を更新するための定期Tickパケット。
     */
    public static class TickPacket extends GhostSnapshot {
        public TickPacket(int entityId, UUID vehicleId, String typeKey,
                          double x, double y, double z,
                          float yaw, float pitch, float roll) {
            super(entityId, vehicleId, typeKey, x, y, z, yaw, pitch, roll);
        }

        public TickPacket(int entityId, UUID vehicleId, String typeKey,
                          double x, double y, double z,
                          float yaw, float pitch, float roll,
                          GhostAnimationState animation) {
            super(entityId, vehicleId, typeKey, x, y, z, yaw, pitch, roll, animation);
        }

        public static void encode(TickPacket pkt, FriendlyByteBuf buf) {
            GhostSnapshot.encode(pkt, buf);
        }

        public static TickPacket decode(FriendlyByteBuf buf) {
            GhostSnapshot snapshot = GhostSnapshot.decode(buf);
            return new TickPacket(snapshot.entityId, snapshot.vehicleId, snapshot.typeKey,
                    snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch, snapshot.roll, snapshot.animation);
        }

        public static void handle(TickPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            com.atsuishio.superbhorizon.client.renderer.VehicleGhostRenderer.onTick(pkt)
                    )
            );
        }
    }
}
