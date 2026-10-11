package com.velleagle.superbhorizons.client;

import com.velleagle.superbhorizons.Config;
import com.velleagle.superbhorizons.SuperbHorizons;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.lang.reflect.Method;

/**
 * シェーダー (Oculus / Iris) 使用時に、ゴーストを Distant Horizons の LOD 地形の裏へ隠します。
 *
 * ■ なぜシェーダー使用時だけ別の処理が要るのか
 * シェーダー無しのときは、DH 専用の画面 (LOD 地形の色と奥行きが入っている) に
 * ゴーストを描くので、前後関係は GPU が自動で処理します。
 *
 * シェーダー使用時は、Oculus が描画先を差し替えます。
 *   LOD 地形の奥行き → DH 専用の深度テクスチャ
 *   ゴースト (エンティティ) の奥行き → 通常の深度バッファ
 * シェーダーパックは最後に「通常の深度バッファに何か描かれていればそちらを優先」して合成します。
 * そのため、ゴーストは常に LOD 地形より手前扱いになり、貫通して見えます。
 *
 * ■ 対策の流れ (毎フレーム)
 *  1. 通常の深度バッファの中身を控えておく
 *  2. LOD 地形の奥行きを、通常の深度バッファへ書き写す (手前にあるものだけ)
 *  3. ゴーストを描く → LOD 地形の裏にある部分は、GPU の深度テストで自然に描かれない
 *  4. ゴーストが描かれなかった画素だけ、通常の深度バッファを 1. の状態に戻す
 *     (戻さないと、シェーダーパックがその画素を「LOD ではない」と誤解して見た目が崩れます)
 *
 * ■ 奥行きの基準をそろえる
 * Oculus は LOD 地形を「Minecraft の視野角 + DH の近距離/遠距離」の遠近設定で描きます。
 * ゴーストも全く同じ設定で描くことで、2. で書き写した値と直接比べられるようにします。
 *
 * OpenGL の状態はすべて元の値を控えてから戻します (Minecraft や Oculus の記録と食い違わないように)。
 */
public final class ShaderDhOcclusion {

    private ShaderDhOcclusion() {}

    // --- シェーダーパックの使用状況 (Oculus の公開 API を名前で呼びます) ---
    private static boolean irisLookedUp = false;
    private static Object irisApi;
    private static Method isShaderPackInUse;

    // --- Oculus が記録しているカメラの行列 ---
    private static boolean capturedLookedUp = false;
    private static Object capturedState;
    private static Method getGbufferModelView;
    private static Method getGbufferProjection;

    // --- GPU 側の資源 ---
    private static boolean glReady = false;
    private static boolean glFailed = false;
    private static int prepassProgram, restoreProgram, vao;
    private static int targetFbo, origFbo, curFbo;
    private static int origTex, curTex;
    private static int texWidth = -1, texHeight = -1, texFormat = -1;
    private static int attachedTarget = -1;

    // --- このフレームの状態 ---
    private static boolean active = false;
    private static boolean activeReversed = false;
    private static int activeDhTex = -1;
    private static int activeTargetTex = -1;

    private static boolean loggedStart = false;
    private static boolean loggedFailure = false;

    // ------------------------------------------------------------------
    // シェーダーパックの判定と行列
    // ------------------------------------------------------------------

    /** シェーダーパックが有効か。Oculus / Iris が無ければ false。 */
    public static boolean isShaderPackInUse() {
        if (!irisLookedUp) {
            irisLookedUp = true;
            try {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = c.getMethod("getInstance").invoke(null);
                isShaderPackInUse = c.getMethod("isShaderPackInUse");
            } catch (Throwable ignored) {
                irisApi = null;
            }
        }
        if (irisApi == null) return false;
        try {
            return (Boolean) isShaderPackInUse.invoke(irisApi);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Oculus が LOD 地形の描画に使っている行列 (投影 × カメラ) を作ります。
     * Oculus の LodRendererEvents と同じ計算式です。取得できなければ null。
     */
    public static Matrix4f lodMatrix(float near, float far) {
        if (!capturedLookedUp) {
            capturedLookedUp = true;
            try {
                Class<?> c = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
                capturedState = c.getField("INSTANCE").get(null);
                getGbufferModelView = c.getMethod("getGbufferModelView");
                getGbufferProjection = c.getMethod("getGbufferProjection");
            } catch (Throwable ignored) {
                capturedState = null;
            }
        }
        if (capturedState == null || near <= 0.0F || far <= near) return null;
        try {
            Matrix4fc projection = (Matrix4fc) getGbufferProjection.invoke(capturedState);
            Matrix4fc modelView = (Matrix4fc) getGbufferModelView.invoke(capturedState);
            if (projection == null || modelView == null) return null;
            return new Matrix4f()
                    .setPerspective(projection.perspectiveFov(),
                            projection.m11() / projection.m00(), near, far)
                    .mul(modelView);
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // ゴースト描画の前後に呼ぶ処理
    // ------------------------------------------------------------------

    /**
     * ゴーストを描く直前に呼びます (手順 1 と 2)。
     *
     * @param reversed DH の深度が逆向き (手前ほど大きい) か
     */
    public static void begin(boolean reversed) {
        active = false;
        if (!Config.DH_SHADER_OCCLUSION.get() || glFailed) return;

        int dhTex = DhDepthTexture.fetchDepthTextureId();
        int targetTex = Minecraft.getInstance().getMainRenderTarget().getDepthTextureId();
        if (dhTex <= 0 || targetTex <= 0 || dhTex == targetTex) return;

        SavedState saved = SavedState.capture();
        try {
            // テクスチャの結び付けはユニット 0〜2 だけで行います (復元対象と一致させるため)
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            if (!ensureGl()) return;
            if (!prepareTextures(targetTex)) return;

            int[] dhSize = textureSize(dhTex);
            if (dhSize[0] <= 0 || dhSize[1] <= 0) return;

            // 1. 通常の深度バッファを控える
            // (最初の数回は、複製が本当に成功したかを OpenGL のエラーで確かめます。
            //  形式が合わずに失敗した場合、このまま続けると画面が壊れるため止めます)
            boolean verify = !loggedStart;
            if (verify) drainGlErrors();
            blitDepth(targetFbo, origFbo);
            if (verify) {
                int err = GL11.glGetError();
                if (err != GL11.GL_NO_ERROR) {
                    fail(new IllegalStateException("depth copy failed: GL error 0x" + Integer.toHexString(err)
                            + " depthFormat=0x" + Integer.toHexString(texFormat)));
                    return;
                }
            }

            // 2. LOD 地形の奥行きを書き写す (今ある値より手前のものだけ)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFbo);
            GL11.glViewport(0, 0, texWidth, texHeight);
            setupFullscreenState();
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(reversed ? GL11.GL_GREATER : GL11.GL_LESS);
            GL11.glDepthMask(true);

            GL20.glUseProgram(prepassProgram);
            bindDepthSampler(0, dhTex);
            GL20.glUniform1i(GL20.glGetUniformLocation(prepassProgram, "dhDepth"), 0);
            GL20.glUniform2f(GL20.glGetUniformLocation(prepassProgram, "scale"),
                    dhSize[0] / (float) texWidth, dhSize[1] / (float) texHeight);
            GL20.glUniform1f(GL20.glGetUniformLocation(prepassProgram, "emptyDepth"),
                    reversed ? 0.0F : 1.0F);
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

            active = true;
            activeReversed = reversed;
            activeDhTex = dhTex;
            activeTargetTex = targetTex;

            if (!loggedStart) {
                loggedStart = true;
                SuperbHorizons.LOGGER.info(
                        "[Superb Horizons] shader mode: LOD occlusion enabled (dhDepth={} target={} {}x{} format=0x{} reversed={})",
                        dhTex, targetTex, texWidth, texHeight, Integer.toHexString(texFormat), reversed);
            }
        } catch (Throwable e) {
            fail(e);
        } finally {
            saved.restore();
        }
    }

    /** ゴーストを描き終えた直後に呼びます (手順 4)。begin が失敗していれば何もしません。 */
    public static void end() {
        if (!active) return;
        active = false;

        SavedState saved = SavedState.capture();
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            // ゴーストを描いた後の深度バッファを控える (描いている最中の値は読めないため)
            blitDepth(targetFbo, curFbo);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFbo);
            GL11.glViewport(0, 0, texWidth, texHeight);
            setupFullscreenState();
            // 比較はシェーダー側で行うので、深度テストは使わずに書き込みます
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_ALWAYS);
            GL11.glDepthMask(true);

            int[] dhSize = textureSize(activeDhTex);

            GL20.glUseProgram(restoreProgram);
            bindDepthSampler(0, activeDhTex);
            bindDepthSampler(1, origTex);
            bindDepthSampler(2, curTex);
            GL20.glUniform1i(GL20.glGetUniformLocation(restoreProgram, "dhDepth"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(restoreProgram, "origDepth"), 1);
            GL20.glUniform1i(GL20.glGetUniformLocation(restoreProgram, "curDepth"), 2);
            GL20.glUniform2f(GL20.glGetUniformLocation(restoreProgram, "scale"),
                    dhSize[0] / (float) texWidth, dhSize[1] / (float) texHeight);
            GL20.glUniform1f(GL20.glGetUniformLocation(restoreProgram, "emptyDepth"),
                    activeReversed ? 0.0F : 1.0F);
            GL20.glUniform1f(GL20.glGetUniformLocation(restoreProgram, "reversed"),
                    activeReversed ? 1.0F : 0.0F);
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } catch (Throwable e) {
            fail(e);
        } finally {
            saved.restore();
        }
    }

    /** ログアウト時などに GPU の資源を解放します。 */
    public static void reset() {
        active = false;
        loggedStart = false;
        if (!glReady) return;
        try {
            GL30.glDeleteFramebuffers(targetFbo);
            GL30.glDeleteFramebuffers(origFbo);
            GL30.glDeleteFramebuffers(curFbo);
            if (origTex > 0) GL11.glDeleteTextures(origTex);
            if (curTex > 0) GL11.glDeleteTextures(curTex);
            GL20.glDeleteProgram(prepassProgram);
            GL20.glDeleteProgram(restoreProgram);
            GL30.glDeleteVertexArrays(vao);
        } catch (Throwable ignored) {
        }
        glReady = false;
        origTex = curTex = 0;
        texWidth = texHeight = texFormat = -1;
        attachedTarget = -1;
    }

    // ------------------------------------------------------------------
    // GPU 資源の準備
    // ------------------------------------------------------------------

    private static final String VERTEX =
            "#version 150\n"
          + "void main() {\n"
          + "    vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);\n"
          + "    gl_Position = vec4(p, 0.0, 1.0);\n"
          + "}\n";

    /** 手順 2: LOD 地形の奥行きを書き出す。LOD の無い画素は書かない。 */
    private static final String PREPASS_FRAGMENT =
            "#version 150\n"
          + "uniform sampler2D dhDepth;\n"
          + "uniform vec2 scale;\n"
          + "uniform float emptyDepth;\n"
          + "out vec4 fragColor;\n"
          + "void main() {\n"
          + "    float d = texelFetch(dhDepth, ivec2(gl_FragCoord.xy * scale), 0).r;\n"
          + "    if (d == emptyDepth) discard;\n"
          + "    gl_FragDepth = d;\n"
          + "    fragColor = vec4(0.0);\n"
          + "}\n";

    /**
     * 手順 4: ゴーストが描かれなかった画素だけ、元の奥行きへ戻す。
     * 手順 2 の後の値は「元の値と LOD の値の手前側」。
     * それより手前の値が入っていれば、ゴーストが描かれた画素です。
     */
    private static final String RESTORE_FRAGMENT =
            "#version 150\n"
          + "uniform sampler2D dhDepth;\n"
          + "uniform sampler2D origDepth;\n"
          + "uniform sampler2D curDepth;\n"
          + "uniform vec2 scale;\n"
          + "uniform float emptyDepth;\n"
          + "uniform float reversed;\n"
          + "out vec4 fragColor;\n"
          + "void main() {\n"
          + "    ivec2 p = ivec2(gl_FragCoord.xy);\n"
          + "    float d = texelFetch(dhDepth, ivec2(gl_FragCoord.xy * scale), 0).r;\n"
          + "    if (d == emptyDepth) discard;\n"                 // LOD が無い画素は書き写していない
          + "    float orig = texelFetch(origDepth, p, 0).r;\n"
          + "    float cur = texelFetch(curDepth, p, 0).r;\n"
          + "    const float EPS = 0.000002;\n"
          + "    bool ghostDrawn;\n"
          + "    if (reversed > 0.5) {\n"
          + "        ghostDrawn = cur > max(orig, d) + EPS;\n"
          + "    } else {\n"
          + "        ghostDrawn = cur < min(orig, d) - EPS;\n"
          + "    }\n"
          + "    if (ghostDrawn) discard;\n"
          + "    gl_FragDepth = orig;\n"
          + "    fragColor = vec4(0.0);\n"
          + "}\n";

    private static boolean ensureGl() {
        if (glReady) return true;
        if (glFailed) return false;
        try {
            prepassProgram = link(VERTEX, PREPASS_FRAGMENT);
            restoreProgram = link(VERTEX, RESTORE_FRAGMENT);
            vao = GL30.glGenVertexArrays();
            targetFbo = GL30.glGenFramebuffers();
            origFbo = GL30.glGenFramebuffers();
            curFbo = GL30.glGenFramebuffers();
            glReady = true;
            return true;
        } catch (Throwable e) {
            fail(e);
            return false;
        }
    }

    /** 控え用のテクスチャを、通常の深度バッファと同じ大きさ・形式で用意します。 */
    private static boolean prepareTextures(int targetTex) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, targetTex);
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        if (w <= 0 || h <= 0) return false;

        boolean stencil = format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8;
        int attachment = GL30.GL_DEPTH_ATTACHMENT;

        if (w != texWidth || h != texHeight || format != texFormat) {
            if (origTex > 0) GL11.glDeleteTextures(origTex);
            if (curTex > 0) GL11.glDeleteTextures(curTex);
            origTex = createDepthTexture(w, h, format, stencil);
            curTex = createDepthTexture(w, h, format, stencil);
            attachDepth(origFbo, origTex, attachment);
            attachDepth(curFbo, curTex, attachment);
            texWidth = w;
            texHeight = h;
            texFormat = format;
            attachedTarget = -1;
        }
        if (attachedTarget != targetTex) {
            attachDepth(targetFbo, targetTex, attachment);
            attachedTarget = targetTex;
        }
        return checkComplete(targetFbo) && checkComplete(origFbo) && checkComplete(curFbo);
    }

    private static int createDepthTexture(int w, int h, int format, boolean stencil) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        int dataFormat = stencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT;
        int dataType = format == GL30.GL_DEPTH32F_STENCIL8 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV
                : stencil ? GL30.GL_UNSIGNED_INT_24_8 : GL11.GL_FLOAT;
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, w, h, 0, dataFormat, dataType,
                (java.nio.ByteBuffer) null);
        return tex;
    }

    private static void attachDepth(int fbo, int tex, int attachment) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, tex, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
    }

    private static boolean checkComplete(int fbo) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            fail(new IllegalStateException("framebuffer incomplete: 0x" + Integer.toHexString(status)
                    + " depthFormat=0x" + Integer.toHexString(texFormat)));
            return false;
        }
        return true;
    }

    private static void blitDepth(int from, int to) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL30.glBlitFramebuffer(0, 0, texWidth, texHeight, 0, 0, texWidth, texHeight,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
    }

    /** 深度テクスチャを比較モード無しで読めるように結び付けます (元の設定は SavedState が戻します)。 */
    private static void bindDepthSampler(int unit, int tex) {
        // Oculus がサンプラー (読み取り設定) を結び付けていると、テクスチャ側の設定より優先されるため外します
        GL33.glBindSampler(unit, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        SavedState.rememberCompareMode(tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
    }

    private static void setupFullscreenState() {
        GL11.glColorMask(false, false, false, false);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
    }

    /** 溜まっている OpenGL のエラーを読み捨てます (直後の確認を正確にするため)。 */
    private static void drainGlErrors() {
        for (int i = 0; i < 32; i++) {
            if (GL11.glGetError() == GL11.GL_NO_ERROR) return;
        }
    }

    private static int[] textureSize(int tex) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        return new int[] {
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH),
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
        };
    }

    private static int link(String vertex, String fragment) {
        int vs = compile(GL20.GL_VERTEX_SHADER, vertex);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, fragment);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL30.glBindFragDataLocation(program, 0, "fragColor");
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new IllegalStateException("link failed: " + log);
        }
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("compile failed: " + log);
        }
        return shader;
    }

    private static void fail(Throwable e) {
        active = false;
        glFailed = true;
        if (!loggedFailure) {
            loggedFailure = true;
            SuperbHorizons.LOGGER.warn(
                    "[Superb Horizons] shader mode: LOD occlusion disabled because of an error", e);
        }
    }

    // ------------------------------------------------------------------
    // OpenGL の状態の控えと復元
    // ------------------------------------------------------------------

    /**
     * 触る前の OpenGL の状態を丸ごと控え、終わったら同じ値へ戻します。
     * Minecraft や Oculus は「今の状態」を記録していて、記録と実際が食い違うと
     * 描画が壊れるため、ここで使ったものはすべて元通りにします。
     */
    private static final class SavedState {
        private static final int UNITS = 3;
        private static final int[] COMPARE_TEX = new int[UNITS];
        private static final int[] COMPARE_MODE = new int[UNITS];
        private static int compareCount = 0;

        int drawFbo, readFbo, program, vertexArray, activeTexture;
        final int[] textures = new int[UNITS];
        final int[] samplers = new int[UNITS];
        boolean depthTest, blend, cull, scissor, stencil;
        int depthFunc;
        boolean depthMask;
        final boolean[] colorMask = new boolean[4];
        final int[] viewport = new int[4];

        static SavedState capture() {
            SavedState s = new SavedState();
            compareCount = 0;
            s.drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            s.readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            s.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            s.vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            s.activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            for (int i = 0; i < UNITS; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                s.textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                s.samplers[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            }
            GL13.glActiveTexture(s.activeTexture);
            s.depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            s.blend = GL11.glIsEnabled(GL11.GL_BLEND);
            s.cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            s.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            s.stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            s.depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            s.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            java.nio.ByteBuffer mask = org.lwjgl.BufferUtils.createByteBuffer(16);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) s.colorMask[i] = mask.get(i) != 0;
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, s.viewport);
            return s;
        }

        /** 比較モードを一時的に変えるテクスチャの元の設定を控えます (テクスチャは結び付け済みの前提)。 */
        static void rememberCompareMode(int tex) {
            for (int i = 0; i < compareCount; i++) {
                if (COMPARE_TEX[i] == tex) return;
            }
            if (compareCount >= UNITS) return;
            COMPARE_TEX[compareCount] = tex;
            COMPARE_MODE[compareCount] = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE);
            compareCount++;
        }

        void restore() {
            try {
                for (int i = 0; i < compareCount; i++) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, COMPARE_TEX[i]);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, COMPARE_MODE[i]);
                }
                compareCount = 0;

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

                setEnabled(GL11.GL_DEPTH_TEST, depthTest);
                setEnabled(GL11.GL_BLEND, blend);
                setEnabled(GL11.GL_CULL_FACE, cull);
                setEnabled(GL11.GL_SCISSOR_TEST, scissor);
                setEnabled(GL11.GL_STENCIL_TEST, stencil);
                GL11.glDepthFunc(depthFunc);
                GL11.glDepthMask(depthMask);
                GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
                GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            } catch (Throwable ignored) {
            }
        }

        private static void setEnabled(int cap, boolean on) {
            if (on) GL11.glEnable(cap);
            else GL11.glDisable(cap);
        }
    }
}
