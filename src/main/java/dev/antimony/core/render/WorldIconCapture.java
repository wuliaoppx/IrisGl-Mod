package dev.antimony.core.render;

import dev.antimony.AntimonyMod;
import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlFramebuffer;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;

/**
 * Saves the world's {@code icon.png} (the little preview in the world list).
 *
 * <p>Vanilla does this in {@code GameRenderer.tryTakeScreenshotIfNeeded()},
 * which is called from {@code GameRenderer.renderLevel()} - and that is the
 * method this mod replaces wholesale. So the icon was never captured: vanilla's
 * own gate ({@code LevelRenderer.countRenderedSections() > 10 &&
 * hasRenderedAllSections()}) can never be reached either, because
 * {@code LevelRenderer} does not render anything any more.</p>
 *
 * <p>This mirrors the vanilla behaviour on top of the software framebuffer:
 * at most one attempt per second, only in singleplayer, only when the file is
 * still missing, and only once the world is actually on screen. The image is
 * centre-cropped to a square and box-reduced to 64x64 exactly like vanilla's
 * {@code NativeImage.resizeSubRectTo} path.</p>
 */
public final class WorldIconCapture {
    /** Vanilla retries once per second while the icon is missing. */
    private static final long RETRY_INTERVAL_MS = 1000L;
    /** Vanilla requires more than 10 rendered sections. */
    private static final int MIN_SECTIONS = 11;
    private static final int ICON_SIZE = 64;
    /** -Dantimony.debug.icon: log every capture attempt and why it was skipped. */
    private static final boolean DEBUG = dev.antimony.AntimonyMod.flag("antimony.debug.icon");

    private static ClientLevel watchedLevel;
    private static long lastAttempt;
    private static long firstSeen;
    private static boolean captured;
    /** Vanilla hasRenderedAllSections(): with staged software meshing the world
     * never fully settles for minutes, so the icon just needs a settled view. */
    private static final long SETTLE_DELAY_MS = 4000L;

    private WorldIconCapture() {
    }

    public static void tick(Minecraft mc, IrisGlFramebuffer fb, WorldRenderer world) {
        if (watchedLevel != mc.level) {
            // New world (or left the old one): vanilla resets the flag too.
            watchedLevel = mc.level;
            captured = false;
            lastAttempt = 0L;
            firstSeen = Util.getMillis();
        }
        if (captured || watchedLevel == null || fb == null) {
            return;
        }
        if (!mc.isLocalServer()) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || server.isStopped()) {
            return;
        }
        long now = Util.getMillis();
        if (now - lastAttempt < RETRY_INTERVAL_MS) {
            return;
        }
        lastAttempt = now;
        var iconFile = server.getWorldScreenshotFile();
        if (iconFile.isEmpty()) {
            return;
        }
        Path path = iconFile.get();
        if (Files.isRegularFile(path)) {
            captured = true;
            return;
        }
        if (now - firstSeen < SETTLE_DELAY_MS || world.visibleSections() < MIN_SECTIONS) {
            if (DEBUG) {
                AntimonyMod.LOG.info("[Antimony] world icon: waiting (visible={}, age={}ms)",
                        world.visibleSections(), now - firstSeen);
            }
            return;
        }
        // Every binned triangle must be in fb.color before we sample it.
        IrisGl.flushRaster();
        write(fb, path);
    }

    private static void write(IrisGlFramebuffer fb, Path path) {
        try {
            int w = fb.width;
            int h = fb.height;
            int size = Math.min(w, h);
            int ox = (w - size) / 2;
            int oy = (h - size) / 2;
            BufferedImage out = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < ICON_SIZE; y++) {
                int y0 = oy + y * size / ICON_SIZE;
                int y1 = oy + (y + 1) * size / ICON_SIZE;
                if (y1 <= y0) {
                    y1 = y0 + 1;
                }
                for (int x = 0; x < ICON_SIZE; x++) {
                    int x0 = ox + x * size / ICON_SIZE;
                    int x1 = ox + (x + 1) * size / ICON_SIZE;
                    if (x1 <= x0) {
                        x1 = x0 + 1;
                    }
                    long r = 0L;
                    long g = 0L;
                    long b = 0L;
                    int n = 0;
                    for (int sy = y0; sy < y1 && sy < h; sy++) {
                        int row = sy * w;
                        for (int sx = x0; sx < x1 && sx < w; sx++) {
                            int c = fb.color[row + sx];
                            r += (c >>> 16) & 0xFF;
                            g += (c >>> 8) & 0xFF;
                            b += c & 0xFF;
                            n++;
                        }
                    }
                    if (n == 0) {
                        n = 1;
                    }
                    int rr = (int) (r / n);
                    int gg = (int) (g / n);
                    int bb = (int) (b / n);
                    out.setRGB(x, y, 0xFF000000 | (rr << 16) | (gg << 8) | bb);
                }
            }
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ImageIO.write(out, "PNG", path.toFile());
            captured = true;
            AntimonyMod.LOG.info("[Antimony] world icon saved to {}", path);
        } catch (Throwable t) {
            AntimonyMod.LOG.warn("[Antimony] world icon capture failed", t);
        }
    }
}
