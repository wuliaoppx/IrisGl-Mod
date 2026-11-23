package dev.antimony.gl.hook;

import dev.antimony.core.render.GlStateTracker;
import dev.antimony.gl.GlCompat;
import dev.antimony.gl.GlCoverage;
import dev.antimony.gl.state.GlState;

/**
 * Handlers for the {@code GlStateManager} entry points the layer watches.
 *
 * <p>In {@code report} mode every handler counts the call and returns false, so
 * the original GL call still happens and the session behaves exactly as before.
 * In {@code on} mode the same handler returns true: the call is absorbed - the
 * driver never sees it - and its value is mirrored into {@link GlState} (and,
 * for the bits the rasterizer honours, into {@link GlStateTracker}).</p>
 *
 * <p>Only <b>pure state setters</b> are absorbed here. Object and resource
 * calls (texture upload, buffer/FBO objects, shader programs) are deliberately
 * still forwarded: vanilla's texture store and the atlas read-back depend on
 * them until the CPU-side texture layer replaces them.</p>
 */
public final class GlSmHooks {
    /** GL_BLEND */
    public static final int CAP_BLEND = 0x0BE2;
    /** GL_DEPTH_TEST */
    public static final int CAP_DEPTH_TEST = 0x0B71;
    /** GL_CULL_FACE */
    public static final int CAP_CULL_FACE = 0x0B44;
    /** GL_SCISSOR_TEST */
    public static final int CAP_SCISSOR_TEST = 0x0C11;

    private GlSmHooks() {
    }

    private static boolean absorb() {
        if (GlCompat.intercepting()) {
            GlState.absorbed++;
            return true;
        }
        return false;
    }

    // ---- clears ---------------------------------------------------------

    /**
     * {@code _clear}/{@code _viewport}/{@code _scissorBox} are absorbed into
     * {@link GlState} only; they are deliberately NOT replayed into the
     * software rasterizer yet. Our passes already apply every clear, viewport
     * and scissor the software framebuffer needs, and blind routing is
     * actively harmful: vanilla {@code RenderTarget.clear()} - which Iris and
     * every modded render target also call - would repaint the main software
     * canvas mid-frame with other targets' clear colours, and an FBO-bound
     * {@code _viewport} would stomp the projection used at flush time. The
     * one scissor path that matters ({@code RenderSystem.enableScissor}) is
     * already routed by RenderSystemMixin. Revisit when phase 4 makes the
     * CPU framebuffer the only target.
     */
    private static boolean routeClear() {
        return absorb();
    }

    public static boolean clear(int mask) {
        GlCoverage.hit("GlStateManager._clear");
        return routeClear();
    }

    public static boolean clearColor(float red, float green, float blue, float alpha) {
        GlCoverage.hit("GlStateManager._clearColor");
        GlState.clearR = red;
        GlState.clearG = green;
        GlState.clearB = blue;
        GlState.clearA = alpha;
        return absorb();
    }

    public static boolean clearDepth(double depth) {
        GlCoverage.hit("GlStateManager._clearDepth");
        GlState.clearDepth = depth;
        return absorb();
    }

    public static boolean clearStencil(int value) {
        GlCoverage.hit("GlStateManager._clearStencil");
        GlState.clearStencil = value;
        return absorb();
    }

    // ---- viewport / scissor ---------------------------------------------

    public static boolean viewport(int x, int y, int width, int height) {
        GlCoverage.hit("GlStateManager._viewport");
        GlState.viewportX = x;
        GlState.viewportY = y;
        GlState.viewportW = width;
        GlState.viewportH = height;
        return absorb();
    }

    public static boolean scissor(int x, int y, int width, int height) {
        GlCoverage.hit("GlStateManager._scissorBox");
        GlState.scissorX = x;
        GlState.scissorY = y;
        GlState.scissorW = width;
        GlState.scissorH = height;
        return absorb();
    }

    public static boolean scissorTest(boolean enabled) {
        GlCoverage.hit(enabled ? "GlStateManager._enableScissorTest" : "GlStateManager._disableScissorTest");
        GlCoverage.cap(CAP_SCISSOR_TEST, enabled);
        GlState.setCap(CAP_SCISSOR_TEST, enabled);
        return absorb();
    }

    // ---- capabilities ----------------------------------------------------

    public static boolean capability(int cap, boolean enabled) {
        GlCoverage.hit(enabled ? "GlStateManager.enable" : "GlStateManager.disable");
        GlCoverage.cap(cap, enabled);
        GlState.setCap(cap, enabled);
        return absorb();
    }

    public static boolean cull(boolean enabled) {
        GlCoverage.hit(enabled ? "GlStateManager._enableCull" : "GlStateManager._disableCull");
        GlCoverage.cap(CAP_CULL_FACE, enabled);
        GlState.setCap(CAP_CULL_FACE, enabled);
        GlStateTracker.setCull(enabled);
        return absorb();
    }

    public static boolean depthTest(boolean enabled) {
        GlCoverage.hit(enabled ? "GlStateManager._enableDepthTest" : "GlStateManager._disableDepthTest");
        GlCoverage.cap(CAP_DEPTH_TEST, enabled);
        GlState.setCap(CAP_DEPTH_TEST, enabled);
        GlStateTracker.setDepthTest(enabled);
        return absorb();
    }

    public static boolean blend(boolean enabled) {
        GlCoverage.hit(enabled ? "GlStateManager._enableBlend" : "GlStateManager._disableBlend");
        GlCoverage.cap(CAP_BLEND, enabled);
        GlState.setCap(CAP_BLEND, enabled);
        GlStateTracker.setBlend(enabled);
        return absorb();
    }

    // ---- depth -----------------------------------------------------------

    public static boolean depthFunc(int func) {
        GlCoverage.hit("GlStateManager._depthFunc");
        GlState.depthFunc = func;
        return absorb();
    }

    public static boolean depthMask(boolean write) {
        GlCoverage.hit("GlStateManager._depthMask");
        GlStateTracker.setDepthWrite(write);
        return absorb();
    }

    // ---- blending --------------------------------------------------------

    public static boolean blendFunc(int source, int destination) {
        GlCoverage.hit("GlStateManager._blendFunc");
        GlStateTracker.setBlendFunc(mapFactor(source), mapFactor(destination));
        return absorb();
    }

    public static boolean blendFuncSeparate(int source, int destination,
                                            int alphaSource, int alphaDestination) {
        GlCoverage.hit("GlStateManager._blendFuncSeparate");
        GlStateTracker.setBlendFuncSeparate(mapFactor(source), mapFactor(destination),
                mapFactor(alphaSource), mapFactor(alphaDestination));
        return absorb();
    }

    public static boolean blendEquation(int mode) {
        GlCoverage.hit("GlStateManager._blendEquation");
        GlState.blendEquation = mode;
        return absorb();
    }

    // ---- raster / fragment state ----------------------------------------

    public static boolean polygonMode(int face, int mode) {
        GlCoverage.hit("GlStateManager._polygonMode");
        return absorb();
    }

    public static boolean polygonOffsetEnable(boolean enabled) {
        GlCoverage.hit(enabled ? "GlStateManager._enablePolygonOffset" : "GlStateManager._disablePolygonOffset");
        GlState.setCap(0x8037, enabled); // GL_POLYGON_OFFSET_FILL
        return absorb();
    }

    public static boolean polygonOffset(float factor, float units) {
        GlCoverage.hit("GlStateManager._polygonOffset");
        GlState.polygonOffsetFactor = factor;
        GlState.polygonOffsetUnits = units;
        return absorb();
    }

    public static boolean colorLogicOp(boolean enabled) {
        GlCoverage.hit(enabled ? "GlStateManager._enableColorLogicOp" : "GlStateManager._disableColorLogicOp");
        GlState.setCap(0x0BF2, enabled); // GL_COLOR_LOGIC_OP
        return absorb();
    }

    public static boolean logicOp(int op) {
        GlCoverage.hit("GlStateManager._logicOp");
        GlState.logicOp = op;
        return absorb();
    }

    public static boolean colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        GlCoverage.hit("GlStateManager._colorMask");
        GlState.colorMaskR = red;
        GlState.colorMaskG = green;
        GlState.colorMaskB = blue;
        GlState.colorMaskA = alpha;
        GlStateTracker.setColorMask(red, green, blue, alpha);
        return absorb();
    }

    // ---- stencil ---------------------------------------------------------

    public static boolean stencilFunc(int func, int reference, int mask) {
        GlCoverage.hit("GlStateManager._stencilFunc");
        return absorb();
    }

    public static boolean stencilMask(int mask) {
        GlCoverage.hit("GlStateManager._stencilMask");
        return absorb();
    }

    public static boolean stencilOp(int fail, int zFail, int zPass) {
        GlCoverage.hit("GlStateManager._stencilOp");
        return absorb();
    }

    // ---- queries ---------------------------------------------------------

    public static boolean getError() {
        GlCoverage.hit("GlStateManager._getError");
        return GlCompat.intercepting();
    }

    // ---- object calls: textures absorb via the NativeImage funnel (5b) ----

    /**
     * Texture object identity calls stay forwarded: vanilla owns the GL
     * names and the fallback read-back paths still need a driver-side copy.
     * Pixel data however is absorbed at {@code NativeImage._upload} while the
     * software renderer owns the frame - see {@link GlTexMirror}.
     */
    public static boolean bindTexture(int texture) {
        GlCoverage.hit("GlStateManager._bindTexture");
        GlTexMirror.observeBindTexture(texture);
        return false;
    }

    public static boolean activeTexture(int unit) {
        GlCoverage.hit("GlStateManager._activeTexture");
        GlTexMirror.observeActiveTexture(unit);
        return false;
    }

    public static boolean genTexture() {
        GlCoverage.hit("GlStateManager._genTexture");
        return false;
    }

    public static boolean deleteTexture(int id) {
        GlCoverage.hit("GlStateManager._deleteTexture");
        GlTexMirror.observeDeleteTexture(id);
        dev.antimony.core.render.CpuDynamicTextures.forget(id);
        return false;
    }

    public static boolean deleteTextures() {
        GlCoverage.hit("GlStateManager._deleteTextures");
        return false;
    }

    public static boolean texImage2D(int target, int level, int internalFormat,
                                     int width, int height, int border,
                                     int format, int type, java.nio.IntBuffer pixels) {
        GlTexMirror.observeTexImage2D(target, level, internalFormat, width, height, format, type);
        return false;
    }

    /** Counted only; slice mirroring happens at the NativeImage._upload hook. */
    public static boolean texSubImage2D(int target, int level, int x, int y,
                                        int width, int height,
                                        int format, int type, long pixels) {
        return false;
    }

    public static boolean bindFramebuffer(int target, int framebuffer) {
        GlCoverage.hit("GlStateManager._glBindFramebuffer");
        // Observed in every mode (never cancelled): tracking the binding is
        // what lets the draw hook below distinguish main-target draws from
        // modded-FBO draws. Cancelling the bind itself would leave every
        // framebuffer operation aimed at whatever the driver still has
        // bound - including our own atlas read-back path.
        GlState.boundFramebuffer = framebuffer;
        return false;
    }

    // ---- draw calls (phase 3b) -------------------------------------------

    /**
     * The single junction every blaze3d draw passes through ({@code
     * VertexBuffer._drawWithShader -> RenderSystem.drawElements -> here}).
     * Vanilla 1.21.1 has no other raw draw site; immediate mode is already
     * intercepted earlier at {@code BufferUploader.drawWithShader}.
     *
     * <p>Policy: while the software rasterizer owns the frame
     * ({@code glcompat=on}, renderer healthy), a draw aimed at the <b>main
     * render target</b> is cancelled - it is duplicate work; the same
     * geometry is replayed by the IRIS GL passes and the software frame is
     * blitted into the main target's colour texture afterwards. A draw aimed
     * at <b>any other framebuffer</b> (Iris post chains, modded render
     * targets) is forwarded untouched: their colour textures are still read
     * back by {@code IrisGlTextures}, so killing them would blank those
     * effects instead of accelerating anything.</p>
     *
     * @return true when the driver call must be absorbed
     */
    public static boolean drawElements(int mode, int count, int type, long indices) {
        GlCoverage.hit("GlStateManager._drawElements");
        if (!GlCompat.intercepting() || !dev.antimony.core.render.IrisGlRenderer.shouldReplace()) {
            return false;
        }
        if (!GlDrawAbsorber.isMainTarget()) {
            // Not the main render target (default framebuffer blit, Iris post
            // chain, modded target whose texture we still sample). Forward it.
            return false;
        }
        GlDrawAbsorber.count(mode, count);
        return true;
    }

    /** GL blend factor enum -> IrisGlContext BF_* constant. */
    private static int mapFactor(int glEnum) {
        return switch (glEnum) {
            case 0 -> dev.antimony.core.irisgl.IrisGlContext.BF_ZERO;
            case 1 -> dev.antimony.core.irisgl.IrisGlContext.BF_ONE;
            case 0x0300 -> dev.antimony.core.irisgl.IrisGlContext.BF_SRC_COLOR;
            case 0x0301 -> dev.antimony.core.irisgl.IrisGlContext.BF_ONE_MINUS_SRC_COLOR;
            case 0x0302 -> dev.antimony.core.irisgl.IrisGlContext.BF_SRC_ALPHA;
            case 0x0303 -> dev.antimony.core.irisgl.IrisGlContext.BF_ONE_MINUS_SRC_ALPHA;
            case 0x0304 -> dev.antimony.core.irisgl.IrisGlContext.BF_DST_ALPHA;
            case 0x0305 -> dev.antimony.core.irisgl.IrisGlContext.BF_ONE_MINUS_DST_ALPHA;
            case 0x0306 -> dev.antimony.core.irisgl.IrisGlContext.BF_DST_COLOR;
            case 0x0307 -> dev.antimony.core.irisgl.IrisGlContext.BF_ONE_MINUS_DST_COLOR;
            default -> dev.antimony.core.irisgl.IrisGlContext.BF_ONE;
        };
    }
}
