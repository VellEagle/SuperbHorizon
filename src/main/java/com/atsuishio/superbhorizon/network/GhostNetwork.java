package com.atsuishio.superbhorizon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public class GhostNetwork {

    private static final String PROTOCOL = "1";

    @SuppressWarnings("deprecation")
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("superbhorizon", "ghost"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static int nextId = 0;

    public static void register() {
        CHANNEL.registerMessage(nextId++, LoadPacket.class,
                LoadPacket::encode, LoadPacket::decode, LoadPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, UnloadPacket.class,
                UnloadPacket::encode, UnloadPacket::decode, UnloadPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, TickPacket.class,
                TickPacket::encode, TickPacket::decode, TickPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendToAll(Object packet) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
    }

    public static void sendToPlayer(Object message, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), message);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  LoadPacket
    // ─────────────────────────────────────────────────────────────────────
    public static class LoadPacket {
        public final int entityId;
        public final UUID vehicleId;
        public final String typeKey;
        public final double x, y, z;
        public final float yaw, pitch, roll;

        public LoadPacket(int entityId, UUID vehicleId, String typeKey,
                          double x, double y, double z,
                          float yaw, float pitch, float roll) {
            this.entityId = entityId;
            this.vehicleId = vehicleId;
            this.typeKey  = typeKey;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch; this.roll = roll;
        }

        public static void encode(LoadPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.entityId);
            buf.writeUUID(pkt.vehicleId);
            buf.writeUtf(pkt.typeKey);
            buf.writeDouble(pkt.x); buf.writeDouble(pkt.y); buf.writeDouble(pkt.z);
            buf.writeFloat(pkt.yaw); buf.writeFloat(pkt.pitch); buf.writeFloat(pkt.roll);
        }

        public static LoadPacket decode(FriendlyByteBuf buf) {
            return new LoadPacket(
                    buf.readInt(), buf.readUUID(), buf.readUtf(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat()
            );
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

    // ─────────────────────────────────────────────────────────────────────
    //  UnloadPacket (UUIDのみ)
    // ─────────────────────────────────────────────────────────────────────
    public static class UnloadPacket {
        public final UUID vehicleId;

        public UnloadPacket(UUID vehicleId) { this.vehicleId = vehicleId; }

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

    // ─────────────────────────────────────────────────────────────────────
    //  TickPacket
    // ─────────────────────────────────────────────────────────────────────
    public static class TickPacket {
        public final int entityId;
        public final UUID vehicleId;
        public final double x, y, z;
        public final float yaw, pitch, roll;

        public TickPacket(int entityId, UUID vehicleId,
                          double x, double y, double z,
                          float yaw, float pitch, float roll) {
            this.entityId = entityId;
            this.vehicleId = vehicleId;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch; this.roll = roll;
        }

        public static void encode(TickPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.entityId);
            buf.writeUUID(pkt.vehicleId);
            buf.writeDouble(pkt.x); buf.writeDouble(pkt.y); buf.writeDouble(pkt.z);
            buf.writeFloat(pkt.yaw); buf.writeFloat(pkt.pitch); buf.writeFloat(pkt.roll);
        }

        public static TickPacket decode(FriendlyByteBuf buf) {
            return new TickPacket(
                    buf.readInt(), buf.readUUID(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat()
            );
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