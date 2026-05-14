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
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
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

    public static void onLoad(GhostNetwork.LoadPacket pkt) {
        loadSnapshot(pkt);
    }

    public static void onBatchLoad(GhostNetwork.BatchLoadPacket pkt) {
        if (pkt.clearFirst) {
            clearSnapshots();
        }
        for (GhostNetwork.GhostSnapshot snapshot : pkt.snapshots) {
            loadSnapshot(snapshot);
        }
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
    }

    @SubscribeEvent
    public static void onClientLogoff(ClientPlayerNetworkEvent.LoggingOut event) {
        clearSnapshots();
        polyMeshCache.clear();
        loggedRenderFailures.clear();
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
        Vec3 camPos = mc.player.getEyePosition();
        PoseStack ps = event.getPoseStack();
        float partialTick = event.getPartialTick();

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        MultiBufferSource.BufferSource isolatedBuffers = MultiBufferSource.immediate(builder);

        Matrix4f oldProj = RenderSystem.getProjectionMatrix();
        float oldFogStart = RenderSystem.getShaderFogStart();
        float oldFogEnd = RenderSystem.getShaderFogEnd();

        try {
            float fov = (float) (2.0 * Math.atan(1.0 / oldProj.m11()));
            float aspect = oldProj.m11() / oldProj.m00();
            Matrix4f hugeProj = new Matrix4f().setPerspective(fov, aspect, 50.0F, FAR_PLANE);
            RenderSystem.setProjectionMatrix(hugeProj, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setShaderFogStart(FAR_PLANE);
            RenderSystem.setShaderFogEnd(FAR_PLANE);

            for (VehicleGhostData snap : snapshots.values()) {
                if (snap.isStale()) {
                    continue;
                }

                Entity realEntity = mc.level.getEntity(snap.entityId);
                if (realEntity != null && !realEntity.isRemoved()) {
                    continue;
                }

                double ghostSwitchDistance = SuperbHorizonConfig.GHOST_SWITCH_DISTANCE.get();
                if (mc.player.distanceToSqr(snap.x, snap.y, snap.z) < ghostSwitchDistance * ghostSwitchDistance) {
                    continue;
                }

                renderSnapshot(snap, ps, isolatedBuffers, partialTick, camPos, mc);
            }
        } finally {
            isolatedBuffers.endBatch();
            RenderSystem.setProjectionMatrix(oldProj, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setShaderFogStart(oldFogStart);
            RenderSystem.setShaderFogEnd(oldFogEnd);
        }
    }

    private static void renderSnapshot(
            VehicleGhostData snap,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers,
            float partialTick,
            Vec3 camPos,
            Minecraft mc) {
        ps.pushPose();
        try {
            ps.translate(
                    snap.renderX(partialTick) - camPos.x,
                    snap.renderY(partialTick) - camPos.y,
                    snap.renderZ(partialTick) - camPos.z);

            if (snap.hasAnimationState() && SuperbHorizonConfig.PREFER_ANIMATED_ENTITY_FALLBACK.get()) {
                if (!renderDummyEntity(snap, ps, buffers, partialTick, mc)) {
                    tryRenderPolyMesh(snap, ps, buffers, partialTick);
                }
            } else if (!tryRenderPolyMesh(snap, ps, buffers, partialTick)) {
                renderDummyEntity(snap, ps, buffers, partialTick, mc);
            }
        } catch (Exception e) {
            logRenderFailureOnce("snapshot:" + snap.typeKey, "Failed to render ghost vehicle " + snap.typeKey, e);
        } finally {
            ps.popPose();
        }
    }

    private static boolean tryRenderPolyMesh(
            VehicleGhostData snap,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers,
            float partialTick) {
        if (!initReflection()) return false;
        if (!SuperbHorizonConfig.ENABLE_POLYMESH.get()) return false;

        Object mesh;
        if (polyMeshCache.containsKey(snap.typeKey)) {
            mesh = polyMeshCache.get(snap.typeKey);
            if (mesh == null) return false;
        } else {
            mesh = loadPolyMesh(snap.typeKey);
            if (mesh != null && !hasPolyMesh(mesh, snap.typeKey)) {
                mesh = null;
            }

            polyMeshCache.put(snap.typeKey, mesh);
            if (mesh == null) return false;
        }

        return doRenderMesh(mesh, snap, ps, buffers, partialTick);
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
            float partialTick) {
        ps.pushPose();
        try {
            Object bindPose = bindPoseField.get(mesh);
            applyPoseMethod.invoke(mesh, bindPose);

            ps.mulPose(Axis.YP.rotationDegrees(180.0F - snap.renderYaw(partialTick)));
            ps.mulPose(Axis.XP.rotationDegrees(-snap.renderPitch(partialTick)));
            ps.mulPose(Axis.ZP.rotationDegrees(-snap.renderRoll(partialTick)));

            renderSplitMethod.invoke(mesh, ps, buffers, snap.textureLocation(), samplePackedLight(snap));
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

            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(type.getNamespace(), "custom_geo/" + type.getPath() + ".geo.json");
            return loadModelMethod.invoke(null, loc);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean renderDummyEntity(
            VehicleGhostData snap,
            PoseStack ps,
            MultiBufferSource.BufferSource buffers,
            float partialTick,
            Minecraft mc) {
        Entity dummy = dummyEntities.computeIfAbsent(snap.typeKey, k -> {
            ResourceLocation id = ResourceLocation.tryParse(k);
            if (id == null) return null;

            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
            return type != null ? type.create(mc.level) : null;
        });
        if (dummy == null) return false;

        dummy.setPos(snap.renderX(partialTick), snap.renderY(partialTick), snap.renderZ(partialTick));
        dummy.setYRot(snap.renderYaw(partialTick));
        dummy.setXRot(snap.renderPitch(partialTick));
        dummy.yRotO = snap.prevYaw;
        dummy.xRotO = snap.prevPitch;
        applyAnimationState(dummy, snap, partialTick);

        var renderer = mc.getEntityRenderDispatcher().getRenderer(dummy);
        if (renderer != null) {
            renderer.render(dummy, snap.renderYaw(partialTick), partialTick, ps, buffers, samplePackedLight(snap));
            return true;
        }
        return false;
    }

    private static int samplePackedLight(VehicleGhostData snap) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return LightTexture.FULL_BRIGHT;

        BlockPos pos = BlockPos.containing(snap.x, snap.y, snap.z);
        if (mc.level.isLoaded(pos)) {
            int block = mc.level.getBrightness(LightLayer.BLOCK, pos);
            int sky = mc.level.getBrightness(LightLayer.SKY, pos);
            return LightTexture.pack(block, sky);
        }

        int sky = Math.max(0, 15 - mc.level.getSkyDarken());
        return LightTexture.pack(0, sky);
    }

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

    private static void applyAnimationState(Entity dummy, VehicleGhostData snap, float partialTick) {
        if (!(dummy instanceof VehicleEntity vehicle) || !snap.hasAnimationState()) return;

        vehicle.setAbsoluteSpeed(snap.animAbsoluteSpeed(partialTick));
        vehicle.setAbsoluteSpeedLerp(snap.animAbsoluteSpeed(partialTick));
        vehicle.setTargetSpeed(snap.animTargetSpeed(partialTick));
        vehicle.setPower(snap.animPower(partialTick));

        vehicle.setTurretYRot(snap.animTurretYaw(partialTick));
        vehicle.setTurretXRot(snap.animTurretPitch(partialTick));
        vehicle.setTurretYRotO(snap.prevAnimation.turretYaw);
        vehicle.setTurretXRotO(snap.prevAnimation.turretPitch);

        vehicle.setGunYRot(snap.animGunYaw(partialTick));
        vehicle.setGunXRot(snap.animGunPitch(partialTick));
        vehicle.setGunYRotO(snap.prevAnimation.gunYaw);
        vehicle.setGunXRotO(snap.prevAnimation.gunPitch);

        vehicle.setLeftWheelRot(snap.animLeftWheelRot(partialTick));
        vehicle.setRightWheelRot(snap.animRightWheelRot(partialTick));
        vehicle.setLeftWheelRotO(snap.prevAnimation.leftWheelRot);
        vehicle.setRightWheelRotO(snap.prevAnimation.rightWheelRot);
        vehicle.setLeftTrack(snap.animLeftTrack(partialTick));
        vehicle.setRightTrack(snap.animRightTrack(partialTick));
        vehicle.setLeftTrackO(snap.prevAnimation.leftTrack);
        vehicle.setRightTrackO(snap.prevAnimation.rightTrack);

        vehicle.setPropellerRot(snap.animPropellerRot(partialTick));
        vehicle.setPropellerRotO(snap.prevAnimation.propellerRot);
        vehicle.setSynchedPropellerRot(snap.animPropellerRot(partialTick));
        vehicle.setSynchedGearRot(snap.animGearRot(partialTick));
        vehicle.setGearRot(snap.animGearRot(partialTick));
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
