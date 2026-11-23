package dev.antimony.core.render;

import dev.antimony.core.irisgl.IrisGlFramebuffer;
import dev.antimony.core.present.CpuScreenImage;
import dev.antimony.AntimonyMod;
import java.nio.file.*;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;

/** Isolated-instance measurement. Invalid data and diagnostic I/O never stop the CPU renderer. */
public final class PresetRuntimeDiagnostics {
    private static final boolean ENABLED = AntimonyMod.flag("antimony.preset.diag");
    private static final boolean INJECT_INVALID_CAMERA = AntimonyMod.flag("antimony.preset.diag.injectInvalidCamera");
    private static final RenderQualitySettings.Preset[] ORDER = {
            RenderQualitySettings.Preset.QUALITY, RenderQualitySettings.Preset.BALANCED,
            RenderQualitySettings.Preset.PERFORMANCE, RenderQualitySettings.Preset.QUALITY };
    private static final int SAMPLES_PER_STEP = Math.clamp(Integer.getInteger("antimony.preset.diag.samples", 7), 3, 32);
    private static final PresetMeasurementState MEASUREMENT = new PresetMeasurementState();
    private static int step, count;
    private static long samples;
    private static boolean invalidReported;
    private static PresetComparisonGuard.Observation observation;

    private PresetRuntimeDiagnostics() { }

    /** Checks actual submitted world geometry, not the depth plane overwritten by later passes. */
    public static void observeScene(Minecraft mc, IrisGlFramebuffer scene, int submittedSceneQuads) {
        if (!ENABLED || step >= ORDER.length || MEASUREMENT.invalid()) return;
        try {
            Path game = isolatedGame(mc);
            var camera = mc.gameRenderer.getMainCamera();
            var eye = camera.getPosition();
            float measuredYaw = camera.getYRot();
            // Fault injection changes only the measurement, not the camera, player or world.
            if (INJECT_INVALID_CAMERA && samples >= 1) measuredYaw += 1f;
            observation = new PresetComparisonGuard.Observation(mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                    scene.width, scene.height, eye.x, eye.y, eye.z, measuredYaw, camera.getXRot(), submittedSceneQuads);
            if (!MEASUREMENT.observe(observation, RenderQualitySettings.active())) {
                reportInvalid(mc);
                return;
            }
            if (count == 0 || count == SAMPLES_PER_STEP - 1) {
                Path directory = game.resolve("antimony-shots");
                Files.createDirectories(directory);
                String suffix = count == 0 ? "-preGUI.png" : "-steady-preGUI.png";
                ImageIO.write(CpuScreenImage.rgb(scene), "PNG", directory.resolve("preset-step" + step + "-" + ORDER[step] + suffix).toFile());
            }
        } catch (Exception invalidDiagnostic) {
            MEASUREMENT.invalidate("Preset diagnostic invalid: " + invalidDiagnostic);
            reportInvalid(mc);
        }
    }

    public static void completedFrame(Minecraft mc, IrisGlFramebuffer frame, long world, long post, long total) {
        if (!ENABLED || step >= ORDER.length || MEASUREMENT.invalid()) return;
        try {
            Path game = isolatedGame(mc);
            var preset = RenderQualitySettings.active();
            if (preset != ORDER[step]) throw new IllegalStateException("Unexpected live preset " + preset);
            if (frame.width != mc.getWindow().getWidth() || frame.height != mc.getWindow().getHeight())
                throw new IllegalStateException("Preset lowered GUI/presentation resolution");
            if (observation == null) throw new IllegalStateException("Preset scene observation missing");
            Path directory = game.resolve("antimony-shots");
            Files.createDirectories(directory);
            count++;
            samples++;
            String line = "step=" + step + " preset=" + preset + " sample=" + samples + " stepSample=" + count
                    + " scene=" + SceneFramebufferScaler.sceneWidth() + "x" + SceneFramebufferScaler.sceneHeight()
                    + " gui=" + frame.width + "x" + frame.height + " geometryQuads=" + observation.geometryQuads()
                    + " eye=" + observation.eyeX() + "," + observation.eyeY() + "," + observation.eyeZ()
                    + " yaw=" + observation.yaw() + " pitch=" + observation.pitch()
                    + " worldMs=" + world / 1e6 + " postMs=" + post / 1e6 + " totalMs=" + total / 1e6;
            Files.writeString(directory.resolve("preset-runtime.txt"), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            AntimonyMod.LOG.info("[Antimony] CPU preset verified completed frame {}", line);
            if (count == 1 || count == SAMPLES_PER_STEP) {
                String suffix = count == 1 ? ".png" : "-steady.png";
                ImageIO.write(CpuScreenImage.rgb(frame), "PNG", directory.resolve("preset-step" + step + "-" + preset + suffix).toFile());
            }
            observation = null;
            if (count >= SAMPLES_PER_STEP) {
                step++;
                count = 0;
                if (step < ORDER.length) {
                    RenderQualitySettings.stage(ORDER[step]);
                    RenderQualitySettings.saveSelection();
                    AntimonyMod.LOG.info("[Antimony] CPU preset verification requested {} through the real persisted setter", ORDER[step]);
                } else {
                    Files.writeString(directory.resolve("preset-runtime-completed.txt"),
                            "All presets completed same-window/same-camera nonempty CPU scenes; full-resolution GUI retained; quality restored.\n");
                    AntimonyMod.LOG.info("[Antimony] CPU preset runtime verification completed; fixed comparison contract passed; quality restored");
                }
            }
        } catch (Exception invalidDiagnostic) {
            MEASUREMENT.invalidate("Preset diagnostic invalid: " + invalidDiagnostic);
            reportInvalid(mc);
        }
    }

    private static void reportInvalid(Minecraft mc) {
        if (invalidReported) return;
        invalidReported = true;
        observation = null;
        AntimonyMod.LOG.warn("[Antimony] CPU preset measurement invalid; CPU rendering continues; reason={}", MEASUREMENT.failure());
        try {
            Path game = isolatedGame(mc);
            Path directory = game.resolve("antimony-shots");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("preset-runtime-invalid.txt"),
                    "measurementInvalid=true CPU-renderer-stopped=false reason=" + MEASUREMENT.failure() + "\n");
            if (RenderQualitySettings.selected() != RenderQualitySettings.Preset.QUALITY) {
                RenderQualitySettings.stage(RenderQualitySettings.Preset.QUALITY);
                RenderQualitySettings.saveSelection();
            }
        } catch (Exception evidenceFailure) {
            AntimonyMod.LOG.warn("[Antimony] Invalid measurement evidence could not be written; CPU rendering still continues", evidenceFailure);
        }
    }

    private static Path isolatedGame(Minecraft mc) {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.toString().contains("validation-instances"))
            throw new IllegalStateException("Preset verification is allowed only in an isolated instance");
        return game;
    }
}
