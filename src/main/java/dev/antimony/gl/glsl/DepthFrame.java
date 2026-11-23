package dev.antimony.gl.glsl;

import dev.antimony.core.irisgl.IrisGlFramebuffer;

/** Render-thread-owned depth copies. A missing stage never falls back to an older frame. */
public final class DepthFrame {
    public enum Stage { PRE_HAND, PRE_TRANSLUCENT, FINAL }

    public record Snapshot(int width, int height, float[] preHand, float[] preTranslucent, float[] finalDepth) {}

    private long generation;
    private Object world;
    private Object pipeline;
    private IrisGlFramebuffer framebuffer;
    private final float[][] copies = new float[Stage.values().length][];
    private int captured;
    private boolean active;
    private boolean consumed;

    /** Disabled frames only invalidate metadata; their depth arrays are never visited. */
    public long begin(Object world, Object pipeline, IrisGlFramebuffer framebuffer, boolean enabled) {
        invalidate();
        generation++;
        if (generation == 0) generation++;
        if (!enabled || world == null || pipeline == null || framebuffer == null) return 0;
        this.world = world;
        this.pipeline = pipeline;
        this.framebuffer = framebuffer;
        this.active = true;
        return generation;
    }

    public void invalidate() {
        active = false;
        consumed = false;
        captured = 0;
        world = null;
        pipeline = null;
        framebuffer = null;
    }

    /** The caller must flush pending raster work before crossing this boundary. */
    public void capture(long token, Stage stage, Object world, IrisGlFramebuffer framebuffer) {
        requireFrame(token, world, pipeline, framebuffer);
        int expected = (1 << stage.ordinal()) - 1;
        if (captured != expected) throw new IllegalStateException("CPU depth stages out of order: " + stage);
        int size = Math.multiplyExact(framebuffer.width, framebuffer.height);
        if (framebuffer.width <= 0 || framebuffer.height <= 0 || framebuffer.depth.length != size)
            throw new IllegalStateException("Invalid CPU depth buffer dimensions");
        float[] copy = copies[stage.ordinal()];
        if (copy == null || copy.length != size) copies[stage.ordinal()] = copy = new float[size];
        System.arraycopy(framebuffer.depth, 0, copy, 0, size);
        captured |= 1 << stage.ordinal();
    }

    /** Single consumption prevents a second post invocation from silently reusing the same frame. */
    public Snapshot consume(Object world, Object pipeline, IrisGlFramebuffer framebuffer) {
        requireFrame(generation, world, pipeline, framebuffer);
        if (captured != (1 << Stage.values().length) - 1)
            throw new IllegalStateException("CPU depth snapshot missing from current generation " + generation);
        consumed = true;
        return new Snapshot(framebuffer.width, framebuffer.height, copies[Stage.PRE_HAND.ordinal()],
            copies[Stage.PRE_TRANSLUCENT.ordinal()], copies[Stage.FINAL.ordinal()]);
    }

    private void requireFrame(long token, Object world, Object pipeline, IrisGlFramebuffer framebuffer) {
        if (!active || consumed || token == 0 || token != generation || this.world != world
                || this.pipeline != pipeline || this.framebuffer != framebuffer)
            throw new IllegalStateException("CPU depth frame is stale or belongs to another world/pipeline/framebuffer");
    }
}
