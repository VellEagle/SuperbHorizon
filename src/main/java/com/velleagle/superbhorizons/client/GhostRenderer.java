package com.velleagle.superbhorizons.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.velleagle.superbhorizons.Config;
import com.velleagle.superbhorizons.SuperbHorizons;
import com.velleagle.superbhorizons.network.GhostAnimation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/**
 * ゴーストモデルの描画。
 *
 * ■ 2 つの描画方法
 *  dhModel (既定) : Distant Horizons のバッファへ直接描きます (DhModelRenderer)。
 *                   LOD 地形との前後関係は GPU が処理するので、ここでは何もしません。
 *  model          : こちらの画面に描きます (このクラス)。
 *                   DH が入っていない場合や、ghostStyle = model のときに使います。
 *                   DH の LOD 地形の裏には隠れません (通常の地形には隠れます)。
 *
 * ■ 座標変換について
 * 遅い描画段階では、直前の処理 (Distant Horizons の LOD 描画、天候描画など) が
 * RenderSystem のモデルビュー行列に変換を残していることがあります。
 * そのため、モデルビュー行列を一時的にリセットし、
 * カメラの位置と角度から座標変換を自前で組み立てて描画します。
 *
 * ■ 負荷対策
 *  - 明るさは固定値 (未読み込みチャンクの光源取得はキャッシュミスで重くなるため)。
 *    「屋外で空の光だけを受けている状態」に固定しているので、昼夜の明るさには追従します。
 *  - 影の描画は無効化 (影はワールドのブロックを参照するため)
 *  - 本物のエンティティが描かれている場合は描画しない (二重描画の防止)
 */
@Mod.EventBusSubscriber(modid = SuperbHorizons.MODID, value = Dist.CLIENT)
public class GhostRenderer {

    private static int debugFrameCounter = 0;

    /**
     * ゴーストに使う明るさ。
     *
     * 「ブロックの光 0、空の光 15」= 屋外で空の下にいる状態に固定します。
     * 空の光は時間帯に合わせて Minecraft (やシェーダー) が暗くするので、
     * 昼は明るく、夜は周りの地形と同じように暗くなります。
     * 固定値なので、その場の明るさを調べる負荷はかかりません。
     */
    public static final int GHOST_LIGHT = LightTexture.pack(0, 15);

    /**
     * DH のバッファへ直接描く方式 (dhModel) を使うか。
     * DH が入っていて、ghostStyle が model 以外なら true です。
     */
    public static boolean useDhModel() {
        if (!ModList.get().isLoaded("distanthorizons")) return false;
        return !Config.GHOST_STYLE.get().trim().equalsIgnoreCase("model");
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Distant Horizons の合成より後に描かないとゴーストが消えるため、天候描画の後に描きます
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        // DH の各種フックを登録します。
        // ここより後で処理を打ち切ると、DH の行列やイベントが一切使えなくなるため、
        // 必ず最初に通してください。
        DhDepthTexture.init();

        if (!Config.RENDER_GHOSTS.get()) return;

        // DH のバッファへ直接描く方式では、こちらでは何も描きません。
        // 描画は DH のコピー直前イベントの中で済んでいます。
        if (useDhModel()) return;

        var ghosts = GhostVehicleStore.all();

        boolean logThisFrame = false;
        if (Config.DEBUG_LOG.get() && ++debugFrameCounter >= 120) {
            debugFrameCounter = 0;
            logThisFrame = true;
        }

        if (ghosts.isEmpty()) {
            if (logThisFrame) {
                SuperbHorizons.LOGGER.info("[Superb Horizons] ghosts=0");
            }
            return;
        }

        int intervalMs = Config.UPDATE_INTERVAL.get() * 50; // 1 tick = 50ms
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();

        // ---- 描画状態の準備 ----------------------------------------
        // モデルビュー行列をリセットし、カメラの角度から座標変換を組み立て直します
        PoseStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushPose();
        modelViewStack.setIdentity();
        RenderSystem.applyModelViewMatrix();

        PoseStack pose = new PoseStack();
        pose.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
        pose.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0F));

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        dispatcher.setRenderShadow(false);

        // ---- 描画 --------------------------------------------------
        int drawn = 0;
        for (GhostVehicle ghost : ghosts) {
            // SBW が既に消した弾は、ゴーストでも出しません
            if (removedBySbw(level, ghost, cam)) continue;
            // 本物のエンティティが画面に描かれているならそちらに任せる
            if (isRealEntityShown(level, ghost, cam)) continue;

            Entity dummy = ghost.getDummy(level);
            if (dummy == null) continue;

            float t = ghost.lerpFactor(intervalMs);
            double gx = ghost.lerpX(t);
            double gy = ghost.lerpY(t);
            double gz = ghost.lerpZ(t);
            float gyRot = ghost.lerpYRot(t);
            float gxRot = ghost.lerpXRot(t);
            float gRoll = ghost.lerpRoll(t);

            dummy.setPos(gx, gy, gz);
            dummy.setYRot(gyRot);
            dummy.setXRot(gxRot);
            dummy.setOldPosAndRot();
            dummy.setYRot(gyRot);
            dummy.setXRot(gxRot);

            // --- 車体のロール (アニメーション情報の有無に関係なく必ず反映) ---
            VehicleAnimationWriter.applyRoll(dummy, gRoll);

            // --- 可動部分 (砲塔・車輪・履帯・プロペラなど) を反映 ---
            GhostAnimation anim = ghost.lerpAnimation(t);
            if (anim != null && ghost.supportsAnimation(dummy)) {
                VehicleAnimationWriter.apply(dummy, anim);
                VehicleAnimationWriter.syncGear(dummy, ghost, anim);   // 脚の出し入れアニメーション
            }
            // 発射体は「発射直後で非表示」扱いにならないようにします
            if (ghost.projectile) VehicleAnimationWriter.prepareProjectile(dummy, ghost.vx, ghost.vy, ghost.vz);

            pose.pushPose();
            pose.translate(gx - cam.x, gy - cam.y, gz - cam.z);

            try {
                dispatcher.render(
                        dummy,
                        0.0D, 0.0D, 0.0D,
                        gyRot,
                        1.0F,                       // partialTick
                        pose,
                        buffers,
                        lightFor(dispatcher, ghost, dummy)   // 屋外・空の光のみ (曳光弾などは自ら光る)
                );
                drawn++;
            } catch (Throwable e) {
                SuperbHorizons.LOGGER.warn("[Superb Horizons] ghost render failed for entity {}",
                        ghost.entityId, e);
                ghost.discardDummy();
            }

            pose.popPose();
        }

        buffers.endBatch();

        // ---- 描画状態を元へ戻す ------------------------------------
        dispatcher.setRenderShadow(true);
        modelViewStack.popPose();
        RenderSystem.applyModelViewMatrix();

        if (logThisFrame) {
            SuperbHorizons.LOGGER.info("[Superb Horizons] ghosts={} drawn={} (style=model)",
                    ghosts.size(), drawn);
        }
    }

    /**
     * SBW 本体が、この画面で自分の弾を既に消している (= ゴーストも出すべきでない) か。
     *
     * 描画距離の中では、SBW 本体の弾がこの画面に届いて表示されます。SBW は画面側で寿命などを見て
     * 弾を消すので、その範囲で本物の弾が見当たらないのは「SBW が消した」ということです。
     * その位置にゴーストを描くと、消えたはずの弾が残って見えてしまうので描きません。
     */
    public static boolean removedBySbw(ClientLevel level, GhostVehicle ghost, Vec3 cam) {
        if (!ghost.projectile) return false;
        Entity real = level.getEntity(ghost.entityId);
        if (real != null && !real.isRemoved()) return false;   // 本物がある (表示は isRealEntityShown で判断)
        double range = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        double dx = ghost.x - cam.x, dz = ghost.z - cam.z;
        return dx * dx + dz * dz <= range * range;
    }

    /**
     * 本物の車両エンティティが、この画面で実際に描かれているか。
     *
     * 「エンティティが読み込まれている」だけでは不十分です。
     * Minecraft は、エンティティの大きさと「エンティティの描画距離」設定から
     * 描画する距離を決めていて、読み込まれていても遠ければ描きません。
     * そこでゴーストまで描かないと、車両が一時的に消えて見えてしまうため、
     * 実際に描かれる距離かどうかまで確認します。
     */
    public static boolean isRealEntityShown(ClientLevel level, GhostVehicle ghost, Vec3 cam) {
        Entity real = level.getEntity(ghost.entityId);
        if (real == null || real.isRemoved()) return false;
        try {
            return real.shouldRender(cam.x, cam.y, cam.z);
        } catch (Throwable e) {
            return true;   // 判定できない場合は、従来どおり本物に任せます
        }
    }

    /**
     * ゴーストを描く明るさ。
     *
     * 車両は「屋外・空の光のみ」で描きます (夜は暗くなり、勝手に光りません)。
     * 曳光弾 (A-10 のガトリング・機銃の弾など) は、SBW が「自ら光る」設定にしているので、
     * 本物と同じくその明るさを使います。
     */
    public static int lightFor(EntityRenderDispatcher dispatcher, GhostVehicle ghost, Entity dummy) {
        if (!ghost.projectile) return GHOST_LIGHT;
        try {
            // Minecraft が本物のエンティティを描くときと同じ問い合わせです
            int block = LightTexture.block(dispatcher.getPackedLightCoords(dummy, 1.0F));
            return LightTexture.pack(block, 15);
        } catch (Throwable e) {
            return GHOST_LIGHT;
        }
    }

    /**
     * 描画用ダミーの経過 tick を進めます。
     * ダミーはワールドの tick 処理に乗らないため、ここで手動で進めないと
     * GeckoLib のアニメーション (砲の待機・射撃など) がゴーストだけ止まって見えます。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;
        GhostVehicleStore.tickDummies();
        // 発射体のゴーストの弾道の煙
        GhostTrails.tick(GhostVehicleStore.all());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        cleanup();
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        cleanup();
    }

    private static void cleanup() {
        GhostVehicleStore.clear();
        if (ModList.get().isLoaded("distanthorizons")) {
            DhModelRenderer.reset();
        }
    }
}
