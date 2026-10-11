package com.velleagle.superbhorizons.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * サーバー -> クライアント。
 * 「今この瞬間、遠方に居る車両と発射体の一覧」をまるごと送ります。
 *
 * 差分ではなくスナップショット方式にしているのは、
 * 追加 / 削除の管理が不要になり、パケットロス時も次の送信で自動復旧するためです。
 *
 * 1 台あたりのサイズ:
 *   位置と向きだけ        … 約 38 バイト
 *   アニメーション付き    … 約 95 バイト (武器の数によって少し増えます)
 *   射撃があった場合      … 1 発につき 武器名の長さ + 4 バイト程度 (砲口の煙・炎があれば +24)
 *   武器データ            … 変わったときだけ、数百バイト〜数 KB (最短でも 1 秒に 1 回まで)
 *   発射体 (砲弾・ミサイル等) … 約 50 バイト
 * 遠すぎる車両にはアニメーションを付けないことで通信量を抑えています。
 */
public class GhostSnapshotPacket {

    /** 車両。 */
    public static final int KIND_VEHICLE = 0;
    /** 発射体 (砲弾・ロケット・ミサイル・爆弾など)。速度を使って滑らかに動かします。 */
    public static final int KIND_PROJECTILE = 1;

    /** 砲口の煙・炎: なし。 */
    public static final int MUZZLE_NONE = 0;
    /** 砲口の煙・炎: 中 (迫撃砲・ロケット砲・対戦車ミサイルなど)。 */
    public static final int MUZZLE_MEDIUM = 1;
    /** 砲口の煙・炎: 大 (戦車砲・榴弾砲など)。 */
    public static final int MUZZLE_BIG = 2;

    /** 1 回分の射撃 (どの武器の、どの発射口から撃ったか)。 */
    public static final class Shot {
        public final String weaponName;
        public final int index;
        /** 砲口の煙・炎の大きさ (MUZZLE_NONE / MEDIUM / BIG)。 */
        public final int muzzle;
        /** 砲口の位置 (車両の位置からの相対座標) と、撃った向き。muzzle が NONE なら 0。 */
        public final float px, py, pz, dx, dy, dz;

        public Shot(String weaponName, int index) {
            this(weaponName, index, MUZZLE_NONE, 0, 0, 0, 0, 0, 0);
        }

        public Shot(String weaponName, int index, int muzzle,
                    float px, float py, float pz, float dx, float dy, float dz) {
            this.weaponName = weaponName;
            this.index = index;
            this.muzzle = muzzle;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    /** 1 台分のデータ。 */
    public static class Entry {
        public final int entityId;
        public final int typeId;    // BuiltInRegistries.ENTITY_TYPE の数値 ID
        public final int kind;      // KIND_VEHICLE / KIND_PROJECTILE
        public final double x, y, z;
        public final float yRot, xRot, roll;
        /** 発射体の速度 (ブロック / tick)。車両では 0。 */
        public final float vx, vy, vz;
        /** 可動部分の状態。遠方の車両や発射体では null になります。 */
        public final GhostAnimation animation;
        /** 前回の送信以降に撃った射撃。無ければ空。 */
        public final List<Shot> shots;
        /** 発射体を撃った車両の ID (確認用の横並び表示で使います)。不明なら -1。 */
        public final int ownerId;
        /**
         * 車両の武器データ (SBW の形式のまま)。変わったときだけ送るので、普段は null。
         * 弾数や選択中の弾種によって表示が変わる武装 (翼下のミサイル等) に使います。
         */
        public byte[] gunData;
        /** 銃弾の曳光の色 (赤・緑・青、0〜1)。色を持たない弾では null。 */
        public float[] color;

        /** 車両用。 */
        public Entry(int entityId, int typeId, double x, double y, double z,
                     float yRot, float xRot, float roll, GhostAnimation animation, List<Shot> shots) {
            this(entityId, typeId, KIND_VEHICLE, x, y, z, yRot, xRot, roll, 0, 0, 0, animation, shots, -1);
        }

        public Entry(int entityId, int typeId, int kind, double x, double y, double z,
                     float yRot, float xRot, float roll, float vx, float vy, float vz,
                     GhostAnimation animation, List<Shot> shots, int ownerId) {
            this.entityId = entityId;
            this.typeId = typeId;
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yRot = yRot;
            this.xRot = xRot;
            this.roll = roll;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.animation = animation;
            this.shots = shots != null ? shots : List.of();
            this.ownerId = ownerId;
        }

        public boolean isProjectile() {
            return kind == KIND_PROJECTILE;
        }
    }

    /** 1 回に送る射撃の上限 (壊れたデータで大量に読み込まないための安全策)。 */
    private static final int MAX_SHOTS = 32;
    /** 武器名の最大文字数。 */
    private static final int MAX_NAME = 64;
    /** 1 台分の武器データの最大サイズ。 */
    private static final int MAX_GUN_DATA = 32768;

    public final List<Entry> entries;

    public GhostSnapshotPacket(List<Entry> entries) {
        this.entries = entries;
    }

    public static void encode(GhostSnapshotPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.entries.size());
        for (Entry e : pkt.entries) {
            buf.writeVarInt(e.entityId);
            buf.writeVarInt(e.typeId);
            buf.writeByte(e.kind);
            buf.writeDouble(e.x);
            buf.writeDouble(e.y);
            buf.writeDouble(e.z);
            buf.writeShort(GhostAnimation.packAngle(e.yRot));
            buf.writeShort(GhostAnimation.packAngle(e.xRot));
            buf.writeShort(GhostAnimation.packAngle(e.roll));
            if (e.isProjectile()) {
                buf.writeFloat(e.vx);
                buf.writeFloat(e.vy);
                buf.writeFloat(e.vz);
                buf.writeVarInt(e.ownerId + 1);   // -1 (不明) を 0 として送ります
                boolean hasColor = e.color != null && e.color.length >= 3;
                buf.writeBoolean(hasColor);
                if (hasColor) {
                    buf.writeByte(colorByte(e.color[0]));
                    buf.writeByte(colorByte(e.color[1]));
                    buf.writeByte(colorByte(e.color[2]));
                }
            }
            buf.writeBoolean(e.animation != null);
            if (e.animation != null) e.animation.encode(buf);

            int shotCount = Math.min(e.shots.size(), MAX_SHOTS);
            buf.writeVarInt(shotCount);
            for (int i = 0; i < shotCount; i++) {
                Shot s = e.shots.get(i);
                buf.writeUtf(s.weaponName, MAX_NAME);
                buf.writeVarInt(s.index);
                buf.writeByte(s.muzzle);
                if (s.muzzle != MUZZLE_NONE) {
                    buf.writeFloat(s.px);
                    buf.writeFloat(s.py);
                    buf.writeFloat(s.pz);
                    buf.writeFloat(s.dx);
                    buf.writeFloat(s.dy);
                    buf.writeFloat(s.dz);
                }
            }

            boolean hasGunData = e.gunData != null && e.gunData.length <= MAX_GUN_DATA;
            buf.writeBoolean(hasGunData);
            if (hasGunData) buf.writeByteArray(e.gunData);
        }
    }

    public static GhostSnapshotPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int id = buf.readVarInt();
            int typeId = buf.readVarInt();
            int kind = buf.readByte();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            float yRot = GhostAnimation.unpackAngle(buf.readShort());
            float xRot = GhostAnimation.unpackAngle(buf.readShort());
            float roll = GhostAnimation.unpackAngle(buf.readShort());
            float vx = 0, vy = 0, vz = 0;
            int ownerId = -1;
            float[] color = null;
            if (kind == KIND_PROJECTILE) {
                vx = buf.readFloat();
                vy = buf.readFloat();
                vz = buf.readFloat();
                ownerId = buf.readVarInt() - 1;
                if (buf.readBoolean()) {
                    color = new float[] {
                            (buf.readByte() & 0xFF) / 255.0F,
                            (buf.readByte() & 0xFF) / 255.0F,
                            (buf.readByte() & 0xFF) / 255.0F};
                }
            }
            GhostAnimation anim = buf.readBoolean() ? GhostAnimation.decode(buf) : null;

            int shotCount = Math.min(buf.readVarInt(), MAX_SHOTS);
            List<Shot> shots = shotCount == 0 ? List.of() : new ArrayList<>(shotCount);
            for (int j = 0; j < shotCount; j++) {
                String name = buf.readUtf(MAX_NAME);
                int index = buf.readVarInt();
                int muzzle = buf.readByte();
                if (muzzle != MUZZLE_NONE) {
                    shots.add(new Shot(name, index, muzzle,
                            buf.readFloat(), buf.readFloat(), buf.readFloat(),
                            buf.readFloat(), buf.readFloat(), buf.readFloat()));
                } else {
                    shots.add(new Shot(name, index));
                }
            }

            Entry entry = new Entry(id, typeId, kind, x, y, z, yRot, xRot, roll, vx, vy, vz, anim, shots, ownerId);
            entry.color = color;
            if (buf.readBoolean()) entry.gunData = buf.readByteArray(MAX_GUN_DATA);
            list.add(entry);
        }
        return new GhostSnapshotPacket(list);
    }

    /** 0〜1 の色の値を 0〜255 の 1 バイトにします。 */
    private static int colorByte(float v) {
        if (!(v > 0.0F)) return 0;
        if (v >= 1.0F) return 255;
        return Math.round(v * 255.0F);
    }

    public static void handle(GhostSnapshotPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> com.velleagle.superbhorizons.client.GhostVehicleStore.applySnapshot(pkt))
        );
        ctx.get().setPacketHandled(true);
    }
}
