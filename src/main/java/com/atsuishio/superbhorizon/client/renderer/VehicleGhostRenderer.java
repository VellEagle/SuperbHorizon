package com.atsuishio.superbhorizon.client.renderer;

import com.atsuishio.superbhorizon.SuperbHorizonConfig;
import com.atsuishio.superbhorizon.network.GhostNetwork;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import com.atsuishio.superbwarfare.tools.EntityFindUtil;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VehicleGhostRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, VehicleGhostData> snapshots = new HashMap<>(32);
    private static final Map<String, Object> polyMeshCache = new HashMap<>();
    private static final Map<String, Entity> dummyEntities = new HashMap<>();
    private static final Set<String> loggedRenderFailures = new HashSet<>();

    private static Method loadModelMethod = null;
    private static Method renderSplitMethod = null;
    private static Field meshMapField = null;
    private static Field bindPoseField = null;
    private static Method applyPoseMethod = null;

    private static boolean reflectionInitialized = false;
    private static boolean reflectionAvailable = false;

    private static final float FAR_PLANE = 32000.0F;

    // フレームスキップカウンター（STATICゴーストのフレーム間引き用）
    private static int globalFrameCounter = 0;

    // STATICゴーストの「最後に描画したスナップショットの状態」キャッシュ。
    // フレームスキップ中のフレームでも、このキャッシュを使って再描画することで点滅を防ぐ。
    private static final Map<UUID, CachedStaticFrame> staticFrameCache = new HashMap<>();

    private static final class CachedStaticFrame {
        final double x, y, z;
        final float yaw;
        CachedStaticFrame(double x, double y, double z, float yaw) {
            this.x = x; this.y = y; this.z = z; this.yaw = yaw;
        }
    }

    // 再利用可能な描画バッファ（毎フレームのGCアロケーションを排除）
    private static MultiBufferSource.BufferSource sharedIsolatedBuffers = null;

    /**
     * LODレベルの定義。
     * FULL   : フルモデル＋アニメーション（ghostSwitchDistance ～ lodFullDistance）
     * STATIC : 静的モデル、アニメなし・バインドポーズ固定（lodFullDistance ～ lodStaticDistance）
     * SKIP   : 描画スキップ（lodStaticDistance より遠い）
     */
    private enum LodLevel {
        FULL, STATIC, STATIC_CACHED, SKIP
    }

    public static void onLoad(GhostNetwork.LoadPacket pkt) {
        loadSnapshot(pkt);
    }

    public static void onBatchLoad(GhostNetwork.BatchLoadPacket pkt) {
        if (pkt.clearFirst) clearSnapshots();
        for (GhostNetwork.GhostSnapshot snapshot : pkt.snapshots) loadSnapshot(snapshot);
    }

    public static void onUnload(UUID vehicleId) {
        snapshots.remove(vehicleId);
    }

    public static void onTick(GhostNetwork.TickPacket pkt) {
        VehicleGhostData snap = snapshots.get(pkt.vehicleId);
        if (snap == null) return;
        snap.update(pkt.entityId, pkt.x, pkt.y, pkt.z, pkt.yaw, pkt.pitch, pkt.roll, pkt.animation);
    }

    public static void clearSnapshots() {
        snapshots.clear();
        dummyEntities.clear();
        staticFrameCache.clear();
    }

    @SubscribeEvent
    public static void onClientLogoff(ClientPlayerNetworkEvent.LoggingOut event) {
        clearSnapshots();
        polyMeshCache.clear();
        loggedRenderFailures.clear();
        sharedIsolatedBuffers = null;
        globalFrameCounter = 0;
        staticFrameCache.clear();
    }

    @SubscribeEvent
    public static void onClientEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            VehicleGhostData snap = snapshots.get(event.getEntity().getUUID());
            if (snap != null) snap.entityId = event.getEntity().getId();
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || snapshots.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack ps = event.getPoseStack();
        float partialTick = event.getPartialTick();
        long gameTime = mc.level.getGameTime();

        globalFrameCounter++;

        // 各距離しきい値の二乗を事前計算
        double clientRenderDistance = mc.options.renderDistance().get() * 16.0;
        double ghostSwitchDist    = Math.min(clientRenderDistance, SuperbHorizonConfig.GHOST_SWITCH_DISTANCE.get());
        double ghostSwitchDistSq  = ghostSwitchDist * ghostSwitchDist;

        // ドローン操縦中はプレイヤーの体の座標とカメラ座標が大きく離れる。
        // このとき「プレイヤーから近い＝通常レンダラーで描く」というスキップ判定が
        // ドローンカメラ視点での描画を正しく行えなくさせるため、
        // ドローン操縦中は ghostSwitchDistSq チェックを無効化する。
        boolean isDroneCamera = false;
        java.util.UUID linkedDroneUuid = null;
        ItemStack heldStack = mc.player.getMainHandItem();
        if (heldStack.is(ModItems.MONITOR.get())
                && heldStack.getOrCreateTag().getBoolean("Using")
                && heldStack.getOrCreateTag().getBoolean("Linked")) {
            DroneEntity linkedDrone = EntityFindUtil.findDrone(
                    mc.level, heldStack.getOrCreateTag().getString("LinkedDrone"));
            if (linkedDrone != null) {
                isDroneCamera = true;
                linkedDroneUuid = linkedDrone.getUUID();
            }
        }

        double lodFullDist     = SuperbHorizonConfig.LOD_FULL_DISTANCE.get();
        boolean lodEnabled     = lodFullDist > 0.0;
        double lodFullDistSq   = lodEnabled ? lodFullDist * lodFullDist : Double.MAX_VALUE;

        double lodStaticDist   = SuperbHorizonConfig.LOD_STATIC_DISTANCE.get();
        boolean staticCullEnabled = lodStaticDist > 0.0;
        double lodStaticDistSq = staticCullEnabled ? lodStaticDist * lodStaticDist : Double.MAX_VALUE;

        int maxGhosts       = SuperbHorizonConfig.MAX_GHOST_COUNT.get();
        int staticFrameSkip = SuperbHorizonConfig.STATIC_FRAME_SKIP.get();

        if (sharedIsolatedBuffers == null) {
            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            sharedIsolatedBuffers = MultiBufferSource.immediate(builder);
        }

        Matrix4f oldProj   = RenderSystem.getProjectionMatrix();
        float oldFogStart  = RenderSystem.getShaderFogStart();
        float oldFogEnd    = RenderSystem.getShaderFogEnd();

        try {
            float fov    = (float) (2.0 * Math.atan(1.0 / oldProj.m11()));
            float aspect = oldProj.m11() / oldProj.m00();
            Matrix4f hugeProj = new Matrix4f().setPerspective(fov, aspect, 0.6F, FAR_PLANE);
            RenderSystem.setProjectionMatrix(hugeProj, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setShaderFogStart(FAR_PLANE);
            RenderSystem.setShaderFogEnd(FAR_PLANE);

            // 描画リスト構築
            List<CandidateEntry> candidates = new ArrayList<>(snapshots.size());

            for (VehicleGhostData snap : snapshots.values()) {
                if (snap.isStale()) continue;

                Entity realEntity = mc.level.getEntity(snap.entityId);
                // ドローン操縦中はカメラがドローン座標にあるため、プレイヤー体付近の
                // チャンクロード済み車両はFrustum外になり通常レンダラーが描かない。
                // その場合 realEntity が存在してもゴーストとして描く必要があるため、
                // isDroneCamera のときは realEntity チェックをスキップする。
                // ただし操縦中のドローン自身はカメラ直下に実体があり通常レンダラーが描くため、
                // ゴーストとして重複描画しないようにスキップする。
                boolean isLinkedDrone = isDroneCamera
                        && linkedDroneUuid != null
                        && linkedDroneUuid.equals(snap.vehicleId);
                if (isLinkedDrone) continue;
                if (!isDroneCamera && realEntity != null && !realEntity.isRemoved()) continue;

                // ghostSwitchDist チェック（近距離スキップ）はプレイヤー座標基準のまま維持し、
                // 通常エンティティレンダラーとの二重描画を避ける。
                // ただしドローン操縦中はカメラ位置がプレイヤーの体から大きく離れるため
                // このスキップを無効化し、ドローン視点から全ゴーストを描画できるようにする。
                if (!isDroneCamera) {
                    double distSqFromPlayer = mc.player.distanceToSqr(snap.x, snap.y, snap.z);
                    if (distSqFromPlayer < ghostSwitchDistSq) continue;
                }

                // LOD・カリング判定はカメラ座標（ドローン操縦時はドローン位置）を基準にする
                double dx = snap.x - camPos.x;
                double dy = snap.y - camPos.y;
                double dz = snap.z - camPos.z;
                double distSq = dx * dx + dy * dy + dz * dz;

                // STATIC距離外は描画スキップ
                if (staticCullEnabled && distSq > lodStaticDistSq) continue;

                LodLevel lod;
                if (lodEnabled && distSq > lodFullDistSq) {
                    lod = LodLevel.STATIC;
                    if (staticFrameSkip > 1) {
                        int offset = Math.abs(snap.vehicleId.hashCode()) % staticFrameSkip;
                        if ((globalFrameCounter + offset) % staticFrameSkip != 0) {
                            // スキップフレーム：前回のキャッシュがあればそれで描画して点滅を防ぐ
                            CachedStaticFrame cached = staticFrameCache.get(snap.vehicleId);
                            if (cached != null) {
                                candidates.add(new CandidateEntry(snap, distSq, LodLevel.STATIC_CACHED, cached));
                            }
                            continue;
                        }
                    }
                    // 描画フレーム：キャッシュを更新
                    staticFrameCache.put(snap.vehicleId, new CachedStaticFrame(snap.x, snap.y, snap.z, snap.yaw));
                } else {
                    lod = LodLevel.FULL;
                }

                candidates.add(new CandidateEntry(snap, distSq, lod, null));
            }

            // 台数上限：カメラに近い順にソートして遠いものを落とす
            if (maxGhosts > 0 && candidates.size() > maxGhosts) {
                candidates.sort(Comparator.comparingDouble(e -> e.distSq));
                candidates = candidates.subList(0, maxGhosts);
            }

            for (CandidateEntry entry : candidates) {
                if (entry.lod == LodLevel.STATIC_CACHED) {
                    // スキップフレーム：キャッシュされた位置・向きで静的に描画（補間なし）
                    renderStaticCached(entry.snap, entry.cached, ps, sharedIsolatedBuffers, camPos, mc);
                } else {
                    entry.snap.prepareFrame(gameTime, partialTick);
                    renderSnapshot(entry.snap, ps, sharedIsolatedBuffers, partialTick, camPos, mc, entry.lod);
                }
            }
        } finally {
            sharedIsolatedBuffers.endBatch();
            RenderSystem.setProjectionMatrix(oldProj, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setShaderFogStart(oldFogStart);
            RenderSystem.setShaderFogEnd(oldFogEnd);
        }
    }

    // -------------------------------------------------------------------------
    // フルモデル / 静的モデル描画の振り分け
    // -------------------------------------------------------------------------

    /**
     * フレームスキップ中のフレームで使用。キャッシュされた座標・向きをそのまま使い
     * アニメなし・補間なしで静的に描画することで、スキップフレームの点滅を防ぎます。
     */
    private static void renderStaticCached(
            VehicleGhostData snap,
            CachedStaticFrame cached,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers,
            Vec3 camPos,
            Minecraft mc) {
        ps.pushPose();
        try {
            ps.translate(cached.x - camPos.x, cached.y - camPos.y, cached.z - camPos.z);
            if (!tryRenderPolyMeshCached(snap, cached, ps, buffers, mc.level)) {
                renderStaticDummyEntityCached(snap, cached, ps, buffers, mc);
            }
        } catch (Exception e) {
            logRenderFailureOnce("cached:" + snap.typeKey, "Failed to render cached ghost " + snap.typeKey, e);
        } finally {
            ps.popPose();
        }
    }

    private static boolean tryRenderPolyMeshCached(
            VehicleGhostData snap,
            CachedStaticFrame cached,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers,
            Level level) {
        // STATIC_CACHED パスも tryRenderPolyMeshStatic に統一し、
        // cached.yaw をスナップショットに反映してから描画する。
        // snap.yaw は renderStaticCached の呼び出し元でキャッシュ時の値が入っているため
        // そのまま tryRenderPolyMeshStatic に渡せる。
        return tryRenderPolyMeshStatic(snap, ps, buffers, level);
    }

    private static void renderStaticDummyEntityCached(
            VehicleGhostData snap,
            CachedStaticFrame cached,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers,
            Minecraft mc) {
        Entity dummy = getOrCreateDummy(snap, mc);
        if (dummy == null) return;
        dummy.xo = cached.x; dummy.yo = cached.y; dummy.zo = cached.z;
        dummy.setPos(cached.x, cached.y, cached.z);
        dummy.setYRot(cached.yaw); dummy.yRotO = cached.yaw;
        dummy.setXRot(0.0F); dummy.xRotO = 0.0F;
        if (dummy instanceof VehicleEntity vehicle) {
            vehicle.setRoll(0.0F); vehicle.setPrevRoll(0.0F);
        }
        var renderer = mc.getEntityRenderDispatcher().getRenderer(dummy);
        if (renderer != null) {
            renderer.render(dummy, cached.yaw, 1.0f, ps, buffers, snap.getCachedPackedLight(mc.level));
        }
    }

    private static void renderSnapshot(
            VehicleGhostData snap,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers,
            float partialTick,
            Vec3 camPos,
            Minecraft mc,
            LodLevel lod) {
        ps.pushPose();
        try {
            ps.translate(
                    snap.renderX(partialTick) - camPos.x,
                    snap.renderY(partialTick) - camPos.y,
                    snap.renderZ(partialTick) - camPos.z);

            if (lod == LodLevel.FULL) {
                // フルモデル：アニメーションあり
                if (snap.hasAnimationState() && SuperbHorizonConfig.PREFER_ANIMATED_ENTITY_FALLBACK.get()) {
                    if (!renderDummyEntity(snap, ps, buffers, partialTick, mc)) {
                        tryRenderPolyMesh(snap, ps, buffers, partialTick, mc.level, false);
                    }
                } else if (!tryRenderPolyMesh(snap, ps, buffers, partialTick, mc.level, false)) {
                    renderDummyEntity(snap, ps, buffers, partialTick, mc);
                }
            } else {
                // STATICモデル：アニメなし（バインドポーズ固定）
                // PolyMesh が使える場合は DummyEntity を一切生成・参照しない。
                // これにより SMC 等のメッシュレンダラー（TrackPath 計算など）が
                // 静的ゴーストのために実行されることを防ぐ。
                // PolyMesh が使えない場合のみ軽量化した静的 DummyEntity 描画にフォールバック。
                if (!tryRenderPolyMeshStatic(snap, ps, buffers, mc.level)) {
                    renderStaticDummyEntity(snap, ps, buffers, partialTick, mc);
                }
            }
        } catch (Exception e) {
            logRenderFailureOnce("snapshot:" + snap.typeKey, "Failed to render ghost vehicle " + snap.typeKey, e);
        } finally {
            ps.popPose();
        }
    }

    // -------------------------------------------------------------------------
    // PolyMesh 描画
    // -------------------------------------------------------------------------

    private static boolean tryRenderPolyMesh(
            VehicleGhostData snap,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers,
            float partialTick,
            Level level,
            boolean staticPose) {
        if (!initReflection()) return false;
        if (!SuperbHorizonConfig.ENABLE_POLYMESH.get()) return false;

        Object mesh;
        if (polyMeshCache.containsKey(snap.typeKey)) {
            mesh = polyMeshCache.get(snap.typeKey);
            if (mesh == null) return false;
        } else {
            mesh = loadPolyMesh(snap.typeKey);
            if (mesh != null && !hasPolyMesh(mesh, snap.typeKey)) mesh = null;
            polyMeshCache.put(snap.typeKey, mesh);
            if (mesh == null) return false;
        }

        return doRenderMesh(mesh, snap, ps, buffers, partialTick, level, staticPose);
    }

    /**
     * STATIC LOD 専用の軽量 PolyMesh 描画。
     * DummyEntity を一切使わず PolyMeshModel.renderWithTranslucentSplit() を直接呼び出す。
     * partialTick を渡さず snap.yaw（最新値）をそのまま使うことで補間計算コストも省く。
     * PolyMesh が利用不可（SBW キューブ車両など）の場合は false を返す。
     */
    private static boolean tryRenderPolyMeshStatic(
            VehicleGhostData snap,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers,
            Level level) {
        if (!initReflection()) return false;
        if (!SuperbHorizonConfig.ENABLE_POLYMESH.get()) return false;

        // キャッシュ済みメッシュを取得（なければ false ＝ DummyEntity フォールバックへ）
        Object mesh;
        if (polyMeshCache.containsKey(snap.typeKey)) {
            mesh = polyMeshCache.get(snap.typeKey);
            if (mesh == null) return false;
        } else {
            mesh = loadPolyMesh(snap.typeKey);
            if (mesh != null && !hasPolyMesh(mesh, snap.typeKey)) mesh = null;
            polyMeshCache.put(snap.typeKey, mesh);
            if (mesh == null) return false;
        }

        ps.pushPose();
        try {
            // バインドポーズを適用（アニメーションなし固定）
            Object bindPose = bindPoseField.get(mesh);
            applyPoseMethod.invoke(mesh, bindPose);

            // ヨーのみ反映。ピッチ・ロールは省略（静的ゴーストなので不要）。
            // partialTick 補間も省略し snap.yaw 最新値をそのまま使う。
            ps.mulPose(Axis.YP.rotationDegrees(180.0F - snap.yaw));

            renderSplitMethod.invoke(mesh, ps, buffers, snap.textureLocation(), snap.getCachedPackedLight(level));
            return true;
        } catch (Exception e) {
            logRenderFailureOnce("polyStatic:" + snap.typeKey,
                    "PolyMesh static render failed for " + snap.typeKey + "; falling back to entity renderer", e);
            return false;
        } finally {
            ps.popPose();
        }
    }

    private static boolean hasPolyMesh(Object mesh, String typeKey) {
        try {
            Map<?, ?> map = (Map<?, ?>) meshMapField.get(mesh);
            return map != null && !map.isEmpty();
        } catch (Exception e) {
            logRenderFailureOnce("meshMap:" + typeKey, "Could not inspect PolyMesh data for " + typeKey, e);
            return false;
        }
    }

    private static boolean doRenderMesh(
            Object mesh,
            VehicleGhostData snap,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers,
            float partialTick,
            Level level,
            boolean staticPose) {
        ps.pushPose();
        try {
            Object bindPose = bindPoseField.get(mesh);
            applyPoseMethod.invoke(mesh, bindPose);

            // staticPose=true のときはヨーのみ反映し、ピッチ・ロールは適用しない
            ps.mulPose(Axis.YP.rotationDegrees(180.0F - snap.renderYaw(partialTick)));
            if (!staticPose) {
                ps.mulPose(Axis.XP.rotationDegrees(-snap.renderPitch(partialTick)));
                ps.mulPose(Axis.ZP.rotationDegrees(-snap.renderRoll(partialTick)));
            }

            renderSplitMethod.invoke(mesh, ps, buffers, snap.textureLocation(), snap.getCachedPackedLight(level));
            return true;
        } catch (Exception e) {
            logRenderFailureOnce("poly:" + snap.typeKey, "PolyMesh render failed for " + snap.typeKey + "; falling back to entity renderer", e);
            return false;
        } finally {
            ps.popPose();
        }
    }

    private static Object loadPolyMesh(String typeKey) {
        try {
            ResourceLocation type = ResourceLocation.tryParse(typeKey);
            if (type == null) return null;
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                    type.getNamespace(), "custom_geo/" + type.getPath() + ".geo.json");
            return loadModelMethod.invoke(null, loc);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // DummyEntity 描画（フル・アニメーションあり）
    // -------------------------------------------------------------------------

    private static boolean renderDummyEntity(
            VehicleGhostData snap,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers,
            float partialTick,
            Minecraft mc) {
        Entity dummy = getOrCreateDummy(snap, mc);
        if (dummy == null) return false;

        dummy.xo = snap.renderX(0.0F);
        dummy.yo = snap.renderY(0.0F);
        dummy.zo = snap.renderZ(0.0F);
        dummy.setPos(snap.renderX(1.0F), snap.renderY(1.0F), snap.renderZ(1.0F));
        dummy.setYRot(snap.renderYaw(1.0F));
        dummy.setXRot(snap.renderPitch(1.0F));
        dummy.yRotO = snap.renderYaw(0.0F);
        dummy.xRotO = snap.renderPitch(0.0F);

        if (dummy instanceof VehicleEntity vehicle) {
            vehicle.setRoll(snap.renderRoll(1.0F));
            vehicle.setPrevRoll(snap.renderRoll(0.0F));
        }

        applyAnimationState(dummy, snap, partialTick);

        var renderer = mc.getEntityRenderDispatcher().getRenderer(dummy);
        if (renderer != null) {
            renderer.render(dummy, snap.renderYaw(partialTick), partialTick, ps, buffers,
                    snap.getCachedPackedLight(mc.level));
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // DummyEntity 描画（静的・アニメなし）
    // -------------------------------------------------------------------------

    /**
     * 静的LOD描画用。applyAnimationState() を呼ばないことでアニメーション計算コストをゼロにします。
     * ヨー方向のみ反映し、ピッチ・ロール・各可動部は初期値のまま描画します。
     */
    private static boolean renderStaticDummyEntity(
            VehicleGhostData snap,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers,
            float partialTick,
            Minecraft mc) {
        Entity dummy = getOrCreateDummy(snap, mc);
        if (dummy == null) return false;

        dummy.xo = snap.renderX(0.0F);
        dummy.yo = snap.renderY(0.0F);
        dummy.zo = snap.renderZ(0.0F);
        dummy.setPos(snap.renderX(1.0F), snap.renderY(1.0F), snap.renderZ(1.0F));
        dummy.setYRot(snap.renderYaw(1.0F));
        dummy.setXRot(0.0F);
        dummy.yRotO = snap.renderYaw(0.0F);
        dummy.xRotO = 0.0F;

        if (dummy instanceof VehicleEntity vehicle) {
            vehicle.setRoll(0.0F);
            vehicle.setPrevRoll(0.0F);
        }

        var renderer = mc.getEntityRenderDispatcher().getRenderer(dummy);
        if (renderer != null) {
            renderer.render(dummy, snap.renderYaw(partialTick), partialTick, ps, buffers,
                    snap.getCachedPackedLight(mc.level));
            return true;
        }
        return false;
    }

    private static Entity getOrCreateDummy(VehicleGhostData snap, Minecraft mc) {
        return dummyEntities.computeIfAbsent(snap.typeKey, k -> {
            ResourceLocation id = ResourceLocation.tryParse(k);
            if (id == null) return null;
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
            return type != null ? type.create(mc.level) : null;
        });
    }

    // -------------------------------------------------------------------------
    // リフレクション初期化
    // -------------------------------------------------------------------------

    private static boolean initReflection() {
        if (reflectionInitialized) return reflectionAvailable;
        reflectionInitialized = true;
        try {
            Class<?> loaderClass = Class.forName("com.example.sbwmeshloader.core.PolyMeshLoader");
            loadModelMethod = loaderClass.getMethod("loadModel", ResourceLocation.class);

            Class<?> modelClass = Class.forName("com.example.sbwmeshloader.core.PolyMeshModel");
            renderSplitMethod = modelClass.getMethod(
                    "renderWithTranslucentSplit",
                    PoseStack.class, MultiBufferSource.class, ResourceLocation.class, int.class);
            meshMapField = modelClass.getDeclaredField("meshMap");
            meshMapField.setAccessible(true);

            Class<?> bedrockModelClass = Class.forName(
                    "com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel");
            bindPoseField = bedrockModelClass.getDeclaredField("bindPose");
            bindPoseField.setAccessible(true);
            applyPoseMethod = bedrockModelClass.getMethod("applyPose", bindPoseField.getType());

            reflectionAvailable = true;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException e) {
            reflectionAvailable = false;
            LOGGER.debug("[SuperbHorizon] PolyMesh bridge is unavailable; using entity fallback renderer.");
        }
        return reflectionAvailable;
    }

    // -------------------------------------------------------------------------
    // 候補エントリ
    // -------------------------------------------------------------------------
    private static final class CandidateEntry {
        final VehicleGhostData snap;
        final double distSq;
        final LodLevel lod;
        final CachedStaticFrame cached; // STATIC_CACHEDのときのみ非null
        CandidateEntry(VehicleGhostData snap, double distSq, LodLevel lod, CachedStaticFrame cached) {
            this.snap = snap;
            this.distSq = distSq;
            this.lod = lod;
            this.cached = cached;
        }
    }

    private static void logRenderFailureOnce(String key, String message, Exception e) {
        if (loggedRenderFailures.add(key)) {
            LOGGER.warn("[SuperbHorizon] {}", message, e);
        }
    }

    private static void loadSnapshot(GhostNetwork.GhostSnapshot snapshot) {
        snapshots.put(snapshot.vehicleId, new VehicleGhostData(
                snapshot.entityId, snapshot.vehicleId, snapshot.typeKey,
                snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch, snapshot.roll, snapshot.animation));
    }

    // -------------------------------------------------------------------------
    // アニメーション状態適用（フルLOD専用）
    // -------------------------------------------------------------------------

    private static void applyAnimationState(Entity dummy, VehicleGhostData snap, float partialTick) {
        if (!(dummy instanceof VehicleEntity vehicle) || !snap.hasAnimationState()) return;

        vehicle.setAbsoluteSpeed(snap.animAbsoluteSpeed(partialTick));
        vehicle.setAbsoluteSpeedLerp(snap.animAbsoluteSpeed(partialTick));
        vehicle.setTargetSpeed(snap.animTargetSpeed(partialTick));
        vehicle.setPower(snap.animPower(partialTick));

        vehicle.setTurretYRot(snap.animTurretYaw(1.0F));
        vehicle.setTurretXRot(snap.animTurretPitch(1.0F));
        vehicle.setTurretYRotO(snap.animTurretYaw(0.0F));
        vehicle.setTurretXRotO(snap.animTurretPitch(0.0F));

        vehicle.setGunYRot(snap.animGunYaw(1.0F));
        vehicle.setGunXRot(snap.animGunPitch(1.0F));
        vehicle.setGunYRotO(snap.animGunYaw(0.0F));
        vehicle.setGunXRotO(snap.animGunPitch(0.0F));

        vehicle.setLeftWheelRot(snap.animLeftWheelRot(1.0F));
        vehicle.setRightWheelRot(snap.animRightWheelRot(1.0F));
        vehicle.setLeftWheelRotO(snap.animLeftWheelRot(0.0F));
        vehicle.setRightWheelRotO(snap.animRightWheelRot(0.0F));
        vehicle.setLeftTrack(snap.animLeftTrack(1.0F));
        vehicle.setRightTrack(snap.animRightTrack(1.0F));
        vehicle.setLeftTrackO(snap.animLeftTrack(0.0F));
        vehicle.setRightTrackO(snap.animRightTrack(0.0F));

        vehicle.setPropellerRot(snap.animPropellerRot(1.0F));
        vehicle.setPropellerRotO(snap.animPropellerRot(0.0F));
        vehicle.setSynchedPropellerRot(snap.animPropellerRot(1.0F));
        vehicle.setSynchedGearRot(snap.animGearRot(1.0F));
        vehicle.setGearRot(snap.animGearRot(1.0F));
        vehicle.setPlaneBreak(snap.animPlaneBreak(partialTick));

        vehicle.setCannonRecoilTime(snap.animCannonRecoilTime());
        vehicle.setCannonRecoilForce(snap.animCannonRecoilForce(partialTick));
        vehicle.setGearUp(snap.animation.gearUp());
        vehicle.setHoverMode(snap.animation.hoverMode());
        vehicle.setWreck(snap.animation.wreck());
        vehicle.setEngineStart(snap.animation.engineRunning());
        vehicle.setEngineStartOver(snap.animation.engineRunning());
    }
}
