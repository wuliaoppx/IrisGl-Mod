package dev.antimony.core.render;

import dev.antimony.core.irisgl.IrisGlTexture;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Render-thread CPU storage for explicitly captured raw-ID texture uploads. No driver readback. */
public final class CpuDynamicTextures {
    private static final int MAX_DIMENSION = 1024;
    private static final int MAX_TEXTURES = 8;
    private static final Map<Integer, IrisGlTexture> TEXTURES = new HashMap<>();
    private static long uploads, allocations;

    private CpuDynamicTextures() { }

    /**
     * Captures GL_RGB / GL_UNSIGNED_BYTE with unpack alignment 4, as used by Xaero 26.6.
     * Rows retain the original upload order; applying another vertical flip would change its UVs.
     * The caller must drain raster workers before replacing pixels they may still be sampling.
     */
    public static synchronized IrisGlTexture uploadRgb(int id, int width, int height, ByteBuffer source) {
        if (id <= 0 || width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION)
            throw new IllegalArgumentException("Invalid CPU dynamic texture identity or extent");
        int rowBytes = width * 3;
        int rowStride = (rowBytes + 3) & ~3;
        int required = (height - 1) * rowStride + rowBytes;
        if (source == null || source.remaining() < required)
            throw new IllegalArgumentException("Incomplete RGB upload: need " + required + " remaining bytes");
        IrisGlTexture texture = TEXTURES.get(id);
        if (texture == null || texture.width != width || texture.height != height) {
            if (texture == null && TEXTURES.size() >= MAX_TEXTURES)
                throw new IllegalStateException("CPU dynamic texture budget exceeded");
            texture = new IrisGlTexture(width, height, new int[width * height]);
            TEXTURES.put(id, texture);
            allocations++;
        }
        int start = source.position();
        for (int y = 0; y < height; y++) {
            int row = start + y * rowStride;
            for (int x = 0; x < width; x++) {
                int at = row + x * 3;
                texture.pixels[y * width + x] = 0xff000000
                        | ((source.get(at) & 255) << 16)
                        | ((source.get(at + 1) & 255) << 8)
                        | (source.get(at + 2) & 255);
            }
        }
        uploads++;
        return texture;
    }

    public static synchronized IrisGlTexture texture(int id) { return TEXTURES.get(id); }
    public static synchronized void forget(int id) { TEXTURES.remove(id); }
    public static synchronized long uploads() { return uploads; }
    public static synchronized long allocations() { return allocations; }
}
