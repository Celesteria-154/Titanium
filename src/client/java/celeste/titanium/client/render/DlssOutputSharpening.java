package celeste.titanium.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 共有NGX出力の返却コピーに、任意の空間シャープ化を統合する。 */
final class DlssOutputSharpening implements AutoCloseable {
    private static final int[] DISABLED_CAPS = {GL33C.GL_DEPTH_TEST, GL33C.GL_STENCIL_TEST,
            GL33C.GL_BLEND, GL33C.GL_CULL_FACE, GL33C.GL_SCISSOR_TEST,
            GL33C.GL_FRAMEBUFFER_SRGB, GL33C.GL_RASTERIZER_DISCARD};
    private int program;
    private int vertexArray;
    private int sampler;
    private int sharpnessLocation;

    /**
     * 共有出力の返却先へシャープ化を伴う全画面描画を行い、変更したGL状態を復元する。
     *
     * 描画スレッドから呼び出す。
     *
     * @param source 読み取り元のGLテクスチャ識別子
     * @param width 対象領域の幅。ピクセル単位
     * @param height 対象領域の高さ。ピクセル単位
     * @param strength シャープ化の強度。0.0〜1.0
     */
    void draw(int source, int width, int height, double strength) {
        RenderSystem.assertOnRenderThread();
        ensureResources();
        // Raw GL calls preserve Blaze3D's cached state because each mutation is scoped.
        int previousProgram = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
        int previousVao = GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING);
        int previousActive = GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE);
        boolean[] enabled = new boolean[DISABLED_CAPS.length];
        for (int i = 0; i < enabled.length; i++) enabled[i] = GL33C.glIsEnabled(DISABLED_CAPS[i]);
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        int previousTexture = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
        int previousSampler = GL33C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var viewport = stack.mallocInt(4);
            var colorMask = stack.malloc(4);
            var polygonMode = stack.mallocInt(2);
            GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, viewport);
            GL33C.glGetBooleanv(GL33C.GL_COLOR_WRITEMASK, colorMask);
            GL33C.glGetIntegerv(GL33C.GL_POLYGON_MODE, polygonMode);
            try {
                for (int cap : DISABLED_CAPS) GL33C.glDisable(cap);
                GL33C.glColorMask(true, true, true, true);
                GL33C.glPolygonMode(GL33C.GL_FRONT_AND_BACK, GL33C.GL_FILL);
                GL33C.glViewport(0, 0, width, height);
                GL33C.glUseProgram(program);
                GL33C.glUniform1f(sharpnessLocation, (float) strength);
                GL33C.glBindVertexArray(vertexArray);
                GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, source);
                GL33C.glBindSampler(0, sampler);
                GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
            } finally {
                GL33C.glBindSampler(0, previousSampler);
                GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, previousTexture);
                GL33C.glActiveTexture(previousActive);
                GL33C.glBindVertexArray(previousVao);
                GL33C.glUseProgram(previousProgram);
                GL33C.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
                // Core profile has one front/back polygon mode.
                GL33C.glPolygonMode(GL33C.GL_FRONT_AND_BACK, polygonMode.get(0));
                GL33C.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0,
                        colorMask.get(2) != 0, colorMask.get(3) != 0);
                for (int i = 0; i < enabled.length; i++) {
                    if (enabled[i]) GL33C.glEnable(DISABLED_CAPS[i]);
                }
            }
        }
    }

    /**
     * 初回の使用時にシャープ化用シェーダー、VAO、専用サンプラーを生成する。
     */
    private void ensureResources() {
        if (program != 0) return;
        int vertex = 0;
        int fragment = 0;
        try {
            vertex = compile(GL33C.GL_VERTEX_SHADER, "dlss_output_sharpen.vsh");
            fragment = compile(GL33C.GL_FRAGMENT_SHADER, "dlss_output_sharpen.fsh");
            program = GL33C.glCreateProgram();
            GL33C.glAttachShader(program, vertex);
            GL33C.glAttachShader(program, fragment);
            GL33C.glLinkProgram(program);
            if (GL33C.glGetProgrami(program, GL33C.GL_LINK_STATUS) == GL33C.GL_FALSE) {
                throw new IllegalStateException("DLSS sharpening link failed: " + GL33C.glGetProgramInfoLog(program));
            }
            // 初回リンク時にuniformの位置を取得し、以後の返却描画では値の更新だけを行う。
            sharpnessLocation = GL33C.glGetUniformLocation(program, "Sharpness");
            int textureLocation = GL33C.glGetUniformLocation(program, "OutputSampler");
            if (sharpnessLocation < 0 || textureLocation < 0) {
                throw new IllegalStateException("DLSS sharpening uniforms unavailable");
            }
            int previousProgram = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
            try {
                GL33C.glUseProgram(program);
                GL33C.glUniform1i(textureLocation, 0);
            } finally {
                GL33C.glUseProgram(previousProgram);
            }
            vertexArray = GL33C.glGenVertexArrays();
            sampler = GL33C.glGenSamplers();
            GL33C.glSamplerParameteri(sampler, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
            GL33C.glSamplerParameteri(sampler, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
            GL33C.glSamplerParameteri(sampler, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
            GL33C.glSamplerParameteri(sampler, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
        } catch (RuntimeException error) {
            close();
            throw error;
        } finally {
            if (vertex != 0) GL33C.glDeleteShader(vertex);
            if (fragment != 0) GL33C.glDeleteShader(fragment);
        }
    }

    /**
     * JAR内の指定シェーダーを読み、コンパイル結果を確認してGL識別子を返す。
     *
     * @param type GLシェーダー種別またはuniformのGL型
     * @param name 対象または診断区間を識別する名前
     * @return コンパイル済みのGLシェーダー識別子。リンク後の削除は呼び出し元が担当する
     */
    private static int compile(int type, String name) {
        String resource = "/assets/titanium/shaders/core/" + name;
        try (var input = DlssOutputSharpening.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing shader " + resource);
            int shader = GL33C.glCreateShader(type);
            GL33C.glShaderSource(shader, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            GL33C.glCompileShader(shader);
            if (GL33C.glGetShaderi(shader, GL33C.GL_COMPILE_STATUS) == GL33C.GL_FALSE) {
                String log = GL33C.glGetShaderInfoLog(shader);
                GL33C.glDeleteShader(shader);
                throw new IllegalStateException("DLSS sharpening compile failed: " + log);
            }
            return shader;
        } catch (IOException error) {
            throw new IllegalStateException("Could not read " + resource, error);
        }
    }

    /**
     * 所有するリソースと保留状態を解放する。
     *
     * 描画スレッドから呼び出す。
     */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (sampler != 0) GL33C.glDeleteSamplers(sampler);
        if (vertexArray != 0) GL33C.glDeleteVertexArrays(vertexArray);
        if (program != 0) GL33C.glDeleteProgram(program);
        sampler = vertexArray = program = 0;
    }
}
