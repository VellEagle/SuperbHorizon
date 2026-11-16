package com.velleagle.superbhorizons.client;

import java.lang.reflect.Method;

/**
 * SBW Optimized との連携。
 *
 * ■ なぜ必要か (2026/11/13 の報告: 「ゴーストの一部 (転輪・履帯) がプレイヤーの真上に常に表示される」)
 * SBW Optimized の旧方式の描画 (PolyMeshModel.queueInstance。Superb Modern Combat の転輪・履帯などで使用) は、
 * その場では描かずに「描く予定」として控えておき、後で (本物のエンティティを描き終えた時点で) まとめて描きます。
 * このとき控えるのは部品の行列だけで、描いた時点の「モデルビュー行列」は控えません。
 *
 * ゴーストは、車両の位置をモデルビュー行列の側に入れて描いているため (SBW の簡略モデルを使わせないため)、
 * 後でまとめて描かれると位置の情報が抜け落ち、カメラの位置 (= プレイヤーの真上) に描かれていました。
 * そこで、ゴーストを描いた直後に、その場で控えを描き切らせます。
 *
 * SBW Optimized が入っていない環境では何もしません。
 */
final class SbwOptimizedCompat {

    private SbwOptimizedCompat() {}

    private static boolean lookedUp = false;
    private static Method flushMethod;

    /** SBW Optimized が控えている「描く予定」を、今の描画設定のまま描き切らせます。 */
    static void flushQueuedModels() {
        if (!lookedUp) {
            lookedUp = true;
            try {
                Class<?> c = Class.forName("com.velleagle.sbwoptimized.meshloader.PolyMeshModel",
                        true, SbwOptimizedCompat.class.getClassLoader());
                flushMethod = c.getMethod("flushAllPendingModels");
            } catch (Throwable ignored) {
                flushMethod = null;   // SBW Optimized が無い (または旧方式が無い) 環境
            }
        }
        if (flushMethod == null) return;
        try {
            flushMethod.invoke(null);
        } catch (Throwable ignored) {
            // 1 フレーム描き損ねても問題ないので黙って無視します
        }
    }
}
