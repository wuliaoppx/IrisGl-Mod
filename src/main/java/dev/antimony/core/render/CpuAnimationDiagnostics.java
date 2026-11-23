package dev.antimony.core.render;

import dev.antimony.AntimonyMod;
import dev.antimony.core.irisgl.IrisGlFramebuffer;
import dev.antimony.core.present.CpuScreenImage;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

/** Opt-in, read-only evidence: actual CPU atlas texels, real ticks, PNG and F2 output. */
public final class CpuAnimationDiagnostics {
    private static final boolean ENABLED = AntimonyMod.flag("antimony.animation.diag");
    private static final ResourceLocation FIRE = ResourceLocation.withDefaultNamespace("block/campfire_fire");
    private static int completed;
    private static boolean notificationsChecked;
    private CpuAnimationDiagnostics() { }

    /** No block placement, time changes, camera control or artificial texture ticks. */
    public static void completedFrame(Minecraft mc, IrisGlFramebuffer fb) {
        if (!ENABLED || completed >= 6 || mc.level == null || mc.player == null) return;
        int sample = ++completed;
        try {
            TextureAtlas atlas = (TextureAtlas) mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS);
            var sprite = atlas.getSprite(FIRE);
            var live = IrisGlTextures.blockAtlas();
            var mirror = dev.antimony.gl.hook.GlTexMirror.mirror(atlas.getId());
            if (live == null || mirror == null || !sprite.contents().name().equals(FIRE))
                throw new IllegalStateException("Actual campfire sprite/mirror unavailable");
            int width = sprite.contents().width(), height = sprite.contents().height();
            long cpuHash = 0xcbf29ce484222325L, mirrorHash = cpuHash;
            var spriteImage = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int at = (sprite.getY() + y) * live.width + sprite.getX() + x;
                int color = live.pixels[at];
                cpuHash = (cpuHash ^ Integer.toUnsignedLong(color)) * 0x100000001b3L;
                mirrorHash = (mirrorHash ^ Integer.toUnsignedLong(mirror.pixels[at])) * 0x100000001b3L;
                spriteImage.setRGB(x, y, color);
            }
            if (cpuHash != mirrorHash) throw new IllegalStateException("CPU atlas is stale relative to upload mirror");
            Path directory = mc.gameDirectory.toPath().resolve("antimony-shots");
            Files.createDirectories(directory);
            Path screen = directory.resolve("animation-" + sample + "-screen.png");
            javax.imageio.ImageIO.write(CpuScreenImage.rgb(fb), "PNG", screen.toFile());
            javax.imageio.ImageIO.write(spriteImage, "PNG", directory.resolve("animation-" + sample + "-campfire-texels.png").toFile());
            try (var f2 = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                if (f2.getWidth() != fb.width || f2.getHeight() != fb.height) throw new IllegalStateException("F2 size mismatch");
                for (int y = 0; y < fb.height; y++) for (int x = 0; x < fb.width; x++)
                    if (f2.getPixelRGBA(x, y) != CpuScreenImage.opaqueAbgr(fb.color[y * fb.width + x]))
                        throw new IllegalStateException("F2 differs from CPU display pixel at " + x + "," + y);
                if (sample == 1) f2.writeToFile(directory.resolve("animation-f2.png"));
            }
            AntimonyMod.LOG.info("[Antimony] CPU animation sample={} gameTime={} playerTick={} paused={} sprite={} cpuHash={} mirrorHash={} cycles={} marks={} uploads={} absorbed={} F2=opaque/display-exact screenshot={}",
                    sample, mc.level.getGameTime(), mc.player.tickCount, mc.isPaused(), FIRE,
                    Long.toUnsignedString(cpuHash, 16), Long.toUnsignedString(mirrorHash, 16),
                    SodiumSpriteAnimationBridge.cycles(), SodiumSpriteAnimationBridge.marks(),
                    dev.antimony.gl.hook.GlTexMirror.mirroredUploads(), dev.antimony.gl.hook.GlTexMirror.absorbedUploads(), screen);
            checkNotifications(mc);
        } catch (Exception | LinkageError failure) {
            AntimonyMod.LOG.error("[Antimony] CPU animation diagnostic failed sample=" + sample, failure);
        }
    }

    private static void checkNotifications(Minecraft mc) {
        if (notificationsChecked) return;
        notificationsChecked = true;
        var pos = mc.player.blockPosition();
        long key = net.minecraft.core.SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        long before = MeshBuildPool.revision(key);
        mc.levelRenderer.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
        long afterArea = MeshBuildPool.revision(key);
        mc.levelRenderer.setSectionDirtyWithNeighbors(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        long afterNeighbors = MeshBuildPool.revision(key);
        if (afterArea <= before || afterNeighbors <= afterArea)
            throw new IllegalStateException("Runtime transformed dirty hooks did not invalidate CPU mesh");
        AntimonyMod.LOG.info("[Antimony] CPU dirty runtime probe section={} revision {} -> {} -> {}; actual LevelRenderer area/neighbors methods, notification only, block states unchanged",
                key, before, afterArea, afterNeighbors);
    }
}
