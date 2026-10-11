package com.velleagle.superbhorizons.network;

import com.velleagle.superbhorizons.SuperbHorizons;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * この Mod 専用の通信チャンネル。
 * バニラのエンティティ同期とは完全に別経路なので、
 * ワールド側の処理には一切影響しません。
 */
public class NetworkHandler {

    // 通信内容の形式を変えたら必ず数字を上げてください。
    // サーバーとクライアントで形式が食い違うと、データを読み違えて描画が壊れます。
    // 2: 車輪・履帯・プロペラの値を圧縮せずに送る形式へ変更
    // 3: 射撃・弾数・発射体 (砲弾・ミサイル等) を追加
    // 4: 弾数の代わりに武器データを丸ごと送る形式へ変更・砲口の煙と炎・発射体の持ち主を追加
    // 5: 曳光弾の色を追加
    // 6, 7: (試作) 止められた弾の自由飛行 — 取りやめ
    // 8: 6, 7 の内容を取り除き、5 と同じ内容に戻したもの (6, 7 の jar と混ざらないよう番号だけ進めています)
    private static final String VERSION = "8";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(SuperbHorizons.MODID, "main"),
            () -> VERSION,
            VERSION::equals,
            VERSION::equals
    );

    private static int nextId = 0;

    public static void register() {
        CHANNEL.registerMessage(
                nextId++,
                GhostSnapshotPacket.class,
                GhostSnapshotPacket::encode,
                GhostSnapshotPacket::decode,
                GhostSnapshotPacket::handle
        );
    }

    public static void sendTo(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
