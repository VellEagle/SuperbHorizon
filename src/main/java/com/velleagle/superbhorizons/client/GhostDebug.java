package com.velleagle.superbhorizons.client;

import com.velleagle.superbhorizons.Config;
import com.velleagle.superbhorizons.SuperbHorizons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 【デバッグ用】ゴーストが描かれたか、描かれなかったならその理由を、ログ (logs/latest.log) に書き出します。
 * 設定 debugLog = true のときだけ動きます (false のときは何もしません)。
 *
 * ■ 書き出す内容 (ログは文字化けしないよう英語です。行の先頭はすべて "[SH-DEBUG]")
 *  ・change  … ゴーストの状態が変わった瞬間に 1 行。消えた瞬間の理由がここに出ます。
 *  ・summary … 3 秒ごとのまとめ (ゴーストの数・DH の描画処理が呼ばれた回数・各ゴーストの状態)
 *  ・store   … サーバーから届いた一覧で、ゴーストが増えた / 減ったとき
 *  ・error   … 描画中にエラーが起きたとき (同じ内容は 1 度だけ)
 *
 * ■ 1 フレームの流れ
 *  1. DH の描画処理 (DhModelRenderer) が、遠方のゴーストを描くたびに dh(...) で結果を記録
 *  2. 通常の描画処理 (GhostRenderer) が、近くのゴーストを描くたびに vanilla(...) で結果を記録
 *  3. フレームの最後 (AFTER_LEVEL) に endFrame(...) で前のフレームと比べ、変わっていればログに出す
 *
 * 状態の書き方: "dh=<DH 側の結果> vanilla=<通常の描画側の結果>"
 *  drawn          … 描いた
 *  real-shown     … 本物の車両が見えている扱いなので描かなかった (本物に任せた)
 *  near-terrain   … (DH 側) 読み込み済みの地形の中なので、通常の描画側に任せた
 *  far-dh         … (通常側) 遠方なので DH 側に任せた
 *  sbw-removed    … SBW が既に消した弾
 *  no-dummy       … 描画用のダミー車両を作れなかった
 *  error          … 描画中にエラー
 *  dh-not-called  … このフレームでは DH の描画処理そのものが呼ばれなかった
 *  not-seen       … その描画処理が、このゴーストを見ないまま終わった (途中で打ち切られた等)
 */
public final class GhostDebug {

    private GhostDebug() {}

    private static final String P = "[SH-DEBUG] ";

    /** このフレームの記録。 */
    private static final Map<Integer, String> DH_FRAME = new HashMap<>();
    private static final Map<Integer, String> VANILLA_FRAME = new HashMap<>();
    private static int dhCallsThisFrame = 0;
    private static String dhNoteThisFrame = null;
    private static String vanillaNoteThisFrame = null;
    private static boolean dhShaderMode = false;
    private static boolean dhDepthReversed = false;

    /** 前のフレームの状態 (変化の検出用)。 */
    private static final Map<Integer, String> LAST = new HashMap<>();

    /** まとめ用の集計。 */
    private static long lastSummaryMs = 0L;
    private static int framesSinceSummary = 0;
    private static int dhCallsSinceSummary = 0;
    private static int snapshotsSinceSummary = 0;

    /** ログが多すぎるときの間引き (1 秒あたりの change 行の上限)。 */
    private static final int MAX_CHANGES_PER_SECOND = 40;
    private static long changeWindowStartMs = 0L;
    private static int changesInWindow = 0;
    private static int suppressedChanges = 0;

    /** 霧 (フォグ) の設定。遠くのものが霧の色に塗りつぶされていないかを確かめるために記録します。 */
    private static float vanillaFogStart = Float.NaN, vanillaFogEnd = Float.NaN;
    private static float dhFogStart = Float.NaN, dhFogEnd = Float.NaN;

    /** 通常の描画のときの霧の設定。 */
    public static void vanillaFog(float start, float end) {
        vanillaFogStart = start;
        vanillaFogEnd = end;
    }

    /** DH の画面へ描くときの霧の設定。 */
    public static void dhFog(float start, float end) {
        dhFogStart = start;
        dhFogEnd = end;
    }

    /**
     * 【デバッグ表示用】このフレームのゴーストの状態の分類。
     *  "drawn" … どちらかの描画処理で描いた (緑の枠)
     *  "real"  … 本物の車両に任せた (青の枠)
     *  "skip"  … 描かなかった (赤の枠)
     */
    public static String overlayState(int id) {
        String a = DH_FRAME.get(id), b = VANILLA_FRAME.get(id);
        if ("drawn".equals(a) || "drawn".equals(b)) return "drawn";
        if ("real-shown".equals(a) || "real-shown".equals(b)) return "real";
        return "skip";
    }

    /** 同じエラーは 1 度だけ出します。 */
    private static final java.util.Set<String> LOGGED_ERRORS = new java.util.HashSet<>();

    public static boolean on() {
        try {
            return Config.DEBUG_LOG.get();
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 記録 (描画処理の中から呼びます)
    // ------------------------------------------------------------------

    /** DH の描画処理が呼ばれた (1 フレームに複数回呼ばれることもあります)。 */
    public static void dhCall() {
        if (on()) dhCallsThisFrame++;
    }

    /** DH の描画処理が、ゴーストを見る前に打ち切られた理由。 */
    public static void dhNote(String note) {
        if (on()) dhNoteThisFrame = note;
    }

    /** DH の描画処理の設定 (シェーダー使用中か・深度が逆向きか)。 */
    public static void dhInfo(boolean shaderMode, boolean depthReversed) {
        dhShaderMode = shaderMode;
        dhDepthReversed = depthReversed;
    }

    public static void dh(GhostVehicle g, String state) {
        if (on()) DH_FRAME.put(g.entityId, state);
    }

    /** 通常の描画処理が、ゴーストを見る前に打ち切られた理由。 */
    public static void vanillaNote(String note) {
        if (on()) vanillaNoteThisFrame = note;
    }

    public static void vanilla(GhostVehicle g, String state) {
        if (on()) VANILLA_FRAME.put(g.entityId, state);
    }

    /** 描画中のエラー (同じ場所・同じ種類のエラーは 1 度だけ書き出します)。 */
    public static void error(String where, GhostVehicle g, Throwable e) {
        if (!on()) return;
        String key = where + "|" + (g != null ? g.typeId : -1) + "|" + e.getClass().getName();
        if (!LOGGED_ERRORS.add(key)) return;
        SuperbHorizons.LOGGER.warn(P + "error in {} ghost#{} type={}",
                where, g != null ? g.entityId : -1, g != null ? typeName(g.typeId) : "-", e);
    }

    /** サーバーから一覧が届き、ゴーストが増えた / 減ったとき。 */
    public static void store(int entries, List<Integer> added, List<Integer> removed) {
        if (!on()) return;
        snapshotsSinceSummary++;
        if (added.isEmpty() && removed.isEmpty()) return;
        SuperbHorizons.LOGGER.info(P + "store snapshot entries={} added={} removed(not in snapshot)={}",
                entries, added, removed);
    }

    // ------------------------------------------------------------------
    // フレームの終わり
    // ------------------------------------------------------------------

    public static void endFrame(ClientLevel level, Vec3 cam, Frustum frustum) {
        if (!on()) {
            clearFrame();
            LAST.clear();
            return;
        }
        if (level == null || cam == null) {
            clearFrame();
            return;
        }
        framesSinceSummary++;
        dhCallsSinceSummary += dhCallsThisFrame;

        Collection<GhostVehicle> ghosts = GhostVehicleStore.all();
        Map<Integer, String> now = new HashMap<>();
        for (GhostVehicle g : ghosts) {
            String state = stateOf(g);
            now.put(g.entityId, state);
            String prev = LAST.get(g.entityId);
            if (!state.equals(prev)) logChange(level, cam, frustum, g, prev, state);
        }
        LAST.clear();
        LAST.putAll(now);

        long t = System.currentTimeMillis();
        if (t - lastSummaryMs >= 3000L) {
            logSummary(level, cam, frustum, ghosts, now);
            lastSummaryMs = t;
            framesSinceSummary = 0;
            dhCallsSinceSummary = 0;
            snapshotsSinceSummary = 0;
        }
        clearFrame();
    }

    private static String stateOf(GhostVehicle g) {
        String dh = DH_FRAME.get(g.entityId);
        if (dh == null) {
            if (dhCallsThisFrame == 0) {
                dh = "dh-not-called" + (dhNoteThisFrame != null ? "(" + dhNoteThisFrame + ")" : "");
            } else {
                dh = dhNoteThisFrame != null ? dhNoteThisFrame : "not-seen";
            }
        }
        String vanilla = VANILLA_FRAME.get(g.entityId);
        if (vanilla == null) vanilla = vanillaNoteThisFrame != null ? vanillaNoteThisFrame : "not-seen";
        return "dh=" + dh + " vanilla=" + vanilla;
    }

    private static void logChange(ClientLevel level, Vec3 cam, Frustum frustum,
                                  GhostVehicle g, String prev, String state) {
        long t = System.currentTimeMillis();
        if (t - changeWindowStartMs >= 1000L) {
            if (suppressedChanges > 0) {
                SuperbHorizons.LOGGER.info(P + "({} change lines suppressed in the last second)", suppressedChanges);
            }
            changeWindowStartMs = t;
            changesInWindow = 0;
            suppressedChanges = 0;
        }
        if (++changesInWindow > MAX_CHANGES_PER_SECOND) {
            suppressedChanges++;
            return;
        }
        SuperbHorizons.LOGGER.info(P + "change ghost#{} type={} {} | {} -> {} | {}",
                g.entityId, typeName(g.typeId), distanceText(g, cam),
                prev == null ? "(new)" : prev, state, detail(level, cam, frustum, g));
    }

    private static void logSummary(ClientLevel level, Vec3 cam, Frustum frustum,
                                   Collection<GhostVehicle> ghosts, Map<Integer, String> states) {
        Minecraft mc = Minecraft.getInstance();
        Map<String, Integer> counts = new TreeMap<>();
        for (String s : states.values()) counts.merge(s, 1, Integer::sum);
        SuperbHorizons.LOGGER.info(P + "summary ghosts={} frames={} dhCalls={} snapshots={} | renderGhosts={} ghostStyle={} useDhModel={} fullDetail={} | dhMatrix={} shaderMode={} depthReversed={} | camera=({}, {}, {}) renderDistance={} entityDistanceScale={} mixinActive={} dhFadeStart={} dhNearClipReflect={} | fog vanilla={}..{} dh={}..{} | states={}",
                ghosts.size(), framesSinceSummary, dhCallsSinceSummary, snapshotsSinceSummary,
                Config.RENDER_GHOSTS.get(), Config.GHOST_STYLE.get(), GhostRenderer.useDhModel(),
                Config.GHOST_FULL_DETAIL.get(),
                DhDepthHookState.hasMatrix(), dhShaderMode, dhDepthReversed,
                fmt(cam.x), fmt(cam.y), fmt(cam.z),
                mc.options.getEffectiveRenderDistance(), mc.options.entityDistanceScaling().get(),
                RealRenderTracker.isActive(), fmt(GhostRenderer.dhFadeStartDistance()), GhostRenderer.dhNearClipReflected(),
                fmt(vanillaFogStart), fmt(vanillaFogEnd), fmt(dhFogStart), fmt(dhFogEnd),
                counts);
        int n = 0;
        for (GhostVehicle g : ghosts) {
            if (++n > 12) {
                SuperbHorizons.LOGGER.info(P + "  ... and {} more", ghosts.size() - 12);
                break;
            }
            SuperbHorizons.LOGGER.info(P + "  ghost#{} type={} {} | {} | {}",
                    g.entityId, typeName(g.typeId), distanceText(g, cam),
                    states.get(g.entityId), detail(level, cam, frustum, g));
        }
    }

    /** 本物の車両の状態など、原因を絞り込むための情報。 */
    private static String detail(ClientLevel level, Vec3 cam, Frustum frustum, GhostVehicle g) {
        StringBuilder sb = new StringBuilder();
        boolean near = false;
        try {
            near = GhostRenderer.insideLoadedTerrain(level, g, cam);
        } catch (Throwable ignored) {
        }
        boolean chunkLoaded = false;
        try {
            chunkLoaded = level.getChunkSource().hasChunk(((int) Math.floor(g.x)) >> 4, ((int) Math.floor(g.z)) >> 4);
        } catch (Throwable ignored) {
        }
        sb.append("nearTerrain=").append(near).append(" chunkLoaded=").append(chunkLoaded);
        try {
            sb.append(" inDhFade=").append(GhostRenderer.inDhFadeZone(g, cam));
        } catch (Throwable ignored) {
        }
        sb.append(" ghostSectionCompiled=").append(GhostRenderer.sectionCompiled(
                net.minecraft.core.BlockPos.containing(g.x, g.y + 1.0, g.z)));

        Entity real = level.getEntity(g.entityId);
        if (real == null) {
            sb.append(" real=none");
        } else {
            sb.append(" real=loaded removed=").append(real.isRemoved());
            try {
                sb.append(" realShouldRender=").append(real.shouldRender(cam.x, cam.y, cam.z));
            } catch (Throwable e) {
                sb.append(" realShouldRender=error");
            }
            // Minecraft が実際に本物の車両を描くかどうか (画面外・SBW 独自の判定なども含む)
            if (frustum != null) {
                try {
                    boolean drawnByVanilla = Minecraft.getInstance().getEntityRenderDispatcher()
                            .shouldRender(real, frustum, cam.x, cam.y, cam.z);
                    sb.append(" realDispatcherShouldRender=").append(drawnByVanilla);
                } catch (Throwable e) {
                    sb.append(" realDispatcherShouldRender=error");
                }
            }
            sb.append(" realInvisible=").append(real.isInvisible());
            sb.append(" realSectionCompiled=").append(GhostRenderer.sectionCompiled(real.blockPosition()));
            sb.append(" realTrusted=").append(GhostRenderer.realEntityDrawsItself(level, g, cam));
            sb.append(" realRenderedRecently=").append(RealRenderTracker.renderedRecently(g.entityId));
            double[] rp = RealRenderTracker.lastRenderPos(g.entityId);
            if (rp != null) {
                double ex = rp[0] - real.getX(), ey = rp[1] - real.getY(), ez = rp[2] - real.getZ();
                sb.append(" realRenderPosGap=").append(fmt(Math.sqrt(ex * ex + ey * ey + ez * ez)));
            }
            double dx = real.getX() - g.x, dy = real.getY() - g.y, dz = real.getZ() - g.z;
            sb.append(" realGhostGap=").append(fmt(Math.sqrt(dx * dx + dy * dy + dz * dz)));
        }
        if (frustum != null) {
            try {
                double r = 6.0;
                boolean inView = frustum.isVisible(new net.minecraft.world.phys.AABB(
                        g.x - r, g.y - 1.0, g.z - r, g.x + r, g.y + r, g.z + r));
                sb.append(" ghostInView=").append(inView);
            } catch (Throwable ignored) {
            }
        }
        long age = System.currentTimeMillis() - g.lastUpdateMs;
        sb.append(" lastUpdateMsAgo=").append(age);
        return sb.toString();
    }

    private static String distanceText(GhostVehicle g, Vec3 cam) {
        double dx = g.x - cam.x, dy = g.y - cam.y, dz = g.z - cam.z;
        return "dist=" + fmt(Math.sqrt(dx * dx + dy * dy + dz * dz))
                + " horiz=" + fmt(Math.sqrt(dx * dx + dz * dz))
                + " pos=(" + fmt(g.x) + ", " + fmt(g.y) + ", " + fmt(g.z) + ")";
    }

    private static String typeName(int typeId) {
        try {
            return String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(BuiltInRegistries.ENTITY_TYPE.byId(typeId)));
        } catch (Throwable e) {
            return "#" + typeId;
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static void clearFrame() {
        DH_FRAME.clear();
        VANILLA_FRAME.clear();
        dhCallsThisFrame = 0;
        dhNoteThisFrame = null;
        vanillaNoteThisFrame = null;
    }

    /** DH の行列を受け取れているか (DH が無い環境でも安全に呼べるよう、名前で調べます)。 */
    private static final class DhDepthHookState {
        static boolean hasMatrix() {
            try {
                // DH が入っていない環境では、ここで読み込みに失敗して false になります
                Class<?> c = Class.forName("com.velleagle.superbhorizons.client.DhDepthHook");
                Object m = c.getField("mvmProj").get(null);
                return m != null;
            } catch (Throwable e) {
                return false;
            }
        }
    }
}
