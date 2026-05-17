package com.atsuishio.superbhorizon.event;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * サーバー側でワールド（ディメンション）ごとにゴースト車両データを永続化保存するためのクラス。
 * ワールド保存時にNBTタグにデータを書き出し、次回起動時にそのデータを復元します。
 */
public class GhostSavedData extends SavedData {
    
    // 車両の個別UUIDをキーにして、車両の位置・姿勢の永続化エントリーを保持します
    public final Map<UUID, GhostEntry> vehicleMap = new HashMap<>();

    /**
     * 各ゴースト車両の永続化される情報のコンテナ。
     */
    public static class GhostEntry {
        public String typeKey; // 車両のエンティティ種別（RegistryName）
        public double x, y, z; // 座標位置
        public float yaw, pitch, roll; // 回転姿勢 (ヨー・ピッチ・ロール)

        public GhostEntry(String typeKey, double x, double y, double z, float yaw, float pitch, float roll) {
            this.typeKey = typeKey;
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch; this.roll = roll;
        }
    }

    /**
     * 指定されたサーバーレベルに対応する GhostSavedData を取得します。
     * 未作成の場合は自動的に新規作成されて登録されます。
     */
    public static GhostSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(GhostSavedData::load, GhostSavedData::new, "superb_ghost_data");
    }

    /**
     * NBTタグから永続化データを読み込み、GhostSavedDataのインスタンスを生成・復元します。
     */
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

    /**
     * データを保存する際に呼び出され、 vehicleMap 内のすべてのゴーストデータをNBTタグへシリアライズして出力します。
     */
    @Override
    public CompoundTag save(CompoundTag nbt) {
        ListTag list = new ListTag();
        vehicleMap.forEach((uuid, entry) -> {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("uuid", uuid);
            tag.putString("type", entry.typeKey);
            tag.putDouble("x", entry.x); tag.putDouble("y", entry.y); tag.putDouble("z", entry.z);

            // 位置・回転の各パラメータを格納
            tag.putFloat("yaw", entry.yaw);
            tag.putFloat("pitch", entry.pitch);
            tag.putFloat("roll", entry.roll);

            list.add(tag);
        });
        nbt.put("vehicles", list);
        return nbt;
    }
}