package com.velleagle.superbhorizons.client;

import com.velleagle.superbhorizons.network.GhostAnimation;
import com.velleagle.superbhorizons.util.GunDataSync;
import com.velleagle.superbhorizons.util.Reflect;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * クライアント側が保持する 1 台分のゴースト情報。
 *
 * 重要: これは Entity ではありません。ワールドにも登録されません。
 * そのため tick 処理・当たり判定・チャンク読み込みは一切発生せず、
 * ワールド側の負荷はゼロです。
 *
 * 描画のときだけ、モデルを描くための「表示専用のダミー Entity」を
 * 1 台につき 1 個だけ作って使い回します。
 */
public class GhostVehicle {

    public final int entityId;
    public final int typeId;

    /**
     * 発射体 (砲弾・ロケット・ミサイル・爆弾など) か。
     * 発射体は速く直線的に飛ぶため、受信した位置の間を補間するのではなく、
     * 速度を使って「今どこにいるか」を計算して滑らかに動かします。
     */
    public final boolean projectile;
    /** 発射体の速度 (ブロック / tick)。 */
    public float vx, vy, vz;
    /** 発射体を撃った車両の ID (確認用の横並び表示で使います)。不明なら -1。 */
    public int ownerId = -1;
    /** 弾道の煙用: 前の tick に表示していた位置と、表示してからの tick 数 (GhostTrails が使います)。 */
    public double trailX, trailY, trailZ;
    public int trailTicks;

    /**
     * 最後に受信した武器データ (弾数・選択中の弾種など)。
     * 翼下のミサイルや爆弾など「弾が入っていると見える武装」の表示に使います。
     * 描画用ダミーを作り直したときにも読み込ませ直すため、保持しておきます。
     */
    private byte[] gunData;

    /** 銃弾の曳光の色 (赤・緑・青)。色を持たない弾では null。 */
    private float[] color;

    /** 直前に受信した状態 (補間の開始点) */
    public double prevX, prevY, prevZ;
    public float prevYRot, prevXRot, prevRoll;
    public GhostAnimation prevAnimation;

    /** 最新の受信状態 (補間の終了点) */
    public double x, y, z;
    public float yRot, xRot, roll;
    public GhostAnimation animation;

    /** 最後に更新された時刻 (ミリ秒)。 */
    public long lastUpdateMs;
    /**
     * 実際に測った受信の間隔 (ミリ秒、0 = まだ測れていない)。
     * サーバーが重くなる (移動中に地形の読み込みが増えるなど) と、受信の間隔は設定より長くなります。
     * 設定どおりの間隔を前提に動かすと、ゴーストが先へ進みすぎて次の受信で引き戻され、
     * 行ったり来たりして見えるので、実際の間隔に合わせて繋ぎます。
     */
    private float measuredIntervalMs;
    /** 2 回以上受信したか (前回と今回の位置の間を繋げるか)。 */
    private boolean hasPrevSample;
    /** 【脚】前回、脚を上げた状態として扱ったか (SBW の wasGearUp と同じ役割)。 */
    public boolean wasGearUp;

    /** 描画専用のダミー。初回描画時に 1 度だけ生成します。 */
    private Entity dummy;
    private boolean dummyFailed;
    /** この車種がアニメーションに対応しているか (未判定は null)。 */
    private Boolean animationSupported;
    /** 履帯アニメーションの周回長 (未取得は NaN)。履帯の補間に使います。 */
    private float trackRange = Float.NaN;

    public GhostVehicle(int entityId, int typeId,
                        double x, double y, double z,
                        float yRot, float xRot, float roll,
                        GhostAnimation animation) {
        this(entityId, typeId, false, x, y, z, yRot, xRot, roll, animation);
    }

    public GhostVehicle(int entityId, int typeId, boolean projectile,
                        double x, double y, double z,
                        float yRot, float xRot, float roll,
                        GhostAnimation animation) {
        this.entityId = entityId;
        this.typeId = typeId;
        this.projectile = projectile;
        this.prevX = this.x = x;
        this.prevY = this.y = y;
        this.prevZ = this.z = z;
        this.prevYRot = this.yRot = yRot;
        this.prevXRot = this.xRot = xRot;
        this.prevRoll = this.roll = roll;
        this.prevAnimation = this.animation = animation;
        this.lastUpdateMs = System.currentTimeMillis();
    }

    /** 新しい状態を受信したときに呼びます。 */
    public void update(double nx, double ny, double nz,
                       float nyRot, float nxRot, float nRoll,
                       GhostAnimation nAnimation) {
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;
        this.prevYRot = this.yRot;
        this.prevXRot = this.xRot;
        this.prevRoll = this.roll;
        this.prevAnimation = this.animation;

        this.x = nx;
        this.y = ny;
        this.z = nz;
        this.yRot = nyRot;
        this.xRot = nxRot;
        this.roll = nRoll;
        this.animation = nAnimation;

        long now = System.currentTimeMillis();
        long dt = now - this.lastUpdateMs;
        if (dt >= 20 && dt <= 3000) {
            measuredIntervalMs = measuredIntervalMs <= 0.0F ? dt : measuredIntervalMs * 0.6F + dt * 0.4F;
        }
        this.lastUpdateMs = now;
        this.hasPrevSample = true;
    }

    /**
     * 補間係数 (0.0 = 前回状態, 1.0 = 最新状態)。
     * 受信間隔でカクつかないよう、受信からの経過時間で滑らかに繋ぎます。
     */
    public float lerpFactor(int updateIntervalMs) {
        // 実際に測った受信の間隔があれば、そちらを使います (サーバーが重いときも滑らかに繋がります)
        float interval = measuredIntervalMs > 0.0F ? Math.max(measuredIntervalMs, updateIntervalMs) : updateIntervalMs;
        if (interval <= 0.0F) return 1.0F;
        float t = (System.currentTimeMillis() - lastUpdateMs) / interval;
        return t < 0.0F ? 0.0F : (t > 1.0F ? 1.0F : t);
    }

    /**
     * 発射体の位置。
     * 2 回以上受信していれば、車両と同じく「前回と今回の受信位置の間」を繋ぎます。
     * 受信した位置より先へは決して進めないので、次の受信で引き戻されることがありません。
     * 初めて受信したときだけは、速度を使って受信位置の手前から受信位置まで進めます。
     */
    private double projectileAxis(double prev, double cur, float vel) {
        if (hasPrevSample) {
            float t = lerpFactor(Math.max(1, com.velleagle.superbhorizons.Config.UPDATE_INTERVAL.get()) * 50);
            return prev + (cur - prev) * t;
        }
        return cur + vel * projectileTicks();
    }

    public double lerpX(float t) { return projectile ? projectileAxis(prevX, x, vx) : prevX + (x - prevX) * t; }
    public double lerpY(float t) { return projectile ? projectileAxis(prevY, y, vy) : prevY + (y - prevY) * t; }
    public double lerpZ(float t) { return projectile ? projectileAxis(prevZ, z, vz) : prevZ + (z - prevZ) * t; }

    public float lerpYRot(float t) { return projectile ? yRot : lerpAngle(prevYRot, yRot, t); }
    public float lerpXRot(float t) { return projectile ? xRot : lerpAngle(prevXRot, xRot, t); }
    public float lerpRoll(float t) { return projectile ? roll : lerpAngle(prevRoll, roll, t); }

    /** 発射体の速度を設定します (受信のたびに呼びます)。 */
    public void setVelocity(float nvx, float nvy, float nvz) {
        this.vx = nvx;
        this.vy = nvy;
        this.vz = nvz;
    }

    /**
     * 発射体を「受信した位置から何 tick 分進めて描くか」。
     *
     * 車両のゴーストは「前回と今回の受信位置の間」を描くため、実際より受信間隔 1 回分
     * 遅れて表示されています。発射体も同じだけ遅らせないと、撃った車両より先に弾が
     * 進んで見えてしまうため、受信間隔 1 回分さかのぼった位置から進めます。
     * 次の受信が来ない場合に飛び続けないよう、進める量には上限を設けています。
     */
    private float projectileTicks() {
        float interval = Math.max(1, com.velleagle.superbhorizons.Config.UPDATE_INTERVAL.get());
        float elapsed = (System.currentTimeMillis() - lastUpdateMs) / 50.0F;
        if (elapsed < 0.0F) elapsed = 0.0F;
        // 受信した位置で止めます (それより先へは進めません)
        if (elapsed > interval) elapsed = interval;
        return elapsed - interval;
    }

    /** 補間済みの可動部分の状態。アニメーション情報が無ければ null。 */
    public GhostAnimation lerpAnimation(float t) {
        if (animation == null) return null;
        return GhostAnimation.lerp(prevAnimation, animation, t, trackRange());
    }

    /**
     * 履帯アニメーションの周回長。
     * SBW では車種ごとに getTrackAnimationLength() で決まっています (既定 100)。
     * ダミーがまだ無い場合は 0 (= 履帯は直線補間) を返します。
     */
    private float trackRange() {
        if (Float.isNaN(trackRange)) {
            if (dummy == null) return 0.0F;
            trackRange = Reflect.getInt(dummy, "getTrackAnimationLength", 0);
        }
        return trackRange;
    }

    /**
     * 描画用ダミーの経過 tick を 1 進めます (毎クライアント tick 呼び出し)。
     *
     * ダミーはワールドに登録されないため tickCount が 0 のまま止まります。
     * SBW の車両は GeckoLib のアニメーション (砲の待機・射撃など) を
     * tickCount を時計にして再生するので、進めないとゴーストだけ固まって見えます。
     * (旧バージョンの onClientTick で行っていた処理と同じものです)
     */
    public void tickDummy() {
        if (dummy != null) dummy.tickCount++;
    }

    private static float lerpAngle(float a, float b, float t) {
        float diff = b - a;
        while (diff < -180.0F) diff += 360.0F;
        while (diff >= 180.0F) diff -= 360.0F;
        return a + diff * t;
    }

    /**
     * 武器データを受信したときに呼びます。
     * 描画用ダミーが既にあればすぐに読み込ませ、まだ無ければ作ったときに読み込ませます。
     */
    public void setColor(float[] c) {
        if (c == null) return;
        this.color = c;
        if (dummy != null) Reflect.set(dummy, "setRGB", c);
    }

    public void setGunData(byte[] data) {
        if (data == null) return;
        this.gunData = data;
        if (dummy != null) GunDataSync.apply(dummy, data);
    }

    /**
     * 描画用ダミーを取得。存在しなければ 1 度だけ生成します。
     * EntityType#create はワールドへの追加を行わないため、負荷はありません。
     */
    public Entity getDummy(ClientLevel level) {
        if (dummy != null || dummyFailed) return dummy;

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.byId(typeId);
        if (type == null) {
            dummyFailed = true;
            return null;
        }
        try {
            dummy = type.create(level);
        } catch (Throwable ignored) {
            dummy = null;
        }
        if (dummy == null) {
            dummyFailed = true;
        } else {
            // 受信済みの武器データを読み込ませます (翼下の武装などが表示されます)
            if (gunData != null) GunDataSync.apply(dummy, gunData);
            // 銃弾の曳光の色
            if (color != null) Reflect.set(dummy, "setRGB", color);
        }
        return dummy;
    }

    /** この車種がアニメーションに対応しているか (1 度だけ判定してキャッシュ)。 */
    public boolean supportsAnimation(Entity dummy) {
        if (animationSupported == null) {
            animationSupported = VehicleAnimationWriter.isSupported(dummy);
        }
        return animationSupported;
    }

    public void discardDummy() {
        dummy = null;
        wasGearUp = false;   // 作り直したダミーでは、脚のアニメーションをもう一度再生させます
        dummyFailed = false;
        animationSupported = null;
        trackRange = Float.NaN;
    }
}
