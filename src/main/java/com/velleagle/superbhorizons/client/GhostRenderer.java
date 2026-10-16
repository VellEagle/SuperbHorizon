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
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
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
 * ■ 描き分け
 *  ・読み込み済みの地形の中 (Minecraft が普通に地形を描いている範囲) のゴースト
 *      → こちらの画面に、本物の車両と同じように描きます (このクラス)。
 *        地形の裏には 1 ピクセル単位で正しく隠れ、明るさもその場所の実際の明るさです。
 *  ・それより遠く (DH の LOD 地形の範囲) のゴースト
 *      dhModel (既定) : Distant Horizons のバッファへ直接描きます (DhModelRenderer)。
 *                       LOD 地形との前後関係は GPU が処理します。
 *      model          : こちらの画面に描きます (DH が入っていない場合も自動的にこちら)。
 *                       DH の LOD 地形の裏には隠れません。
 *
 * ■ 座標変換について
 * 遅い描画段階では、直前の処理 (Distant Horizons の LOD 描画、天候描画など) が
 * RenderSystem のモデルビュー行列に変換を残していることがあります。
 * そのため、モデルビュー行列を一時的にリセットし、
 * カメラの位置と角度から座標変換を自前で組み立てて描画します。
 *
 * ■ 負荷対策
 *  - 遠方のゴーストの明るさは固定値 (未読み込みチャンクの光源取得はキャッシュミスで重くなるため)。
 *    「屋外で空の光だけを受けている状態」に固定しているので、昼夜の明るさには追従します。
 *    読み込み済みの地形の中では、本物の車両と同じくその場所の明るさを使います。
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

    /**
     * ゴーストの位置が「Minecraft が普通に地形を描いている範囲」(読み込み済みのチャンク) の中か。
     *
     * この範囲の地形は Minecraft 自身が描いていて、Distant Horizons は LOD を描きません
     * (境目付近では DH の描画を薄くして消します)。そのため、ここにいるゴーストを DH の画面に描くと、
     * 地形の裏に正しく隠れず、少し動いただけで丸ごと消えたりします。
     * この範囲のゴーストは、本物の車両と同じく通常の描画で描きます (地形による隠れ方も明るさも本物と同じ)。
     * 判定には最新の受信位置を使います (DH 側と通常の描画側で、同じフレームの判定が必ず一致するように)。
     */
    public static boolean insideLoadedTerrain(ClientLevel level, GhostVehicle ghost, Vec3 cam) {
        int cx = Mth.floor(ghost.x) >> 4;
        int cz = Mth.floor(ghost.z) >> 4;
        if (!level.getChunkSource().hasChunk(cx, cz)) return false;
        // 描画距離の一番外側のチャンクは描かれないことがあるため、1 チャンク内側までにします
        double range = Math.max(0, Minecraft.getInstance().options.getEffectiveRenderDistance() - 1) * 16.0;
        double dx = ghost.x - cam.x, dz = ghost.z - cam.z;
        if (dx * dx + dz * dz > range * range) return false;
        // 描画距離の中でも、Minecraft がまだその場所の地形を描いていない (DH の LOD で埋めている) ことがあります
        // (2026/10/15 のデバッグで確認)。その場合は LOD の裏に隠れるよう、DH 側で描きます。
        return sectionCompiled(net.minecraft.core.BlockPos.containing(ghost.x, ghost.y + 1.0, ghost.z));
    }

    // ---- Distant Horizons の「バニラ地形を LOD になじませる処理」(vanillaFadeMode) ----
    //
    // DH は描画距離の境目が自然に見えるよう、Minecraft が描いた画面を、カメラからの「直線距離」に応じて
    // LOD の画面へ置き換えます (DH の設定 vanillaFadeMode。初期値 DOUBLE_PASS)。
    // このとき地形だけでなく、本物の車両エンティティも一緒に LOD の画面へ置き換わって見えなくなります。
    // (2026/10/15: 高所から見下ろすと、本物の車両が描かれているのに見えない「空白地帯」ができる原因)
    // そこで、この距離より遠い車両は本物が描かれていても DH の画面にゴーストを描きます。
    // DH の画面に描いたゴーストは置き換え後の画面に残るので、本物 → ゴーストへ自然に切り替わります。

    /** DH の内部の計算 (RenderUtil.getNearClipPlaneInBlocks) を呼び出すためのメソッド。見つからなければ null */
    private static java.lang.reflect.Method dhNearClipMethod;
    private static boolean dhNearClipLookedUp;

    /**
     * DH が Minecraft の画面を LOD へ置き換え始める距離 (カメラからの直線距離、ブロック)。
     * DH 3.3.3 の計算式: 開始 = (近くの切り取り距離 + 16) × 1.5、完全に置き換わるのは × 1.9。
     */
    public static double dhFadeStartDistance() {
        float near = -1f;
        if (!dhNearClipLookedUp) {
            dhNearClipLookedUp = true;
            try {
                Class<?> c = Class.forName("com.seibel.distanthorizons.core.util.RenderUtil",
                        false, GhostRenderer.class.getClassLoader());
                dhNearClipMethod = c.getMethod("getNearClipPlaneInBlocks");
            } catch (Throwable ignored) {
                dhNearClipMethod = null;
            }
        }
        if (dhNearClipMethod != null) {
            try {
                near = ((Number) dhNearClipMethod.invoke(null)).floatValue();
            } catch (Throwable e) {
                dhNearClipMethod = null;   // 以後は自前の計算に切り替えます
            }
        }
        if (!(near > 0f)) near = estimateDhNearClip();
        return (near + 16.0) * 1.5;
    }

    /** 【デバッグ用】DH の内部の計算を呼び出せているか */
    public static boolean dhNearClipReflected() {
        return dhNearClipMethod != null;
    }

    /** DH の内部を呼び出せなかったときの代わりの計算 (DH 3.3.3 の自動設定と同じ式) */
    private static float estimateDhNearClip() {
        Minecraft mc = Minecraft.getInstance();
        int rd = mc.options.getEffectiveRenderDistance();
        float factor = rd <= 2 ? 0.2f : rd <= 4 ? 0.3f : rd <= 6 ? 0.6f : rd <= 10 ? 0.8f : 0.9f;
        float blocks = Math.max(1f, rd * 16f * factor);
        double w = Math.max(1, mc.getWindow().getWidth());
        double h = Math.max(1, mc.getWindow().getHeight());
        double aspect = w / h;
        double tan = Math.tan(Math.toRadians(70.0) / 2.0);
        return (float) (blocks / Math.sqrt(1.0 + tan * tan * (aspect * aspect + 1.0)));
    }

    /**
     * このゴーストの位置が、DH によって Minecraft の画面が LOD へ置き換えられる距離にあるか。
     * true のとき、本物の車両は (描かれていても) 見えなくなっているので、ゴーストを DH の画面に描きます。
     * 判定には最新の受信位置を使います (DH 側と通常の描画側で、同じフレームの判定が必ず一致するように)。
     */
    public static boolean inDhFadeZone(GhostVehicle ghost, Vec3 cam) {
        if (!ModList.get().isLoaded("distanthorizons")) return false;
        double d = dhFadeStartDistance();
        double dx = ghost.x - cam.x, dy = (ghost.y + 1.0) - cam.y, dz = ghost.z - cam.z;
        return dx * dx + dy * dy + dz * dz > d * d;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Distant Horizons の合成より後に描かないとゴーストが消えるため、天候描画の後に描きます
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            // 【デバッグ用】このフレームのゴーストの状態をまとめてログに出します (debugLog = true のときだけ)
            if (GhostDebug.on()) {
                try {
                    renderDebugBoxes(event.getCamera());
                } catch (Throwable e) {
                    GhostDebug.error("debug-boxes", null, e);
                }
            }
            GhostDebug.endFrame(Minecraft.getInstance().level, event.getCamera().getPosition(), event.getFrustum());
            RealRenderTracker.endFrame();
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        // DH の各種フックを登録します。
        // ここより後で処理を打ち切ると、DH の行列やイベントが一切使えなくなるため、
        // 必ず最初に通してください。
        DhDepthTexture.init();
        // 【デバッグ用】このときの霧の設定を記録します
        GhostDebug.vanillaFog(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());

        if (!Config.RENDER_GHOSTS.get()) {
            GhostDebug.vanillaNote("render-ghosts-off");
            return;
        }

        // DH のバッファへ直接描く方式では、遠方 (LOD 地形の範囲) のゴーストは
        // DH のコピー直前イベントの中で描画済みです。ここでは読み込み済みの地形の中にいるゴーストだけを描きます。
        boolean dh = useDhModel();
        boolean fullDetail = Config.GHOST_FULL_DETAIL.get();

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
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting sorting = RenderSystem.getVertexSorting();

        // ---- 描画 --------------------------------------------------
        int drawn = 0;
        for (GhostVehicle ghost : ghosts) {
            // SBW が既に消した弾は、ゴーストでも出しません
            if (removedBySbw(level, ghost, cam)) {
                GhostDebug.vanilla(ghost, "sbw-removed");
                continue;
            }
            // DH が Minecraft の画面を LOD へ置き換える距離か (本物の車両も見えなくなる距離)
            boolean faded = inDhFadeZone(ghost, cam);
            // 本物のエンティティが画面に描かれているならそちらに任せる (置き換えで見えなくなる距離は除く)
            if (!faded && realEntityDrawsItself(level, ghost, cam)) {
                GhostDebug.vanilla(ghost, "real-shown");
                continue;
            }
            // 読み込み済みの地形の中か (中なら、その場所の実際の明るさで描きます)
            boolean nearby = insideLoadedTerrain(level, ghost, cam);
            // 遠方 (または置き換えの距離) のゴーストは DH 側で描画済み
            if (dh && (!nearby || faded)) {
                GhostDebug.vanilla(ghost, "far-dh");
                continue;
            }

            Entity dummy = ghost.getDummy(level);
            if (dummy == null) {
                GhostDebug.vanilla(ghost, "no-dummy");
                continue;
            }

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

            double rx = gx - cam.x;
            double ry = gy - cam.y;
            double rz = gz - cam.z;
            int light = lightFor(dispatcher, ghost, dummy, nearby);

            try {
                if (fullDetail && !ghost.projectile) {
                    // --- 通常モデルで描く (SBW の簡略モデルを使わせない) ---
                    // 車両の位置を PoseStack ではなくモデルビュー行列の側に入れると、画面上の位置は同じまま
                    // SBW からは距離 0 に見えるので、通常モデルが選ばれます (DhModelRenderer と同じ考え方)。
                    // 1.20.1 では光の向きがカメラの向きを基準にしているため、カメラの回転は PoseStack 側に残し、
                    // 回転させた後の位置だけをモデルビュー行列へ入れます (明暗が正しくなるように)。
                    Vector3f v = pose.last().pose().transformPosition((float) rx, (float) ry, (float) rz, new Vector3f());
                    buffers.endBatch();
                    modelViewStack.pushPose();
                    modelViewStack.translate(v.x(), v.y(), v.z());
                    RenderSystem.applyModelViewMatrix();
                    // 半透明部分 (キャノピー等) の前後の並べ替えも、カメラの位置を基準にします
                    RenderSystem.setProjectionMatrix(projection, VertexSorting.byDistance(-v.x(), -v.y(), -v.z()));
                    try {
                        dispatcher.render(dummy, 0.0D, 0.0D, 0.0D, gyRot, 1.0F, pose, buffers, light);
                        buffers.endBatch();
                    } finally {
                        modelViewStack.popPose();
                        RenderSystem.applyModelViewMatrix();
                        RenderSystem.setProjectionMatrix(projection, sorting);
                    }
                } else {
                    pose.pushPose();
                    pose.translate(rx, ry, rz);
                    try {
                        dispatcher.render(dummy, 0.0D, 0.0D, 0.0D, gyRot, 1.0F, pose, buffers, light);
                    } finally {
                        pose.popPose();
                    }
                }
                drawn++;
                GhostDebug.vanilla(ghost, "drawn");
            } catch (Throwable e) {
                SuperbHorizons.LOGGER.warn("[Superb Horizons] ghost render failed for entity {}",
                        ghost.entityId, e);
                GhostDebug.vanilla(ghost, "error");
                GhostDebug.error("vanilla", ghost, e);
                ghost.discardDummy();
            }
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
     * 【デバッグ用】ゴーストの位置に、地形を無視して常に手前に見える枠を描きます (debugLog = true のときだけ)。
     *  緑 … このフレームでゴーストを描いた
     *  青 … 本物の車両に任せた (ゴーストは描いていない)
     *  赤 … 描かなかった (理由はログの change 行)
     * 枠の上には、遠くからでも見つけやすいよう高さ 40 ブロックの柱を立てます。
     * 「緑の枠は見えるのに戦車が見えない」なら、描いてはいるのに何かに隠されている / 塗りつぶされている、と分かります。
     */
    private static void renderDebugBoxes(Camera camera) {
        var ghosts = GhostVehicleStore.all();
        if (ghosts.isEmpty()) return;
        Vec3 cam = camera.getPosition();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        buffers.endBatch();

        PoseStack mv = RenderSystem.getModelViewStack();
        mv.pushPose();
        mv.setIdentity();
        mv.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
        mv.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0F));
        RenderSystem.applyModelViewMatrix();
        PoseStack pose = new PoseStack();
        // 深度の比較を「常に描く」にします (Minecraft 側の記録は LEQUAL のまま、OpenGL にだけ直接設定)
        RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
        org.lwjgl.opengl.GL11.glDepthFunc(org.lwjgl.opengl.GL11.GL_ALWAYS);
        try {
            com.mojang.blaze3d.vertex.VertexConsumer vc = buffers.getBuffer(net.minecraft.client.renderer.RenderType.lines());
            for (GhostVehicle g : ghosts) {
                String state = GhostDebug.overlayState(g.entityId);
                float r = 1.0F, gr = 0.0F, b = 0.0F;
                if (state.equals("drawn")) { r = 0.0F; gr = 1.0F; b = 0.0F; }
                else if (state.equals("real")) { r = 0.2F; gr = 0.6F; b = 1.0F; }
                double x = g.x - cam.x, y = g.y - cam.y, z = g.z - cam.z;
                net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, vc,
                        new net.minecraft.world.phys.AABB(x - 3.0, y, z - 3.0, x + 3.0, y + 4.0, z + 3.0),
                        r, gr, b, 1.0F);
                net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, vc,
                        new net.minecraft.world.phys.AABB(x - 0.25, y + 4.0, z - 0.25, x + 0.25, y + 44.0, z + 0.25),
                        r, gr, b, 1.0F);
            }
            buffers.endBatch(net.minecraft.client.renderer.RenderType.lines());
        } finally {
            org.lwjgl.opengl.GL11.glDepthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
            mv.popPose();
            RenderSystem.applyModelViewMatrix();
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
     * 本物の車両が「実際に」画面に描かれると信用して、ゴーストを描くのをやめてよいか。
     *
     * 描画距離の境目付近では、本物の車両が読み込まれていて Minecraft の判定も「描く」なのに、
     * 実際には描かれないことがあります (2026/10/15 のデバッグで確認: 境目の内側 1 チャンク分ほどで、
     * 本物に任せた戦車が当たり判定ごと見えなくなっていた)。
     * そこで、次のすべてを満たすときだけ本物に任せ、それ以外はゴーストを描きます。
     *  ・本物が読み込まれていて、Minecraft が描く距離の中にいる (isRealEntityShown)
     *  ・カメラからの横方向の距離が、描画距離より 1 チャンク内側まで
     *  ・その場所の地形 (セクション) を Minecraft が描画できる状態になっている
     * (本物も実際に描かれていた場合は、ゴーストと本物が同じ位置に重なるだけです)
     */
    public static boolean realEntityDrawsItself(ClientLevel level, GhostVehicle ghost, Vec3 cam) {
        // 本物が「実際に描かれたか」の記録 (Mixin) が使えるなら、それだけで判断します。
        // 事前の判定と違い、理由が何であれ本物が描かれていなければゴーストを描けます。
        if (RealRenderTracker.isActive()) return RealRenderTracker.renderedRecently(ghost.entityId);

        // --- 以下は予備 (記録の仕組みが動いていない場合の、従来の判定) ---
        if (!isRealEntityShown(level, ghost, cam)) return false;
        Entity real = level.getEntity(ghost.entityId);
        if (real == null) return false;
        double range = Math.max(0, Minecraft.getInstance().options.getEffectiveRenderDistance() - 1) * 16.0;
        double dx = real.getX() - cam.x, dz = real.getZ() - cam.z;
        if (dx * dx + dz * dz > range * range) return false;
        return sectionCompiled(real.blockPosition());
    }

    /** その場所の地形 (セクション) を Minecraft が描画できる状態か (本物の車両は、これが false だと描かれません)。 */
    public static boolean sectionCompiled(net.minecraft.core.BlockPos pos) {
        try {
            return Minecraft.getInstance().levelRenderer.isChunkCompiled(pos);
        } catch (Throwable e) {
            return true;
        }
    }

    /**
     * ゴーストを描く明るさ。
     *
     * worldLight = true (読み込み済みの地形の中) のときは、本物の車両と同じく
     * その場所の実際の明るさを使います (渓谷の底や木陰なら暗く、松明の近くなら明るく)。
     *
     * それ以外 (遠方の LOD 地形の範囲) では、その場所の明るさを調べると読み込まれていないチャンクの
     * 問い合わせで重くなるため、「屋外・空の光のみ」の固定値で描きます (夜は暗くなり、勝手に光りません)。
     * 曳光弾 (A-10 のガトリング・機銃の弾など) は、SBW が「自ら光る」設定にしているので、
     * 本物と同じくその明るさを使います。
     */
    public static int lightFor(EntityRenderDispatcher dispatcher, GhostVehicle ghost, Entity dummy, boolean worldLight) {
        try {
            if (worldLight) return dispatcher.getPackedLightCoords(dummy, 1.0F);   // 本物の車両と同じ問い合わせ
        } catch (Throwable e) {
            return GHOST_LIGHT;
        }
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
        RealRenderTracker.clear();
        if (ModList.get().isLoaded("distanthorizons")) {
            DhModelRenderer.reset();
        }
    }
}
