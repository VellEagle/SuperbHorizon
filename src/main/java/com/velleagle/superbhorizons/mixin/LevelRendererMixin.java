package com.velleagle.superbhorizons.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.velleagle.superbhorizons.client.RealRenderTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft が本物のエンティティを描く処理 (LevelRenderer.renderEntity) の入口に割り込み、
 * 「本物の車両が実際に描かれた」ことを記録します (RealRenderTracker)。
 * 描画の内容には一切手を加えません (記録するだけです)。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "renderEntity", at = @At("HEAD"))
    private void superbhorizons$recordRealRender(Entity entity, double camX, double camY, double camZ,
                                                 float partialTick, PoseStack poseStack,
                                                 MultiBufferSource bufferSource, CallbackInfo ci) {
        try {
            RealRenderTracker.onRenderEntity(entity, partialTick);
        } catch (Throwable ignored) {
            // 記録に失敗しても、本物の描画は止めません
        }
    }
}
