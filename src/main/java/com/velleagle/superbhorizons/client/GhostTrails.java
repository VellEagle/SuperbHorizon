package com.velleagle.superbhorizons.client;

import com.velleagle.superbhorizons.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 発射体のゴーストに「弾道の煙」(砲弾の白い煙の筋、ロケットの炎の筋など) を出します。
 *
 * ■ なぜこちらで出すのか
 * SBW 本体では、弾が飛んでいる間、クライアント側の弾が毎 tick 自分で煙を出しています。
 * ゴーストの描画用ダミーは tick 処理をしないので、煙が出ません。
 * また SBW の煙は Minecraft の決まりで「カメラから 32 ブロック以内」でしか出ないため、
 * SBW の処理を呼んでも遠くのゴーストでは何も出ません。
 *
 * そこで、SBW と同じ種類・同じ色・同じ間隔の煙を、距離の制限なしで出します。
 * どの弾がどの煙を出すかは設定 (trailClasses) で決めています。
 */
public final class GhostTrails {

    private GhostTrails() {}

    /** 煙の種類。 */
    private enum Style { SHELL, LARGE, MEDIUM, SMALL }

    /** クラスごとの煙の種類と、煙を出す最低速度。 */
    private record Rule(Style style, double minSpeed) {}

    private static final Rule NO_TRAIL = new Rule(null, 0);
    private static final Map<Class<?>, Rule> RULES = new ConcurrentHashMap<>();
    private static List<? extends String> rulesSource;

    /** SBW の煙の部品 (見つからなければ null)。 */
    private static Constructor<?> flareOption;
    private static Constructor<?> cloudOption;
    private static boolean lookedUp;

    /** 毎クライアント tick 呼び出します。 */
    public static void tick(Collection<GhostVehicle> ghosts) {
        if (!Config.GHOST_TRAILS.get()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();

        for (GhostVehicle g : ghosts) {
            if (!g.projectile) continue;

            // 今ゴーストが表示されている位置
            double x = g.lerpX(1.0F);
            double y = g.lerpY(1.0F);
            double z = g.lerpZ(1.0F);

            boolean hasPrev = g.trailTicks > 0;
            double px = g.trailX, py = g.trailY, pz = g.trailZ;
            g.trailX = x;
            g.trailY = y;
            g.trailZ = z;
            g.trailTicks++;
            // SBW と同じく、撃ってすぐ (2 tick 以内) は煙を出しません
            if (!hasPrev || g.trailTicks <= 2) continue;

            // 本物の弾が描かれているなら、SBW 本体が煙を出します。
            if (GhostRenderer.removedBySbw(level, g, cam)) continue;   // SBW が既に消した弾
            if (GhostRenderer.isRealEntityShown(level, g, cam)) continue;

            Rule rule = ruleFor(g, level);
            if (rule.style == null) continue;

            Vec3 motion = new Vec3(g.vx, g.vy, g.vz);
            double speed = motion.length();
            if (speed < 1.0E-3 || speed < rule.minSpeed) continue;

            try {
                spawn(level, rule.style, new Vec3(px, py + heightHalf(g.typeId), pz), motion);
            } catch (Throwable ignored) {
                // 煙が出なくても問題ないので無視します
            }
        }
    }

    // ------------------------------------------------------------------

    /** SBW の FastThrowableProjectile の各 Trail 処理と同じ値で煙を出します。 */
    private static void spawn(ClientLevel level, Style style, Vec3 start, Vec3 motion) throws Exception {
        lookup();
        double length = motion.length();
        Vec3 back = motion.normalize().scale(-1.0);
        var random = level.random;

        if (style == Style.SHELL) {
            if (cloudOption == null) return;
            for (double i = 0.0; i < length; i += 2.0) {
                Vec3 pos = start.add(back.scale(i));
                float r = random.nextFloat();
                Object option = cloudOption.newInstance(
                        color(0.6f, 0.58f, 0.57f), (int) (120 + 40 * r), 1.5f + 0.5f * r, 0f, false, false);
                level.addAlwaysVisibleParticle((ParticleOptions) option, true,
                        pos.x + 0.25f * r, pos.y + 0.25f * r, pos.z + 0.25f * r, 0.0, 0.0, 0.0);
            }
            return;
        }

        if (flareOption == null) return;
        int life;
        float fade, sizeAdd, size;
        double spread, step;
        switch (style) {
            case LARGE -> { life = 160; fade = 0.93f; sizeAdd = 0.03f; size = 0.75f; spread = 0.2; step = 2.0; }
            case MEDIUM -> { life = 160; fade = 0.93f; sizeAdd = 0.03f; size = 0.4f; spread = 0.125; step = 1.5; }
            default -> { life = 80; fade = 0.9f; sizeAdd = 0.01f; size = 0.25f; spread = 0.1; step = 1.0; }
        }
        int color = color(0.5f, 0.43f, 0.36f);
        for (double i = 0.0; i < length; i += step) {
            Vec3 pos = start.add(back.scale(i));
            float r = 2 * (random.nextFloat() - 0.5f);
            // CustomFlareOption(色, 寿命, 消え方, 大きさ, アニメ速度, 大きくなる量)
            Object option = flareOption.newInstance(color, life, fade, size, (int) (10 + 8 * r), sizeAdd);
            level.addAlwaysVisibleParticle((ParticleOptions) option, true,
                    pos.x + r * spread, pos.y + r * spread, pos.z + r * spread, 0.0, 0.0, 0.0);
        }
    }

    private static int color(float r, float g, float b) {
        return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    private static double heightHalf(int typeId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.byId(typeId);
        return type != null ? type.getHeight() / 2.0 : 0.0;
    }

    private static void lookup() {
        if (lookedUp) return;
        lookedUp = true;
        try {
            Class<?> c = Class.forName("com.atsuishio.superbwarfare.client.particle.CustomFlareOption");
            flareOption = c.getConstructor(int.class, int.class, float.class, float.class, int.class, float.class);
        } catch (Throwable e) {
            flareOption = null;
        }
        try {
            Class<?> c = Class.forName("com.atsuishio.superbwarfare.client.particle.CustomCloudOption");
            cloudOption = c.getConstructor(int.class, int.class, float.class, float.class, boolean.class, boolean.class);
        } catch (Throwable e) {
            cloudOption = null;
        }
    }

    /** 弾の種類から、煙の種類を決めます (親クラスも含めて、クラス名の末尾で照合)。 */
    private static Rule ruleFor(GhostVehicle g, ClientLevel level) {
        List<? extends String> source = Config.TRAIL_CLASSES.get();
        if (source != rulesSource) {
            // 設定が変わったら判定をやり直します
            RULES.clear();
            rulesSource = source;
        }
        Entity dummy = g.getDummy(level);
        if (dummy == null) return NO_TRAIL;
        return RULES.computeIfAbsent(dummy.getClass(), c -> findRule(c, source));
    }

    private static Rule findRule(Class<?> clazz, List<? extends String> source) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            String simple = c.getSimpleName();
            for (String line : source) {
                Rule r = parse(line, simple);
                if (r != null) return r;
            }
        }
        return NO_TRAIL;
    }

    /** 「クラス名=種類」または「クラス名=種類:最低速度」の 1 行を読みます。合わなければ null。 */
    static Rule parse(String line, String simpleName) {
        if (line == null) return null;
        int eq = line.indexOf('=');
        if (eq <= 0) return null;
        String name = line.substring(0, eq).trim();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(dot + 1);
        if (!name.equals(simpleName)) return null;

        String value = line.substring(eq + 1).trim().toLowerCase();
        double minSpeed = 0.0;
        int colon = value.indexOf(':');
        if (colon >= 0) {
            try {
                minSpeed = Double.parseDouble(value.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                minSpeed = 0.0;
            }
            value = value.substring(0, colon).trim();
        }
        Style style = switch (value) {
            case "shell" -> Style.SHELL;
            case "large" -> Style.LARGE;
            case "medium" -> Style.MEDIUM;
            case "small" -> Style.SMALL;
            default -> null;   // "none" など
        };
        return new Rule(style, minSpeed);
    }
}
