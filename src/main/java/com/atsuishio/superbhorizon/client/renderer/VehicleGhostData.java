package com.atsuishio.superbhorizon.client.renderer;

import com.atsuishio.superbhorizon.SuperbHorizonConfig;
import com.atsuishio.superbhorizon.network.GhostNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.UUID;

public class VehicleGhostData {
    public int entityId;
    public final UUID vehicleId;
    public final String typeKey;
    public double x, y, z;
    public double prevX, prevY, prevZ;
    public float yaw, pitch, roll;
    public float prevYaw, prevPitch, prevRoll;
    public GhostNetwork.GhostAnimationState animation;
    public GhostNetwork.GhostAnimationState prevAnimation;
    public long lastUpdateGameTime;

    public VehicleGhostData(int entityId, UUID vehicleId, String typeKey, double x, double y, double z, float yaw, float pitch, float roll) {
        this(entityId, vehicleId, typeKey, x, y, z, yaw, pitch, roll, GhostNetwork.GhostAnimationState.EMPTY);
    }

    public VehicleGhostData(int entityId, UUID vehicleId, String typeKey, double x, double y, double z, float yaw, float pitch, float roll,
                            GhostNetwork.GhostAnimationState animation) {
        this.entityId = entityId;
        this.vehicleId = vehicleId;
        this.typeKey = typeKey;
        this.x = this.prevX = x;
        this.y = this.prevY = y;
        this.z = this.prevZ = z;
        this.yaw = this.prevYaw = yaw;
        this.pitch = this.prevPitch = pitch;
        this.roll = this.prevRoll = roll;
        this.animation = animation;
        this.prevAnimation = animation;
        this.lastUpdateGameTime = currentGameTime();
    }

    public void update(int entityId, double x, double y, double z, float yaw, float pitch, float roll) {
        update(entityId, x, y, z, yaw, pitch, roll, GhostNetwork.GhostAnimationState.EMPTY);
    }

    public void update(int entityId, double x, double y, double z, float yaw, float pitch, float roll,
                       GhostNetwork.GhostAnimationState animation) {
        this.entityId = entityId;
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;
        this.prevYaw = this.yaw;
        this.prevPitch = this.pitch;
        this.prevRoll = this.roll;
        this.prevAnimation = this.animation;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.animation = animation;
        this.lastUpdateGameTime = currentGameTime();
    }

    public boolean hasAnimationState() {
        return animation != null && animation != GhostNetwork.GhostAnimationState.EMPTY;
    }

    public double renderX(float partialTick) {
        return Mth.lerp(renderAlpha(partialTick), prevX, x);
    }

    public double renderY(float partialTick) {
        return Mth.lerp(renderAlpha(partialTick), prevY, y);
    }

    public double renderZ(float partialTick) {
        return Mth.lerp(renderAlpha(partialTick), prevZ, z);
    }

    public float renderYaw(float partialTick) {
        return Mth.rotLerp(renderAlpha(partialTick), prevYaw, yaw);
    }

    public float renderPitch(float partialTick) {
        return Mth.rotLerp(renderAlpha(partialTick), prevPitch, pitch);
    }

    public float renderRoll(float partialTick) {
        return Mth.rotLerp(renderAlpha(partialTick), prevRoll, roll);
    }

    public float animAbsoluteSpeed(float partialTick) {
        return lerpAnimation(partialTick, prevAnimation.absoluteSpeed, animation.absoluteSpeed);
    }

    public float animTargetSpeed(float partialTick) {
        return lerpAnimation(partialTick, prevAnimation.targetSpeed, animation.targetSpeed);
    }

    public float animPower(float partialTick) {
        return lerpAnimation(partialTick, prevAnimation.power, animation.power);
    }

    public float animTurretYaw(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.turretYaw, animation.turretYaw);
    }

    public float animTurretPitch(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.turretPitch, animation.turretPitch);
    }

    public float animGunYaw(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.gunYaw, animation.gunYaw);
    }

    public float animGunPitch(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.gunPitch, animation.gunPitch);
    }

    public float animLeftWheelRot(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.leftWheelRot, animation.leftWheelRot);
    }

    public float animRightWheelRot(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.rightWheelRot, animation.rightWheelRot);
    }

    public float animLeftTrack(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.leftTrack, animation.leftTrack);
    }

    public float animRightTrack(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.rightTrack, animation.rightTrack);
    }

    public float animPropellerRot(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.propellerRot, animation.propellerRot);
    }

    public float animGearRot(float partialTick) {
        return rotLerpAnimation(partialTick, prevAnimation.gearRot, animation.gearRot);
    }

    public float animPlaneBreak(float partialTick) {
        return lerpAnimation(partialTick, prevAnimation.planeBreak, animation.planeBreak);
    }

    public float animCannonRecoilForce(float partialTick) {
        return lerpAnimation(partialTick, prevAnimation.cannonRecoilForce, animation.cannonRecoilForce);
    }

    public int animCannonRecoilTime() {
        return animation.cannonRecoilTime;
    }

    public boolean isStale() {
        int staleTicks = SuperbHorizonConfig.STALE_TICKS.get();
        if (staleTicks <= 0) return false;

        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getGameTime() - lastUpdateGameTime > staleTicks;
    }

    public ResourceLocation textureLocation() {
        String path = pathFromTypeKey();
        return ResourceLocation.fromNamespaceAndPath("superb_modern_combat", "textures/entity/" + path + ".png");
    }

    public ResourceLocation polyMeshLocation() {
        String path = pathFromTypeKey();
        return ResourceLocation.fromNamespaceAndPath(namespaceFromTypeKey(), "custom_geo/" + path + ".geo.json");
    }

    private float renderAlpha(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 1.0F;

        float elapsed = (mc.level.getGameTime() - lastUpdateGameTime) + partialTick;
        return Mth.clamp(elapsed / SuperbHorizonConfig.TICK_INTERVAL.get().floatValue(), 0.0F, 1.0F);
    }

    private long currentGameTime() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.getGameTime() : 0L;
    }

    private float lerpAnimation(float partialTick, float prev, float current) {
        return Mth.lerp(renderAlpha(partialTick), prev, current);
    }

    private float rotLerpAnimation(float partialTick, float prev, float current) {
        return Mth.rotLerp(renderAlpha(partialTick), prev, current);
    }

    private String namespaceFromTypeKey() {
        return typeKey.contains(":") ? typeKey.substring(0, typeKey.indexOf(':')) : "minecraft";
    }

    private String pathFromTypeKey() {
        return typeKey.contains(":") ? typeKey.substring(typeKey.indexOf(':') + 1) : typeKey;
    }
}
