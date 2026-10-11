package com.velleagle.superbhorizons.client;

import com.velleagle.superbhorizons.SuperbHorizons;
import net.neoforged.fml.ModList;

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
