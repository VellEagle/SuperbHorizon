package com.velleagle.superbhorizons;

import com.mojang.logging.LogUtils;
import com.velleagle.superbhorizons.network.NetworkHandler;
import com.velleagle.superbhorizons.server.FireTracker;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Superb Horizons - Mod のエントリポイント (NeoForge 1.21.1 版)。
 *
 * ここでは以下の 3 つだけを行います:
 *   1. 設定ファイル (superbhorizons-common.toml) の登録
 *   2. 独自の通信の登録
 *   3. SBW の射撃イベントを受け取る準備
 *
 * サーバー側の車両収集と、クライアント側の描画は
 * それぞれ @EventBusSubscriber を付けたクラスが自動で登録されるため、
 * ここには書きません。
 *
 * 【NeoForge 版の変更点】Forge の ModLoadingContext.get() は廃止されたため、
 * Mod のイベントバスと ModContainer をコンストラクタの引数で受け取ります。
 */
@Mod(SuperbHorizons.MODID)
public class SuperbHorizons {

    public static final String MODID = "superbhorizons";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SuperbHorizons(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modBus.addListener(NetworkHandler::register);
        // SBW の射撃イベントを受け取る準備 (SBW が無い場合は何もしません)
        FireTracker.register();
        LOGGER.info("[Superb Horizons] initialized");
    }
}
