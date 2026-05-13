package com.atsuishio.superbhorizon.client.renderer;

import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public class VehicleGhostData {
    public int entityId; // 随時更新されるためfinalを外す
    public final UUID vehicleId;
    public final String typeKey;
    public double x, y, z;
    public float yaw, pitch, roll;

    public VehicleGhostData(int entityId, UUID vehicleId, String typeKey, double x, double y, double z, float yaw, float pitch, float roll) {
        this.entityId = entityId;
        this.vehicleId = vehicleId;
        this.typeKey = typeKey;
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw; this.pitch = pitch; this.roll = roll;
    }

    public ResourceLocation textureLocation() {
        String path = typeKey.contains(":") ? typeKey.substring(typeKey.indexOf(':') + 1) : typeKey;
        return new ResourceLocation("superb_modern_combat", "textures/entity/" + path + ".png");
    }

    public ResourceLocation polyMeshLocation() {
        String path = typeKey.contains(":") ? typeKey.substring(typeKey.indexOf(':') + 1) : typeKey;
        return new ResourceLocation("superb_modern_combat", "custom_geo/" + path + ".geo.json");
    }
}