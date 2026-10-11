package com.velleagle.superbhorizons.client;

import com.velleagle.superbhorizons.network.GhostSnapshotPacket;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * クライアント側のゴースト一覧。
 * 受信したスナップショットに含まれない車両は自動的に消えます。
 */
public class GhostVehicleStore {

    private static final Map<Integer, GhostVehicle> GHOSTS = new HashMap<>();

    public static synchronized void applySnapshot(GhostSnapshotPacket pkt) {
        // 今回のスナップショットに含まれる ID を記録しつつ更新
        java.util.Set<Integer> alive = new java.util.HashSet<>();

        for (GhostSnapshotPacket.Entry e : pkt.entries) {
            alive.add(e.entityId);
            // 1 台・1 発の処理でつまずいても、残りの処理と下の「消す処理」は必ず行います
            // (途中で止まると、送られてこなくなったゴーストが消えずに残ってしまうため)
            try {
                applyEntry(e);
            } catch (Throwable t) {
                if (!errorLogged) {
                    errorLogged = true;
                    com.velleagle.superbhorizons.SuperbHorizons.LOGGER.warn(
                            "[Superb Horizons] ゴーストの更新中にエラーが発生しました (以後は表示しません)", t);
                }
            }
        }

        // 含まれなくなった車両を削除
        GHOSTS.keySet().removeIf(id -> !alive.contains(id));
    }

    /** エラーを 1 度だけログに出したか。 */
    private static boolean errorLogged = false;

    /** 受信した 1 台・1 発分をゴーストに反映します。 */
    private static void applyEntry(GhostSnapshotPacket.Entry e) {
        GhostVehicle g = GHOSTS.get(e.entityId);
        if (g == null || g.typeId != e.typeId) {
            // 新しく見えた車両・発射体 (ID が別の物に再利用された場合も作り直します)
            if (g != null) g.discardDummy();
            g = new GhostVehicle(
                    e.entityId, e.typeId, e.isProjectile(), e.x, e.y, e.z,
                    e.yRot, e.xRot, e.roll, e.animation);
            GHOSTS.put(e.entityId, g);
        } else {
            g.update(e.x, e.y, e.z, e.yRot, e.xRot, e.roll, e.animation);
        }
        if (e.isProjectile()) {
            g.setVelocity(e.vx, e.vy, e.vz);
            g.ownerId = e.ownerId;
            g.setColor(e.color);
        }

        // 武器データ (変わったときだけ届きます)。射撃の再生より先に読み込ませます。
        if (e.gunData != null) g.setGunData(e.gunData);

        // 前回の受信以降に撃った射撃を、ゴーストで再生します
        if (!e.shots.isEmpty()) GhostFireEffects.play(g, e.shots);
    }

    /** ID からゴーストを探します。無ければ null。 */
    public static synchronized GhostVehicle get(int entityId) {
        return GHOSTS.get(entityId);
    }

    public static synchronized Collection<GhostVehicle> all() {
        return new java.util.ArrayList<>(GHOSTS.values());
    }

    /** 全ゴーストの描画用ダミーの経過 tick を 1 進めます。 */
    public static synchronized void tickDummies() {
        for (GhostVehicle g : GHOSTS.values()) g.tickDummy();

        // 更新が届かなくなった弾のゴーストを消します。
        // 弾のゴーストは、サーバーが受信間隔ごとに位置を送っている間だけ表示するものです。
        // 3 秒以上 更新が無い弾は、何かの理由で取り残されたものなので消します
        // (SBW の弾が見えなくなったのに、ゴーストだけが空中に残り続けるのを防ぎます)。
        // (サーバーが重いと受信の間隔が長くなるので、余裕を持って 3 秒にしています)
        long staleMs = 3000L;
        long now = System.currentTimeMillis();
        var it = GHOSTS.values().iterator();
        while (it.hasNext()) {
            GhostVehicle g = it.next();
            if (g.projectile && now - g.lastUpdateMs > staleMs) {
                g.discardDummy();
                it.remove();
            }
        }
    }

    /** ワールド退出・切断時に呼びます。 */
    public static synchronized void clear() {
        GHOSTS.values().forEach(GhostVehicle::discardDummy);
        GHOSTS.clear();
    }
}
