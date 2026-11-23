package dev.antimony.core.render;

/**
 * Render-thread-only mirrors of GL enable bits that vanilla toggles through
 * GlStateManager while replaying RenderType state shards. GL defaults: cull
 * and depth test enabled, blending disabled, depth writes enabled.
 */
public final class GlStateTracker {
    private static volatile boolean cull = true;
    private static volatile boolean depthTest = true;
    private static volatile boolean blend = false;
    private static volatile boolean depthWrite = true;
    private static volatile int colorMask = 15;
    private static volatile int blendSrc = dev.antimony.core.irisgl.IrisGlContext.BF_SRC_ALPHA;
    private static volatile int blendDst = dev.antimony.core.irisgl.IrisGlContext.BF_ONE_MINUS_SRC_ALPHA;
    private static volatile int blendAlphaSrc = 0;
    private static volatile int blendAlphaDst = 0;

    private GlStateTracker() {
    }

    public static void setCull(boolean value) {
        cull = value;
    }

    public static boolean cull() {
        return cull;
    }

    public static void setDepthTest(boolean value) {
        depthTest = value;
    }

    public static boolean depthTest() {
        return depthTest;
    }

    public static void setBlend(boolean value) {
        blend = value;
    }

    public static boolean blend() {
        return blend;
    }

    public static void setDepthWrite(boolean value) {
        depthWrite = value;
    }

    public static void setBlendFunc(int src, int dst) {
        blendSrc = src;
        blendDst = dst;
        blendAlphaSrc = 0;
        blendAlphaDst = 0;
    }

    public static void setBlendFuncSeparate(int src, int dst, int alphaSrc, int alphaDst) {
        blendSrc = src;
        blendDst = dst;
        blendAlphaSrc = alphaSrc;
        blendAlphaDst = alphaDst;
    }

    public static int blendSrc() {
        return blendSrc;
    }

    public static int blendDst() {
        return blendDst;
    }

    public static int blendAlphaSrc() {
        return blendAlphaSrc;
    }

    public static int blendAlphaDst() {
        return blendAlphaDst;
    }

    public static boolean depthWrite() {
        return depthWrite;
    }

    public static void setColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        colorMask = (red ? 1 : 0) | (green ? 2 : 0) | (blue ? 4 : 0) | (alpha ? 8 : 0);
    }

    public static boolean colorWrite() {
        if (colorMask == 0) return false;
        if (colorMask == 15) return true;
        throw new IllegalStateException("Partial GUI color masks have no CPU draw adapter");
    }

    /** Restores the GL defaults vanilla's clearRenderState relies on. */
    public static void reset() {
        cull = true;
        depthTest = true;
        blend = false;
        depthWrite = true;
        colorMask = 15;
        blendSrc = dev.antimony.core.irisgl.IrisGlContext.BF_SRC_ALPHA;
        blendDst = dev.antimony.core.irisgl.IrisGlContext.BF_ONE_MINUS_SRC_ALPHA;
        blendAlphaSrc = 0;
        blendAlphaDst = 0;
    }
}
