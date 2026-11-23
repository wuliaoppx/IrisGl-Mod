package dev.antimony.gl.hook;

import dev.antimony.AntimonyMod;

/**
 * Diagnostics for the GPU draw calls the compatibility layer cancels on the
 * main render target while the software rasterizer owns the frame (phase 3b).
 *
 * <p>These draws are pure duplicate work today - the vanilla world/entity/GUI
 * geometry is already replayed through the IRIS GL passes, and the software
 * frame is blitted over the main target's colour texture afterwards. Phase 4
 * will additionally decode the cancelled meshes so modded main-target draws
 * get a CPU replay too.</p>
 */
public final class GlDrawAbsorber {
    private static long total;
    private static long frame;
    private static long inFrame;
    private static long peakInFrame;
    private static long lastLogFrame;
    private static int mainFbo = -1;

    private GlDrawAbsorber() {
    }

    /** Called once per software-rendered frame (from the immediate-draw hook). */
    public static void beginFrame() {
        if (inFrame > peakInFrame) {
            peakInFrame = inFrame;
        }
        inFrame = 0;
        frame++;
        // Refresh every frame: a window resize rebuilds the main render
        // target and its framebuffer id with it.
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mainFbo = mc != null && mc.getMainRenderTarget() != null
                    ? mc.getMainRenderTarget().frameBufferId
                    : -1;
        } catch (Throwable t) {
            mainFbo = -1;
        }
        if (total > 0 && frame - lastLogFrame >= 600) {
            lastLogFrame = frame;
            AntimonyMod.LOG.info("[Antimony] draw absorb: {} GPU draws cancelled so far, peak {}/frame",
                    total, peakInFrame);
        }
    }

    /**
     * True when the currently bound framebuffer (as observed by the
     * {@code _glBindFramebuffer} hook) is Minecraft's main render target -
     * the one the software frame replaces.
     */
    public static boolean isMainTarget() {
        return mainFbo > 0 && dev.antimony.gl.state.GlState.boundFramebuffer == mainFbo;
    }

    /** Records one cancelled draw call ({@code _drawElements} on the main target). */
    public static void count(int mode, int count) {
        total++;
        inFrame++;
    }

    public static long total() {
        return total;
    }

    public static long peakPerFrame() {
        return peakInFrame;
    }
}
