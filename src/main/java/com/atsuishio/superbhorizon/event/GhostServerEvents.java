package com.atsuishio.superbhorizon.event;

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

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GhostServerEvents {

    private static final Map<ResourceKey<Level>, Map<UUID, VehicleEntity>> ACTIVE_VEHICLES = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<UUID, GhostNetwork.GhostAnimationState>> LAST_ANIMATION_STATES = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof VehicleEntity vehicle)) return;

        ServerLevel serverLevel = (ServerLevel) event.getLevel();
        registerActiveVehicle(serverLevel, vehicle);

        String typeKey = typeKey(vehicle);
        GhostSavedData data = GhostSavedData.get(serverLevel);
        data.vehicleMap.put(vehicle.getUUID(), new GhostSavedData.GhostEntry(
                typeKey, vehicle.getX(), vehicle.getY(), vehicle.getZ(), vehicle.getYRot(), vehicle.getXRot(), vehicle.getRoll()
        ));
        data.setDirty();
        GhostNetwork.GhostAnimationState animation = captureAnimationState(vehicle);
        rememberAnimationState(serverLevel, vehicle.getUUID(), animation);

        GhostNetwork.sendToLevelNear(new GhostNetwork.LoadPacket(
                vehicle.getId(), vehicle.getUUID(), typeKey, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                vehicle.getYRot(), vehicle.getXRot(), vehicle.getRoll(), animation
        ), serverLevel, vehicle.getX(), vehicle.getY(), vehicle.getZ(), SuperbHorizonConfig.MAX_SYNC_DISTANCE.get());
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof VehicleEntity vehicle)) return;

        ServerLevel serverLevel = (ServerLevel) event.getLevel();
        unregisterActiveVehicle(serverLevel, vehicle.getUUID());
        forgetAnimationState(serverLevel, vehicle.getUUID());

        Entity.RemovalReason reason = vehicle.getRemovalReason();
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

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.level.isClientSide()) return;

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

            boolean changed = hasMeaningfulChange(entry, vehicle);
            GhostNetwork.GhostAnimationState animation = captureAnimationState(vehicle);
            boolean animationChanged = hasAnimationChanged(serverLevel, vehicle.getUUID(), animation);
            entry.typeKey = typeKey;
            entry.x = vehicle.getX(); entry.y = vehicle.getY(); entry.z = vehicle.getZ();
            entry.yaw = vehicle.getYRot(); entry.pitch = vehicle.getXRot(); entry.roll = vehicle.getRoll();

            if (changed || animationChanged || shouldHeartbeat) {
                GhostNetwork.sendToLevelNear(new GhostNetwork.TickPacket(
                        vehicle.getId(), vehicle.getUUID(), typeKey, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                        vehicle.getYRot(), vehicle.getXRot(), vehicle.getRoll(), animation
                ), serverLevel, vehicle.getX(), vehicle.getY(), vehicle.getZ(), SuperbHorizonConfig.MAX_SYNC_DISTANCE.get());
                rememberAnimationState(serverLevel, vehicle.getUUID(), animation);
            }

            if ((changed || animationChanged) && shouldSave) {
                data.setDirty();
            }
        }
    }

    private static void syncPlayer(ServerPlayer player) {
        ServerLevel serverLevel = player.serverLevel();
        GhostSavedData data = GhostSavedData.get(serverLevel);
        List<GhostNetwork.GhostSnapshot> snapshots = new ArrayList<>();
        double maxDistance = SuperbHorizonConfig.MAX_SYNC_DISTANCE.get();
        double maxDistanceSq = maxDistance * maxDistance;

        data.vehicleMap.forEach((uuid, entry) -> {
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
