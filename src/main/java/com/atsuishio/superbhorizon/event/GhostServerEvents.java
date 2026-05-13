package com.atsuishio.superbhorizon.event;

import com.atsuishio.superbhorizon.network.GhostNetwork;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GhostServerEvents {

    private static final int TICK_INTERVAL = 1;

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity().level() instanceof ServerLevel serverLevel) {
            GhostSavedData data = GhostSavedData.get(serverLevel);
            data.vehicleMap.forEach((uuid, entry) -> {
                // ロード前なのでエンティティIDはダミー（-1）を送る
                GhostNetwork.sendToPlayer(new GhostNetwork.LoadPacket(
                        -1, uuid, entry.typeKey, entry.x, entry.y, entry.z, entry.yaw, entry.pitch, entry.roll
                ), event.getEntity());
            });
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof VehicleEntity vehicle)) return;

        String typeKey = EntityType.getKey(vehicle.getType()).toString();
        GhostSavedData data = GhostSavedData.get((ServerLevel) event.getLevel());
        data.vehicleMap.put(vehicle.getUUID(), new GhostSavedData.GhostEntry(
                typeKey, vehicle.getX(), vehicle.getY(), vehicle.getZ(), vehicle.getYRot(), vehicle.getXRot(), vehicle.getRoll()
        ));
        data.setDirty();

        GhostNetwork.sendToAll(new GhostNetwork.LoadPacket(
                vehicle.getId(), vehicle.getUUID(), typeKey, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                vehicle.getYRot(), vehicle.getXRot(), vehicle.getRoll()
        ));
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof VehicleEntity vehicle)) return;

        Entity.RemovalReason reason = vehicle.getRemovalReason();
        if (reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED) {
            GhostSavedData data = GhostSavedData.get((ServerLevel) event.getLevel());
            data.vehicleMap.remove(vehicle.getUUID());
            data.setDirty();

            GhostNetwork.sendToAll(new GhostNetwork.UnloadPacket(vehicle.getUUID()));
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.level.isClientSide() || event.level.getGameTime() % TICK_INTERVAL != 0) return;

        ServerLevel serverLevel = (ServerLevel) event.level;
        GhostSavedData data = GhostSavedData.get(serverLevel);

        serverLevel.getAllEntities().forEach(entity -> {
            if (entity instanceof VehicleEntity vehicle) {
                GhostSavedData.GhostEntry entry = data.vehicleMap.get(vehicle.getUUID());
                if (entry != null) {
                    entry.x = vehicle.getX(); entry.y = vehicle.getY(); entry.z = vehicle.getZ();
                    entry.yaw = vehicle.getYRot(); entry.pitch = vehicle.getXRot(); entry.roll = vehicle.getRoll();
                    data.setDirty();
                }

                GhostNetwork.sendToAll(new GhostNetwork.TickPacket(
                        vehicle.getId(), vehicle.getUUID(), vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                        vehicle.getYRot(), vehicle.getXRot(), vehicle.getRoll()
                ));
            }
        });
    }
}