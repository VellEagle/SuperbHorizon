package com.atsuishio.superbhorizon.event;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GhostSavedData extends SavedData {
    // 車両のUUIDをキーにしてデータを保存
    public final Map<UUID, GhostEntry> vehicleMap = new HashMap<>();

    public static class GhostEntry {
        public String typeKey;
        public double x, y, z;
        public float yaw, pitch, roll;

        public GhostEntry(String typeKey, double x, double y, double z, float yaw, float pitch, float roll) {
            this.typeKey = typeKey;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch; this.roll = roll;
        }
    }

    public static GhostSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(GhostSavedData::load, GhostSavedData::new, "superb_ghost_data");
    }

    public static GhostSavedData load(CompoundTag nbt) {
        GhostSavedData data = new GhostSavedData();
        ListTag list = nbt.getList("vehicles", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            data.vehicleMap.put(tag.getUUID("uuid"), new GhostEntry(
                    tag.getString("type"), tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"),
                    tag.getFloat("yaw"), tag.getFloat("pitch"), tag.getFloat("roll")
            ));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        ListTag list = new ListTag();
        vehicleMap.forEach((uuid, entry) -> {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("uuid", uuid);
            tag.putString("type", entry.typeKey);
            tag.putDouble("x", entry.x); tag.putDouble("y", entry.y); tag.putDouble("z", entry.z);

            // 【修正】 setFloat を putFloat に変更
            tag.putFloat("yaw", entry.yaw);
            tag.putFloat("pitch", entry.pitch);
            tag.putFloat("roll", entry.roll);

            list.add(tag);
        });
        nbt.put("vehicles", list);
        return nbt;
    }
}