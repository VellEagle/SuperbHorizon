package com.atsuishio.superbhorizon;

import com.atsuishio.superbhorizon.network.GhostNetwork;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * SuperbHorizon Mod のメインクラス。
 * クライアント描画距離外にあるビークルの「ゴースト車両」をレンダリングし、
 * マルチプレイ時の車両の位置同期やアニメーション同期を滑らかに行うModのエントリーポイントです。
 */
@Mod(SuperbHorizon.MODID)
public class SuperbHorizon {

    // Modの識別子 (Mod ID)
    public static final String MODID = "superbhorizon";
    private static final Logger LOGGER = LogUtils.getLogger();

    public SuperbHorizon(FMLJavaModLoadingContext context) {
        // Forge共通設定 (Common Config) の登録
        context.registerConfig(ModConfig.Type.COMMON, SuperbHorizonConfig.SPEC);
        
        // ゴースト送受信ネットワークパケットの登録
        GhostNetwork.register();

        // ForgeイベントバスへのModインスタンス登録
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("[SuperbHorizon] Initialized.");
    }
}
