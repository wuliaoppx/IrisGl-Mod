package dev.antimony.core.irisgl;

/**
 * Shared 256-entry reciprocal table for the /255 normalisations that run once
 * per vertex colour and up to seven times per rasterized pixel. Division has
 * ~20-cycle latency on the hot path; a bounds-free float array load is one
 * L1 hit. Values are the exact {@code i / 255.0F} results, so raster output
 * is bit-identical to the divide version.
 */
public final class Inv255 {
    /** {@code INV_255[i] == i / 255.0F} for 0..255; out-of-range reads are UB in theory, callers clamp or mask. */
    public static final float[] INV_255 = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            INV_255[i] = i / 255.0F;
        }
    }

    private Inv255() {
    }
}
