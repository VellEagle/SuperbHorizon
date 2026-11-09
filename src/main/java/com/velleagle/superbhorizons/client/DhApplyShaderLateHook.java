package com.velleagle.superbhorizons.client;

import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeApplyShaderRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.velleagle.superbhorizons.SuperbHorizons;

/**
 * DH の「画面へコピーする直前」のイベントの 2 つ目の受け口 (後の組)。
 *
 * ■ なぜ 2 つに分けるのか (Simple Clouds との互換性)
 * Simple Clouds は、このイベントの中で DH の奥行きを写し取り、
 * 雲・嵐の霧が「どこに物があるか」を判断するのに使います。
 *
 *  ・LOD 地形の範囲 (描画距離の外) にいるゴースト
 *      周りの LOD 地形も奥行きに入っているので、ゴーストも Simple Clouds より先に描いて
 *      奥行きに入れます (DhApplyShaderHook。雲の手前を飛ぶ航空機などが正しく雲の手前に出ます)。
 *  ・描画距離の中 (Minecraft が地形を描いている範囲) にいるゴースト
 *      周りの地形は Minecraft が描いているため、DH の奥行きには入っていません。
 *      ここでゴーストだけ先に描くと、Simple Clouds から見て「ゴーストの形だけ物がある」ことになり、
 *      雲や霧がゴーストの形にくり抜かれて、車両と周りの地形が透けて見えてしまいます。
 *      そこで、Simple Clouds が奥行きを写し取った後 (= このクラス) で描き、周りの地形と同じ扱いにします。
 *
 * このクラスは最初の描画フレームで登録するので、Mod の読み込み時に登録する
 * Simple Clouds などより必ず後に呼ばれます。
 */
public class DhApplyShaderLateHook extends DhApiBeforeApplyShaderRenderEvent {

    private static boolean registered = false;

    /** 後の組が登録済みか。登録できていなければ、先の組がすべてのゴーストを描きます。 */
    public static boolean isRegistered() {
        return registered;
    }

    public static boolean register() {
        if (registered) return true;
        try {
            Object result = DhApiEventRegister.on(
                    DhApiBeforeApplyShaderRenderEvent.class, new DhApplyShaderLateHook());
            if (!DhDepthTexture.succeeded(result)) {
                SuperbHorizons.LOGGER.warn("[Superb Horizons] DH late apply event could not be registered -> {}", result);
                return false;
            }
            registered = true;
            SuperbHorizons.LOGGER.info("[Superb Horizons] DH late apply event registered (after other DH add-ons)");
            return true;
        } catch (Throwable e) {
            SuperbHorizons.LOGGER.warn("[Superb Horizons] DH late apply event registration failed", e);
            return false;
        }
    }

    @Override
    public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
        try {
            DhModelRenderer.render(true);
        } catch (Throwable ignored) {
            // 1 フレーム取り損ねても問題ないので黙って無視します
        }
    }
}
