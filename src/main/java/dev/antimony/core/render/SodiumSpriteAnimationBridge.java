package dev.antimony.core.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Re-enables Sodium's animated-sprite uploads while the CPU renderer samples the mirrored atlas. */
public final class SodiumSpriteAnimationBridge {
    private static final Map<TextureAtlas,List<TextureAtlasSprite>> ANIMATED = new WeakHashMap<>();
    private static java.lang.reflect.Method hasAnimation;
    private static java.lang.reflect.Method markActive;
    private static Object spriteUtil;
    private static boolean lookupDone;
    private static boolean warned;
    private static long cycles, marks;
    public static long cycles() { return cycles; }
    public static long marks() { return marks; }

    private SodiumSpriteAnimationBridge() { }

    /** Called at TextureAtlas.cycleAnimationFrames HEAD, before Sodium's ticker HEAD hook. */
    public static void markAnimatedSprites(TextureAtlas atlas) {
        if (atlas == null || !dev.antimony.gl.GlCompat.intercepting()
                || !IrisGlRenderer.shouldReplace()) return;
        try {
            initializeApi();
            if (hasAnimation == null) return;
            List<TextureAtlasSprite> animated = ANIMATED.get(atlas);
            if (animated == null) {
                animated = discover(atlas);
                ANIMATED.put(atlas, animated);
            }
            for (TextureAtlasSprite sprite : animated) markActive.invoke(spriteUtil, sprite);
            cycles++;
            marks += animated.size();
        } catch (Throwable failure) {
            if (!warned) {
                warned = true;
                dev.antimony.AntimonyMod.LOG.warn("[Antimony] could not mark CPU-sampled Sodium atlas sprites active", failure);
            }
        }
    }

    private static void initializeApi() throws ReflectiveOperationException {
        if (lookupDone) return;
        lookupDone = true;
        ClassLoader loader = SodiumSpriteAnimationBridge.class.getClassLoader();
        Class<?> api;
        try { api = Class.forName("net.caffeinemc.mods.sodium.api.texture.SpriteUtil", false, loader); }
        catch (ClassNotFoundException absent) { return; }
        spriteUtil = api.getField("INSTANCE").get(null);
        hasAnimation = api.getMethod("hasAnimation", TextureAtlasSprite.class);
        markActive = api.getMethod("markSpriteActive", TextureAtlasSprite.class);
    }

    private static List<TextureAtlasSprite> discover(TextureAtlas atlas) throws ReflectiveOperationException {
        java.lang.reflect.Field field = null;
        for (Class<?> type = atlas.getClass(); type != null && field == null; type = type.getSuperclass()) {
            try { field = type.getDeclaredField("texturesByName"); }
            catch (NoSuchFieldException ignored) { }
        }
        if (field == null) throw new NoSuchFieldException("TextureAtlas.texturesByName");
        field.setAccessible(true);
        Object value = field.get(atlas);
        if (!(value instanceof Map<?,?> sprites)) throw new IllegalStateException("Texture atlas sprite map unavailable");
        List<TextureAtlasSprite> animated = new ArrayList<>();
        for (Object candidate : sprites.values()) {
            if (candidate instanceof TextureAtlasSprite sprite && Boolean.TRUE.equals(hasAnimation.invoke(spriteUtil, sprite)))
                animated.add(sprite);
        }
        return List.copyOf(animated);
    }

    public static void invalidate(TextureAtlas atlas) {
        ANIMATED.remove(atlas);
    }

}
