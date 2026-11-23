package dev.antimony.core.render;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.antimony.AntimonyMod;
import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlContext;
import dev.antimony.core.irisgl.IrisGlTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;

/**
 * Intercepts vanilla's immediate-mode draws ({@code BufferUploader.drawWithShader})
 * — toast backgrounds, nine-sliced sprites, screen effects — and replays the
 * mesh through the IRIS GL software rasterizer.
 *
 * <p>The shader texture for each draw comes from the shader-texture log kept by
 * the {@code RenderSystem} mixin ({@code innerBlit} sets it right before the
 * draw call). Immediate draws only ever happen for GUI-space geometry here, so
 * they are rasterized without depth testing.</p>
 */
public final class ImmediateDraw {
    private static boolean initialized;
    private static long drawCount;
    private static long frameNumber = -1;

    /** Called from the GameRenderer mixin once per software-rendered frame. */
    public static void beginFrame() {
        frameNumber++;
    }

    private ImmediateDraw() {
    }

    public static void hook() {
        if (!initialized) {
            initialized = true;
            AntimonyMod.LOG.info("[Antimony] immediate draw hook active");
        }
    }

    /** True once the immediate-mode capture is active in this process. */
    public static boolean hooked() {
        return initialized;
    }

    /** Entry point called from the BufferUploader mixin instead of the GPU draw. */
    public static void draw(MeshData mesh) {
        if (mesh == null) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || IrisGlRenderer.failed() || IrisGl.framebuffer() == null) {
                return;
            }
            boolean previousColorWrite = IrisGl.ctx().colorWrite;
            try {
                IrisGl.ctx().colorWrite = GlStateTracker.colorWrite();
                draw0(mesh);
            } finally {
                IrisGl.ctx().colorWrite = previousColorWrite;
            }
        } catch (Throwable t) {
            AntimonyMod.LOG.warn("[Antimony] immediate draw failed", t);
        } finally {
            mesh.close();
        }
    }

    private static void draw0(MeshData mesh) {
        MeshData.DrawState state = mesh.drawState();
        VertexFormat format = state.format();
        int vertexCount = state.vertexCount();
        if (vertexCount <= 0) {
            return;
        }
        int posOff = format.getOffset(VertexFormatElement.POSITION);
        int uvOff = format.getOffset(VertexFormatElement.UV0);
        int colOff = format.getOffset(VertexFormatElement.COLOR);
        if (posOff < 0) {
            return;
        }
        boolean hasUv = uvOff >= 0;
        boolean hasColor = colOff >= 0;
        int stride = format.getVertexSize();
        int stride4 = stride / 4;

        ByteBuffer raw = mesh.vertexBuffer().order(ByteOrder.nativeOrder());
        java.nio.FloatBuffer floats = raw.asFloatBuffer();

        IrisGlTexture texture = IrisGlTextures.shaderTexture(0);
        drawCount++;

        if (texture != null) {
            IrisGl.texbind(texture);
        } else {
            IrisGl.texture(false);
        }

        // Honour the exact blend function vanilla set for this draw through
        // RenderSystem/GlStateManager (RenderSystem.enableBlend was already
        // mirrored by the GlStateManager mixin). The vignette draws with
        // blendFuncSeparate(ZERO, ONE_MINUS_SRC_COLOR): multiplying the frame
        // by (1 - texColour). The old hard-coded alpha blend turned that
        // multiply-darkening overlay into an opaque black sheet that covered
        // the whole world.
        if (GlStateTracker.blend()) {
            if (GlStateTracker.blendAlphaSrc() != 0 || GlStateTracker.blendAlphaDst() != 0) {
                IrisGl.blendfuncseparate(GlStateTracker.blendSrc(), GlStateTracker.blendDst(),
                        GlStateTracker.blendAlphaSrc(), GlStateTracker.blendAlphaDst());
            } else {
                IrisGl.blendfunction(GlStateTracker.blendSrc(), GlStateTracker.blendDst());
            }
        } else {
            IrisGl.ctx().blend = false;
        }
        IrisGl.alphatest(hasUv, 0.02F);
        IrisGl.fog(false);
        IrisGl.ctx().depthTest = false;
        IrisGl.ctx().depthWrite = false;

        switch (state.mode()) {
            case QUADS -> {
                for (int q = 0; q < vertexCount / 4; q++) {
                    IrisGl.bgnpolygon();
                    for (int i = 0; i < 4; i++) {
                        putVertex(floats, raw, q * 4 + i, stride4, posOff, uvOff, colOff, hasUv, hasColor);
                    }
                    IrisGl.endpolygon();
                }
            }
            case TRIANGLES -> {
                for (int t = 0; t < vertexCount / 3; t++) {
                    IrisGl.bgnpolygon();
                    for (int i = 0; i < 3; i++) {
                        putVertex(floats, raw, t * 3 + i, stride4, posOff, uvOff, colOff, hasUv, hasColor);
                    }
                    IrisGl.endpolygon();
                }
            }
            case TRIANGLE_STRIP -> {
                for (int t = 0; t + 2 < vertexCount; t++) {
                    IrisGl.bgnpolygon();
                    putVertex(floats, raw, t, stride4, posOff, uvOff, colOff, hasUv, hasColor);
                    putVertex(floats, raw, t + 1, stride4, posOff, uvOff, colOff, hasUv, hasColor);
                    putVertex(floats, raw, t + 2, stride4, posOff, uvOff, colOff, hasUv, hasColor);
                    IrisGl.endpolygon();
                }
            }
            case TRIANGLE_FAN -> {
                for (int t = 1; t + 1 < vertexCount; t++) {
                    IrisGl.bgnpolygon();
                    putVertex(floats, raw, 0, stride4, posOff, uvOff, colOff, hasUv, hasColor);
                    putVertex(floats, raw, t, stride4, posOff, uvOff, colOff, hasUv, hasColor);
                    putVertex(floats, raw, t + 1, stride4, posOff, uvOff, colOff, hasUv, hasColor);
                    IrisGl.endpolygon();
                }
            }
            default -> {
                // LINES / points and debug modes are not needed for the GUI.
            }
        }

        IrisGl.blendmode(false);
        IrisGl.alphatest(false, 0.0F);
    }
    private static void putVertex(java.nio.FloatBuffer floats, ByteBuffer raw, int index, int stride4,
                                  int posOff, int uvOff, int colOff, boolean hasUv, boolean hasColor) {
        int fo = index * stride4;
        if (hasColor) {
            int bo = index * stride4 * 4 + colOff;
            float[] inv = dev.antimony.core.irisgl.Inv255.INV_255;
            IrisGl.c4f(inv[raw.get(bo) & 0xFF], inv[raw.get(bo + 1) & 0xFF],
                    inv[raw.get(bo + 2) & 0xFF], inv[raw.get(bo + 3) & 0xFF]);
        } else {
            IrisGl.c4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
        if (hasUv) {
            int uo = fo + uvOff / 4;
            IrisGl.t2f(floats.get(uo), floats.get(uo + 1));
        }
        int po = fo + posOff / 4;
        IrisGl.v3f(floats.get(po), floats.get(po + 1), floats.get(po + 2));
    }
}
