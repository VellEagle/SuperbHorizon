package com.velleagle.superbhorizons.util;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 車両の「武器データ」(SBW の GUN_DATA_MAP) を、ゴーストへそのまま写します。
 *
 * ■ なぜ丸ごと写すのか
 * 翼下のミサイルや爆弾などの「弾が入っていると見える武装」は、
 * 弾数だけでなく「選択中の弾種」(例: Mk-82 と Mk-84 の切り替え) によって
 * 表示する模型や位置が変わります。弾数だけを写しても正しく表示されません。
 *
 * そこで、SBW 本体が「近くのプレイヤーへ車両を同期するとき」に使っている
 * 武器データの書き出し・読み込み処理をそのまま使い、サーバーの武器データを
 * ゴーストへ丸ごと写します。これで本物の車両と全く同じ状態になります。
 *
 * SBW のクラスは名前で探すので、SBW の構成が変わってもこの機能が止まるだけで済みます。
 */
public final class GunDataSync {

    private GunDataSync() {}

    /** クラスごとの GUN_DATA_MAP (見つからなければ NONE)。 */
    private static final Map<Class<?>, Object> ACCESSORS = new ConcurrentHashMap<>();
    private static final Object NONE = new Object();

    /** 1 台分の武器データの上限 (壊れたデータで大量に読み込まないための安全策)。 */
    public static final int MAX_BYTES = 32768;

    /** 車両の武器データを書き出します。武器を持たない・読めない場合は null。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static byte[] write(Entity vehicle) {
        EntityDataAccessor accessor = accessor(vehicle.getClass());
        if (accessor == null) return null;
        try {
            Object value = vehicle.getEntityData().get(accessor);
            if (!(value instanceof Map<?, ?> m) || m.isEmpty()) return null;
            EntityDataSerializer serializer = accessor.getSerializer();
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256));
            serializer.write(buf, value);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            buf.release();
            return out.length <= MAX_BYTES ? out : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 書き出した武器データを、描画用ダミーへ読み込ませます。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean apply(Entity dummy, byte[] data) {
        if (dummy == null || data == null) return false;
        EntityDataAccessor accessor = accessor(dummy.getClass());
        if (accessor == null) return false;
        try {
            EntityDataSerializer serializer = accessor.getSerializer();
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            Object value = serializer.read(buf);
            if (value == null) return false;
            // SBW 本体の同期と同じく、エンティティのデータとして設定します。
            // SBW 側はこれを受けて武器データの作り直しを自動で行います。
            dummy.getEntityData().set(accessor, value);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @SuppressWarnings("rawtypes")
    private static EntityDataAccessor accessor(Class<?> clazz) {
        Object found = ACCESSORS.computeIfAbsent(clazz, GunDataSync::findAccessor);
        return found == NONE ? null : (EntityDataAccessor) found;
    }

    /** 親クラスをさかのぼって、GUN_DATA_MAP という名前の静的フィールドを探します。 */
    private static Object findAccessor(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != Entity.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("GUN_DATA_MAP");
                if (!Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof EntityDataAccessor<?>) return v;
            } catch (NoSuchFieldException ignored) {
                // 親クラスを探します
            } catch (Throwable e) {
                return NONE;
            }
        }
        return NONE;
    }
}
