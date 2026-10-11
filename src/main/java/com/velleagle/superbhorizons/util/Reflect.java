package com.velleagle.superbhorizons.util;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Superb Warfare のメソッドを名前で呼び出すための共通処理。
 *
 * ■ なぜリフレクション (動的呼び出し) を使うのか
 * SBW のクラスを直接参照すると、ビルド時に SBW の jar が必要になり、
 * SBW が更新されてメソッド構成が変わるとコンパイルが通らなくなります。
 * 名前で動的に呼び出せば、ビルド設定を変えずに済み、
 * 一部のメソッドが無くなっても「そのアニメーションだけ効かない」で済みます。
 *
 * 検索結果はキャッシュするので、2 回目以降のコストはごくわずかです。
 */
public final class Reflect {

    private Reflect() {}

    /** 見つからなかったことを表す目印 (毎回探し直さないため)。 */
    private static final Method MISSING;

    static {
        Method m;
        try {
            m = Reflect.class.getDeclaredMethod("missingMarker");
        } catch (Throwable e) {
            m = null;
        }
        MISSING = m;
    }

    @SuppressWarnings("unused")
    private static void missingMarker() {}

    private static final Map<String, Method> CACHE = new ConcurrentHashMap<>();

    private static Method find(Class<?> clazz, String name, int paramCount) {
        String key = clazz.getName() + '#' + name + '/' + paramCount;
        Method cached = CACHE.get(key);
        if (cached != null) return cached == MISSING ? null : cached;

        Method found = null;
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() == paramCount && m.getName().equals(name)) {
                found = m;
                break;
            }
        }
        CACHE.put(key, found != null ? found : MISSING);
        return found;
    }

    /** 引数なしのメソッドを呼び、結果を返します。見つからなければ null。 */
    public static Object call(Object target, String name) {
        if (target == null) return null;
        Method m = find(target.getClass(), name, 0);
        if (m == null) return null;
        try {
            return m.invoke(target);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * メソッドを呼び、無ければ同じ意味の公開フィールドを読みます。
     * SBW の GunData は弾数 (ammo) や発射口の番号 (fireIndex) を、
     * getAmmo() などのメソッドではなく公開フィールドで持っているためです。
     */
    public static Object callOrField(Object target, String method, String field) {
        if (target == null) return null;
        if (find(target.getClass(), method, 0) != null) return call(target, method);
        try {
            return target.getClass().getField(field).get(target);
        } catch (Throwable e) {
            return null;
        }
    }

    public static float getFloat(Object target, String name, float fallback) {
        Object v = call(target, name);
        return v instanceof Number n ? n.floatValue() : fallback;
    }

    public static int getInt(Object target, String name, int fallback) {
        Object v = call(target, name);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    public static boolean getBoolean(Object target, String name, boolean fallback) {
        Object v = call(target, name);
        return v instanceof Boolean b ? b : fallback;
    }

    /**
     * 引数 1 つのメソッドを呼びます。
     * 引数の型 (float / double / int / boolean) は自動で合わせます。
     * メソッドが無ければ何もしません。
     *
     * @return 呼び出せたら true
     */
    public static boolean set(Object target, String name, Object value) {
        if (target == null) return false;
        Method m = find(target.getClass(), name, 1);
        if (m == null) return false;
        try {
            m.invoke(target, convert(m.getParameterTypes()[0], value));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static Object convert(Class<?> type, Object value) {
        if (value instanceof Number n) {
            if (type == float.class || type == Float.class) return n.floatValue();
            if (type == double.class || type == Double.class) return n.doubleValue();
            if (type == int.class || type == Integer.class) return n.intValue();
            if (type == long.class || type == Long.class) return n.longValue();
        }
        return value;
    }

    /** そのクラスが指定したメソッドを 1 つでも持っているか。 */
    public static boolean has(Object target, String name, int paramCount) {
        return target != null && find(target.getClass(), name, paramCount) != null;
    }
}
