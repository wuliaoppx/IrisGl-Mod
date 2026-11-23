package dev.antimony.core.render;

import dev.antimony.core.irisgl.IrisGlContext;
import java.util.Arrays;

/** Real tile-raster regression: a depth-clear quad must never become an opaque black GUI panel. */
public final class GuiDepthClearProbe {
    public static void main(String[] args) {
        GlStateTracker.reset();
        check(GlStateTracker.colorWrite(), "default RGBA writes");
        GlStateTracker.setColorMask(false, false, false, false);
        check(!GlStateTracker.colorWrite(), "depth-only color mask");
        GlStateTracker.setColorMask(true, false, true, false);
        try { GlStateTracker.colorWrite(); throw new AssertionError("partial mask silently accepted"); }
        catch (IllegalStateException expected) { }
        GlStateTracker.reset();
        check(GlStateTracker.colorWrite(), "mask reset does not leak");
        IrisGlContext context = new IrisGlContext();
        context.beginFrame(16, 12);
        context.ortho(0, 16, 12, 0, -100000, 100000);
        Arrays.fill(context.framebuffer.color, 0xff123456);
        Arrays.fill(context.framebuffer.depth, .25f);
        context.depthTest = false;
        context.depthWrite = true;
        context.colorWrite = false;
        context.forceFarDepth = true;
        context.c4f(0, 0, 0, 1);
        quad(context, 4, 3, 12, 9);
        context.colorWrite = true;
        context.forceFarDepth = false; // Queued draw must retain its frozen depth-only contract.
        context.flushRaster();
        for (int y = 0; y < 12; y++) for (int x = 0; x < 16; x++) {
            int at = y * 16 + x;
            boolean inside = x >= 4 && x < 12 && y >= 3 && y < 9;
            check(context.framebuffer.color[at] == 0xff123456, "depth clear changed color at " + x + "," + y);
            check(context.framebuffer.depth[at] == (inside ? 1f : .25f), "depth clear changed wrong coverage at " + x + "," + y);
        }
        context.c4f(1, 0, 0, 1);
        quad(context, 5, 4, 11, 8);
        context.flushRaster();
        check(context.framebuffer.color[6 * 16 + 8] == 0xffff0000, "following GUI layer still writes its color");
        check(context.framebuffer.depth[6 * 16 + 8] == .5f, "far-depth output leaked into following layer");
        context.forceFarDepth = true;
        context.beginFrame(16, 12);
        check(!context.forceFarDepth, "new frame clears depth-output override");
        System.out.println("GUI DEPTH CLEAR PROBE OK: real tile coverage, unchanged color, far depth, frozen queue state and following-layer/frame isolation");
    }
    private static void quad(IrisGlContext context, float x0, float y0, float x1, float y1) {
        context.bgnpolygon();
        context.v3f(x0, y0, 0); context.v3f(x1, y0, 0);
        context.v3f(x1, y1, 0); context.v3f(x0, y1, 0);
        context.endpolygon();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
