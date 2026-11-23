package dev.antimony.core.render;

import dev.antimony.AntimonyMod;
import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlFramebuffer;
import dev.antimony.core.render.ImmediateDraw;
import dev.antimony.core.test.DebugScenario;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.core.BlockPos;

/**
 * Replaces the whole frame: IRIS GL software rasterizer -> CPU framebuffer -> GDI window.
 *
 * <p>The software framebuffer always matches the OS window size (no 640x360
 * cap). That keeps vanilla's GUI coordinate space identical to ours, so every
 * screen lays out, draws and hit-tests at exactly the same pixels and mouse
 * clicks land where the game drew them.</p>
 */
public final class IrisGlRenderer {
    /** Fallback dimensions until the first real window size is known. */
    private static final int FALLBACK_WIDTH = 640;
    private static final int FALLBACK_HEIGHT = 360;
    /** Safety cap for pathological window sizes (nothing valid exceeds this). */
    private static final int MAX_DIMENSION = 8192;

    private static final WorldRenderer WORLD = new WorldRenderer();
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static boolean started;
    private static long frames;
    private static long profiledShaderFrames;
    private static final boolean PROFILE=AntimonyMod.flag("antimony.profile");
    private static Object terrainDiagnosticPipeline;

    public static String startupSceneDetail() {
        return WORLD.startupSceneDetail();
    }

    /** Source capture only: terrain rendering is not enabled by this diagnostic. */
    private static void captureTerrainSources(Minecraft mc) {
        if (!"on".equalsIgnoreCase(System.getProperty("antimony.terrain.diag", "off"))) return;
        try {
            Object pipeline = dev.antimony.gl.glsl.IrisTerrainSources.currentPipeline();
            if (pipeline == null || pipeline == terrainDiagnosticPipeline) return;
            terrainDiagnosticPipeline = pipeline;
            var capture = dev.antimony.gl.glsl.IrisTerrainSources.captureTerrainCurrent();
            if (capture.isEmpty()) return;
            for (var source : capture.get().sources()) {
                AntimonyMod.LOG.info("[Antimony] terrain-source identity={} vertexChars={} fragmentChars={} extraStages={}",
                    source.identity(), source.vertex().length(), source.fragment().length(), source.hasExtraVertexStages());
            }
            try {
                var destination = dev.antimony.gl.glsl.IrisTerrainSources.dump(capture.get(), mc.gameDirectory.toPath());
                AntimonyMod.LOG.info("[Antimony] terrain-source dump={}", destination);
            } catch (Exception failure) {
                AntimonyMod.LOG.warn("[Antimony] terrain source dump failed; rendering continues", failure);
            }
            var blockEntities=dev.antimony.gl.glsl.IrisTerrainSources.captureBlockEntitiesCurrent();
            if(blockEntities.isPresent()) {
                var destination=dev.antimony.gl.glsl.IrisTerrainSources.dump(blockEntities.get(),mc.gameDirectory.toPath());
                AntimonyMod.LOG.info("[Antimony] block-entity source dump={}",destination);
            }
            var shadow=dev.antimony.gl.glsl.IrisTerrainSources.captureShadowCurrent();
            var manifestPrograms=new java.util.ArrayList<dev.antimony.gl.glsl.CpuShaderConversionManifest.ProgramResult>();
            capture.get().sources().forEach(source->manifestPrograms.add(dev.antimony.gl.glsl.CpuShaderConversionManifest.preflight(source)));
            if(shadow.isPresent()){
                var destination=dev.antimony.gl.glsl.IrisTerrainSources.dump(shadow.get(),mc.gameDirectory.toPath());
                AntimonyMod.LOG.info("[Antimony] shadow-source dump={} scope=source-only; no voxel production",destination);
                int id=1_600_000_001;
                for(var source:shadow.get().sources()){
                    AntimonyMod.LOG.info("[Antimony] shadow-source identity={} vertexChars={} fragmentChars={} extraStages={}",source.identity(),source.vertex().length(),source.fragment().length(),source.hasExtraVertexStages());
                    var loaded=dev.antimony.gl.glsl.GlslProgramLoader.loadVertex(id,source.vertex());
                    AntimonyMod.LOG.info("[Antimony] shadow-source CPU preflight key={} compiled={} reason={}; no vertex dispatch/image writes",source.identity().shaderKey(),loaded!=null,dev.antimony.gl.glsl.GlslProgramLoader.vertexFailure(id++));
                    manifestPrograms.add(dev.antimony.gl.glsl.CpuShaderConversionManifest.preflight(source));
                }
            }
            var first=capture.get().sources().get(0).identity();
            var manifest=new dev.antimony.gl.glsl.CpuShaderConversionManifest.Manifest(first.packName(),first.irisVersion(),first.dimension(),first.transform(),first.pipeline(),first.programSet(),first.resolver(),false,false,manifestPrograms);
            var manifestPath=dev.antimony.gl.glsl.CpuShaderConversionManifest.write(manifest,mc.gameDirectory.toPath());
            AntimonyMod.LOG.info("[Antimony] CPU conversion manifest={} programs={} originalPackModified=false executable=false",manifestPath,manifestPrograms.size());
        } catch (Exception | LinkageError failure) {
            AntimonyMod.LOG.warn("[Antimony] terrain/shadow source capture failed; rendering continues", failure);
        }
    }
    private static int fbWidth = FALLBACK_WIDTH;
    private static int fbHeight = FALLBACK_HEIGHT;
    /** Frames to save as PNG; override with -Dantimony.demo.shots=60,120,... */
    private static final java.util.Set<Long> SCREENSHOT_FRAMES = parseFrames(
            System.getProperty("antimony.demo.shots", "60,120,300,600"));

    private static java.util.Set<Long> parseFrames(String spec) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (String part : spec.split(",")) {
            try {
                out.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // skip malformed entries
            }
        }
        return out;
    }

    private IrisGlRenderer() {
    }

    /** Camera rotation matrix (world -> view directions), set every frame. */
    private static final org.joml.Matrix4f CAMERA_ROTATION = new org.joml.Matrix4f();
    /** Camera world position, remembered for passes that re-derive it. */
    private static volatile Vec3 cameraPos = Vec3.ZERO;

    public static org.joml.Matrix4f lastCameraRotation() {
        return CAMERA_ROTATION;
    }

    public static Vec3 lastCameraPos() {
        return cameraPos;
    }

    /** Package hook for WorldRenderer to record the per-frame camera position. */
    static void cameraPosField(Vec3 pos) {
        cameraPos = pos;
    }

    public static boolean shouldReplace() {
        // A CPU renderer failure must not silently re-enable the native world/shader path.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.noRender || mc.gameRenderer == null) {
            return false;
        }
        // Must not run while the window is (being) torn down: the main render
        // target and input state are gone and drawing would throw.
        if (mc.getWindow() == null || mc.getWindow().getWidth() <= 0) {
            return false;
        }
        return true;
    }

    public static void render(GameRenderer gameRenderer, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getCameraEntity()==null) {
            dev.antimony.gl.glsl.IrisPostChain.invalidateDepthFrame();
            dev.antimony.core.present.ScreenPresenter.frameTick(false);
            return;
        }
        if(FAILED.get()){renderFailureFrame(mc);return;}
        long frameStart=System.nanoTime();
        try {
            RenderQualitySettings.ensureLoaded(mc.gameDirectory.toPath());
            RenderQualitySettings.applyPending();
            // Vanilla's renderLevel applies queued light-packet data every
            // frame (pollLightUpdates + runLightUpdates, LevelRenderer.java
            // "light_update_queue"/"light_updates"). Our mixin replaces
            // GameRenderer.render wholesale, so without mirroring these two
            // calls the client light engine's queued section data was never
            // swapped in and propagated: block light read as 0 everywhere and
            // torches/glowstone never lit their surroundings. Must run before
            // the chunk-update drain so remeshes see the fresh light values.
            mc.level.pollLightUpdates();
            mc.level.getChunkSource().getLightEngine().runLightUpdates();
            int w = mc.getWindow().getWidth();
            int h = mc.getWindow().getHeight();
            if (w <= 0 || h <= 0) {
                w = FALLBACK_WIDTH;
                h = FALLBACK_HEIGHT;
            }
            w = Math.min(w, MAX_DIMENSION);
            h = Math.min(h, MAX_DIMENSION);
            if (w != fbWidth || h != fbHeight) {
                fbWidth = w;
                fbHeight = h;
                AntimonyMod.LOG.info("[Antimony] software framebuffer is now {}x{}", fbWidth, fbHeight);
            }
            if (!started) {
                started = true;
                AntimonyMod.LOG.info("[Antimony] software renderer active: {}x{} (window-sized)", fbWidth, fbHeight);
            }
            dev.antimony.core.win32.SplashOverlay.updateStage(3, "CPU 软光栅器运行中 " + fbWidth + "x" + fbHeight);
            if (mc.screen instanceof ReceivingLevelScreen) {
                mc.setScreen(null);
            }
            if (mc.getOverlay() != null) {
                mc.setOverlay(null);
            }
            SceneFramebufferScaler.beginScene(IrisGl.ctx(),fbWidth,fbHeight,RenderQualitySettings.active());
            // Animated block textures (campfire fire, water, lava, portal, ...)
            // live in the block atlas and are re-uploaded by vanilla every
            // tick; refresh our CPU copy or every animation freezes.
            IrisGlTextures.tick();
            ImmediateDraw.beginFrame();
            dev.antimony.gl.hook.GlDrawAbsorber.beginFrame();
            XaeroCpuCompatibility.updateMap();
            DebugScenario.tick(mc, frames);
            // A mod (or the savetest probe) may tear the world down while we
            // are inside this frame (vanilla's save-and-quit runs a nested
            // runTick loop from the click handler). Bail out quietly instead
            // of NPE-ing into FAILED and losing the software renderer for the
            // rest of the session.
            if (mc.level == null || mc.player == null) {
                return;
            }
            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
            mc.gameRenderer.lightTexture().updateLightTexture(partialTick);
            captureTerrainSources(mc);
            long worldStart=System.nanoTime();
            WORLD.render(mc.level, gameRenderer.getMainCamera(), partialTick, SceneFramebufferScaler.sceneWidth(), SceneFramebufferScaler.sceneHeight());
            long worldNanos=System.nanoTime()-worldStart;
            // Phase A3: the shader pack's fullscreen post-processing chain runs
            // on the CPU over the finished world image (bloom / tonemap / ...)
            // and writes the final colour back into the framebuffer before the
            // HUD is drawn on top. No-op when Iris or the pack is absent.
            long postStart=System.nanoTime();
            IrisGl.flushRaster();
            boolean shaderCompleted=dev.antimony.gl.glsl.IrisPostChain.render(IrisGl.framebuffer());
            long postNanos=System.nanoTime()-postStart;
            boolean sceneDebug = dev.antimony.AntimonyMod.flag("antimony.debug.scene");
            if (sceneDebug && frames == 65) {
                IrisGl.flushRaster();
                IrisGlFramebuffer fb = IrisGl.framebuffer();
                int cx = fb.width / 2;
                int cy = fb.height / 2;
                AntimonyMod.LOG.info("[Antimony] preGui65: centre={} top={} cornerBL={} row x=100/250/550/700: {} {} {} {}",
                        String.format("%08X", fb.color[cy * fb.width + cx]),
                        String.format("%08X", fb.color[(fb.height / 8) * fb.width + cx]),
                        String.format("%08X", fb.color[(fb.height * 3 / 4) * fb.width + fb.width / 8]),
                        String.format("%08X", fb.color[cy * fb.width + 100]),
                        String.format("%08X", fb.color[cy * fb.width + 250]),
                        String.format("%08X", fb.color[cy * fb.width + 550]),
                        String.format("%08X", fb.color[cy * fb.width + 700]));
            }
            // Vanilla's world-icon capture lives in GameRenderer.renderLevel,
            // which this mod replaces: mirror it here, between the 3D pass and
            // the GUI (vanilla's icon has no HUD in it either).
            var sceneRasterStats=IrisGl.ctx().rasterStatistics();
            if(shaderCompleted)PresetRuntimeDiagnostics.observeScene(mc,IrisGl.framebuffer(),WORLD.totalQuads());
            SceneFramebufferScaler.prepareGui(IrisGl.ctx());
            WorldIconCapture.tick(mc, IrisGl.framebuffer(), WORLD);
            GuiPass.render(mc, deltaTracker);
            IrisGl.flushRaster();
            IrisGlFramebuffer fb = IrisGl.framebuffer();
            if (sceneDebug && frames == 65) {
                var lv = mc.level;
                var eye = gameRenderer.getMainCamera().getPosition();
                var eyeBlock = BlockPos.containing(eye);
                var skyC = lv.getSkyColor(eye, 1.0F);
                var lm = dev.antimony.core.render.IrisGlTextures.lightmap();
                String lmInfo = lm == null ? "null" : String.format("%dx%d [0,0]=%08X [15,0]=%08X [0,15]=%08X [15,15]=%08X",
                        lm.width, lm.height, lm.pixels[0], lm.pixels[15], lm.pixels[15 * lm.width],
                        lm.pixels[15 * lm.width + 15]);
                AntimonyMod.LOG.info("[Antimony] scene65: eye=({},{},{}) fluid={} skylight@eye={} biome={} skyColor=({}) lightmap={}",
                        String.format("%.1f", eye.x), String.format("%.1f", eye.y), String.format("%.1f", eye.z),
                        lv.getFluidState(eyeBlock).getType(),
                        lv.getBrightness(net.minecraft.world.level.LightLayer.SKY, eyeBlock),
                        lv.getBiome(eyeBlock).unwrapKey().map(k -> k.location().toString()).orElse("?"),
                        String.format("%.3f,%.3f,%.3f", skyC.x, skyC.y, skyC.z), lmInfo);
                int cx = fb.width / 2;
                int cy = fb.height / 2;
                AntimonyMod.LOG.info("[Antimony] fb65: centre={} top={} upperLeft={} lowerLeft={} bottomRight={} row(y=cy) x=100..700 step150: {} {} {} {} {}",
                        String.format("%08X", fb.color[cy * fb.width + cx]),
                        String.format("%08X", fb.color[(fb.height / 8) * fb.width + cx]),
                        String.format("%08X", fb.color[(fb.height / 4) * fb.width + fb.width / 8]),
                        String.format("%08X", fb.color[(fb.height * 3 / 4) * fb.width + fb.width / 8]),
                        String.format("%08X", fb.color[(fb.height * 3 / 4) * fb.width + fb.width * 7 / 8]),
                        String.format("%08X", fb.color[cy * fb.width + 100]),
                        String.format("%08X", fb.color[cy * fb.width + 250]),
                        String.format("%08X", fb.color[cy * fb.width + 400]),
                        String.format("%08X", fb.color[cy * fb.width + 550]),
                        String.format("%08X", fb.color[cy * fb.width + 700]));
            }
            if (!shaderCompleted && dev.antimony.gl.glsl.IrisPostChain.hasPipeline()) {
                dev.antimony.core.win32.SplashOverlay.updateStage(5,
                        dev.antimony.gl.glsl.IrisPostChain.startupWaitDetail());
            }
            dev.antimony.core.win32.SplashOverlay.drawInFrame(fb);
            dev.antimony.core.present.ScreenPresenter.present(mc, fb);
            if (shaderCompleted) CpuAnimationDiagnostics.completedFrame(mc, fb);
            if (shaderCompleted) XaeroCpuCompatibility.completedFrame(mc, fb);
            if(PROFILE&&shaderCompleted&&++profiledShaderFrames<=5)AntimonyMod.LOG.info(
                "[Antimony] CPU completed frame profile renderer={} worldMs={} postMs={} totalMs={} raster={}",
                frames+1,worldNanos/1_000_000.0,postNanos/1_000_000.0,(System.nanoTime()-frameStart)/1_000_000.0,
                sceneRasterStats);
            if(shaderCompleted)PresetRuntimeDiagnostics.completedFrame(mc,fb,worldNanos,postNanos,System.nanoTime()-frameStart);
            if (frames % 100 == 0) {
                dev.antimony.gl.GlCoverage.flush();
            }
            frames++;
            if (frames == 1) {
                AntimonyMod.LOG.info("[Antimony] first frame presented");
            }
            if (SCREENSHOT_FRAMES.contains(frames)) {
                screenshot(mc, frames);
            }
            watchdog(mc, fb);
        } catch (Throwable t) {
            if(mc.level==null||mc.player==null||mc.getCameraEntity()==null){
                dev.antimony.gl.glsl.IrisPostChain.invalidateDepthFrame();
                dev.antimony.core.present.ScreenPresenter.frameTick(false);return;
            }
            FAILED.set(true);
            AntimonyMod.LOG.error("[Antimony] CPU renderer failed; native world/GPU fallback is disabled",t);
            renderFailureFrame(mc);
        }
    }

    private static IrisGlFramebuffer failureFramebuffer;
    private static void renderFailureFrame(Minecraft mc){
        try{
            int width=Math.max(1,Math.min(MAX_DIMENSION,mc.getWindow().getWidth()));
            int height=Math.max(1,Math.min(MAX_DIMENSION,mc.getWindow().getHeight()));
            if(failureFramebuffer==null||failureFramebuffer.width!=width||failureFramebuffer.height!=height){
                failureFramebuffer=new IrisGlFramebuffer(width,height);
                failureFramebuffer.clearColor(0xff2b1010);
                java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D graphics=image.createGraphics();
                try{
                    graphics.setColor(new java.awt.Color(43,16,16));graphics.fillRect(0,0,width,height);
                    graphics.setColor(java.awt.Color.WHITE);graphics.setFont(new java.awt.Font("Dialog",java.awt.Font.PLAIN,18));
                    graphics.drawString("Antimony CPU renderer stopped",24,48);
                    graphics.drawString("Check the game log for the error.",24,80);
                    graphics.drawString("No OpenGL / GPU rendering fallback.",24,112);
                }finally{graphics.dispose();}
                image.getRGB(0,0,width,height,failureFramebuffer.color,0,width);
            }
            dev.antimony.core.present.ScreenPresenter.present(mc,failureFramebuffer);
        }catch(Throwable diagnosticFailure){
            AntimonyMod.LOG.error("[Antimony] CPU failure display unavailable; GPU fallback remains disabled",diagnosticFailure);
        }
    }

    /**
     * Black-frame watchdog: samples a few pixels after the GUI pass and logs
     * when a frame that should show the world is (almost) fully black. The
     * reported "inventory flashes black for a frame" leaves no other trace,
     * so the watchdog dumps the scene state of the offending frame to the log.
     */
    private static void watchdog(Minecraft mc, IrisGlFramebuffer fb) {
        if (!dev.antimony.AntimonyMod.flag("antimony.debug.blackwatch")) {
            return;
        }
        int w = fb.width;
        int h = fb.height;
        long lumSum = 0;
        int opaqueBlack = 0;
        int samples = 0;
        for (int i = 0; i < 64; i++) {
            int x = (i * 37 + 13) % w;
            int y = (i * 53 + 29) % h;
            int c = fb.color[y * w + x];
            int r = (c >>> 16) & 0xFF;
            int g = (c >>> 8) & 0xFF;
            int b = c & 0xFF;
            lumSum += r + g + b;
            samples++;
            if (r + g + b < 12) {
                opaqueBlack++;
            }
        }
        boolean black = lumSum / (double) (samples * 3) < 6.0;
        if (black) {
            if (blackRunStart == 0) {
                blackRunStart = frames;
            }
            if (frames - blackRunStart <= 2 || frames % 60 == 0) {
                var eye = mc.gameRenderer.getMainCamera().getPosition();
                var eyeBlock = BlockPos.containing(eye);
                AntimonyMod.LOG.warn(
                        "[Antimony] black frame {}: screen={} paused={} screenGui={} underwater={} skylight={} skyDarken={}",
                        frames, w + "x" + h, mc.isPaused(), mc.screen != null,
                        mc.level.getFluidState(eyeBlock).getType(),
                        mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eyeBlock),
                        mc.level.getSkyDarken());
            }
        } else {
            if (blackRunStart != 0) {
                AntimonyMod.LOG.info("[Antimony] black run ended at frame {} (started {})", frames, blackRunStart);
                blackRunStart = 0;
            }
        }
    }

    private static long blackRunStart;

    public static boolean failed() {
        return FAILED.get();
    }

    public static long frameCount() {
        return frames;
    }

    public static int framebufferWidth() {
        return fbWidth;
    }

    public static int framebufferHeight() {
        return fbHeight;
    }

    private static void screenshot(Minecraft mc, long frame) {
        try {
            IrisGlFramebuffer fb = IrisGl.framebuffer();
            BufferedImage image = dev.antimony.core.present.CpuScreenImage.rgb(fb);
            File dir = new File(mc.gameDirectory, "antimony-shots");
            dir.mkdirs();
            File out = new File(dir, "frame-" + frame + ".png");
            ImageIO.write(image, "PNG", out);
            AntimonyMod.LOG.info("[Antimony] screenshot saved {}", out.getAbsolutePath());
        } catch (Throwable t) {
            AntimonyMod.LOG.warn("[Antimony] screenshot failed", t);
        }
    }
}
