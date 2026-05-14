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

public class GhostNetwork {

    private static final String PROTOCOL = "4";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(SuperbHorizon.MODID, "ghost"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static int nextId = 0;

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

    public static void sendToLevel(Object packet, ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            sendToServerPlayer(packet, player);
        }
    }

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

    public static void sendToPlayer(Object message, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            sendToServerPlayer(message, serverPlayer);
        }
    }

    private static void sendToServerPlayer(Object message, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static class GhostSnapshot {
        public final int entityId;
        public final UUID vehicleId;
        public final String typeKey;
        public final double x, y, z;
        public final float yaw, pitch, roll;
        public final GhostAnimationState animation;

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

    public static class GhostAnimationState {
        public static final GhostAnimationState EMPTY = new GhostAnimationState(
                0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 0,
                0.0F, 0
        );

        public final float absoluteSpeed;
        public final float targetSpeed;
        public final float power;
        public final float turretYaw;
        public final float turretPitch;
        public final float gunYaw;
        public final float gunPitch;
        public final float leftWheelRot;
        public final float rightWheelRot;
        public final float leftTrack;
        public final float rightTrack;
        public final float propellerRot;
        public final float gearRot;
        public final float planeBreak;
        public final int cannonRecoilTime;
        public final float cannonRecoilForce;
        public final int flags;

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

    public static class BatchLoadPacket {
        public final boolean clearFirst;
        public final List<GhostSnapshot> snapshots;

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

    public static class UnloadPacket {
        public final UUID vehicleId;

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
