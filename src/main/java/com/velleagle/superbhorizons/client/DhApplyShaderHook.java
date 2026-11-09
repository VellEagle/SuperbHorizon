package com.velleagle.superbhorizons.client;

import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeApplyShaderRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.velleagle.superbhorizons.SuperbHorizons;

/**
 * Distant Horizons が「描いた内容を Minecraft の画面へコピーする直前」に呼ばれます。
 *
 * ■ なぜこのタイミングなのか
 * DH は自分専用のフレームバッファに LOD 地形を描き、
 * 最後にその内容を Minecraft の画面へコピーします。
 * コピーが終わったあと (描画完了イベント) では、
 * DH の深度テクスチャは既に中身が失われています。
 *
 * このイベントはコピーが走る直前なので、
 * 深度テクスチャに確実に中身が入っています。
 *
 * ■ 注意
 * このイベントはキャンセル可能です。
 * キャンセルすると DH の描画結果が画面に反映されなくなるため、
 * 引数のメソッドを不用意に呼ばないでください。
 */
public class DhApplyShaderHook extends DhApiBeforeApplyShaderRenderEvent {

    private static boolean registered = false;

    /**
     * イベントを登録します。何度呼んでも 1 回しか登録されません。
     * 登録に失敗した場合は false を返し、次に呼ばれたときにもう一度試します。
     */
    public static boolean register() {
        if (registered) return true;
        try {
            Object result = DhApiEventRegister.on(
                    DhApiBeforeApplyShaderRenderEvent.class, new DhApplyShaderHook());
            if (!DhDepthTexture.succeeded(result)) {
                SuperbHorizons.LOGGER.warn("[Superb Horizons] DH コピー直前イベントを登録できませんでした (後でもう一度試します) -> {}", result);
                return false;
            }
            registered = true;
            SuperbHorizons.LOGGER.info("[Superb Horizons] DH コピー直前イベントを登録しました -> {}", result);
            return true;
        } catch (Throwable e) {
            SuperbHorizons.LOGGER.warn("[Superb Horizons] DH コピー直前イベントの登録に失敗 (後でもう一度試します)", e);
            return false;
        }
    }

    @Override
    public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
        try {
            // DH のバッファへゴーストモデルを描き込みます。
            // ここで描くと、LOD 地形との前後関係を GPU が処理してくれます。
            DhModelRenderer.render(false);   // 先の組 (LOD 地形の範囲のゴースト)
        } catch (Throwable ignored) {
            // 1 フレーム取り損ねても問題ないので黙って無視します
        }
    }
}
