package dev.antimony.core.render;

import dev.antimony.AntimonyMod;
import dev.antimony.core.irisgl.IrisGlTexture;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/** Optional, version-scoped adapter. Uses Xaero's own CPU safe-mode map/radar algorithm. */
public final class XaeroCpuCompatibility {
    public static final String VERIFIED_VERSION = "26.6.0";
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();
    private static final AtomicLong WRITER_CALLS = new AtomicLong();
    private static final boolean DIAGNOSTIC = AntimonyMod.flag("antimony.xaero.diag");
    private static long lastSeenWriterCalls, mirroredWriterCalls;
    private static Object depthClearType;
    private static boolean writerFailureReported, mapEvidenceFailed;
    private static IrisGlTexture diagnosticMap;
    private static int diagnosticMapId, mapEvidenceCount;
    private static long nextEvidenceUpload;
    private static boolean uploadedThisFrame;

    private XaeroCpuCompatibility() { }

    public static boolean supportsVersion(String version) { return VERIFIED_VERSION.equals(version); }

    public static boolean supportedInstallation() {
        ModList mods = ModList.get();
        return mods != null && mods.getModContainerById("xaerominimap")
                .map(mod -> supportsVersion(mod.getModInfo().getVersion().toString())).orElse(false);
    }

    /** Stable for the whole CPU session, including pre-frame writer callbacks and loading transitions. */
    public static boolean useSafeMode() {
        return dev.antimony.gl.GlCompat.intercepting() && supportedInstallation();
    }

    public static boolean active() {
        return useSafeMode() && IrisGlRenderer.shouldReplace();
    }

    public static void announce() {
        if (ANNOUNCED.compareAndSet(false, true))
            AntimonyMod.LOG.info("[Antimony] Xaero {} CPU safe-mode adapter active: map/coordinates/waypoints, radar dots; no 3D radar icons, no GPU map readback, Xaero options unchanged", VERIFIED_VERSION);
    }

    public static void writerObserved() { WRITER_CALLS.incrementAndGet(); }

    /** Read-only evidence: distinguish scheduling, world data, and publication failures. */
    public static void writerState(Object writer) {
        long calls = WRITER_CALLS.get();
        if (!DIAGNOSTIC || !useSafeMode() || (calls > 12 && calls % 10 != 0)) return;
        try {
            Class<?> type = Class.forName("xaero.common.minimap.write.MinimapWriter");
            var state = new StringBuilder("calls=").append(calls);
            for (String name : new String[]{"previousShouldLoad", "workingFrameCount", "framesFreedTime",
                    "writeFreeSinceLastWrite", "lastWrite", "lastWriteTry", "writeFreeSizeTiles",
                    "writeFreeFullUpdateTargetTime", "loadingSideInChunks", "loadedSideInChunks",
                    "loadingLevels", "loadedLevels", "loadingCaving", "loadedCaving", "loadingNonWorldMap",
                    "loadedNonWorldMap", "updateChunkX", "updateChunkZ", "tileInsideX", "tileInsideZ", "runNumber"}) {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                state.append(' ').append(name).append('=').append(field.get(writer));
            }
            for (String name : new String[]{"loadingBlocks", "loadedBlocks"}) {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                Object[][] grid = (Object[][]) field.get(writer);
                int chunks = 0, tiles = 0;
                if (grid != null) for (Object[] row : grid) if (row != null) for (Object chunk : row) if (chunk != null) {
                    chunks++;
                    var getTile = chunk.getClass().getMethod("getTile", int.class, int.class);
                    for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++)
                        if (getTile.invoke(chunk, x, z) != null) tiles++;
                }
                state.append(' ').append(name).append('=').append(grid == null ? "null" : chunks + "chunks/" + tiles + "tiles");
            }
            AntimonyMod.LOG.info("[Antimony] Xaero writer state {}", state);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            AntimonyMod.LOG.warn("[Antimony] Xaero writer diagnostic unavailable; rendering continues", failure);
        }
    }

    /** Supplies a missing RenderFrameEvent.Pre update only; skips it when the original event ran. */
    public static void updateMap() {
        uploadedThisFrame = false;
        if (!active()) return;
        announce();
        try {
            long observed = WRITER_CALLS.get();
            if (observed == lastSeenWriterCalls) {
                Class<?> sessions = Class.forName("xaero.common.XaeroMinimapSession");
                Object session = sessions.getMethod("getCurrentSession").invoke(null);
                if (session != null) {
                    Object processor = session.getClass().getMethod("getMinimapProcessor").invoke(session);
                    Object writer = processor.getClass().getMethod("getMinimapWriter").invoke(processor);
                    writer.getClass().getMethod("onRender").invoke(writer);
                    mirroredWriterCalls++;
                    if (mirroredWriterCalls == 1)
                        AntimonyMod.LOG.info("[Antimony] Xaero CPU map-update lifecycle mirrored; original writer, unchanged cave/server rules, no duplicate update when the native event already ran");
                }
            }
            lastSeenWriterCalls = WRITER_CALLS.get();
        } catch (ReflectiveOperationException | LinkageError failure) {
            if (!writerFailureReported) {
                writerFailureReported = true;
                AntimonyMod.LOG.error("[Antimony] Xaero CPU map update failed; no GPU fallback", failure);
            }
        }
    }

    /** The verified depth-clear layer writes far depth and has an all-false color mask. */
    public static boolean isDepthClear(Object type) {
        if (!active()) return false;
        try {
            if (depthClearType == null)
                depthClearType = Class.forName("xaero.common.graphics.CustomRenderTypes").getField("DEPTH_CLEAR").get(null);
            return type == depthClearType;
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException("Verified Xaero depth-clear layer unavailable", unavailable);
        }
    }

    /** Defers evidence until this upload is actually consumed by a completed CPU GUI frame. */
    public static void uploaded(int id, IrisGlTexture texture) {
        announce();
        if (!DIAGNOSTIC || mapEvidenceFailed || mapEvidenceCount >= 3) return;
        diagnosticMap = texture;
        diagnosticMapId = id;
        uploadedThisFrame = true;
    }

    /** Optional paired map/display/F2 evidence. Diagnostic failure never stops rendering. */
    public static void completedFrame(Minecraft mc, dev.antimony.core.irisgl.IrisGlFramebuffer frame) {
        if (!DIAGNOSTIC || mapEvidenceFailed || mapEvidenceCount >= 3 || !uploadedThisFrame
                || diagnosticMap == null || CpuDynamicTextures.uploads() < nextEvidenceUpload) return;
        try {
            IrisGlTexture texture = diagnosticMap;
            var colors = new HashSet<Integer>();
            int visible = 0;
            for (int color : texture.pixels) {
                int rgb = color & 0xffffff;
                colors.add(rgb);
                if (((rgb >>> 16) & 255) > 8 || ((rgb >>> 8) & 255) > 8 || (rgb & 255) > 8) visible++;
            }
            if (colors.size() < 2 || visible < 16) return;
            var game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
            if (!game.toString().contains("validation-instances"))
                throw new IllegalStateException("Xaero evidence is allowed only in an isolated instance");
            var directory = game.resolve("antimony-shots");
            Files.createDirectories(directory);
            int sample = mapEvidenceCount + 1;
            String stem = "xaero-cpu-" + sample;
            var image = new BufferedImage(texture.width, texture.height, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, texture.width, texture.height, texture.pixels, 0, texture.width);
            ImageIO.write(image, "PNG", directory.resolve(stem + "-map.png").toFile());
            ImageIO.write(dev.antimony.core.present.CpuScreenImage.rgb(frame), "PNG", directory.resolve(stem + "-screen.png").toFile());
            try (var f2 = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                if (f2.getWidth() != frame.width || f2.getHeight() != frame.height)
                    throw new IllegalStateException("Xaero F2 extent mismatch");
                for (int y = 0; y < frame.height; y++) for (int x = 0; x < frame.width; x++)
                    if (f2.getPixelRGBA(x, y) != dev.antimony.core.present.CpuScreenImage.opaqueAbgr(frame.color[y * frame.width + x]))
                        throw new IllegalStateException("Xaero F2 differs from CPU display at " + x + "," + y);
                f2.writeToFile(directory.resolve(stem + "-f2.png"));
            }
            Files.writeString(directory.resolve(stem + ".txt"),
                    "version=" + VERIFIED_VERSION + " texture=" + diagnosticMapId + " size=" + texture.width + "x" + texture.height
                    + " uniqueColors=" + colors.size() + " visibleTexels=" + visible + " uploads=" + CpuDynamicTextures.uploads()
                    + " writerCalls=" + WRITER_CALLS.get() + " mirroredWriterCalls=" + mirroredWriterCalls
                    + " frame=" + (IrisGlRenderer.frameCount() + 1) + " gameTime=" + mc.level.getGameTime()
                    + " source=Xaero-safe-mode-RGB-bytes GPU-readback=false config-written=false radar=dots F2=display-exact\n");
            mapEvidenceCount = sample;
            nextEvidenceUpload = CpuDynamicTextures.uploads() + 10;
            if (sample == 3) diagnosticMap = null;
            AntimonyMod.LOG.info("[Antimony] Xaero CPU map/display evidence sample={} {}x{} uniqueColors={} visibleTexels={} upload={} F2=display-exact GPU-readback=false",
                    sample, texture.width, texture.height, colors.size(), visible, CpuDynamicTextures.uploads());
        } catch (Exception | LinkageError failure) {
            mapEvidenceFailed = true;
            diagnosticMap = null;
            AntimonyMod.LOG.warn("[Antimony] Xaero CPU map evidence failed; CPU rendering continues", failure);
        }
    }
}
