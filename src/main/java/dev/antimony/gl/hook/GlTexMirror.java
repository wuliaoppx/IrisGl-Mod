package dev.antimony.gl.hook;

import com.mojang.blaze3d.platform.NativeImage;
import dev.antimony.AntimonyMod;
import dev.antimony.gl.GlCoverage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.opengl.GL11;

/**
 * CPU mirror of the textures vanilla uploads (phase 4a), observed at
 * {@link NativeImage#_upload} - the single funnel every sprite animation,
 * font glyph page and dynamic texture upload passes through.
 *
 * <p>Why here and not {@code GlStateManager._texSubImage2D}: the GlStateManager
 * overload receives a raw native pointer plus whatever GL unpack state happens
 * to be installed (ROW_LENGTH / SKIP_ROWS / SKIP_PIXELS), and mis-deriving the
 * source stride from driver state produced both wrong pixels and, once the
 * derivation read past the buffer, a JVM-level access violation. _upload
 * instead carries everything as explicit Java ints against the NativeImage
 * object: destination (x, y) on the bound atlas, source (skipPixels, skipRows)
 * inside the image, source stride = image width - and out-of-range reads
 * would throw, not crash.</p>
 *
 * <p>Phase 5b step 1: while the software renderer owns the frame
 * ({@code glcompat=on} and {@code IrisGlRenderer.shouldReplace()}) and the
 * mirror already knows the bound texture, an upload is <b>absorbed</b> - the
 * driver never sees the pixel data - and the CPU mirror becomes the single
 * source of truth for vanilla's dynamic textures. Absorbing also skips the
 * vanilla {@code setFilter}/{@code clamp} side effects, which only ever
 * mattered to GL-side consumers that no longer receive our pixels anyway.
 * Mirror-miss, title screen and context-less uploads still forward, so the
 * synchronous read-back fallback paths keep working.</p>
 */
public final class GlTexMirror {
    /** Mirrored level-0 pixel data in ARGB packed ints, by GL texture id. */
    private static final Map<Integer, Mirror> MIRRORS = new ConcurrentHashMap<>();
    /** Upload-attribution debug: uploads seen per GL texture id. */
    private static final Map<Integer, Long> UPLOADS_BY_ID = new ConcurrentHashMap<>();
    private static long mirroredUploads;
    private static long absorbedUploads;
    private static long forwardedUploads;
    private static boolean warnedNoContext;

    /** A mirrored texture's level-0 data. */
    public static final class Mirror {
        public final int width;
        public final int height;
        public final int[] pixels;

        Mirror(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }
    }

    private GlTexMirror() {
    }

    // ---- observation hooks (calls stay forwarded to the driver) ----------

    /** Observed {@code GlStateManager._activeTexture} (unused; kept for coverage). */
    public static void observeActiveTexture(int unit) {
    }

    /** Observed {@code GlStateManager._bindTexture} (unused; kept for coverage). */
    public static void observeBindTexture(int texture) {
    }

    /** Observed {@code GlStateManager._deleteTexture}/_deleteTextures. */
    public static void observeDeleteTexture(int id) {
        if (id > 0) {
            MIRRORS.remove(id);
        }
    }

    /** Observed {@code GlStateManager._texImage2D}: (re)allocates the mirror. */
    public static void observeTexImage2D(int target, int level, int internalFormat,
                                         int width, int height,
                                         int format, int type) {
        GlCoverage.hit("GlStateManager._texImage2D");
        if (target != GL11.GL_TEXTURE_2D || level != 0 || width <= 0 || height <= 0) {
            return;
        }
        int id;
        try {
            id = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        } catch (Throwable t) {
            return;
        }
        if (id <= 0) {
            return;
        }
        MIRRORS.put(id, new Mirror(width, height, new int[width * height]));
    }

    /**
     * Observed {@code NativeImage._upload(level, x, y, skipPixels, skipRows,
     * width, height, blur, clamp, filterArg, close)}: copies the uploaded slice
     * from the NativeImage's own pixel buffer into the mirror of the
     * currently bound texture. The close replay lives in the mixin because
     * the decompile's parameter names are shifted - the 11th boolean is the
     * close gate, not the 10th (see NativeImageUploadMixin).
     *
     * @return true when the vanilla upload body may be skipped entirely
     *         (absorbed); the mixin then performs the close side effect.
     */
    public static boolean observeNativeImageUpload(NativeImage image, int level, int x, int y,
                                                   int skipPixels, int skipRows,
                                                   int width, int height) {
        GlCoverage.hit("NativeImage._upload");
        if (level != 0 || width <= 0 || height <= 0 || image == null) {
            return false;
        }
        int id;
        try {
            id = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        } catch (Throwable t) {
            if (!warnedNoContext) {
                warnedNoContext = true;
                AntimonyMod.LOG.info("[Antimony] texture mirror: no GL context during upload observation (forwarding only)");
            }
            return false;
        }
        UPLOADS_BY_ID.merge(id, 1L, Long::sum);
        if (id <= 0) {
            return false;
        }
        Mirror mirror = MIRRORS.get(id);
        // Phase 5b absorption gate: only swallow the upload when the software
        // renderer is actually on screen and the mirror already tracks the
        // bound texture (a first upload allocates nothing - forward it so the
        // read-back fallbacks keep a consistent driver-side copy).
        boolean absorb = mirror != null
                && dev.antimony.gl.GlCompat.intercepting()
                && dev.antimony.core.render.IrisGlRenderer.shouldReplace();
        try {
            int srcW = image.getWidth();
            // Copy through NativeImage's own accessor, NOT raw MemoryUtil:
            // getPixelRGBA performs checkAllocated() and bounds validation and
            // throws catchable Java exceptions, while a bare memGetInt on a
            // freed/never-allocated image (close=true uploads replayed from
            // queues, interleaved closes) is a JVM-level access violation that
            // no try-catch can intercept. It also handles non-RGBA storage
            // formats (LUMINANCE font pages) correctly. The returned packing
            // is ABGR - the same layout the raw read produced - so the ARGB
            // swap below is unchanged.
            for (int row = 0; row < height; row++) {
                int dy = y + row;
                if (dy < 0 || dy >= mirror.height) {
                    continue;
                }
                int sy = skipRows + row;
                if (sy < 0 || sy >= image.getHeight()) {
                    continue;
                }
                int dst = dy * mirror.width;
                for (int col = 0; col < width; col++) {
                    int sx = skipPixels + col;
                    if (sx < 0 || sx >= srcW) {
                        continue;
                    }
                    int dx = x + col;
                    if (dx < 0 || dx >= mirror.width) {
                        continue;
                    }
                    int abgr = image.getPixelRGBA(sx, sy);
                    int a = (abgr >>> 24) & 0xFF;
                    int b = (abgr >>> 16) & 0xFF;
                    int g = (abgr >>> 8) & 0xFF;
                    int r = abgr & 0xFF;
                    mirror.pixels[dst + dx] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            mirroredUploads++;
        } catch (Throwable t) {
            // A wrong pixel costs a visual glitch; the driver upload already
            // happened (or the mirror copy is stale by one frame - both are
            // cosmetic). Never let mirror faults escape into vanilla code.
            forwardedUploads++;
            return false;
        }
        if (absorb) {
            absorbedUploads++;
        } else {
            forwardedUploads++;
        }
        return absorb;
    }

    // ---- consumer API -----------------------------------------------------

    /** Mirror for a GL texture id, or null when unknown/unsupported. */
    public static Mirror mirror(int glId) {
        return glId > 0 ? MIRRORS.get(glId) : null;
    }

    public static long mirroredUploads() {
        return mirroredUploads;
    }

    /** Uploads swallowed in 5b mode (driver never received the pixels). */
    public static long absorbedUploads() {
        return absorbedUploads;
    }

    /** Uploads whose copy failed or that were forwarded for driver consumers. */
    public static long forwardedUploads() {
        return forwardedUploads;
    }

    /** Debug: uploads attributed per GL texture id (largest first). */
    public static String uploadAttribution() {
        StringBuilder sb = new StringBuilder();
        UPLOADS_BY_ID.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(6)
                .forEach(e -> sb.append("glId ").append(e.getKey()).append('=').append(e.getValue()).append(' '));
        return sb.toString();
    }

    // ---- helpers -----------------------------------------------------------

    // (The VarHandle raw-pixels path was removed with 5b: getPixelRGBA is the
    // only safe accessor - see the copy loop above.)
}
