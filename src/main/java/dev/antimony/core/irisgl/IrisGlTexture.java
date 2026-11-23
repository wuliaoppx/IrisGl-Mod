package dev.antimony.core.irisgl;

/**
 * A CPU-side RGBA texture. Pixel format is 0xAARRGGBB.
 */
public final class IrisGlTexture {
    public final int width;
    public final int height;
    public final int[] pixels;
    /**
     * GL_REPEAT instead of GL_CLAMP_TO_EDGE. Vanilla relies on repeat for the
     * scrolling rain/snow sheets and the cloud layer; clamping froze the rain
     * into a static curtain (observed bug).
     */
    public boolean wrapRepeat;

    public IrisGlTexture(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    /** Marks this texture as GL_REPEAT-sampled. */
    public IrisGlTexture repeat() {
        this.wrapRepeat = true;
        return this;
    }

    public int sampleNearest(float u, float v) {
        // MC sampleNearest semantics: GL_NEAREST + CLAMP_TO_EDGE (the block
        // atlas is created clamped in 1.21). Vanilla block UVs sit in [0, 1)
        // exactly on 1.0 at the far edge of a face; clamping keeps the sample
        // inside the sprite instead of bleeding into the neighbouring sprite
        // row (observed as dirty edges on redstone dust / item icons).
        return this.at(
                (int) Math.floor(this.wrap(u) * this.width),
                (int) Math.floor(this.wrap(v) * this.height));
    }

    public int sampleBilinear(float u, float v) {
        float fx = this.wrap(u) * this.width - 0.5F;
        float fy = this.wrap(v) * this.height - 0.5F;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        float tx = fx - x0;
        float ty = fy - y0;
        int c00 = this.at(x0, y0);
        int c10 = this.at(x0 + 1, y0);
        int c01 = this.at(x0, y0 + 1);
        int c11 = this.at(x0 + 1, y0 + 1);
        return lerpColor(lerpColor(c00, c10, tx), lerpColor(c01, c11, tx), ty);
    }

    private float wrap(float t) {
        if (!this.wrapRepeat) {
            return t;
        }
        float f = t - (float) Math.floor(t);
        // exact 1.0 must wrap back to 0.0, not to the last texel
        return f >= 1.0F ? 0.0F : f;
    }

    private int at(int x, int y) {
        if (this.wrapRepeat) {
            x = Math.floorMod(x, this.width);
            y = Math.floorMod(y, this.height);
            return this.pixels[y * this.width + x];
        }
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x >= this.width) x = this.width - 1;
        if (y >= this.height) y = this.height - 1;
        return this.pixels[y * this.width + x];
    }

    public static int lerpColor(int a, int b, float t) {
        int aa = (a >>> 24) & 0xFF;
        int ar = (a >>> 16) & 0xFF;
        int ag = (a >>> 8) & 0xFF;
        int ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF;
        int br = (b >>> 16) & 0xFF;
        int bg = (b >>> 8) & 0xFF;
        int bb = b & 0xFF;
        int ca = (int) (aa + (ba - aa) * t);
        int cr = (int) (ar + (br - ar) * t);
        int cg = (int) (ag + (bg - ag) * t);
        int cb = (int) (ab + (bb - ab) * t);
        return (ca << 24) | (cr << 16) | (cg << 8) | cb;
    }
}
