package com.velleagle.superbhorizons;

import com.mojang.logging.LogUtils;
import com.velleagle.superbhorizons.network.NetworkHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Superb Horizons - Mod のエントリポイント。
 *
 * ここでは以下の 2 つだけを行います:
 *   1. 設定ファイル (superbhorizons-common.toml) の登録
 *   2. 独自ネットワークチャンネルの登録
 *
 * サーバー側の車両収集と、クライアント側の描画は
 * それぞれ @Mod.EventBusSubscriber を付けたクラスが自動で登録されるため、
 * ここには書きません。
 */
@Mod(SuperbHorizons.MODID)
public class SuperbHorizons {

    public static final String MODID = "superbhorizons";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SuperbHorizons() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NetworkHandler.register();
        // SBW の射撃イベントを受け取る準備 (SBW が無い場合は何もしません)
        com.velleagle.superbhorizons.server.FireTracker.register();
        LOGGER.info("[Superb Horizons] initialized");
    }
}
