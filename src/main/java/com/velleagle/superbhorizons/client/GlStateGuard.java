package com.velleagle.superbhorizons.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * Distant Horizons の描画イベントの中でゴーストを描くときに、
 * OpenGL の状態を「借りて、そのまま返す」ための仕組みです。
 *
 * ■ なぜ必要なのか (他の Mod との互換性)
 * DH の「画面へコピーする直前」のイベントは、Superb Horizons だけでなく
 * Simple Clouds など「DH の LOD に合わせて遠くの物を描く Mod」も使っています。
 * 同じイベントの中で順番に処理が呼ばれるため、
 *   ・前の Mod や DH が直接 OpenGL を操作した状態のままだと、Minecraft の記録と
 *     実際の状態が食い違い、ゴーストの描画が壊れる (半透明になる・別のテクスチャになる等)
 *   ・ゴーストを描いた後の状態をそのまま残すと、後の Mod や DH の処理が壊れる
 * という問題が起きます。
 *
 * そこで、
 *  1. 描く前に今の OpenGL の状態を丸ごと控える
 *  2. Minecraft の記録と実際の状態を一致させてから描く
 *  3. 描き終わったら、控えておいた状態へ正確に戻す
 * という手順で、他の Mod から見て「何も変わっていない」ようにします。
 */
final class GlStateGuard {

    /** 控えるテクスチャユニットの数 (Minecraft のエンティティ描画は 0〜2 番、余裕を見て 8 番まで)。 */
    private static final int UNITS = 8;

    /** Minecraft の記録をリセットするための、中身の無い小さなテクスチャ。 */
    private static int dummyTexture = 0;

    private int drawFbo, readFbo, program, vertexArray, arrayBuffer, activeTexture;
    private final int[] textures = new int[UNITS];
    private final int[] samplers = new int[UNITS];
    private boolean blend, depthTest, cull, scissor, stencil, polygonOffset;
    private int blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha, blendEqRgb, blendEqAlpha;
    private float polygonOffsetFactor, polygonOffsetUnits;
    private int depthFunc;
    private boolean depthMask;
    private final boolean[] colorMask = new boolean[4];
    private final int[] viewport = new int[4];

    private GlStateGuard() {}

    /** 描く直前に呼びます。今の状態を控え、Minecraft の記録を実際の状態とそろえます。 */
    static GlStateGuard begin() {
        GlStateGuard saved = capture();
        try {
            syncMinecraftState();
        } catch (Throwable ignored) {
            // そろえられなくても描画自体は続けます
        }
        return saved;
    }

    /** 描き終わったら必ず呼びます (finally の中で)。控えておいた状態へ戻します。 */
    void end() {
        try {
            // Minecraft のテクスチャの記録を「何も結び付けていない」にしてから実際の状態を戻します。
            // こうしておくと、後で Minecraft がテクスチャを使うときに必ず結び付け直されます。
            resetTextureCache();
        } catch (Throwable ignored) {
        }
        restore();
        try {
            // Minecraft の「今どの頂点バッファを使っているか」の記録も捨てます
            // (戻した後の状態は DH や他の Mod のものなので)
            BufferUploader.invalidate();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------

    private static GlStateGuard capture() {
        GlStateGuard s = new GlStateGuard();
        s.drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        s.readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        s.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        s.vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        s.arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        s.activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for (int i = 0; i < UNITS; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            s.textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            s.samplers[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }
        GL13.glActiveTexture(s.activeTexture);

        s.blend = GL11.glIsEnabled(GL11.GL_BLEND);
        s.depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        s.cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        s.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        s.stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        s.polygonOffset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
        s.blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        s.blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        s.blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        s.blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        s.blendEqRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        s.blendEqAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        s.polygonOffsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
        s.polygonOffsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        s.depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        s.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        java.nio.ByteBuffer mask = org.lwjgl.BufferUtils.createByteBuffer(16);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
        for (int i = 0; i < 4; i++) s.colorMask[i] = mask.get(i) != 0;
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, s.viewport);
        return s;
    }

    private void restore() {
        try {
            for (int i = 0; i < UNITS; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
                GL33.glBindSampler(i, samplers[i]);
            }
            GL13.glActiveTexture(activeTexture);

            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);

            setEnabled(GL11.GL_BLEND, blend);
            setEnabled(GL11.GL_DEPTH_TEST, depthTest);
            setEnabled(GL11.GL_CULL_FACE, cull);
            setEnabled(GL11.GL_SCISSOR_TEST, scissor);
            setEnabled(GL11.GL_STENCIL_TEST, stencil);
            setEnabled(GL11.GL_POLYGON_OFFSET_FILL, polygonOffset);
            GL14.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
            GL20.glBlendEquationSeparate(blendEqRgb, blendEqAlpha);
            GL11.glPolygonOffset(polygonOffsetFactor, polygonOffsetUnits);
            GL11.glDepthFunc(depthFunc);
            GL11.glDepthMask(depthMask);
            GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Minecraft は「今の OpenGL の状態」を自分で記録していて、記録と同じ値を設定しようとすると
     * OpenGL への命令を省略します。DH や他の Mod が OpenGL を直接操作していると記録が古くなり、
     * 例えば「ブレンドを切る」命令が省略されてゴーストが半透明になる、などが起きます。
     * 一度反対の値を設定してから目的の値を設定し、記録と実際の状態を確実に一致させます。
     */
    private static void syncMinecraftState() {
        BufferUploader.invalidate();

        RenderSystem.enableBlend();
        RenderSystem.disableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        RenderSystem.defaultBlendFunc();
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);   // Minecraft はこの値を記録していないので直接設定します

        RenderSystem.disableDepthTest();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.depthMask(true);

        RenderSystem.disableCull();
        RenderSystem.enableCull();
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enablePolygonOffset();
        RenderSystem.disablePolygonOffset();
        GlStateManager._enableScissorTest();
        GlStateManager._disableScissorTest();
        GL11.glDisable(GL11.GL_STENCIL_TEST);

        // 他の Mod が結び付けた「サンプラー (読み取り設定)」はテクスチャの設定より優先されるため外します
        for (int i = 0; i < UNITS; i++) GL33.glBindSampler(i, 0);
        GL20.glUseProgram(0);

        resetTextureCache();
    }

    /** 各テクスチャユニットについて、Minecraft の記録と実際の状態を「何も結び付けていない」にそろえます。 */
    private static void resetTextureCache() {
        int dummy = dummyTexture();
        // 「今どのユニットを操作しているか」の記録をそろえます (2 回とも違う値なので必ず命令が届きます)
        GlStateManager._activeTexture(GL13.GL_TEXTURE1);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        for (int i = 0; i < UNITS; i++) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
            GlStateManager._bindTexture(dummy);   // 記録と違う値なので必ず命令が届きます
            GlStateManager._bindTexture(0);
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    private static int dummyTexture() {
        if (dummyTexture == 0) {
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            dummyTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, dummyTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
        return dummyTexture;
    }

    private static void setEnabled(int cap, boolean on) {
        if (on) GL11.glEnable(cap);
        else GL11.glDisable(cap);
    }
}
