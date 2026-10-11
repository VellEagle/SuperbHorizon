package com.velleagle.superbhorizons.server;

import com.velleagle.superbhorizons.Config;
import com.velleagle.superbhorizons.SuperbHorizons;
import com.velleagle.superbhorizons.network.GhostAnimation;
import com.velleagle.superbhorizons.network.GhostSnapshotPacket;
import com.velleagle.superbhorizons.network.NetworkHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.velleagle.superbhorizons.util.GunDataSync;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * サーバー側。
 *
 * ワールド全体を毎回スキャンすると重いので、
 * エンティティがワールドに入った / 出たタイミングで
 * 車両だけを自前のリストに登録・削除しておきます。
 * これにより毎 tick の処理は「登録済みリストを見るだけ」で済みます。
 */
@Mod.EventBusSubscriber(modid = SuperbHorizons.MODID)
public class VehicleTracker {

    /** ディメンションごとの車両リスト。 */
    private static final Map<Level, Set<Entity>> VEHICLES = new HashMap<>();

    /** ディメンションごとの発射体 (砲弾・ロケット・ミサイル・爆弾など) のリスト。 */
    private static final Map<Level, Set<Entity>> PROJECTILES = new HashMap<>();

    private static int tickCounter = 0;

    // ------------------------------------------------------------------
    // 車両の登録 / 削除
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        Map<Level, Set<Entity>> target;
        if (isVehicle(entity)) {
            target = VEHICLES;
        } else if (isProjectile(entity)) {
            target = PROJECTILES;
        } else if (isVehicleBullet(entity)) {
            // 機銃弾などは、車両から撃たれたものだけ (撃った人がまだ分からない場合は送信時に判定します)
            if (entity instanceof Projectile p && p.getOwner() != null && ownerVehicleId(entity) < 0) return;
            target = PROJECTILES;
        } else {
            return;
        }
        target.computeIfAbsent(event.getLevel(),
                l -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(entity);
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Set<Entity> set = VEHICLES.get(event.getLevel());
        if (set != null) set.remove(event.getEntity());
        Set<Entity> projectiles = PROJECTILES.get(event.getLevel());
        if (projectiles != null) projectiles.remove(event.getEntity());
        LAST_TICK.remove(event.getEntity());
    }

    /** クラスごとの判定結果のキャッシュ (同じ車種を何度も調べ直さないため)。 */
    private static final Map<Class<?>, Boolean> VEHICLE_CLASS_CACHE = new ConcurrentHashMap<>();

    /**
     * 「これは Superb Warfare の車両か？」の判定。
     * SBW のクラスを直接参照せず、クラス名の接頭辞だけで判定しています。
     * そのためビルド時に SBW の jar が不要で、SBW が更新されても壊れにくくなります。
     *
     * 自分自身のクラス名だけでなく、親クラスもさかのぼって調べます。
     * Superb Modern Combat などのアドオン車両は、クラス自体はアドオン側のパッケージ
     * (例: superb_modern_combat.entity.vehicle) にありますが、親クラスは
     * SBW の VehicleEntity です。自分のクラス名だけで判定すると、
     * アドオン車両がすべてゴースト対象から外れてしまいます。
     * (旧バージョンは instanceof VehicleEntity で判定していたため、アドオン車両も対象でした)
     */
    public static boolean isVehicle(Entity entity) {
        return VEHICLE_CLASS_CACHE.computeIfAbsent(entity.getClass(),
                c -> matchesPrefix(c, Config.VEHICLE_CLASS_PREFIXES.get()));
    }

    /** クラスごとの判定結果のキャッシュ (発射体用)。 */
    private static final Map<Class<?>, Boolean> PROJECTILE_CLASS_CACHE = new ConcurrentHashMap<>();

    /**
     * 「これはゴースト表示する発射体か？」の判定。
     * 既定では SBW の砲弾・ロケット・ミサイル・爆弾・グレネードの共通の親クラス
     * (FastThrowableProjectile) を持つものが対象です。
     * 小銃弾 (数が多く、遠くでは見えない) や、地雷・C4 などの設置物は対象外です。
     */
    public static boolean isProjectile(Entity entity) {
        if (!Config.GHOST_PROJECTILES.get()) return false;
        return PROJECTILE_CLASS_CACHE.computeIfAbsent(entity.getClass(),
                c -> matchesPrefix(c, Config.PROJECTILE_CLASS_PREFIXES.get()));
    }

    /** クラスごとの判定結果のキャッシュ (車両の機銃弾用)。 */
    private static final Map<Class<?>, Boolean> BULLET_CLASS_CACHE = new ConcurrentHashMap<>();

    /**
     * 「これは車両から撃たれたときだけゴースト表示する弾 (機銃弾など) か？」の判定。
     * 銃弾は歩兵の銃からも大量に撃たれるため、通常の発射体とは分けて、
     * 車両から撃たれたものだけを対象にします。
     */
    public static boolean isVehicleBullet(Entity entity) {
        if (!Config.GHOST_PROJECTILES.get()) return false;
        return BULLET_CLASS_CACHE.computeIfAbsent(entity.getClass(),
                c -> matchesPrefix(c, Config.VEHICLE_BULLET_CLASS_PREFIXES.get()));
    }

    private static boolean matchesPrefix(Class<?> clazz, List<? extends String> prefixes) {
        for (Class<?> c = clazz; c != null && c != Entity.class; c = c.getSuperclass()) {
            String className = c.getName();
            for (String prefix : prefixes) {
                if (className.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 定期送信
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        tickCounter++;
        if (tickCounter < Config.UPDATE_INTERVAL.get()) return;
        tickCounter = 0;

        double maxDist = Config.MAX_DISTANCE.get();
        double minDist = Config.MIN_DISTANCE.get();
        double maxDistSqr = maxDist * maxDist;
        double minDistSqr = minDist * minDist;
        int maxCount = Config.MAX_VEHICLES.get();
        boolean sendAnimation = Config.SEND_ANIMATION.get();
        double animDist = Config.ANIMATION_MAX_DISTANCE.get();
        double animDistSqr = animDist * animDist;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        // 前回の送信以降に撃たれた射撃 (車両の ID → 射撃の一覧)
        Map<Integer, List<GhostSnapshotPacket.Shot>> shots = FireTracker.drain();

        // 武器データ (この回の送信中に何度も書き出さないよう、車両ごとに 1 回だけ作ります)
        boolean sendGunData = Config.SEND_AMMO.get();
        Map<Integer, byte[]> gunDataThisRound = new HashMap<>();
        long now = server.getTickCount();

        int maxProjectiles = Config.MAX_PROJECTILES.get();
        // Minecraft が本物のエンティティを画面へ送る距離の上限 (サーバーの描画距離)
        double viewDistanceBlocks = server.getPlayerList().getViewDistance() * 16.0;

        // 車両が 1 台も無いディメンションも含め、すべてのディメンションを回ります。
        // 以前は車両の無いディメンションを飛ばしていたため、最後の 1 台が消えたときに
        // 「もう車両は無い」という連絡が届かず、ゴーストが残り続けていました
        // (ディメンションを移動したときに、前のディメンションのゴーストが残るのも同じ原因です)。
        for (ServerLevel serverLevel : server.getAllLevels()) {
            Set<Entity> vehicles = VEHICLES.getOrDefault(serverLevel, Collections.emptySet());
            Set<Entity> projectiles = PROJECTILES.getOrDefault(serverLevel, Collections.emptySet());

            // 消滅済みのエンティティを掃除
            removeDead(vehicles);
            removeDead(projectiles);

            // --- 発射体の下調べ (プレイヤーごとではなく、1 回だけ行います) ---
            // ゴーストには、SBW が実際に動かしている弾を、そのまま写すだけです
            // (SBW の弾の動きには一切手を加えません)。
            //  ・車両から撃たれた弾であること
            //  ・その弾が今も動いていること (下記)
            // Minecraft は、プレイヤーから離れた場所 (シミュレーション距離の外) のエンティティを
            // 動かさずに止めておきます。止まった弾は、ゴーストを動かす元になる動きが無いので送りません
            // (送ると、ゴーストが同じ場所を行き来して見えてしまいます)。
            List<Entity[]> activeProjectiles = new ArrayList<>();
            if (maxProjectiles > 0) {
                for (Entity p : projectiles) {
                    // 前回調べたときの {tick 数, 位置}。tick 数も位置も変わっていなければ止まっています
                    double[] last = LAST_TICK.put(p, new double[] {p.tickCount, p.getX(), p.getY(), p.getZ()});
                    if (last != null) {
                        boolean sameTick = last[0] == p.tickCount;
                        double mx = p.getX() - last[1], my = p.getY() - last[2], mz = p.getZ() - last[3];
                        boolean samePlace = mx * mx + my * my + mz * mz < 1.0E-4;
                        if (sameTick || samePlace) continue;                   // 止まっている
                    }
                    if (p.getDeltaMovement().lengthSqr() < 0.0025) continue;   // 着弾直前など
                    Entity owner = ownerVehicle(p);
                    if (owner == null) continue;                               // 車両から撃たれた弾ではない
                    activeProjectiles.add(new Entity[] {p, owner});
                }
            }

            for (ServerPlayer player : serverLevel.players()) {
                List<GhostSnapshotPacket.Entry> entries = new ArrayList<>();
                // このプレイヤーへ前回までに送った武器データの記録 (今回送る車両の分だけ残します)
                Map<Integer, long[]> sentBefore = GUN_DATA_SENT.getOrDefault(player.getUUID(), Map.of());
                Map<Integer, long[]> sentNow = new HashMap<>();

                for (Entity v : vehicles) {
                    double dSqr = v.distanceToSqr(player.getX(), player.getY(), player.getZ());
                    // 近すぎる車両は本物のエンティティが描画するので送らない
                    if (dSqr < minDistSqr) continue;
                    if (dSqr > maxDistSqr) continue;

                    int typeId = BuiltInRegistries.ENTITY_TYPE.getId(v.getType());
                    if (typeId < 0) continue;

                    // 可動部分の状態。遠すぎる車両では通信量を抑えるため送りません。
                    GhostAnimation animation = null;
                    if (sendAnimation && dSqr <= animDistSqr) {
                        animation = VehicleAnimationReader.read(v);
                    }

                    GhostSnapshotPacket.Entry entry = new GhostSnapshotPacket.Entry(
                            v.getId(),
                            typeId,
                            v.getX(), v.getY(), v.getZ(),
                            v.getYRot(),
                            v.getXRot(),
                            VehicleAnimationReader.readRoll(v),
                            animation,
                            shots.get(v.getId())      // 前回の送信以降に撃った射撃 (無ければ null)
                    );

                    // 武器データ (弾数・選択中の弾種など)。可動部分を送る距離の車両だけ、
                    // 変わったときに送ります。機関砲を撃ち続けると毎回変わるため、
                    // 同じ車両には最短でも 1 秒 (20 tick) に 1 回までにしています。
                    if (sendGunData && animation != null) {
                        byte[] data = gunDataThisRound.computeIfAbsent(v.getId(), id -> {
                            byte[] d = GunDataSync.write(v);
                            return d != null ? d : new byte[0];
                        });
                        if (data.length > 0) {
                            long hash = java.util.Arrays.hashCode(data);
                            long[] last = sentBefore.get(v.getId());
                            boolean first = last == null;
                            boolean changed = !first && last[0] != hash;
                            if (first || (changed && now - last[1] >= 20)) {
                                entry.gunData = data;
                                sentNow.put(v.getId(), new long[] {hash, now});
                            } else {
                                sentNow.put(v.getId(), last);
                            }
                        }
                    }
                    entries.add(entry);
                }

                // 台数が多すぎる場合は近い順に打ち切る
                if (entries.size() > maxCount) {
                    entries.sort((a, bb) -> Double.compare(
                            distSqr(a, player), distSqr(bb, player)));
                    entries = new ArrayList<>(entries.subList(0, maxCount));
                    // 打ち切った車両の武器データは届いていないので、「送った」記録からも外します
                    Set<Integer> kept = new HashSet<>();
                    for (GhostSnapshotPacket.Entry e : entries) kept.add(e.entityId);
                    sentNow.keySet().retainAll(kept);
                }
                GUN_DATA_SENT.put(player.getUUID(), sentNow);

                // --- 発射体 (砲弾・ロケット・ミサイル・爆弾など) ---
                if (!activeProjectiles.isEmpty()) {
                    List<GhostSnapshotPacket.Entry> flying = new ArrayList<>();
                    for (Entity[] pair : activeProjectiles) {
                        Entity p = pair[0];
                        int owner = pair[1].getId();

                        double dSqr = p.distanceToSqr(player.getX(), player.getY(), player.getZ());
                        if (dSqr < minDistSqr) continue;
                        if (dSqr > maxDistSqr) continue;

                        // このプレイヤーの画面に SBW 本体の弾が届く範囲 (描画距離の中) では、ゴーストは送りません。
                        // その範囲の弾の表示・消滅は SBW 本体に任せます (SBW は画面側で寿命などを見て弾を消すので、
                        // ここでゴーストを送ると、SBW が消した弾がゴーストとして残って見えてしまいます)。
                        double range = Math.min(p.getType().clientTrackingRange() * 16, viewDistanceBlocks);
                        double hx = p.getX() - player.getX(), hz = p.getZ() - player.getZ();
                        if (hx * hx + hz * hz <= range * range) continue;

                        int typeId = BuiltInRegistries.ENTITY_TYPE.getId(p.getType());
                        if (typeId < 0) continue;

                        Vec3 motion = p.getDeltaMovement();
                        boolean bullet = !isProjectile(p) && isVehicleBullet(p);

                        GhostSnapshotPacket.Entry pe = new GhostSnapshotPacket.Entry(
                                p.getId(), typeId, GhostSnapshotPacket.KIND_PROJECTILE,
                                p.getX(), p.getY(), p.getZ(),
                                p.getYRot(), p.getXRot(), 0.0F,
                                (float) motion.x, (float) motion.y, (float) motion.z,
                                null, null, owner);
                        // 曳光弾の色 (SBW の銃弾は弾ごとに色を持っています)
                        if (bullet) pe.color = readColor(p);
                        flying.add(pe);
                    }
                    if (flying.size() > maxProjectiles) {
                        flying.sort((a, bb) -> Double.compare(
                                distSqr(a, player), distSqr(bb, player)));
                        flying = flying.subList(0, maxProjectiles);
                    }
                    entries.addAll(flying);
                }

                // 空の一覧は「ゴーストを全部消して」という意味になります。
                // 毎回送ると無駄なので、前回ゴーストを送ったプレイヤーにだけ 1 回送ります。
                if (entries.isEmpty()) {
                    if (!PLAYERS_WITH_GHOSTS.remove(player.getUUID())) continue;
                } else {
                    PLAYERS_WITH_GHOSTS.add(player.getUUID());
                }

                NetworkHandler.sendTo(player, new GhostSnapshotPacket(entries));
            }
        }
    }

    /**
     * このプレイヤーへ最後に送った武器データ (車両の ID → {内容のハッシュ, 送った tick})。
     * 変わっていない武器データを毎回送らないために使います。
     */
    private static final Map<UUID, Map<Integer, long[]>> GUN_DATA_SENT = new HashMap<>();

    /**
     * 発射体を撃った車両の ID。不明なら -1。
     * (確認用の横並び表示で、弾を撃った車両と同じだけずらすために使います)
     */
    private static int ownerVehicleId(Entity projectile) {
        Entity v = ownerVehicle(projectile);
        return v != null ? v.getId() : -1;
    }

    /** 発射体を撃った車両。車両から撃たれた弾でなければ null。 */
    private static Entity ownerVehicle(Entity projectile) {
        if (!(projectile instanceof Projectile p)) return null;
        Entity owner = p.getOwner();
        if (owner == null) return null;
        if (isVehicle(owner)) return owner;
        Entity ridden = owner.getVehicle();
        if (ridden != null && isVehicle(ridden)) return ridden;
        Entity root = owner.getRootVehicle();
        if (root != owner && isVehicle(root)) return root;
        return null;
    }

    /** 発射体ごとの、前回調べたときの {tick 数, 位置} (止まっている弾を見分けるため)。 */
    private static final Map<Entity, double[]> LAST_TICK = new ConcurrentHashMap<>();

    /** 銃弾の曳光の色 (SBW の getRGB)。読めなければ null。 */
    private static float[] readColor(Entity bullet) {
        Object rgb = com.velleagle.superbhorizons.util.Reflect.call(bullet, "getRGB");
        if (rgb instanceof float[] c && c.length >= 3) return new float[] {c[0], c[1], c[2]};
        return null;
    }

    /** ログアウト・ディメンション移動では、クライアント側のゴーストが作り直されるので記録を消します。 */
    @SubscribeEvent
    public static void onLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        GUN_DATA_SENT.remove(event.getEntity().getUUID());
        PLAYERS_WITH_GHOSTS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onChangeDimension(net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        GUN_DATA_SENT.remove(event.getEntity().getUUID());
    }

    private static void removeDead(Set<Entity> set) {
        if (set.isEmpty()) return;
        Iterator<Entity> it = set.iterator();
        while (it.hasNext()) {
            Entity e = it.next();
            if (e.isRemoved()) {
                it.remove();
                LAST_TICK.remove(e);
            }
        }
    }

    /** 直前にゴーストを 1 台以上送ったプレイヤー (空の一覧を送る必要があるかの判定用)。 */
    private static final Set<UUID> PLAYERS_WITH_GHOSTS = new HashSet<>();

    private static double distSqr(GhostSnapshotPacket.Entry e, ServerPlayer p) {
        double dx = e.x - p.getX();
        double dy = e.y - p.getY();
        double dz = e.z - p.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** サーバー停止時にリストを空にする。 */
    @SubscribeEvent
    public static void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        VEHICLES.clear();
        PROJECTILES.clear();
        LAST_TICK.clear();
        // 設定が変わっていても次回起動時に反映されるように
        VEHICLE_CLASS_CACHE.clear();
        PROJECTILE_CLASS_CACHE.clear();
        PLAYERS_WITH_GHOSTS.clear();
        GUN_DATA_SENT.clear();
        FireTracker.clear();
    }
}
