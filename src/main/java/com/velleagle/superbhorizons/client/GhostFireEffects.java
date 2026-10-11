package com.velleagle.superbhorizons.client;

import com.velleagle.superbhorizons.Config;
import com.velleagle.superbhorizons.network.GhostSnapshotPacket;
import com.velleagle.superbhorizons.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ゴーストに射撃アニメーションを再生させます。
 *
 * SBW 本体は射撃を受け取ると、車両の「アニメーション」に
 *   fire(武器名をスネークケースにしたもの, 発射口の番号)
 * を伝え、射撃アニメーション (砲身の後退・砲口の閃光など) を再生します。
 * ここでは同じ呼び出しを、ゴーストの描画用ダミーに対して行います。
 * これで、SBW が用意している射撃演出がそのままゴーストでも再生されます。
 *
 * また、戦車砲などの「砲口の煙・炎」は SBW 本体では車両ごとの処理でサーバーから
 * 出しているため、アニメーションでは再生されません。サーバーから届いた
 * 砲口の位置と向きを使い、SBW と同じ煙・炎をこちらで出します。
 */
public final class GhostFireEffects {

    private GhostFireEffects() {}

    /** クラスごとの fire メソッド (見つからなかった場合は MISSING)。 */
    private static final Map<Class<?>, Method> FIRE_METHODS = new ConcurrentHashMap<>();
    private static final Method MISSING;

    static {
        Method m;
        try {
            m = GhostFireEffects.class.getDeclaredMethod("missingMarker");
        } catch (Throwable e) {
            m = null;
        }
        MISSING = m;
    }

    @SuppressWarnings("unused")
    private static void missingMarker() {}

    /** 受信した射撃をゴーストで再生します。 */
    public static void play(GhostVehicle ghost, List<GhostSnapshotPacket.Shot> shots) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || ghost.projectile) return;

        // 本物の車両が画面に描かれている場合は、SBW 本体が射撃を再生するので何もしません
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        if (GhostRenderer.isRealEntityShown(level, ghost, cam)) return;

        // --- 砲口の煙・炎 ---
        spawnMuzzleParticles(level, ghost, shots);

        // --- 射撃アニメーション (砲身の後退・砲口の閃光など) ---
        Entity dummy = ghost.getDummy(level);
        if (dummy == null) return;

        Object animation = Reflect.call(dummy, "getAnimationInstance");
        if (animation == null) return;   // 射撃アニメーションを持たない車種

        Method fire = FIRE_METHODS.computeIfAbsent(animation.getClass(), GhostFireEffects::findFire);
        if (fire == MISSING || fire == null) return;

        for (GhostSnapshotPacket.Shot shot : shots) {
            try {
                fire.invoke(animation, camelToSnake(shot.weaponName), shot.index);
            } catch (Throwable ignored) {
                // 1 発再生できなくても問題ないので無視します
            }
        }
    }

    // ------------------------------------------------------------------
    // 砲口の煙・炎
    // ------------------------------------------------------------------

    /**
     * SBW の「大」の煙・炎 (ParticleTool.spawnBigCannonMuzzleParticles と同じ値)。
     * 1 行が 1 回の呼び出しで、{粒の数, 広がり, 色 r, g, b, 寿命, 消え方, アニメ速度, 大きさ, 速さ}。
     */
    private static final double[][] BIG = {
            {10, 0.1, 1f, 1f, 1f, 8, 0.7f, 1, 2.2f, 1.0},
            {8, 0.06, 1f, 1f, 1f, 8, 0.72f, 1, 1.5f, 0.8},
            {1, 0.0, 0.4f, 0.4f, 0.4f, 36, 0.84f, 2, 1.1f, 0.6},
            {1, 0.0, 0.4f, 0.4f, 0.4f, 39, 0.87f, 2, 0.9f, 0.5},
            {1, 0.0, 0.4f, 0.4f, 0.4f, 42, 0.87f, 2, 0.7f, 0.35},
            {1, 0.0, 0.4f, 0.4f, 0.4f, 45, 0.88f, 2, 0.5f, 0.25},
            {1, 0.0, 0.45f, 0.45f, 0.45f, 47, 0.90f, 2, 0.3f, 0.17},
            {1, 0.0, 0.5f, 0.5f, 0.5f, 48, 0.92f, 2, 0.1f, 0.1},
    };

    /** SBW の「中」の煙・炎 (ParticleTool.spawnMediumCannonMuzzleParticles と同じ値)。 */
    private static final double[][] MEDIUM = {
            {4, 0.1, 1f, 1f, 1f, 8, 0.7f, 1, 0.2f, 0.3},
            {3, 0.06, 1f, 1f, 1f, 8, 0.72f, 1, 0.15f, 0.2},
            {1, 0.0, 0.4f, 0.4f, 0.4f, 45, 0.88f, 2, 0.05f, 0.15},
            {1, 0.0, 0.45f, 0.45f, 0.45f, 47, 0.90f, 2, 0.03f, 0.125},
            {1, 0.0, 0.5f, 0.5f, 0.5f, 48, 0.92f, 2, 0.01f, 0.1},
    };

    /** SBW の部品 (見つからなければ null)。初回に 1 度だけ探します。 */
    private static Method spawnDirectional;
    private static Constructor<?> flareOption;
    private static boolean sbwLookedUp;

    private static void lookupSbw() {
        if (sbwLookedUp) return;
        sbwLookedUp = true;
        try {
            Class<?> tool = Class.forName("com.atsuishio.superbwarfare.tools.ParticleTool");
            spawnDirectional = tool.getMethod("spawnDirectionalParticles",
                    int.class, double.class, Level.class, ParticleOptions.class,
                    Vec3.class, Vec3.class, double.class);
            Class<?> option = Class.forName("com.atsuishio.superbwarfare.client.particle.CannonMuzzleFlareOption");
            flareOption = option.getConstructor(
                    float.class, float.class, float.class, int.class, float.class, int.class, float.class);
        } catch (Throwable e) {
            spawnDirectional = null;
            flareOption = null;
        }
    }

    private static void spawnMuzzleParticles(ClientLevel level, GhostVehicle ghost,
                                             List<GhostSnapshotPacket.Shot> shots) {
        int intervalMs = Config.UPDATE_INTERVAL.get() * 50;
        float t = ghost.lerpFactor(intervalMs);
        // ゴーストが今表示されている位置を基準にします (受信間隔ぶん遅れて表示しているため)
        double baseX = ghost.lerpX(t);
        double baseY = ghost.lerpY(t);
        double baseZ = ghost.lerpZ(t);

        for (GhostSnapshotPacket.Shot shot : shots) {
            if (shot.muzzle == GhostSnapshotPacket.MUZZLE_NONE) continue;
            Vec3 pos = new Vec3(baseX + shot.px, baseY + shot.py, baseZ + shot.pz);
            Vec3 dir = new Vec3(shot.dx, shot.dy, shot.dz);
            if (dir.lengthSqr() < 1.0E-6) continue;
            try {
                spawnMuzzle(level, shot.muzzle == GhostSnapshotPacket.MUZZLE_BIG ? BIG : MEDIUM, pos, dir);
            } catch (Throwable ignored) {
                // 煙が出なくても問題ないので無視します
            }
        }
    }

    private static void spawnMuzzle(ClientLevel level, double[][] rows, Vec3 pos, Vec3 dir) throws Exception {
        lookupSbw();
        if (spawnDirectional == null || flareOption == null) {
            // SBW の部品が見つからない場合は、Minecraft 標準の煙と炎で代用します
            Vec3 d = dir.normalize();
            int n = rows == BIG ? 12 : 5;
            for (int i = 0; i < n; i++) {
                double s = 0.1 + level.random.nextDouble() * 0.4;
                level.addAlwaysVisibleParticle(ParticleTypes.LARGE_SMOKE, true,
                        pos.x, pos.y, pos.z, d.x * s, d.y * s + 0.02, d.z * s);
            }
            level.addAlwaysVisibleParticle(ParticleTypes.FLASH, true, pos.x, pos.y, pos.z, 0, 0, 0);
            return;
        }
        for (double[] r : rows) {
            Object option = flareOption.newInstance(
                    (float) r[2], (float) r[3], (float) r[4], (int) r[5], (float) r[6], (int) r[7], (float) r[8]);
            spawnDirectional.invoke(null, (int) r[0], r[1], level, option, dir, pos, r[9]);
        }
    }

    private static Method findFire(Class<?> clazz) {
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals("fire") || m.getParameterCount() != 2) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p[0] == String.class && (p[1] == int.class || p[1] == Integer.class)) return m;
        }
        return MISSING;
    }

    /**
     * 「MainCannon」→「main_cannon」のように変換します。
     * SBW の FormatTool.camelToSnake と同じ処理です (アニメーション名がこの形式のため)。
     */
    static String camelToSnake(String camel) {
        if (camel == null || camel.isEmpty()) return camel;
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        sb.append(Character.toLowerCase(camel.charAt(0)));
        for (int i = 1; i < camel.length(); i++) {
            char ch = camel.charAt(i);
            if (Character.isUpperCase(ch)) {
                sb.append('_').append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
