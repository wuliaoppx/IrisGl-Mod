package dev.antimony.core.irisgl;

/**
 * CPU colour + depth buffer. Colour is 0xAARRGGBB, depth is 0..1.
 */
public final class IrisGlFramebuffer {
    public final int width;
    public final int height;
    public final int[] color;
    public final float[] depth;

    public IrisGlFramebuffer(int width, int height) {
        this.width = width;
        this.height = height;
        this.color = new int[width * height];
        this.depth = new float[width * height];
    }

    public void clearColor(int argb) {
        java.util.Arrays.fill(this.color, argb);
    }

    public void clearDepth() {
        java.util.Arrays.fill(this.depth, 1.0F);
    }
}
