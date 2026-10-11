package com.velleagle.superbhorizons.server;

import com.velleagle.superbhorizons.Config;
import com.velleagle.superbhorizons.SuperbHorizons;
import com.velleagle.superbhorizons.network.GhostSnapshotPacket.Shot;
import com.velleagle.superbhorizons.util.Reflect;
import com.velleagle.superbhorizons.network.GhostSnapshotPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * サーバー側で「どの車両が、どの武器を撃ったか」を記録します。
 *
 * ■ 仕組み
 * Superb Warfare は、武器を撃ち終えた直後に ShootEvent.Post というイベントを出します
 * (SBW 0.8.9 以降の公開 API)。これを受け取り、
 *   ・撃った車両
 *   ・武器の名前 (例: "Cannon")
 *   ・砲身の番号 (連装砲やミサイルポッドで、どの発射口から出たか)
 * を記録しておきます。
 * 記録は次のゴースト送信 (VehicleTracker) で一緒に送られ、遠くのプレイヤーの画面で
 * ゴーストに射撃アニメーション (砲口の閃光を含む) を再生させます。
 *
 * ■ なぜ SBW 本体の通知を使わないのか
 * SBW も射撃時に全プレイヤーへ通知を送っていますが、受け取った側は
 * 「自分の画面に読み込まれている車両」しか探さないため、遠くの車両 (= ゴースト) では
 * 捨てられてしまいます。そこで、こちらで同じ内容を記録し直しています。
 *
 * SBW のクラスは名前で呼び出すため、SBW の jar が無くてもビルドでき、
 * SBW の構成が変わってもこの機能が止まるだけで済みます。
 */
public final class FireTracker {

    private FireTracker() {}

    /** 1 回の送信間隔に記録する射撃数の上限 (連射の速い機関砲で通信量が膨らまないように)。 */
    private static final int MAX_SHOTS_PER_VEHICLE = 8;

    /** 車両の ID → 前回の送信以降の射撃。 */
    private static final Map<Integer, List<Shot>> PENDING = new HashMap<>();

    private static boolean registered = false;

    // --- SBW の部品 (名前で探して覚えておきます) ---
    private static Field parametersField;           // ShootEvent → ShootParameters を探す手がかり
    private static Method getParameters;            // ShootEvent.getParameters()
    private static Field ammoSupplierField;         // ShootParameters.ammoSupplier
    private static Field shooterField;              // ShootParameters.shooter
    private static Field dataField;                 // ShootParameters.data
    private static Object shootPosProp;             // GunProp.SHOOT_POS
    private static Method gunDataGet;               // GunData.get(GunProp)

    /** Mod の初期化時に 1 度だけ呼びます。SBW が無い・構成が違う場合は何もしません。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register() {
        if (registered) return;
        registered = true;
        try {
            Class<?> postClass = Class.forName("com.atsuishio.superbwarfare.api.event.ShootEvent$Post");
            Consumer<Event> listener = FireTracker::onShootPost;
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, true,
                    (Class) postClass, (Consumer) listener);
            SuperbHorizons.LOGGER.info("[Superb Horizons] fire sync: listening to SBW ShootEvent.Post");
        } catch (Throwable e) {
            SuperbHorizons.LOGGER.info("[Superb Horizons] fire sync disabled (SBW ShootEvent not found): {}", e.toString());
        }
    }

    /** 記録を取り出して空にします (VehicleTracker が送信のたびに呼びます)。 */
    public static synchronized Map<Integer, List<Shot>> drain() {
        if (PENDING.isEmpty()) return Map.of();
        Map<Integer, List<Shot>> out = new HashMap<>(PENDING);
        PENDING.clear();
        return out;
    }

    public static synchronized void clear() {
        PENDING.clear();
        MUZZLE_BY_PROJECTILE.clear();   // 設定が変わっていても次回起動時に反映されるように
    }

    // ------------------------------------------------------------------

    private static void onShootPost(Event event) {
        try {
            if (!Config.SEND_FIRE_EVENTS.get()) return;

            Object params = parameters(event);
            if (params == null) return;

            // 撃った車両を探します。通常は「弾薬の持ち主」が車両そのものです。
            Entity vehicle = asVehicle(readField(params, "ammoSupplier"));
            if (vehicle == null) {
                // 念のため、撃った人が乗っている車両も見ます
                Object shooter = readField(params, "shooter");
                if (shooter instanceof Entity e) {
                    vehicle = asVehicle(e.getVehicle());
                    if (vehicle == null) vehicle = asVehicle(e.getRootVehicle());
                }
            }
            if (vehicle == null || vehicle.level().isClientSide()) return;

            Object gunData = readField(params, "data");
            if (gunData == null) return;

            String weaponName = findWeaponName(vehicle, gunData);
            if (weaponName == null) return;

            int index = fireIndex(gunData);

            // 砲口の煙・炎 (戦車砲の爆炎など)。SBW は車種ごとのプログラムで出しているため、
            // 撃った弾の種類から大きさを決め、砲口の位置と向きを一緒に送ります。
            Shot shot = new Shot(weaponName, index);
            int muzzle = muzzleSize(vehicle, gunData);
            if (muzzle != GhostSnapshotPacket.MUZZLE_NONE) {
                Object pos = readField(params, "shootPosition");
                Object dir = readField(params, "shootDirection");
                if (pos instanceof Vec3 p && dir instanceof Vec3 d) {
                    shot = new Shot(weaponName, index, muzzle,
                            (float) (p.x - vehicle.getX()), (float) (p.y - vehicle.getY()), (float) (p.z - vehicle.getZ()),
                            (float) d.x, (float) d.y, (float) d.z);
                }
            }

            synchronized (FireTracker.class) {
                List<Shot> list = PENDING.computeIfAbsent(vehicle.getId(), k -> new ArrayList<>(2));
                if (list.size() < MAX_SHOTS_PER_VEHICLE) list.add(shot);
            }
        } catch (Throwable ignored) {
            // 1 発取りこぼしても問題ないので、ゲームを止めないよう黙って無視します
        }
    }

    /** 弾の種類 (エンティティ ID) → 砲口の煙・炎の大きさ。 */
    private static final Map<String, Integer> MUZZLE_BY_PROJECTILE = new HashMap<>();
    private static Object projectileProp;           // GunProp.PROJECTILE

    /**
     * 撃った弾の種類から、砲口の煙・炎の大きさを決めます。
     * SBW 本体の振り分け (戦車砲・榴弾砲は大、迫撃砲・ロケット砲・有線ミサイルは中) に合わせた
     * 既定値を設定ファイルで変えられます。弾のクラスとその親クラスで判定するので、
     * アドオンの戦車が SBW の砲弾を使っていれば同じように出ます。
     */
    private static int muzzleSize(Entity vehicle, Object gunData) {
        try {
            if (gunDataGet == null) fireIndex(gunData);   // GunData.get を探す準備
            if (gunDataGet == null) return GhostSnapshotPacket.MUZZLE_NONE;
            if (projectileProp == null) {
                projectileProp = Class.forName("com.atsuishio.superbwarfare.data.gun.GunProp")
                        .getField("PROJECTILE").get(null);
            }
            Object info = gunDataGet.invoke(gunData, projectileProp);
            Object id = Reflect.call(info, "getItemId");
            if (!(id instanceof String typeId)) return GhostSnapshotPacket.MUZZLE_NONE;

            Integer cached = MUZZLE_BY_PROJECTILE.get(typeId);
            if (cached != null) return cached;

            int size = GhostSnapshotPacket.MUZZLE_NONE;
            EntityType<?> type = EntityType.byString(typeId).orElse(null);
            Entity sample = type != null ? type.create(vehicle.level()) : null;
            if (sample != null) {
                if (matches(sample.getClass(), Config.MUZZLE_BIG_CLASSES.get())) {
                    size = GhostSnapshotPacket.MUZZLE_BIG;
                } else if (matches(sample.getClass(), Config.MUZZLE_MEDIUM_CLASSES.get())) {
                    size = GhostSnapshotPacket.MUZZLE_MEDIUM;
                }
            }
            MUZZLE_BY_PROJECTILE.put(typeId, size);
            return size;
        } catch (Throwable e) {
            return GhostSnapshotPacket.MUZZLE_NONE;
        }
    }

    /** クラス (と親クラス) の名前が、一覧のどれかと完全に一致するか。 */
    private static boolean matches(Class<?> clazz, List<? extends String> names) {
        for (Class<?> c = clazz; c != null && c != Entity.class; c = c.getSuperclass()) {
            if (names.contains(c.getName())) return true;
        }
        return false;
    }

    private static Entity asVehicle(Object o) {
        if (o instanceof Entity e && VehicleTracker.isVehicle(e)) return e;
        return null;
    }

    /** ShootEvent からShootParameters を取り出します。 */
    private static Object parameters(Event event) throws Exception {
        if (getParameters == null && parametersField == null) {
            try {
                getParameters = event.getClass().getMethod("getParameters");
            } catch (NoSuchMethodException e) {
                for (Class<?> c = event.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (f.getType().getName().endsWith("ShootParameters")) {
                            f.setAccessible(true);
                            parametersField = f;
                        }
                    }
                }
            }
        }
        if (getParameters != null) return getParameters.invoke(event);
        if (parametersField != null) return parametersField.get(event);
        return null;
    }

    private static Object readField(Object owner, String name) {
        try {
            Field f = switch (name) {
                case "ammoSupplier" -> ammoSupplierField != null ? ammoSupplierField
                        : (ammoSupplierField = owner.getClass().getField(name));
                case "shooter" -> shooterField != null ? shooterField
                        : (shooterField = owner.getClass().getField(name));
                case "data" -> dataField != null ? dataField
                        : (dataField = owner.getClass().getField(name));
                default -> owner.getClass().getField(name);
            };
            return f.get(owner);
        } catch (Throwable e) {
            // 公開フィールドが無い場合は、同名の取得メソッドを試します
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            return Reflect.call(owner, getter);
        }
    }

    /**
     * 車両の武器一覧 (名前 → GunData) から、撃った GunData の名前を探します。
     * SBW は武器ごとに 1 つの GunData を使い回すので、同じ物かどうかで比べます。
     */
    private static String findWeaponName(Entity vehicle, Object gunData) {
        Object map = Reflect.call(vehicle, "getGunDataMap");
        if (!(map instanceof Map<?, ?> m)) return null;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getValue() == gunData && e.getKey() instanceof String s) return s;
        }
        return null;
    }

    /**
     * どの発射口から撃ったか。SBW の VehicleEntity.vehicleShoot と同じ計算です。
     * (このイベントは弾数を減らす前・発射口番号を進める前に出るので、そのまま使えます)
     */
    private static int fireIndex(Object gunData) {
        try {
            if (gunDataGet == null) {
                Class<?> propClass = Class.forName("com.atsuishio.superbwarfare.data.gun.GunProp");
                shootPosProp = propClass.getField("SHOOT_POS").get(null);
                for (Method m : gunData.getClass().getMethods()) {
                    if (m.getName().equals("get") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].isAssignableFrom(propClass)) {
                        gunDataGet = m;
                        break;
                    }
                }
                if (gunDataGet == null) return 0;
            }
            Object shootPos = gunDataGet.invoke(gunData, shootPosProp);
            Object positions = Reflect.call(shootPos, "getPositions");
            int size = positions instanceof List<?> l ? l.size() : 0;
            if (size <= 0) return 0;

            boolean boundUp = Reflect.getBoolean(shootPos, "getBoundUpWithAmmoAmount", false);
            if (boundUp) {
                int ammo = Reflect.getInt(Reflect.callOrField(gunData, "getAmmo", "ammo"), "get", 0);
                return Math.max(0, Math.min(ammo - 1, size));
            }
            int fireIndex = Reflect.getInt(Reflect.callOrField(gunData, "getFireIndex", "fireIndex"), "get", 0);
            return Math.floorMod(fireIndex, size);
        } catch (Throwable e) {
            return 0;
        }
    }
}
