package com.atsuishio.superbhorizon.client.renderer;

import com.atsuishio.superbhorizon.network.GhostNetwork;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VehicleGhostRenderer {

    private static final Map<UUID, VehicleGhostData> snapshots    = new HashMap<>(32);
    private static final Map<String, Object>          polyMeshCache = new HashMap<>();
    private static final Map<String, Entity>          dummyEntities = new HashMap<>();

    // ─── sbwmeshloader リフレクション ────────────────────────────────────
    // 対象: com.example.sbwmeshloader.core.PolyMeshLoader / PolyMeshModel
    //       com.github.mcmodderanchor.simplebedrockmodel...BedrockModel
    private static Method  loadModelMethod   = null; // PolyMeshLoader.loadModel(ResourceLocation)
    private static Method  renderSplitMethod = null; // PolyMeshModel.renderWithTranslucentSplit(...)
    private static Field   meshMapField      = null; // PolyMeshModel.meshMap（poly_mesh 有無の判定）
    private static Field   bindPoseField     = null; // BedrockModel.bindPose
    private static Method  applyPoseMethod   = null; // BedrockModel.applyPose(Pose)

    private static boolean reflectionInitialized = false;
    private static boolean reflectionAvailable   = false;

    private static final double GHOST_SWITCH_DISTANCE_SQ = 96.0 * 96.0;

    // ─── パケットハンドラ ─────────────────────────────────────────────────

    public static void onLoad(GhostNetwork.LoadPacket pkt) {
        snapshots.put(pkt.vehicleId, new VehicleGhostData(
                pkt.entityId, pkt.vehicleId, pkt.typeKey,
                pkt.x, pkt.y, pkt.z, pkt.yaw, pkt.pitch, pkt.roll));
    }

    public static void onUnload(UUID vehicleId) { snapshots.remove(vehicleId); }

    public static void onTick(GhostNetwork.TickPacket pkt) {
        VehicleGhostData snap = snapshots.get(pkt.vehicleId);
        if (snap == null) return;
        snap.entityId = pkt.entityId;
        snap.x = pkt.x; snap.y = pkt.y; snap.z = pkt.z;
        snap.yaw = pkt.yaw; snap.pitch = pkt.pitch; snap.roll = pkt.roll;
    }

    @SubscribeEvent
    public static void onClientLogoff(ClientPlayerNetworkEvent.LoggingOut event) {
        snapshots.clear();
        polyMeshCache.clear();
    }

    @SubscribeEvent
    public static void onClientEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            VehicleGhostData snap = snapshots.get(event.getEntity().getUUID());
            if (snap != null) snap.entityId = event.getEntity().getId();
        }
    }

    // ─── メインレンダーイベント ───────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (snapshots.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack ps = event.getPoseStack();

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        MultiBufferSource.BufferSource isolatedBuffers = MultiBufferSource.immediate(builder);

        Matrix4f oldProj = RenderSystem.getProjectionMatrix();
        float fov    = (float) (2.0 * Math.atan(1.0 / oldProj.m11()));
        float aspect = oldProj.m11() / oldProj.m00();
        Matrix4f hugeProj = new Matrix4f().setPerspective(fov, aspect, 50.0F, 32000.0F);
        RenderSystem.setProjectionMatrix(hugeProj, VertexSorting.DISTANCE_TO_ORIGIN);

        float oldFogStart = RenderSystem.getShaderFogStart();
        float oldFogEnd   = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(32000.0F);
        RenderSystem.setShaderFogEnd(32000.0F);

        for (VehicleGhostData snap : snapshots.values()) {
            Entity realEntity = mc.level.getEntity(snap.entityId);
            if (realEntity != null) {
                double distSq = mc.player.distanceToSqr(snap.x, snap.y, snap.z);
                if (distSq < GHOST_SWITCH_DISTANCE_SQ) continue;
            }

            try {
                ps.pushPose();
                ps.translate(snap.x - camPos.x, snap.y - camPos.y, snap.z - camPos.z);

                // ──────────────────────────────────────────────────────────
                // 描画ルーティング:
                //  A) sbwmeshloader が存在 かつ poly_mesh を持つモデルが
                //     ロードできた → PolyMeshModel パス
                //  B) それ以外 → 既存の dummy Entity パス（通常 SBW 車両）
                //
                // typeKey のハードコードは一切不要。
                // geo.json が存在して poly_mesh を含んでいれば自動的に A になる。
                // ──────────────────────────────────────────────────────────
                if (!tryRenderPolyMesh(snap, ps, isolatedBuffers)) {
                    renderDummyEntity(snap, ps, isolatedBuffers, event.getPartialTick(), mc);
                }

                ps.popPose();
            } catch (Exception ignored) {}
        }

        isolatedBuffers.endBatch();
        RenderSystem.setProjectionMatrix(oldProj, VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.setShaderFogStart(oldFogStart);
        RenderSystem.setShaderFogEnd(oldFogEnd);
    }

    // ─── PolyMesh 描画 ────────────────────────────────────────────────────

    /**
     * sbwmeshloader の PolyMeshModel でゴーストを描画する。
     *
     * <h3>判定フロー</h3>
     * <ol>
     *   <li>sbwmeshloader がクラスパスに存在しない → false（リフレクション初期化失敗）</li>
     *   <li>typeKey に対応する geo.json がロードできない → false（SBW 標準パスへ fallback）</li>
     *   <li>ロードした PolyMeshModel の meshMap が空 → false
     *       （poly_mesh を持たないモデルは SBW 標準パスで描く）</li>
     *   <li>上記をすべて通過 → renderWithTranslucentSplit() を呼んで true を返す</li>
     * </ol>
     *
     * <p>どのアドオンの geo.json でも、poly_mesh を含んでいれば typeKey に関係なく
     * 自動的にこのパスで描画される。ハードコードリスト不要。</p>
     */
    private static boolean tryRenderPolyMesh(
            VehicleGhostData snap,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers) {

        if (!initReflection()) return false;

        // キャッシュチェック（null = 「試行済みだが poly_mesh なし / ロード失敗」）
        Object mesh;
        if (polyMeshCache.containsKey(snap.typeKey)) {
            mesh = polyMeshCache.get(snap.typeKey);
            if (mesh == null) return false;
        } else {
            // 未キャッシュ: ロード試行
            mesh = loadPolyMesh(snap.typeKey);

            // poly_mesh を持つかチェック（meshMap が空なら SBW 標準パスへ）
            if (mesh != null) {
                try {
                    Map<?, ?> map = (Map<?, ?>) meshMapField.get(mesh);
                    if (map == null || map.isEmpty()) mesh = null;
                } catch (Exception ignored) {}
            }

            polyMeshCache.put(snap.typeKey, mesh); // null もキャッシュして再試行を防ぐ
            if (mesh == null) return false;
        }

        return doRenderMesh(mesh, snap, ps, buffers);
    }

    /**
     * リフレクション経由で {@code renderWithTranslucentSplit()} を呼ぶ。
     * 呼び出し前に bindPose を適用してアニメーションをリセットする。
     */
    private static boolean doRenderMesh(
            Object mesh,
            VehicleGhostData snap,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers) {
        try {
            // アニメーション状態をリセット
            Object bindPose = bindPoseField.get(mesh);
            applyPoseMethod.invoke(mesh, bindPose);

            ResourceLocation texture = snap.textureLocation();

            // 機体の向きを適用（applyEntityTransform 相当）
            ps.pushPose();
            ps.mulPose(Axis.YP.rotationDegrees(180f - snap.yaw));
            ps.mulPose(Axis.XP.rotationDegrees(-snap.pitch));
            ps.mulPose(Axis.ZP.rotationDegrees(-snap.roll));

            // renderWithTranslucentSplit(PoseStack, MultiBufferSource, ResourceLocation, int)
            int packedLight = samplePackedLight(snap);
            renderSplitMethod.invoke(mesh, ps, buffers, texture, packedLight);

            ps.popPose();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * typeKey から geo.json パスを組み立てて PolyMeshModel をロードする。
     *
     * <p>パス規則: {@code <namespace>:custom_geo/<path>.geo.json}<br>
     * 例: {@code "superb_modern_combat:leopard2"} →
     *     {@code ResourceLocation("superb_modern_combat", "custom_geo/leopard2.geo.json")}</p>
     */
    private static Object loadPolyMesh(String typeKey) {
        try {
            String namespace = typeKey.contains(":") ? typeKey.substring(0, typeKey.indexOf(':')) : "minecraft";
            String path      = typeKey.contains(":") ? typeKey.substring(typeKey.indexOf(':') + 1) : typeKey;
            ResourceLocation loc = new ResourceLocation(namespace, "custom_geo/" + path + ".geo.json");
            return loadModelMethod.invoke(null, loc);
        } catch (Exception e) {
            return null;
        }
    }

    // ─── 通常エンティティ描画（既存パス、変更なし）──────────────────────────

    private static void renderDummyEntity(
            VehicleGhostData snap,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers,
            float partialTick,
            Minecraft mc) {
        Entity dummy = dummyEntities.computeIfAbsent(snap.typeKey, k -> {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(k));
            return (type != null) ? type.create(mc.level) : null;
        });
        if (dummy == null) return;

        dummy.setPos(snap.x, snap.y, snap.z);
        dummy.setYRot(snap.yaw);
        dummy.setXRot(snap.pitch);
        dummy.yRotO = snap.yaw;
        dummy.xRotO = snap.pitch;

        var renderer = mc.getEntityRenderDispatcher().getRenderer(dummy);
        if (renderer != null) {
            int packedLight = samplePackedLight(snap);
            renderer.render(dummy, snap.yaw, partialTick, ps, buffers, packedLight);
        }
    }

    // ─── ライトレベル取得 ─────────────────────────────────────────────────

    /**
     * ゴーストの座標からワールドのライトレベルを取得して packed light 値を返す。
     *
     * <p>ゴーストは描画距離外にいるため、クライアントにそのチャンクがロードされていない
     * 可能性がある。その場合は空のブロック光(0)と現在の空の光(skyLight)を使い、
     * 夜間・屋内では暗くなるようにする。</p>
     */
    private static int samplePackedLight(VehicleGhostData snap) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return LightTexture.FULL_BRIGHT;

        BlockPos pos = BlockPos.containing(snap.x, snap.y, snap.z);

        // チャンクがロードされていれば正確な値を使う
        if (mc.level.isLoaded(pos)) {
            int block = mc.level.getBrightness(LightLayer.BLOCK, pos);
            int sky   = mc.level.getBrightness(LightLayer.SKY,   pos);
            return LightTexture.pack(block, sky);
        }

        // チャンク未ロード（ゴーストが遠方にいる典型的なケース）:
        // ブロック光は 0、空光は現在の時刻に応じた天空輝度で近似する
        int skyDarken = mc.level.getSkyDarken(); // 0(昼)～15(夜)
        int sky = Math.max(0, 15 - skyDarken);
        return LightTexture.pack(0, sky);
    }

    // ─── リフレクション初期化 ─────────────────────────────────────────────

    /**
     * sbwmeshloader のクラス・メソッド・フィールドをリフレクションで取得する。
     *
     * <p>sbwmeshloader が導入されていない環境では {@code ClassNotFoundException} が発生し、
     * {@code reflectionAvailable = false} のまま終わる。この場合は全車両が
     * dummy Entity パスにフォールバックする（通常の SBW 動作と同じ）。</p>
     *
     * <p>取得対象:</p>
     * <ul>
     *   <li>{@code com.example.sbwmeshloader.core.PolyMeshLoader
     *       #loadModel(ResourceLocation)}</li>
     *   <li>{@code com.example.sbwmeshloader.core.PolyMeshModel
     *       #renderWithTranslucentSplit(PoseStack, MultiBufferSource, ResourceLocation, int)}</li>
     *   <li>{@code com.example.sbwmeshloader.core.PolyMeshModel#meshMap}（poly_mesh 判定用）</li>
     *   <li>{@code com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel
     *       #bindPose}</li>
     *   <li>{@code BedrockModel#applyPose(Pose)}</li>
     * </ul>
     */
    private static boolean initReflection() {
        if (reflectionInitialized) return reflectionAvailable;
        reflectionInitialized = true;
        try {
            // PolyMeshLoader
            Class<?> loaderClass = Class.forName("com.example.sbwmeshloader.core.PolyMeshLoader");
            loadModelMethod = loaderClass.getMethod("loadModel", ResourceLocation.class);

            // PolyMeshModel
            Class<?> modelClass = Class.forName("com.example.sbwmeshloader.core.PolyMeshModel");
            renderSplitMethod = modelClass.getMethod(
                    "renderWithTranslucentSplit",
                    PoseStack.class, MultiBufferSource.class, ResourceLocation.class, int.class);
            meshMapField = modelClass.getDeclaredField("meshMap");
            meshMapField.setAccessible(true);

            // BedrockModel（SBW 付属の simplebedrockmodel ライブラリ）
            Class<?> bedrockModelClass = Class.forName(
                    "com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel");
            bindPoseField = bedrockModelClass.getDeclaredField("bindPose");
            bindPoseField.setAccessible(true);
            applyPoseMethod = bedrockModelClass.getMethod("applyPose", bindPoseField.getType());

            reflectionAvailable = true;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException e) {
            // sbwmeshloader が存在しない環境では正常にここへ来る（エラーではない）
            reflectionAvailable = false;
        }
        return reflectionAvailable;
    }
}
