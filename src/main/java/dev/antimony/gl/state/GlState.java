package dev.antimony.gl.state;

/**
 * Mirror of the OpenGL state seen so far.
 *
 * <p>GL state belongs to whoever called it - a mod's {@code glEnable} must not
 * leak into Minecraft's own batches - so this is kept separate from
 * {@code IrisGlContext}. In {@code report} mode it is a pure observation; in
 * {@code on} mode it is the only remaining record of the driver state, because
 * every pure state setter is cancelled before it reaches GL.</p>
 */
public final class GlState {
    /** Capability bits indexed by their GL enum (0x0B00..0xFFFF). */
    private static final boolean[] CAPS = new boolean[0x10000];

    /** GL defaults. */
    public static int viewportX;
    public static int viewportY;
    public static int viewportW = 1;
    public static int viewportH = 1;

    public static int scissorX;
    public static int scissorY;
    public static int scissorW;
    public static int scissorH;

    public static float clearR;
    public static float clearG;
    public static float clearB;
    public static float clearA = 1.0F;
    public static double clearDepth = 1.0;
    public static int clearStencil;

    /** GL_DEPTH_FUNC, default GL_LESS (0x0201). */
    public static int depthFunc = 0x0201;
    /** GL_COLOR_WRITEMASK, default all true. */
    public static boolean colorMaskR = true;
    public static boolean colorMaskG = true;
    public static boolean colorMaskB = true;
    public static boolean colorMaskA = true;
    /** GL_POLYGON_OFFSET_FILL factors. */
    public static float polygonOffsetFactor;
    public static float polygonOffsetUnits;
    /** GL_LOGIC_OP_MODE, default GL_COPY (0x1503). */
    public static int logicOp = 0x1503;

    /** Last blend equation (GL_FUNC_ADD by default). */
    public static int blendEquation = 0x8006;

    /**
     * Last framebuffer bound through {@code GlStateManager._glBindFramebuffer}
     * (observed in every mode, never cancelled). Lets the draw-call hook tell
     * main-target draws (cancelled in {@code on} mode) from modded-FBO draws
     * (forwarded, so the CPU rasterizer can still sample their textures).
     */
    public static int boundFramebuffer;

    /** Number of state setters absorbed in {@code on} mode - diagnostics. */
    public static long absorbed;

    private GlState() {
    }

    public static void setCap(int cap, boolean enabled) {
        if (cap >= 0 && cap < CAPS.length) {
            CAPS[cap] = enabled;
        }
    }

    public static boolean cap(int cap) {
        return cap >= 0 && cap < CAPS.length && CAPS[cap];
    }

    /** Number of capabilities currently enabled - handy for sanity logging. */
    public static int enabledCount() {
        int n = 0;
        for (boolean b : CAPS) {
            if (b) {
                n++;
            }
        }
        return n;
    }
}
