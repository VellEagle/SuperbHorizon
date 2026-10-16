package com.velleagle.superbhorizons.client;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * Minecraft が本物の車両エンティティを「実際に描いた」ことを記録します。
 *
 * ■ なぜ必要か (2026/10/15 のデバッグで判明)
 * 描画距離の境目付近などでは、本物の車両が読み込まれていて、Minecraft の事前の判定も
 * すべて「描く」なのに、実際には描かれないことがありました。事前の判定を信じてゴーストを消すと、
 * 本物もゴーストも見えなくなります。
 * そこで Mixin (LevelRendererMixin) で「本物を描く処理が実際に呼ばれた」瞬間を記録し、
 * 直前のフレームまでに本物が描かれていた車両だけ、ゴーストを描かないようにします。
 * 理由が何であれ (地形が未準備・他の Mod による非表示など)、本物が描かれなければゴーストが出ます。
 *
 * ■ Mixin が動かなかった場合
 * 一度も記録が来なければ isActive() が false のままになり、従来の判定に戻ります (GhostRenderer)。
 */
public final class RealRenderTracker {

    private RealRenderTracker() {}

    /** 車両の ID → 最後に本物が描かれたフレーム番号。 */
    private static final Map<Integer, Long> LAST_RENDER_FRAME = new HashMap<>();
    /** 【デバッグ用】最後に本物が描かれた位置 (Minecraft が実際に使った補間後の位置)。 */
    private static final Map<Integer, double[]> LAST_RENDER_POS = new HashMap<>();

    private static long frame = 1L;
    private static boolean active = false;

    /** Mixin から呼ばれます: Minecraft が本物のエンティティを描こうとした。 */
    public static void onRenderEntity(Entity entity, float partialTick) {
        active = true;
        int id = entity.getId();
        if (GhostVehicleStore.get(id) == null) return;   // ゴーストのある車両だけ記録します
        LAST_RENDER_FRAME.put(id, frame);
        if (GhostDebug.on()) {
            LAST_RENDER_POS.put(id, new double[] {
                    Mth.lerp(partialTick, entity.xOld, entity.getX()),
                    Mth.lerp(partialTick, entity.yOld, entity.getY()),
                    Mth.lerp(partialTick, entity.zOld, entity.getZ())});
        }
    }

    /** フレームの終わり (GhostRenderer の AFTER_LEVEL) に呼びます。 */
    public static void endFrame() {
        frame++;
        if (frame % 1200L == 0L) {
            // 古い記録を片付けます (1 分ほど描かれていない車両)
            LAST_RENDER_FRAME.values().removeIf(f -> frame - f > 1200L);
            LAST_RENDER_POS.keySet().retainAll(LAST_RENDER_FRAME.keySet());
        }
    }

    /** 記録の仕組み (Mixin) が動いているか。 */
    public static boolean isActive() {
        return active;
    }

    /**
     * このフレームか直前のフレームで、本物が実際に描かれたか。
     * DH 側の描画は本物の描画より前に行われるので、直前のフレームまでの記録を見ます。
     */
    public static boolean renderedRecently(int id) {
        Long f = LAST_RENDER_FRAME.get(id);
        return f != null && f >= frame - 1L;
    }

    /** 【デバッグ用】最後に本物が描かれた位置。記録が無ければ null。 */
    public static double[] lastRenderPos(int id) {
        return LAST_RENDER_POS.get(id);
    }

    public static void clear() {
        LAST_RENDER_FRAME.clear();
        LAST_RENDER_POS.clear();
    }
}
