package com.velleagle.superbhorizons.client;

import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.velleagle.superbhorizons.SuperbHorizons;

import java.lang.reflect.Field;

/**
 * Distant Horizons の描画直前に呼ばれ、DH が使っている
 * 近クリップ距離と遠クリップ距離を受け取ります。
 *
 * ■ なぜこれが必要なのか
 * DH は LOD 地形を、通常の描画とは別の遠近設定 (near / far) で描いています。
 * そのため DH の深度テクスチャに入っている値は、
 * バニラの深度バッファの値とそのままでは比較できません。
 * 変換に使う near / far をここで受け取ります。
 *
 * ■ このクラスだけ DH API への依存が必要な理由
 * イベントの登録は「DH の抽象クラスを継承したもの」を渡す必要があり、
 * 動的呼び出しでは実現できません。
 * そのため build.gradle に DH API を compileOnly で追加しています
 * (実行時には DH 本体に同梱されているため、別途の導入は不要です)。
 *
 * DH が入っていない環境ではこのクラスは一切読み込まれません。
 * 呼び出し側で必ず存在確認をしてから触ってください。
 */
public class DhDepthHook extends DhApiBeforeRenderEvent {

    /** DH の近クリップ距離 (ブロック)。 */
    public static volatile float nearClip = 0.0F;
    /** DH の遠クリップ距離 (ブロック)。 */
    public static volatile float farClip = 0.0F;
    /** 一度でも値を受け取れたか。 */
    public static volatile boolean captured = false;

    /**
     * DH が使っている「モデルビュー × 投影」行列 (16 要素)。
     * これを使えば、深度の変換式を仮定せずに正確な値が出せます。
     */
    public static volatile float[] mvmProj = null;

    private static boolean registered = false;
    private static boolean loggedFirstCapture = false;

    /** イベントが 1 度でも呼ばれたか (原因切り分け用)。 */
    public static volatile boolean everCalled = false;

    /** イベントを登録します。何度呼んでも 1 回しか登録されません。 */
    public static void register() {
        if (registered) return;
        registered = true;
        try {
            Object result = DhApiEventRegister.on(DhApiBeforeRenderEvent.class, new DhDepthHook());
            SuperbHorizons.LOGGER.info("[Superb Horizons] DH 描画イベントを登録しました -> {}",
                    describe(result));
        } catch (Throwable e) {
            SuperbHorizons.LOGGER.warn("[Superb Horizons] DH 描画イベントの登録に失敗", e);
        }
    }

    @Override
    public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> param) {
        everCalled = true;
        try {
            Object renderParam = extractRenderParam(param);
            if (renderParam == null) return;

            Float near = readFloat(renderParam, "nearClipPlane");
            Float far = readFloat(renderParam, "farClipPlane");
            if (near == null || far == null || far <= near) return;

            nearClip = near;
            farClip = far;
            captured = true;

            float[] m = readMatrix(renderParam, "dhMvmProjMatrix");
            if (m != null) mvmProj = m;

            if (!loggedFirstCapture) {
                loggedFirstCapture = true;
                SuperbHorizons.LOGGER.info("[Superb Horizons] DH の遠近設定を取得: near={} far={} ({})",
                        near, far, renderParam.getClass().getName());
            }
        } catch (Throwable ignored) {
            // 1 フレーム取り損ねても問題ないので黙って無視します
        }
    }

    /**
     * イベント引数の中から描画情報を取り出します。
     *
     * 注意: ここで「引数のないメソッドを総当たりで呼ぶ」ようなことは絶対にしないでください。
     * DhApiCancelableEventParam には cancelEvent() という
     * DH の描画そのものを中止するメソッドが含まれています。
     * 総当たりで呼ぶと DH が一切描画しなくなります。
     *
     * 公開フィールドを読むだけにとどめます。
     */
    private static Object extractRenderParam(Object param) throws IllegalAccessException {
        if (param == null) return null;
        for (Field f : param.getClass().getFields()) {
            Object v = f.get(param);
            if (isRenderParam(v)) return v;
        }
        return null;
    }

    /**
     * 描画情報かどうかを、継承元まで遡って判定します。
     * 実際に入っている値は DhApiRenderParam を継承した別のクラス
     * (RenderParams など) であることがあります。
     */
    private static boolean isRenderParam(Object v) {
        if (v == null) return false;
        if (v instanceof DhApiRenderParam) return true;
        for (Class<?> c = v.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getSimpleName().equals("DhApiRenderParam")) return true;
        }
        return false;
    }

    private static String describe(Object o) {
        if (o == null) return "null";
        StringBuilder sb = new StringBuilder(o.getClass().getSimpleName()).append('{');
        boolean first = true;
        for (Field f : o.getClass().getFields()) {
            try {
                if (!first) sb.append(", ");
                first = false;
                sb.append(f.getName()).append('=').append(f.get(o));
            } catch (Throwable ignored) {
            }
        }
        return sb.append('}').toString();
    }

    /**
     * DhApiMat4f から 16 要素の配列を取り出します。
     * 内部の持ち方がバージョンで違う可能性があるため、
     * フィールドと取得メソッドの両方を探します。
     */
    private static float[] readMatrix(Object obj, String name) {
        Object mat = null;
        try {
            mat = obj.getClass().getField(name).get(obj);
        } catch (Throwable ignored) {
        }
        if (mat == null) {
            for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    mat = f.get(obj);
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        if (mat == null) return null;

        // 公開フィールドから float[16] を探す
        for (Field f : mat.getClass().getFields()) {
            try {
                Object v = f.get(mat);
                if (v instanceof float[] arr && arr.length >= 16) return arr.clone();
            } catch (Throwable ignored) {
            }
        }
        // 取得メソッドから探す (名前が get で始まるものだけ。副作用のあるものを呼ばないため)
        for (java.lang.reflect.Method m : mat.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (!m.getName().startsWith("get")) continue;
            if (m.getReturnType() != float[].class) continue;
            try {
                Object v = m.invoke(mat);
                if (v instanceof float[] arr && arr.length >= 16) return arr.clone();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Float readFloat(Object obj, String name) {
        try {
            Object v = obj.getClass().getField(name).get(obj);
            return v instanceof Number n ? n.floatValue() : null;
        } catch (Throwable ignored) {
        }
        // 公開フィールドで見つからない場合は継承元まで遡って探します
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof Number n) return n.floatValue();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
