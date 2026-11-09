package com.velleagle.superbhorizons.client;

import com.velleagle.superbhorizons.SuperbHorizons;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Distant Horizons との接続の準備。
 *
 *  1. DH のイベント (描画直前・画面へコピーする直前) を登録します。
 *     ゴーストは「コピー直前」のイベントの中で DH のバッファへ描かれます (DhModelRenderer)。
 *  2. DH の深度テクスチャの番号を取り出します (シェーダー使用時の遮蔽処理で使います)。
 *
 * DH が入っていない環境では何もしません。
 */
public class DhDepthTexture {

    private static boolean initialized = false;

    private static Method getDepthTextureIdMethod;
    private static Field renderProxyField;

    /**
     * Mod の読み込み中 (コンストラクタ) に呼び、DH のイベントをできるだけ早く登録します。
     *
     * ■ なぜ早く登録するのか (他の Mod との互換性)
     * Simple Clouds など「DH の LOD に合わせて遠くの物を描く Mod」も、同じ DH のイベントを使っています。
     * DH は登録された順にイベントを呼ぶため、以前のように最初の描画フレームで登録すると、
     * 必ず他の Mod の後になります。すると、
     *   ・Simple Clouds が DH の奥行きを先に写し取るので、雲がゴーストの手前に描かれてしまう
     *   ・他の Mod が変えた OpenGL の設定 (深度のクリア値など) のせいで、深度の向きの判定を誤り、
     *     遠くのゴーストが消えたり LOD 地形を貫通して見えたりする
     * といった問題が起きます。先に登録して、ゴーストを先に DH のバッファへ描き込みます。
     * ここで失敗しても、従来どおり最初の描画フレーム (init) でもう一度登録を試みます。
     */
    public static void registerEventsEarly() {
        if (!ModList.get().isLoaded("distanthorizons")) return;
        try {
            boolean depth = DhDepthHook.register();
            boolean apply = DhApplyShaderHook.register();
            if (depth && apply) {
                SuperbHorizons.LOGGER.info("[Superb Horizons] DH events registered early (before other DH add-ons)");
            }
        } catch (Throwable e) {
            SuperbHorizons.LOGGER.warn("[Superb Horizons] early DH event registration failed; retrying on first frame", e);
        }
    }

    /**
     * DH の登録結果 (DhApiResult) が成功かどうか。
     * 結果の形が分からない場合は、従来どおり成功として扱います。
     */
    static boolean succeeded(Object result) {
        if (result == null) return true;
        Object success = readField(result, "success");
        return !(success instanceof Boolean b) || b;
    }

    public static void init() {
        if (initialized) return;
        initialized = true;

        if (!ModList.get().isLoaded("distanthorizons")) {
            SuperbHorizons.LOGGER.info("[Superb Horizons] Distant Horizons not found; DH integration disabled");
            return;
        }

        try {
            DhDepthHook.register();        // 遠近設定と投影行列を受け取る
            DhApplyShaderHook.register();  // 画面へコピーする直前にゴーストを描く (本命)
            // 描画距離の中のゴースト用。他の Mod (Simple Clouds など) より後に呼ばれるよう、ここで登録します
            DhApplyShaderLateHook.register();

            Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
            renderProxyField = delayed.getField("renderProxy");

            Object proxy = renderProxyField.get(null);
            if (proxy != null) {
                getDepthTextureIdMethod = proxy.getClass().getMethod("getDhDepthTextureId");
            }
            SuperbHorizons.LOGGER.info("[Superb Horizons] DH integration ready");
        } catch (Throwable e) {
            SuperbHorizons.LOGGER.warn("[Superb Horizons] DH integration failed", e);
        }
    }

    /** DH の深度テクスチャの番号。取得できなければ -1。(シェーダー使用時の遮蔽処理から使います) */
    static int fetchDepthTextureId() {
        try {
            if (renderProxyField == null) return -1;
            Object proxy = renderProxyField.get(null);
            if (proxy == null) return -1;
            if (getDepthTextureIdMethod == null) {
                getDepthTextureIdMethod = proxy.getClass().getMethod("getDhDepthTextureId");
            }
            Object result = getDepthTextureIdMethod.invoke(proxy);
            if (result == null) return -1;

            Object success = readField(result, "success");
            if (success instanceof Boolean b && !b) return -1;

            Object payload = readField(result, "payload");
            return payload instanceof Number n ? n.intValue() : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    private static Object readField(Object obj, String name) {
        try {
            return obj.getClass().getField(name).get(obj);
        } catch (Throwable e) {
            return null;
        }
    }
}
