package dev.antimony.core.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.antimony.AntimonyMod;
import dev.antimony.core.irisgl.IrisGlTexture;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.inventory.InventoryMenu;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * Turns Minecraft textures into CPU-side pixel arrays. Phase 4a: the block
 * atlas comes from the {@link dev.antimony.gl.hook.GlTexMirror} - the CPU copy
 * of every {@code glTexSubImage2D} vanilla issues - so atlas animation costs
 * zero GL round trips. The synchronous {@code glGetTexImage} read-back path is
 * kept only as a fallback for textures the mirror has never seen (modded
 * atlases created before the hooks went live, unusual formats).
 */
public final class IrisGlTextures {
    private static final Map<ResourceLocation, IrisGlTexture> CACHE = new HashMap<>();
    private static final Map<ResourceLocation, Long> CACHE_TIME = new HashMap<>();
    private static final ResourceLocation[] SHADER_TEXTURES = new ResourceLocation[8];
    /** Raw IDs are separate bindings, never attributed to a stale ResourceLocation. */
    private static final int[] SHADER_TEXTURE_IDS = new int[8];
    /** Sheets vanilla samples with GL_REPEAT (scrolling weather + cloud layer). */
    private static final java.util.Set<String> REPEAT_TEXTURES = java.util.Set.of(
            "textures/environment/rain.png",
            "textures/environment/snow.png",
            "textures/environment/clouds.png");
    private static TextureAtlas lastAtlas;
    private static int lastAtlasGlId = -1;
    private static IrisGlTexture blockAtlas;
    private static IrisGlTexture lightmapCache;
    private static boolean atlasWarned;
    private static int tickCounter;
    /** Live instances for runtime-stitched atlases (particles, ...). */
    private static final Map<ResourceLocation, IrisGlTexture> LIVE_ATLASES = new HashMap<>();
    private static int liveAtlasRefresh;
    private static final int LIVE_ATLAS_REFRESH_FRAMES = 2;

    private IrisGlTextures() {
    }

    public static IrisGlTexture blockAtlas() {
        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS);
        if (atlas == null) {
            return null;
        }
        // Phase 4a: build from the upload mirror. The atlas instance stays
        // stable across resource reloads only sometimes - key on the GL
        // texture id instead, which changes whenever vanilla re-creates it.
        int glId = atlas.getId();
        if (glId > 0) {
            dev.antimony.gl.hook.GlTexMirror.Mirror mirror = dev.antimony.gl.hook.GlTexMirror.mirror(glId);
            if (mirror != null) {
                if (blockAtlas == null || lastAtlasGlId != glId
                        || blockAtlas.width != mirror.width || blockAtlas.height != mirror.height) {
                    blockAtlas = new IrisGlTexture(mirror.width, mirror.height,
                            java.util.Arrays.copyOf(mirror.pixels, mirror.pixels.length));
                    lastAtlasGlId = glId;
                    lastAtlas = atlas;
                    AntimonyMod.LOG.info("[Antimony] block atlas mirrored {}x{} from GL id {}",
                            mirror.width, mirror.height, glId);
                } else {
                    System.arraycopy(mirror.pixels, 0, blockAtlas.pixels, 0, blockAtlas.pixels.length);
                }
                return blockAtlas;
            }
        }
        // Mirror miss: fall back to the read-back path.
        if (atlas == lastAtlas && blockAtlas != null) {
            return blockAtlas;
        }
        IrisGlTexture captured = readGlTexture(InventoryMenu.BLOCK_ATLAS);
        if (captured == null) {
            captured = captureAtlasFromSprites(atlas);
        }
        if (captured == null) {
            return blockAtlas;
        }
        blockAtlas = captured;
        lastAtlas = atlas;
        lastAtlasGlId = atlas.getId();
        AntimonyMod.LOG.info("[Antimony] block atlas captured {}x{} from GL", captured.width, captured.height);
        return blockAtlas;
    }

    /**
     * Called once per rendered frame. Refreshes the animated block atlas about
     * five times per second and the lightmap every frame (both in place, so
     * every mesh holding the texture object keeps working).
     */
    public static void tick() {
        if (dev.antimony.AntimonyMod.flag("antimony.debug.lightmap") && ++lightmapDumpFrame % 120 == 0) {
            dumpLightmap();
        }
        // Phase 4a: the mirror is refreshed by vanilla's own _texSubImage2D
        // uploads and blockAtlas() re-copies it per frame, so the periodic
        // synchronous glGetTexImage read-back is only needed when the mirror
        // has never seen the atlas (fallback path active).
        if (blockAtlas != null && lastAtlasGlId <= 0 && ++tickCounter % ATLAS_REFRESH_FRAMES == 0) {
            refreshInPlace(InventoryMenu.BLOCK_ATLAS, blockAtlas);
        }
    }

    /**
     * The atlas is 1024x512 (2 MB); a full readback plus swizzle costs a
     * millisecond or two. Every second frame gives a 10 Hz animation at the
     * current frame rate - enough for fire/water/lava to read as motion - and
     * stays well under one percent of frame time.
     */
    private static final int ATLAS_REFRESH_FRAMES = 2;

    private static int lightmapDumpFrame;

    /**
     * Debug aid: writes the 16x16 lightmap to {@code antimony-shots/lightmap.png}
     * (scaled 16x) and logs its corners, so brightness/gamma problems can be
     * told apart from lightmap sampling bugs.
     */
    private static void dumpLightmap() {
        try {
            IrisGlTexture lm = lightmap();
            if (lm == null) {
                return;
            }
            int s = 16;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    lm.width * s, lm.height * s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < lm.height; y++) {
                for (int x = 0; x < lm.width; x++) {
                    int argb = lm.pixels[y * lm.width + x];
                    for (int dy = 0; dy < s; dy++) {
                        for (int dx = 0; dx < s; dx++) {
                            img.setRGB(x * s + dx, y * s + dy, argb);
                        }
                    }
                }
            }
            java.io.File dir = new java.io.File(Minecraft.getInstance().gameDirectory, "antimony-shots");
            dir.mkdirs();
            javax.imageio.ImageIO.write(img, "PNG", new java.io.File(dir, "lightmap.png"));
            AntimonyMod.LOG.info("[Antimony] lightmap dump: block0/sky0={} block15/sky0={} block0/sky15={} block15/sky15={}",
                    Integer.toHexString(lm.pixels[0]),
                    Integer.toHexString(lm.pixels[15]),
                    Integer.toHexString(lm.pixels[15 * lm.width]),
                    Integer.toHexString(lm.pixels[15 * lm.width + 15]));
        } catch (Throwable t) {
            AntimonyMod.LOG.warn("[Antimony] lightmap dump failed", t);
        }
    }

    /** Fallback: rebuild the atlas from each sprite's source image. */
    private static IrisGlTexture captureAtlasFromSprites(TextureAtlas atlas) {
        if (!atlasWarned) {
            atlasWarned = true;
            AntimonyMod.LOG.warn("[Antimony] GL atlas readback unavailable, falling back to sprite images");
        }
        try {
            int width = (int) field(TextureAtlas.class, "width").get(atlas);
            int height = (int) field(TextureAtlas.class, "height").get(atlas);
            @SuppressWarnings("unchecked")
            Map<ResourceLocation, TextureAtlasSprite> sprites =
                    (Map<ResourceLocation, TextureAtlasSprite>) field(TextureAtlas.class, "texturesByName").get(atlas);
            int[] pixels = new int[width * height];
            Field originalImage = field(SpriteContents.class, "originalImage");
            for (TextureAtlasSprite sprite : sprites.values()) {
                NativeImage image = (NativeImage) originalImage.get(sprite.contents());
                if (image == null) {
                    continue;
                }
                int sw = Math.min(image.getWidth(), sprite.contents().width());
                int sh = Math.min(image.getHeight(), sprite.contents().height());
                copySprite(image, pixels, width, height, sprite.getX(), sprite.getY(), sw, sh, 0, 0);
            }
            return new IrisGlTexture(width, height, pixels);
        } catch (ReflectiveOperationException e) {
            AntimonyMod.LOG.warn("[Antimony] failed to capture block atlas", e);
            return null;
        }
    }

    private static void copySprite(NativeImage image, int[] pixels, int atlasW, int atlasH,
                                   int ox, int oy, int sw, int sh, int srcOffX, int srcOffY) {
        for (int y = 0; y < sh; y++) {
            int dy = oy + y;
            if (dy < 0 || dy >= atlasH) {
                continue;
            }
            int row = dy * atlasW;
            for (int x = 0; x < sw; x++) {
                int dx = ox + x;
                if (dx < 0 || dx >= atlasW) {
                    continue;
                }
                pixels[row + dx] = argb(image.getPixelRGBA(srcOffX + x, srcOffY + y));
            }
        }
    }

    public static IrisGlTexture lightmap() {
        try {
            LightTexture lt = Minecraft.getInstance().gameRenderer.lightTexture();
            NativeImage image = lt == null ? null : (NativeImage) field(LightTexture.class, "lightPixels").get(lt);
            if (image == null) {
                // During shutdown or resource reload the native image can be
                // gone; a NPE here must not poison IrisGlRenderer.FAILED for
                // the rest of the session. A closed image throws inside the
                // copy loop below and is caught the same way.
                return lightmapCache;
            }
            int w = image.getWidth();
            int h = image.getHeight();
            if (lightmapCache == null || lightmapCache.width != w || lightmapCache.height != h) {
                lightmapCache = new IrisGlTexture(w, h, new int[w * h]);
            }
            int[] pixels = lightmapCache.pixels;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = argb(image.getPixelRGBA(x, y));
                }
            }
            return lightmapCache;
        } catch (Throwable t) {
            return lightmapCache;
        }
    }

    public static IrisGlTexture texture(ResourceLocation location) {
        if (location == null) {
            return null;
        }
        // The block atlas is the one texture vanilla re-uploads every tick for
        // animated sprites (fire, water, lava, portals, campfire, ...). Every
        // consumer must share the live, periodically refreshed instance: the
        // generic cache below would hand out a frozen snapshot, which is why a
        // burning mob's fire overlay was a single still frame and the campfire
        // never flickered.
        if (InventoryMenu.BLOCK_ATLAS.equals(location)) {
            IrisGlTexture live = blockAtlas();
            if (live != null) {
                return live;
            }
        }
        // Runtime-stitched atlases (particles) live only in GL memory; the
        // generic cache would freeze them at the first readback, which froze
        // campfire flame/smoke particles at their first animation frame.
        // Refresh those in place every few frames instead.
        if (location.getPath().startsWith("textures/atlas/")) {
            return liveAtlas(location);
        }
        boolean dynamic = location.getPath().matches("[a-z0-9_]+/\\d+");
        if (CACHE.containsKey(location)) {
            // NOTE: deliberately NO mirror refresh here. Mapping a
            // ResourceLocation to a live GL id and copying that id's mirror
            // into the cached copy corrupted every cached texture in practice
            // (the zombie skin sampled fire-atlas pixels, the hotbar widgets
            // sampled icon-sheet rows): several locations resolve to shared
            // GL texture objects, so the id -> location attribution is not
            // sound. The block atlas keeps its dedicated mirror path below.
            if (!dynamic || System.currentTimeMillis() - CACHE_TIME.getOrDefault(location, 0L) < 1000L) {
                return CACHE.get(location);
            }
        }
        IrisGlTexture texture = loadPng(location);
        if (texture == null) {
            texture = readGlTexture(location);
        }
        if (texture != null && REPEAT_TEXTURES.contains(location.getPath())) {
            texture.repeat();
        }
        synchronized (CACHE) {
            CACHE.put(location, texture);
            CACHE_TIME.put(location, System.currentTimeMillis());
        }
        return texture;
    }

    /**
     * Live view of a runtime-stitched atlas. Refreshes in place every
     * {@link #LIVE_ATLAS_REFRESH_FRAMES} frames from the best source: the
     * upload mirror when it tracks this texture (the truth while uploads are
     * absorbed), else a GL read-back.
     */
    private static IrisGlTexture liveAtlas(ResourceLocation location) {
        IrisGlTexture live = LIVE_ATLASES.get(location);
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location);
        int glId = texture == null ? 0 : texture.getId();
        if (glId <= 0) {
            return live;
        }
        dev.antimony.gl.hook.GlTexMirror.Mirror mirror = dev.antimony.gl.hook.GlTexMirror.mirror(glId);
        if (mirror != null) {
            if (live == null || live.width != mirror.width || live.height != mirror.height) {
                live = new IrisGlTexture(mirror.width, mirror.height,
                        java.util.Arrays.copyOf(mirror.pixels, mirror.pixels.length));
                LIVE_ATLASES.put(location, live);
            } else if (++liveAtlasRefresh % LIVE_ATLAS_REFRESH_FRAMES == 0) {
                System.arraycopy(mirror.pixels, 0, live.pixels, 0, live.pixels.length);
            }
            return live;
        }
        // Mirror miss: read back through GL, sized on the fly. glGetTexImage
        // queries the *bound* texture, so bind it around the whole branch.
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
        try {
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (w <= 0 || h <= 0) {
                return live;
            }
            if (live == null || live.width != w || live.height != h) {
                live = new IrisGlTexture(w, h, new int[w * h]);
                LIVE_ATLASES.put(location, live);
            } else if (++liveAtlasRefresh % LIVE_ATLAS_REFRESH_FRAMES == 0) {
                refreshInPlace(location, live);
            }
            return live;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    public static void rememberShaderTexture(int unit, ResourceLocation location) {
        if (unit >= 0 && unit < SHADER_TEXTURES.length) {
            SHADER_TEXTURES[unit] = location;
            SHADER_TEXTURE_IDS[unit] = 0;
        }
    }

    public static void rememberShaderTexture(int unit, int textureId) {
        if (unit >= 0 && unit < SHADER_TEXTURES.length) {
            SHADER_TEXTURES[unit] = null;
            SHADER_TEXTURE_IDS[unit] = textureId;
        }
    }

    /** Raw bindings are consumed only from captured CPU pixels, never from glGetTexImage. */
    public static IrisGlTexture shaderTexture(int unit) {
        if (unit < 0 || unit >= SHADER_TEXTURES.length) return null;
        int id = SHADER_TEXTURE_IDS[unit];
        if (id > 0) {
            IrisGlTexture captured = CpuDynamicTextures.texture(id);
            if (captured == null)
                throw new IllegalStateException("Raw shader texture " + id + " has no captured CPU pixels");
            return captured;
        }
        return texture(SHADER_TEXTURES[unit]);
    }

    public static ResourceLocation lastShaderTexture(int unit) {
        return unit >= 0 && unit < SHADER_TEXTURES.length ? SHADER_TEXTURES[unit] : null;
    }

    private static IrisGlTexture loadPng(ResourceLocation location) {
        try {
            ResourceLocation file = location.getPath().startsWith("textures/")
                    ? location
                    : ResourceLocation.fromNamespaceAndPath(location.getNamespace(), "textures/" + location.getPath() + ".png");
            Resource resource = Minecraft.getInstance().getResourceManager().getResource(file).orElse(null);
            if (resource == null) {
                return null;
            }
            try (InputStream in = resource.open(); NativeImage image = NativeImage.read(in)) {
                int w = image.getWidth();
                int h = image.getHeight();
                int[] pixels = new int[w * h];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        pixels[y * w + x] = argb(image.getPixelRGBA(x, y));
                    }
                }
                return new IrisGlTexture(w, h, pixels);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Reused native scratch for atlas readback (2 MB twice a second). */
    private static IntBuffer readbackBuffer;

    /** Re-reads a GL texture into an existing CPU texture, without allocating. */
    private static boolean refreshInPlace(ResourceLocation location, IrisGlTexture target) {
        try {
            AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location);
            int id = texture.getId();
            if (id <= 0) {
                return false;
            }
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            try {
                // glGetTexImage fills the LIVE texture's full extent while the
                // cached target and the readback scratch were sized at capture
                // time. A re-stitch that grows the atlas on the same instance
                // would overflow the scratch, so verify the size first and
                // drop the snapshot when it moved (next blockAtlas() call
                // recaptures at the new size).
                int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (w != target.width || h != target.height) {
                    if (InventoryMenu.BLOCK_ATLAS.equals(location)) {
                        blockAtlas = null;
                        lastAtlas = null;
                    }
                    return false;
                }
                int[] pixels = target.pixels;
                if (readbackBuffer == null || readbackBuffer.capacity() < pixels.length) {
                    if (readbackBuffer != null) {
                        MemoryUtil.memFree(readbackBuffer);
                    }
                    readbackBuffer = MemoryUtil.memAllocInt(pixels.length);
                }
                IntBuffer buf = readbackBuffer;
                buf.clear();
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
                for (int i = 0; i < pixels.length; i++) {
                    pixels[i] = argb(buf.get(i));
                }
                return true;
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static IrisGlTexture readGlTexture(ResourceLocation location) {
        try {
            AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location);
            int id = texture.getId();
            if (id <= 0) {
                return null;
            }
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            try {
                int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (w <= 0 || h <= 0) {
                    return null;
                }
                int internal = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                int[] pixels = new int[w * h];
                if (internal == GL30.GL_R8 || internal == GL11.GL_RED) {
                    // Single-channel font pages: vanilla's font shader uses the
                    // red channel as alpha with white ink. MC creates them with
                    // the unsized GL_RED format, so accept both here.
                    java.nio.ByteBuffer red = MemoryUtil.memAlloc(w * h);
                    try {
                        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL30.GL_RED, GL11.GL_UNSIGNED_BYTE, red);
                        for (int i = 0; i < w * h; i++) {
                            int v = red.get(i) & 0xFF;
                            pixels[i] = (v << 24) | 0xFFFFFF;
                        }
                    } finally {
                        MemoryUtil.memFree(red);
                    }
                } else {
                    IntBuffer rgba = MemoryUtil.memAllocInt(w * h);
                    try {
                        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
                        for (int i = 0; i < w * h; i++) {
                            pixels[i] = argb(rgba.get(i));
                        }
                    } finally {
                        MemoryUtil.memFree(rgba);
                    }
                }
                return new IrisGlTexture(w, h, pixels);
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            }
        } catch (Throwable t) {
            AntimonyMod.LOG.warn("[Antimony] texture readback failed for {}", location, t);
            return null;
        }
    }

    public static void clear() {
        CACHE.clear();
        LIVE_ATLASES.clear();
        blockAtlas = null;
        lastAtlas = null;
        lightmapCache = null;
    }

    private static int argb(int abgr) {
        int a = (abgr >>> 24) & 0xFF;
        int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
