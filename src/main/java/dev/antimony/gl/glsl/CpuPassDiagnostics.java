package dev.antimony.gl.glsl;

import dev.antimony.AntimonyMod;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;

/** Opt-in, isolated-instance float captures. Observes completed CPU work; never changes pixels. */
final class CpuPassDiagnostics {
    private static final boolean ENABLED = AntimonyMod.flag("antimony.pass.capture");
    private static final long FRAME = Long.getLong("antimony.pass.capture.frame", 10L);
    private static boolean failed;
    private CpuPassDiagnostics() { }

    static void image(long frame, String name, CpuImage image) {
        if (!ENABLED || failed || frame != FRAME || image == null) return;
        try {
            Path directory = directory(frame);
            Path floats = directory.resolve(name + ".f32be.gz");
            try (var output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(floats))))) {
                for (float value : image.rgba) output.writeFloat(value);
            }
            var png = new BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB);
            int[] pixels = new int[image.width * image.height];
            float max = Float.NEGATIVE_INFINITY; int maxAt = 0, nonfinite = 0;
            for (int pixel = 0; pixel < pixels.length; pixel++) {
                int offset = pixel * 4;
                for (int c = 0; c < 3; c++) {
                    float value = image.rgba[offset + c];
                    if (!Float.isFinite(value)) nonfinite++;
                    else if (value > max) { max = value; maxAt = pixel; }
                }
                pixels[pixel] = channel(image.rgba[offset]) << 16 | channel(image.rgba[offset + 1]) << 8 | channel(image.rgba[offset + 2]);
            }
            png.setRGB(0, 0, image.width, image.height, pixels, 0, image.width);
            ImageIO.write(png, "PNG", directory.resolve(name + "-raw-clamped.png").toFile());
            Files.writeString(directory.resolve(name + ".txt"), String.format(Locale.ROOT,
                    "width=%d height=%d channels=4 floats=big-endian rowOrder=top-down max=%s maxX=%d maxY=%d nonfinite=%d PNG=raw-clamped-no-transfer\n",
                    image.width, image.height, max, maxAt % image.width, maxAt / image.width, nonfinite));
        } catch (Exception | LinkageError failure) { fail(failure); }
    }

    static void program(long frame, String name, int program) {
        if (!ENABLED || failed || frame != FRAME) return;
        try {
            var record = dev.antimony.gl.shim.GlShimState.program(program);
            if (record == null) return;
            Path directory = directory(frame);
            if (record.vertexSource != null) Files.writeString(directory.resolve(name + ".vsh"), record.vertexSource);
            if (record.fragmentSource != null) Files.writeString(directory.resolve(name + ".fsh"), record.fragmentSource);
        } catch (Exception | LinkageError failure) { fail(failure); }
    }

    private static int channel(float value) { return Math.round(Math.max(0, Math.min(1, value)) * 255); }
    private static Path directory(long frame) throws IOException {
        Path game = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.toString().contains("validation-instances")) throw new IllegalStateException("Pass capture requires isolated game directory");
        Path directory = game.resolve("antimony-shots/pass-" + frame);
        Files.createDirectories(directory);
        return directory;
    }
    private static void fail(Throwable failure) {
        failed = true;
        AntimonyMod.LOG.warn("[Antimony] CPU pass capture unavailable; CPU rendering continues", failure);
    }
}
