package com.velleagle.superbhorizons.client;

import com.velleagle.superbhorizons.Config;
import com.velleagle.superbhorizons.SuperbHorizons;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * Distant Horizons の深度 (奥行き) の値が「どちら向き」かを判定します。
 *
 * ■ なぜ必要か
 * 深度の値には 2 通りの流儀があります。
 *   normal   : 手前ほど小さく、奥ほど大きい (Minecraft 本体と同じ)
 *   reversed : 手前ほど大きく、奥ほど小さい (遠距離の精度を上げるための手法)
 *
 * Minecraft の描画設定は「normal」を前提に、深度の比較を
 * 「自分の値が既存の値以下なら描く (LEQUAL)」で行います。
 * DH が reversed で描いている場合、この比較が完全に逆になり、
 *   ・LOD 地形の手前にあるはずの部分が消える
 *   ・LOD 地形の奥にあるはずの部分が透けて見える
 * という症状になります。
 *
 * ■ 判定方法
 * 1. DH の「モデルビュー × 投影」行列で、視線の先の近い点と遠い点を変換し、
 *    どちらの深度が大きいかを比べます (行列で逆向きにしている場合)。
 * 2. OpenGL の深度範囲 (glDepthRange) が逆向きに設定されているかを見ます。
 * 3. 深度のクリア値 (何も無い所の値) が 0 かどうかを見ます。
 * GL の状態の問い合わせは負荷がかかるため、1 秒に 1 回だけ行います。
 */
public final class DhDepthDirection {

    private DhDepthDirection() {}

    private static final FloatBuffer RANGE = BufferUtils.createFloatBuffer(16);

    private static long lastGlQueryMs = 0L;
    private static boolean glRangeReversed = false;
    private static float glClearDepth = 1.0F;

    private static Boolean lastLogged = null;
    private static boolean lastLoggedMode = true;

    /** DH の深度が逆向き (手前ほど大きい) なら true。 */
    public static boolean isReversed(Matrix4f dhMatrix, Camera camera) {
        return isReversed(dhMatrix, camera, true);
    }

    /**
     * @param useClearValue 「何も無い所の値」も判定に使うか。
     *                      シェーダー使用時は Oculus が LOD を自前の設定で描くため、
     *                      DH が残した値は当てにならないので使いません。
     */
    public static boolean isReversed(Matrix4f dhMatrix, Camera camera, boolean useClearValue) {
        String setting = Config.DH_DEPTH_DIRECTION.get().trim().toLowerCase();
        if (setting.equals("normal")) return false;
        if (setting.equals("reversed")) return true;

        Boolean byMatrix = reversedByMatrix(dhMatrix, camera);
        queryGlState();

        boolean reversed;
        if (byMatrix != null) {
            reversed = byMatrix ^ glRangeReversed;
        } else {
            reversed = glRangeReversed;
        }
        // 行列と深度範囲からは normal に見えても、
        // 何も無い所の値が 0 なら reversed で描かれています。
        if (useClearValue && !reversed && glClearDepth < 0.5F) reversed = true;

        if (lastLogged == null || lastLogged != reversed || lastLoggedMode != useClearValue) {
            lastLogged = reversed;
            lastLoggedMode = useClearValue;
            // このログは文字化けしても読めるよう英語にしています
            // (DH の深度の向き / 行列からの判定 / 深度範囲が逆か / クリア値 / 設定値)
            SuperbHorizons.LOGGER.info(
                    "[Superb Horizons] DH depth direction: {} (matrix={}, depthRangeReversed={}, clearDepth={}, setting={}, shaderMode={})",
                    reversed ? "REVERSED" : "NORMAL",
                    byMatrix == null ? "unknown" : (byMatrix ? "reversed" : "normal"),
                    glRangeReversed, glClearDepth, setting, !useClearValue);
        }
        return reversed;
    }

    /** 設定変更・再ログイン時に呼び、次回の判定結果をログに出し直します。 */
    public static void reset() {
        lastLogged = null;
        lastGlQueryMs = 0L;
    }

    /**
     * 視線の先の「近い点」と「遠い点」を DH の行列で変換し、深度を比べます。
     * 近い点の方が大きければ逆向きです。判定できない場合は null。
     */
    private static Boolean reversedByMatrix(Matrix4f m, Camera camera) {
        if (m == null || camera == null) return null;
        float near = DhDepthHook.captured ? DhDepthHook.nearClip : 0.0F;
        float far = DhDepthHook.captured ? DhDepthHook.farClip : 0.0F;
        if (near <= 0.0F || far <= near) {
            near = 16.0F;
            far = 4096.0F;
        }
        float d1 = near * 1.5F;
        float d2 = Math.max(d1 * 2.0F, Math.min(far * 0.5F, near * 50.0F));

        Vector3f look = camera.getLookVector();
        Float z1 = ndcDepth(m, look, d1);
        Float z2 = ndcDepth(m, look, d2);
        if (z1 == null || z2 == null || Math.abs(z1 - z2) < 1.0e-7F) return null;
        return z1 > z2;
    }

    private static Float ndcDepth(Matrix4f m, Vector3f look, float dist) {
        Vector4f v = new Vector4f(look.x() * dist, look.y() * dist, look.z() * dist, 1.0F);
        m.transform(v);
        if (!(v.w > 1.0e-6F) || !Float.isFinite(v.z)) return null;
        return v.z / v.w;
    }

    private static void queryGlState() {
        long now = System.currentTimeMillis();
        if (now - lastGlQueryMs < 1000L) return;
        lastGlQueryMs = now;
        try {
            RANGE.clear();
            GL11.glGetFloatv(GL11.GL_DEPTH_RANGE, RANGE);
            glRangeReversed = RANGE.get(0) > RANGE.get(1);
            glClearDepth = GL11.glGetFloat(GL11.GL_DEPTH_CLEAR_VALUE);
        } catch (Throwable ignored) {
            glRangeReversed = false;
            glClearDepth = 1.0F;
        }
    }
}
