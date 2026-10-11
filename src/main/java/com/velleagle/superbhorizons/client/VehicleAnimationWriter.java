package com.velleagle.superbhorizons.client;

import com.velleagle.superbhorizons.network.GhostAnimation;
import com.velleagle.superbhorizons.util.Reflect;
import net.minecraft.world.entity.Entity;

/**
 * クライアント側で、描画用ダミーに可動部分の状態を書き込みます。
 *
 * Superb Warfare のモデルは「今の値」と「1 tick 前の値」の 2 つを使って
 * 描画時に補間します。そのため setXxx と setXxxO の両方に値を入れる必要があります。
 * ここでは補間済みの値を両方に入れ、二重補間による揺れを防いでいます。
 */
public class VehicleAnimationWriter {

    /** この車種がアニメーションに対応しているか (対応していなければ以降スキップ)。 */
    public static boolean isSupported(Entity dummy) {
        return Reflect.has(dummy, "setTurretYRot", 1)
                || Reflect.has(dummy, "setPropellerRot", 1)
                || Reflect.has(dummy, "setLeftTrack", 1);
    }

    /**
     * 車体のロール (左右の傾き) を書き込みます。
     *
     * ロールは砲塔などの可動部分とは違い「車体の姿勢」なので、
     * 可動部分の情報が送られてこない遠方の車両や、可動部分を持たない車種でも
     * 必ず反映する必要があります。そのため apply() とは分けて、毎フレーム必ず呼びます。
     * (旧バージョンでも、アニメーションの有無に関係なく常にロールを反映していました)
     */
    public static void applyRoll(Entity dummy, float roll) {
        if (dummy == null) return;
        Reflect.set(dummy, "setRoll", roll);
        Reflect.set(dummy, "setPrevRoll", roll);
    }

    public static void apply(Entity dummy, GhostAnimation a) {
        if (dummy == null || a == null) return;

        // 速度・出力系
        Reflect.set(dummy, "setAbsoluteSpeed", a.absoluteSpeed);
        Reflect.set(dummy, "setAbsoluteSpeedLerp", a.absoluteSpeed);
        Reflect.set(dummy, "setTargetSpeed", a.targetSpeed);
        Reflect.set(dummy, "setPower", a.power);
        Reflect.set(dummy, "setPlaneBreak", a.planeBreak);

        // 砲塔
        Reflect.set(dummy, "setTurretYRot", a.turretYaw);
        Reflect.set(dummy, "setTurretXRot", a.turretPitch);
        Reflect.set(dummy, "setTurretYRotO", a.turretYaw);
        Reflect.set(dummy, "setTurretXRotO", a.turretPitch);

        // 銃身
        Reflect.set(dummy, "setGunYRot", a.gunYaw);
        Reflect.set(dummy, "setGunXRot", a.gunPitch);
        Reflect.set(dummy, "setGunYRotO", a.gunYaw);
        Reflect.set(dummy, "setGunXRotO", a.gunPitch);

        // 車輪・履帯
        Reflect.set(dummy, "setLeftWheelRot", a.leftWheelRot);
        Reflect.set(dummy, "setRightWheelRot", a.rightWheelRot);
        Reflect.set(dummy, "setLeftWheelRotO", a.leftWheelRot);
        Reflect.set(dummy, "setRightWheelRotO", a.rightWheelRot);
        Reflect.set(dummy, "setLeftTrack", a.leftTrack);
        Reflect.set(dummy, "setRightTrack", a.rightTrack);
        Reflect.set(dummy, "setLeftTrackO", a.leftTrack);
        Reflect.set(dummy, "setRightTrackO", a.rightTrack);

        // プロペラ・脚
        Reflect.set(dummy, "setPropellerRot", a.propellerRot);
        Reflect.set(dummy, "setPropellerRotO", a.propellerRot);
        Reflect.set(dummy, "setSynchedPropellerRot", a.propellerRot);
        float gear = gearAmount(a);
        Reflect.set(dummy, "setGearRot", gear);
        Reflect.set(dummy, "setSynchedGearRot", gear);

        // 主砲リコイル
        Reflect.set(dummy, "setCannonRecoilTime", a.cannonRecoilTime);
        Reflect.set(dummy, "setCannonRecoilForce", a.cannonRecoilForce);

        // ON/OFF 状態
        Reflect.set(dummy, "setGearUp", a.gearUp());
        Reflect.set(dummy, "setHoverMode", a.hoverMode());
        Reflect.set(dummy, "setWreck", a.wreck());
        Reflect.set(dummy, "setEngineStart", a.engineRunning());
        Reflect.set(dummy, "setEngineStartOver", a.engineRunning());
    }

    /**
     * 脚の出し入れの量 (0 = 出ている, 1 = 収納済み)。
     * 送信時に少しだけ丸められるので、ほぼ端の値はちょうど 0 / 1 に戻します
     * (SBW は「ちょうど 0 / 1 か」で脚の状態を判断するため)。
     */
    private static float gearAmount(GhostAnimation a) {
        float g = a.gearRot;
        if (g > 0.99F) return 1.0F;
        if (g < 0.01F) return 0.0F;
        return g;
    }

    // ------------------------------------------------------------------
    // 脚 (ランディングギア) のアニメーション
    // ------------------------------------------------------------------

    /** 車種ごとの {脚を上げるアニメーション名, 脚を下ろすアニメーション名} (無い車種は NO_GEAR)。 */
    private static final java.util.Map<Class<?>, String[]> GEAR_ANIMATIONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String[] NO_GEAR = new String[0];
    private static java.lang.reflect.Method playMethod, stopMethod;
    private static Object playOnceHold;
    private static boolean gearLookedUp;

    /**
     * 脚の出し入れアニメーションを、本物と同じ条件で再生します。
     *
     * SBW では、A-10 などの機体が画面側の毎 tick の処理 (baseTick) の中で
     *   ・脚を上げている途中 or 上げ終わった → 「脚を上げる」アニメーションを再生
     *   ・脚を下ろしている途中 or 下ろし終わった → 「脚を下ろす」アニメーションを再生
     * を行っています。ゴーストの描画用ダミーはこの処理を通らないので、同じ判断をここで行います。
     * アニメーション名は機体ごとに違うため、「gear_up」「gear_down」で終わる名前を探して使います。
     */
    public static void syncGear(Entity dummy, GhostVehicle ghost, GhostAnimation a) {
        if (dummy == null || ghost == null || a == null) return;
        try {
            Object instance = Reflect.call(dummy, "getAnimationInstance");
            if (instance == null) return;
            Object context = Reflect.call(instance, "getContext");
            if (context == null) return;

            String[] names = GEAR_ANIMATIONS.get(dummy.getClass());
            if (names == null) {
                Object map = Reflect.call(context, "getAnimations");
                if (!(map instanceof java.util.Map<?, ?> animations) || animations.isEmpty()) return;   // まだ読み込み前
                String up = null, down = null;
                for (Object key : animations.keySet()) {
                    String k = String.valueOf(key);
                    if (k.endsWith("gear_up")) up = k;
                    else if (k.endsWith("gear_down")) down = k;
                }
                names = (up != null && down != null) ? new String[] {up, down} : NO_GEAR;
                GEAR_ANIMATIONS.put(dummy.getClass(), names);
            }
            if (names == NO_GEAR) return;
            if (!lookupGearMethods(context)) return;

            // SBW (A10Entity など) と同じ判断です
            float rot = gearAmount(a);
            boolean gearUpCmd = a.gearUp();
            boolean up = (gearUpCmd && rot > 0.0F && rot < 1.0F) || rot == 1.0F;
            boolean down = (!gearUpCmd && rot > 0.0F && rot < 1.0F) || rot == 0.0F;

            if (up && !ghost.wasGearUp) {
                stopMethod.invoke(context, names[1], 0);
                playMethod.invoke(context, names[0], playOnceHold, 0);
            } else if (down && ghost.wasGearUp) {
                stopMethod.invoke(context, names[0], 0);
                playMethod.invoke(context, names[1], playOnceHold, 0);
            }
            ghost.wasGearUp = up;
        } catch (Throwable ignored) {
            // 脚が動かないだけなので無視します
        }
    }

    private static boolean lookupGearMethods(Object context) {
        if (gearLookedUp) return playMethod != null && stopMethod != null && playOnceHold != null;
        gearLookedUp = true;
        try {
            Class<?> typeClass = Class.forName("com.atsuishio.superbwarfare.client.animation.AnimationPlayType");
            for (Object c : typeClass.getEnumConstants()) {
                if (((Enum<?>) c).name().equals("PLAY_ONCE_HOLD")) playOnceHold = c;
            }
            playMethod = context.getClass().getMethod("playAnimation", String.class, typeClass, int.class);
            stopMethod = context.getClass().getMethod("stopAnimation", String.class, int.class);
        } catch (Throwable e) {
            playMethod = null;
            stopMethod = null;
        }
        return playMethod != null && stopMethod != null && playOnceHold != null;
    }

    /**
     * 発射体 (砲弾・ミサイル等) のダミーを描画できる状態にします。
     *
     * SBW の発射体は、発射直後の数 tick は描かれず、
     * ミサイルやロケットの噴射炎も一定時間経ってから表示されます。
     * その判定には「発射からの経過 tick」(syncedTick / tickCount) が使われますが、
     * ダミーはワールドの処理に乗らないため 0 のままです。
     * そこで、十分に時間が経った扱いにして、本体と噴射炎が表示されるようにします。
     */
    public static void prepareProjectile(Entity dummy, float vx, float vy, float vz) {
        if (dummy == null) return;
        if (dummy.tickCount < 100) dummy.tickCount = 100;
        Reflect.set(dummy, "setSyncedTick", 100);
        // 機関砲弾の曳光 (光の筋) は、弾の速度から長さと向きが決まります。
        // ダミーは止まっているので、受信した速度を与えないと光の筋が長さ 0 になって見えません。
        dummy.setDeltaMovement(vx, vy, vz);
    }
}
