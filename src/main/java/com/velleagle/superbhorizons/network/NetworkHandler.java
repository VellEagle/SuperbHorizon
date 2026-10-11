package com.velleagle.superbhorizons.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * この Mod 専用の通信。
 * バニラのエンティティ同期とは完全に別経路なので、
 * ワールド側の処理には一切影響しません。
 *
 * 【NeoForge 版の変更点】Forge の SimpleChannel は廃止されたため、
 * NeoForge の「カスタムパケット (payload)」の仕組みで登録します。
 * 登録は Mod のイベントバスの RegisterPayloadHandlersEvent で行います
 * (SuperbHorizons のコンストラクタで登録しています)。
 */
public class NetworkHandler {

    // 通信内容の形式を変えたら必ず数字を上げてください。
    // サーバーとクライアントで形式が食い違うと、データを読み違えて描画が壊れます。
    // (1.20.1 Forge 版の履歴に合わせて 8 から始めています)
    private static final String VERSION = "8";

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(
                GhostSnapshotPacket.TYPE,
                GhostSnapshotPacket.STREAM_CODEC,
                GhostSnapshotPacket::handle);
    }

    public static void sendTo(ServerPlayer player, GhostSnapshotPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }
}
