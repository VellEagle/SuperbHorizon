package com.velleagle.superbhorizons.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.velleagle.superbhorizons.Config;
import com.velleagle.superbhorizons.SuperbHorizons;
import com.velleagle.superbhorizons.network.GhostAnimation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.Set;

/**
 * ゴーストモデルを、Distant Horizons 自身のフレームバッファへ描き込みます。
 *
 * ■ なぜこの方式なのか
 * DH は LOD 地形の深度を外から使える形で公開していません。
 * そのため、こちらの画面に描いたモデルを LOD 地形の裏に隠すことができませんでした。
 *
 * しかし DH が「描いた内容を画面へコピーする直前」に割り込めば、
 * その時点では DH のフレームバッファに LOD 地形の色と深度が入っています。
 * そこへモデルを描けば、前後関係は GPU の深度テストが自動的に処理します。
 * あとは DH が地形ごと画面へコピーしてくれます。
 *
 * 箱に置き換える方式と違い、モデルはそのままの形で残り、
 * 砲塔やプロペラなどのアニメーションもそのまま反映されます。
 *
 * ■ 座標変換について
 * DH は通常の描画とは別の遠近設定で描いているため、
 * DH 自身の「モデルビュー × 投影」行列を使う必要があります。
 * この行列にカメラからの相対座標を掛けると DH の画面座標になります
 * (深度判定の実装時に、バニラ側の計算と一致することを確認済みです)。
 *
 * そこで、この行列をまるごと投影行列として設定し、
 * モデルビュー側は車両の相対位置の平行移動だけにします。
 */
public class DhModelRenderer {

    private static boolean loggedFirst = false;
    private static boolean loggedNoMatrix = false;
    private static boolean loggedFailure = false;

    /** 直前のフレームで DH 側に描いた車両。通常の描画から除外するために使います。 */
    private static volatile Set<Integer> rendered = new HashSet<>();

    public static Set<Integer> renderedLastFrame() {
        return rendered;
    }

    /**
     * DH のコピー直前に呼ばれます。
     * ここで描いたものは DH の LOD 地形と正しく前後関係が付きます。
     */
    public static void render() {
        GhostDebug.dhCall();   // 【デバッグ用】DH の描画処理が呼ばれた回数
        GhostDebug.dhFog(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
        if (!isEnabled()) {
            GhostDebug.dhNote("disabled");
            rendered = new HashSet<>();
            return;
        }

        float[] m = DhDepthHook.mvmProj;
        if (m == null || m.length < 16) {
            GhostDebug.dhNote("no-dh-matrix");
            if (!loggedNoMatrix) {
                loggedNoMatrix = true;
                SuperbHorizons.LOGGER.warn(
                        "[Superb Horizons] DH の行列をまだ受け取れていません。"
                                + " 描画イベントの登録に失敗している可能性があります。");
            }
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        var ghosts = GhostVehicleStore.all();
        if (ghosts.isEmpty()) {
            rendered = new HashSet<>();
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();

        Set<Integer> drawn = new HashSet<>();

        // --- 描画状態を DH に合わせる ---
        Matrix4f previousProjection = RenderSystem.getProjectionMatrix();
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        PoseStack modelViewStack = RenderSystem.getModelViewStack();

        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // シェーダーパック使用時は、Oculus が LOD 地形を自前の遠近設定で描いています。
        // ゴーストも同じ設定で描かないと、LOD 地形と奥行きを比べられません。
        boolean shaderMode = ShaderDhOcclusion.isShaderPackInUse();
        Matrix4f dhMatrix = null;
        if (shaderMode) {
            dhMatrix = ShaderDhOcclusion.lodMatrix(DhDepthHook.nearClip, DhDepthHook.farClip);
        }
        if (dhMatrix == null) dhMatrix = toMatrix(m);

        // DH の深度が逆向き (手前ほど大きい) かどうか
        boolean depthReversed = false;
        try {
            depthReversed = DhDepthDirection.isReversed(dhMatrix, camera, !shaderMode);
            GhostDebug.dhInfo(shaderMode, depthReversed);
        } catch (Throwable ignored) {
            // 判定できない場合は通常の向きとして扱います
        }

        try {
            // DH の行列をそのまま投影行列として設定します。
            // これで「カメラからの相対座標」がそのまま DH の画面座標になります。
            RenderSystem.setProjectionMatrix(dhMatrix, VertexSorting.DISTANCE_TO_ORIGIN);

            modelViewStack.pushPose();
            modelViewStack.setIdentity();
            RenderSystem.applyModelViewMatrix();

            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            dispatcher.setRenderShadow(false);

            // シェーダー使用時: LOD 地形の奥行きを、ゴーストが書き込む深度バッファへ写します。
            // これで LOD 地形の裏にあるゴーストの部分は描かれなくなります。
            if (shaderMode) ShaderDhOcclusion.begin(depthReversed);

            // DH の深度が逆向きなら、深度の比較方向も逆にします。
            if (depthReversed) useReversedDepthTest();

            int intervalMs = Config.UPDATE_INTERVAL.get() * 50;
            boolean fullDetail = Config.GHOST_FULL_DETAIL.get();
            PoseStack pose = new PoseStack();

            for (GhostVehicle ghost : ghosts) {
                // DH が Minecraft の画面を LOD へ置き換える距離では、本物の車両が描かれていても見えなくなるため、
                // 下の 2 つの判定をせずに、ここ (DH の画面) へゴーストを描きます
                boolean faded = GhostRenderer.inDhFadeZone(ghost, cam);
                // 本物のエンティティが画面に描かれているならそちらに任せる
                // (読み込まれていても、遠くて描かれていない場合はゴーストを描きます)
                if (!faded && GhostRenderer.realEntityDrawsItself(level, ghost, cam)) {
                    GhostDebug.dh(ghost, "real-shown");
                    continue;
                }
                // 読み込み済みの地形の中にいるゴーストは、通常の描画 (GhostRenderer) で描きます
                if (!faded && GhostRenderer.insideLoadedTerrain(level, ghost, cam)) {
                    GhostDebug.dh(ghost, "near-terrain");
                    continue;
                }
                if (GhostRenderer.removedBySbw(level, ghost, cam)) {   // SBW が既に消した弾
                    GhostDebug.dh(ghost, "sbw-removed");
                    continue;
                }

                Entity dummy = ghost.getDummy(level);
                if (dummy == null) {
                    GhostDebug.dh(ghost, "no-dummy");
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

                // 車体のロール (アニメーション情報の有無に関係なく必ず反映)
                VehicleAnimationWriter.applyRoll(dummy, gRoll);

                // 可動部分 (砲塔・車輪・履帯・プロペラなど) と弾数を反映
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

                if (fullDetail && !ghost.projectile) {
                    // --- 通常モデルで描く (SBW の簡略モデルを使わせない) ---
                    // SBW は、描画時の位置 (PoseStack の平行移動) からカメラとの距離を測り、
                    // 遠ければ簡略モデル (LOD) に切り替えます。簡略モデルでは射撃アニメーションと
                    // 武装 (翼下のミサイル等) の表示が省略されます。
                    // そこで、車両の位置を PoseStack ではなく「モデルビュー行列」側に入れて描きます。
                    // 画面上の位置は全く同じですが、SBW からは距離 0 に見えるので通常モデルが選ばれます。
                    // モデルビュー行列は描画の瞬間に使われるため、1 台ずつ描き切ります。
                    if (depthReversed) useReversedDepthTest();
                    buffers.endBatch();
                    modelViewStack.pushPose();
                    modelViewStack.translate(rx, ry, rz);
                    RenderSystem.applyModelViewMatrix();
                    // 半透明部分 (キャノピー等) の前後の並べ替えも、カメラの位置を基準にします
                    RenderSystem.setProjectionMatrix(dhMatrix,
                            VertexSorting.byDistance((float) -rx, (float) -ry, (float) -rz));
                    try {
                        if (depthReversed) useReversedDepthTest();
                        dispatcher.render(dummy, 0.0D, 0.0D, 0.0D, gyRot, 1.0F,
                                pose, buffers, GhostRenderer.lightFor(dispatcher, ghost, dummy, false));   // 車両は空の光のみ (夜は暗い)、曳光弾は自ら光る
                        if (depthReversed) useReversedDepthTest();
                        buffers.endBatch();
                        drawn.add(ghost.entityId);
                        GhostDebug.dh(ghost, "drawn");
                    } catch (Throwable e) {
                        GhostDebug.dh(ghost, "error");
                        GhostDebug.error("dh", ghost, e);
                        ghost.discardDummy();
                    } finally {
                        modelViewStack.popPose();
                        RenderSystem.applyModelViewMatrix();
                        RenderSystem.setProjectionMatrix(dhMatrix, VertexSorting.DISTANCE_TO_ORIGIN);
                    }
                    continue;
                }

                pose.pushPose();
                pose.translate(rx, ry, rz);
                // 車両のレンダラーが途中で比較方法を変えていても、1 台ごとにかけ直します
                if (depthReversed) useReversedDepthTest();
                try {
                    dispatcher.render(dummy, 0.0D, 0.0D, 0.0D, gyRot, 1.0F,
                            pose, buffers, GhostRenderer.lightFor(dispatcher, ghost, dummy, false));   // 車両は空の光のみ (夜は暗い)、曳光弾は自ら光る
                    drawn.add(ghost.entityId);
                        GhostDebug.dh(ghost, "drawn");
                } catch (Throwable e) {
                    GhostDebug.dh(ghost, "error");
                        GhostDebug.error("dh", ghost, e);
                        ghost.discardDummy();
                }
                pose.popPose();
            }

            // 実際の描画はこの endBatch の中で行われるため、直前にもう一度かけ直します
            // (途中で別の比較方法に切り替えた描画があっても確実に効かせるため)。
            if (depthReversed) useReversedDepthTest();
            buffers.endBatch();

            // シェーダー使用時: ゴーストが描かれなかった画素の深度を元に戻します
            ShaderDhOcclusion.end();

            if (!loggedFirst && !drawn.isEmpty()) {
                loggedFirst = true;
                SuperbHorizons.LOGGER.info(
                        "[Superb Horizons] DH のバッファへゴーストを描画しています ({}台)", drawn.size());
            }

        } catch (Throwable e) {
            GhostDebug.dhNote("failed");
            GhostDebug.error("dh-frame", null, e);
            if (!loggedFailure) {
                loggedFailure = true;
                SuperbHorizons.LOGGER.warn("[Superb Horizons] DH のバッファへの描画に失敗", e);
            }
        } finally {
            // 途中で失敗した場合も、深度バッファは必ず元に戻します (何もしていなければ何もしません)
            ShaderDhOcclusion.end();
            // 深度の比較方向を通常に戻します (Minecraft 側の記録と実際の状態を一致させる)
            if (depthReversed) {
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
            }
            dispatcher.setRenderShadow(true);
            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
        }

        rendered = drawn;
    }

    /**
     * 深度の比較を「既存の値以上なら描く (GEQUAL)」に切り替えます。
     *
     * ■ 仕組み
     * Minecraft の描画設定 (RenderType) は、描画のたびに比較方法を
     * 「以下なら描く (LEQUAL)」に設定し直します。
     * ただし Minecraft は「今の設定」を記録していて、
     * 記録と同じ値を設定しようとした場合は OpenGL への命令を省略します。
     *
     * そこで、まず記録を LEQUAL にそろえてから、OpenGL にだけ直接 GEQUAL を設定します。
     * すると以降の「LEQUAL に設定」はすべて省略され、実際には GEQUAL のまま描かれます。
     * 終わったら finally で OpenGL を LEQUAL に戻し、記録と一致させます。
     * (モデルの描画方法が VBO などでも、同じ仕組みでまとめて効きます)
     */
    private static void useReversedDepthTest() {
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        GL11.glDepthFunc(GL11.GL_GEQUAL);
    }

    private static boolean isEnabled() {
        // 「ゴーストを描画しない」設定のときは DH 側にも描きません
        return Config.RENDER_GHOSTS.get() && GhostRenderer.useDhModel();
    }

    /**
     * DH から受け取った 16 個の数値を行列にします。
     *
     * DH の行列は行優先で並んでいます (深度判定の実装時に、
     * 自動判定で「行優先, ずれ=0.0000」と確認済みです)。
     * JOML の行列は列優先なので、並べ替えて渡す必要があります。
     * 間違えると転置された行列になり、モデルが妙な位置に出て、
     * 視点に応じて変形します。
     */
    private static Matrix4f toMatrix(float[] m) {
        boolean rowMajor = Config.DH_MATRIX_ROW_MAJOR.get();

        if (rowMajor) {
            // 行優先で届いた値を、列ごとに並べ替えます
            return new Matrix4f(
                    m[0], m[4], m[8], m[12],
                    m[1], m[5], m[9], m[13],
                    m[2], m[6], m[10], m[14],
                    m[3], m[7], m[11], m[15]);
        }
        return new Matrix4f(
                m[0], m[1], m[2], m[3],
                m[4], m[5], m[6], m[7],
                m[8], m[9], m[10], m[11],
                m[12], m[13], m[14], m[15]);
    }

    public static void reset() {
        DhDepthDirection.reset();
        ShaderDhOcclusion.reset();
        loggedFirst = false;
        loggedFailure = false;
        loggedNoMatrix = false;
        rendered = new HashSet<>();
    }
}
