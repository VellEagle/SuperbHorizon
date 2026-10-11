package com.velleagle.superbhorizons.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * 車両の可動部分 (砲塔・銃身・車輪・履帯・プロペラ・脚など) の状態。
 *
 * 砲塔・銃身・脚の角度は「度」で ±180 に収まるため、2 バイトに圧縮しています
 * (精度およそ 0.006 度)。
 *
 * 一方、車輪・履帯・プロペラの値は角度ではありません。
 *   車輪・プロペラ … 走った分だけ増え続ける積算値 (SBW のモデル側でラジアンとして使用)
 *   履帯           … 0 ～ 履帯アニメーション長 (既定 100) の範囲を周回する値
 * これらを ±180 度に折り返して圧縮すると、折り返すたびに回転が飛ぶため、
 * 圧縮せずにそのまま送ります。
 * 1 台あたり約 52 バイトです。
 */
public class GhostAnimation {

    /** 動きのない車両用。 */
    public static final GhostAnimation EMPTY = new GhostAnimation();

    // --- 速度・出力系 ---
    public float absoluteSpeed;      // 実速度
    public float targetSpeed;        // 目標速度
    public float power;              // エンジン出力
    public float planeBreak;         // フラップなどの制動変位
    public float cannonRecoilForce;  // 主砲リコイル力

    // --- 角度系 ---
    public float turretYaw;          // 砲塔の旋回
    public float turretPitch;        // 砲塔の俯仰
    public float gunYaw;             // 銃身の旋回
    public float gunPitch;           // 銃身の俯仰
    public float leftWheelRot;       // 左車輪の回転
    public float rightWheelRot;      // 右車輪の回転
    public float leftTrack;          // 左履帯の送り
    public float rightTrack;         // 右履帯の送り
    public float propellerRot;       // プロペラ / ローターの回転
    public float gearRot;            // 脚の上げ下げ角

    // --- その他 ---
    public int cannonRecoilTime;     // 主砲リコイルの残り時間
    public int flags;                // 各種 ON/OFF をまとめたビット列

    public boolean engineRunning() { return (flags & 1) != 0; }
    public boolean gearUp()        { return (flags & 2) != 0; }
    public boolean firing()        { return (flags & 4) != 0; }
    public boolean hoverMode()     { return (flags & 8) != 0; }
    public boolean wreck()         { return (flags & 16) != 0; }

    // ------------------------------------------------------------------
    // 通信
    // ------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeFloat(absoluteSpeed);
        buf.writeFloat(targetSpeed);
        buf.writeFloat(power);
        buf.writeFloat(planeBreak);
        buf.writeFloat(cannonRecoilForce);

        // 「度」で表される角度 (±180 に折り返しても見た目が変わらないもの)
        buf.writeShort(packAngle(turretYaw));
        buf.writeShort(packAngle(turretPitch));
        buf.writeShort(packAngle(gunYaw));
        buf.writeShort(packAngle(gunPitch));
        buf.writeShort(packAngle(gearRot));

        // 積算値・周回値 (折り返すと回転が飛ぶので圧縮しない)
        buf.writeFloat(leftWheelRot);
        buf.writeFloat(rightWheelRot);
        buf.writeFloat(leftTrack);
        buf.writeFloat(rightTrack);
        buf.writeFloat(propellerRot);

        buf.writeVarInt(cannonRecoilTime);
        buf.writeVarInt(flags);
    }

    public static GhostAnimation decode(FriendlyByteBuf buf) {
        GhostAnimation a = new GhostAnimation();
        a.absoluteSpeed = buf.readFloat();
        a.targetSpeed = buf.readFloat();
        a.power = buf.readFloat();
        a.planeBreak = buf.readFloat();
        a.cannonRecoilForce = buf.readFloat();

        a.turretYaw = unpackAngle(buf.readShort());
        a.turretPitch = unpackAngle(buf.readShort());
        a.gunYaw = unpackAngle(buf.readShort());
        a.gunPitch = unpackAngle(buf.readShort());
        a.gearRot = unpackAngle(buf.readShort());

        a.leftWheelRot = buf.readFloat();
        a.rightWheelRot = buf.readFloat();
        a.leftTrack = buf.readFloat();
        a.rightTrack = buf.readFloat();
        a.propellerRot = buf.readFloat();

        a.cannonRecoilTime = buf.readVarInt();
        a.flags = buf.readVarInt();
        return a;
    }

    public static short packAngle(float degrees) {
        return (short) Math.round(Mth.wrapDegrees(degrees) * (32767.0F / 180.0F));
    }

    public static float unpackAngle(short packed) {
        return packed * (180.0F / 32767.0F);
    }

    /**
     * 2 つの状態の間を補間した新しい状態を作ります。
     * 履帯の周回範囲が分からない場合用 (履帯は単純な直線補間になります)。
     */
    public static GhostAnimation lerp(GhostAnimation prev, GhostAnimation next, float t) {
        return lerp(prev, next, t, 0.0F);
    }

    /**
     * 2 つの状態の間を補間した新しい状態を作ります。
     *
     *   砲塔・銃身・脚 … 角度 (度) なので、最短方向で回るように補間
     *   車輪・プロペラ … 積算値なので、そのまま直線で補間
     *                    (角度として補間すると、値が大きくなったときに逆回転します)
     *   履帯           … 0 ～ trackRange を周回する値なので、周回の継ぎ目をまたいでも
     *                    逆走しないように補間
     *
     * @param trackRange 履帯アニメーションの周回長 (SBW の getTrackAnimationLength)。
     *                   0 以下なら直線補間。
     */
    public static GhostAnimation lerp(GhostAnimation prev, GhostAnimation next, float t, float trackRange) {
        if (prev == null) return next;
        if (next == null) return prev;
        GhostAnimation a = new GhostAnimation();
        a.absoluteSpeed = Mth.lerp(t, prev.absoluteSpeed, next.absoluteSpeed);
        a.targetSpeed = Mth.lerp(t, prev.targetSpeed, next.targetSpeed);
        a.power = Mth.lerp(t, prev.power, next.power);
        a.planeBreak = Mth.lerp(t, prev.planeBreak, next.planeBreak);
        a.cannonRecoilForce = Mth.lerp(t, prev.cannonRecoilForce, next.cannonRecoilForce);

        a.turretYaw = Mth.rotLerp(t, prev.turretYaw, next.turretYaw);
        a.turretPitch = Mth.rotLerp(t, prev.turretPitch, next.turretPitch);
        a.gunYaw = Mth.rotLerp(t, prev.gunYaw, next.gunYaw);
        a.gunPitch = Mth.rotLerp(t, prev.gunPitch, next.gunPitch);
        a.gearRot = Mth.rotLerp(t, prev.gearRot, next.gearRot);

        a.leftWheelRot = Mth.lerp(t, prev.leftWheelRot, next.leftWheelRot);
        a.rightWheelRot = Mth.lerp(t, prev.rightWheelRot, next.rightWheelRot);
        a.propellerRot = Mth.lerp(t, prev.propellerRot, next.propellerRot);
        a.leftTrack = lerpWrapped(t, prev.leftTrack, next.leftTrack, trackRange);
        a.rightTrack = lerpWrapped(t, prev.rightTrack, next.rightTrack, trackRange);

        a.cannonRecoilTime = next.cannonRecoilTime;
        a.flags = next.flags;
        return a;
    }

    /**
     * 0 ～ range を周回する値の補間。
     * 例: range=100 で 98 → 2 に変わった場合、98 → 100(=0) → 2 と進むように補間します
     * (単純に補間すると 98 → 50 → 2 と逆走して見えます)。
     */
    private static float lerpWrapped(float t, float from, float to, float range) {
        if (range <= 0.0F) return Mth.lerp(t, from, to);
        float diff = to - from;
        float half = range * 0.5F;
        if (diff > half) diff -= range;
        else if (diff < -half) diff += range;
        float v = from + diff * t;
        v %= range;
        if (v < 0.0F) v += range;
        return v;
    }
}
