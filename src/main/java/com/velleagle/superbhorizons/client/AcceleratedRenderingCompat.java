package com.velleagle.superbhorizons.client;

import java.lang.reflect.Method;

/**
 * Accelerated Rendering (エンティティ描画を GPU でまとめて行う高速化 Mod) との連携。
 *
 * ■ なぜ必要か (2026/11/13: 「本物の車両から離れてゴーストに切り替わった瞬間、プレイヤーの真上にモデルが出る」)
 * Accelerated Rendering は、エンティティの頂点をその場では描かずに控えておき、後でまとめて GPU で描きます。
 * このとき控えるのは部品の行列 (PoseStack) だけで、描いた時点の「モデルビュー行列」は控えません。
 *
 * ゴーストは、車両の位置をモデルビュー行列の側に入れて描いているため (SBW の簡略モデルを使わせないため)、
 * 後でまとめて描かれると位置の情報が抜け落ち、カメラの位置 (= プレイヤーの真上) に描かれていました。
 * また、DH の画面に描くはずのゴーストが、通常の画面に描かれてしまいます。
 *
 * そこで、ゴーストを描いている間だけ、Accelerated Rendering に「通常の描き方を使う」よう頼みます
 * (Accelerated Rendering 自身が GUI の描画などで使っている公開の切り替え方法です)。
 * Accelerated Rendering が入っていない環境では何もしません。
 */
final class AcceleratedRenderingCompat {

    private AcceleratedRenderingCompat() {}

    private static final String[] FEATURES = {
            "com.github.argon4w.acceleratedrendering.features.entities.AcceleratedEntityRenderingFeature",
            "com.github.argon4w.acceleratedrendering.features.items.AcceleratedItemRenderingFeature",
            "com.github.argon4w.acceleratedrendering.features.text.AcceleratedTextRenderingFeature",
    };

    private static boolean lookedUp = false;
    private static Method[] useVanilla;
    private static Method[] reset;

    /**
     * ゴーストを描く直前に呼びます。通常の描き方に切り替えた場合は true を返します。
     * true が返ったら、描き終わった後に必ず {@link #restore(boolean)} を呼んでください。
     */
    static boolean useVanillaPipeline() {
        if (!lookedUp) {
            lookedUp = true;
            try {
                Method[] u = new Method[FEATURES.length];
                Method[] r = new Method[FEATURES.length];
                for (int i = 0; i < FEATURES.length; i++) {
                    Class<?> c = Class.forName(FEATURES[i], true, AcceleratedRenderingCompat.class.getClassLoader());
                    u[i] = c.getMethod("useVanillaPipeline");
                    r[i] = c.getMethod("resetPipeline");
                }
                useVanilla = u;
                reset = r;
            } catch (Throwable ignored) {
                useVanilla = null;   // Accelerated Rendering が無い (または仕組みが違う) 環境
                reset = null;
            }
        }
        if (useVanilla == null) return false;
        int pushed = 0;
        try {
            for (Method m : useVanilla) {
                m.invoke(null);
                pushed++;
            }
            return true;
        } catch (Throwable e) {
            // 途中で失敗した場合は、切り替えた分だけ元に戻します
            for (int i = 0; i < pushed; i++) {
                try {
                    reset[i].invoke(null);
                } catch (Throwable ignored) {
                }
            }
            return false;
        }
    }

    /** {@link #useVanillaPipeline()} が true を返したときに、描き終わった後で呼びます。 */
    static void restore(boolean pushed) {
        if (!pushed || reset == null) return;
        for (Method m : reset) {
            try {
                m.invoke(null);
            } catch (Throwable ignored) {
            }
        }
    }
}
