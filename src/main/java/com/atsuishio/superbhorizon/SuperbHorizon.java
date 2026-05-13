package com.atsuishio.superbhorizon;

import com.atsuishio.superbhorizon.network.GhostNetwork;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(SuperbHorizon.MODID)
public class SuperbHorizon {

    public static final String MODID = "superbhorizon";
    private static final Logger LOGGER = LogUtils.getLogger();

    public SuperbHorizon(FMLJavaModLoadingContext context) {

        // ネットワークチャンネル登録
        GhostNetwork.register();

        MinecraftForge.EVENT_BUS.register(this);

        // DH初期化後に BeforeRenderPassEvent ハンドラを登録
        // DhApiAfterDhInitEvent の正しいメソッド名は afterDistantHorizonsInit()


        LOGGER.info("[SuperbHorizon] Initialized.");
    }
}
