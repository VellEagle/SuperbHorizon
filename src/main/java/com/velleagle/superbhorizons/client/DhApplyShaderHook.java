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

    public static void register() {
        if (registered) return;
        registered = true;
        try {
            Object result = DhApiEventRegister.on(
                    DhApiBeforeApplyShaderRenderEvent.class, new DhApplyShaderHook());
            SuperbHorizons.LOGGER.info("[Superb Horizons] DH コピー直前イベントを登録しました -> {}", result);
        } catch (Throwable e) {
            SuperbHorizons.LOGGER.warn("[Superb Horizons] DH コピー直前イベントの登録に失敗", e);
        }
    }

    @Override
    public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
        try {
            // DH のバッファへゴーストモデルを描き込みます。
            // ここで描くと、LOD 地形との前後関係を GPU が処理してくれます。
            DhModelRenderer.render();
        } catch (Throwable ignored) {
            // 1 フレーム取り損ねても問題ないので黙って無視します
        }
    }
}
