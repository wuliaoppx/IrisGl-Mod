package dev.antimony.gl.glsl;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Offline smoke test for the phase-A3 executor: translate and compile the
 * pack's bloom program, run it over a synthetic image through the same
 * {@link FragState} path the in-game chain uses, and write the result as PNG.
 * Doubles as a per-pixel cost measurement (the CPU post chain's frame budget).
 *
 * <p>Run: {@code java -cp build/classes/java/main dev.antimony.gl.glsl.PostChainSelfTest
 * <dumpDir> <out.png> [width] [height]}</p>
 */
public final class PostChainSelfTest {
    /** Mutable per-fragment state, mirroring IrisPostChain.CpuPassState. */
    private static final class State implements FragState {
        final CpuImage[] units = new CpuImage[16];
        final int width;
        final int height;
        int x;
        int y;

        State(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public float[] sample(int unit, float u, float v) {
            CpuImage image = unit >= 0 && unit < units.length ? units[unit] : null;
            return image == null ? new float[]{0, 0, 0, 1} : image.sample(u, v);
        }

        @Override
        public float width() {
            return width;
        }

        @Override
        public float height() {
            return height;
        }

        @Override
        public float fragU() {
            return (x + 0.5f) / width;
        }

        @Override
        public float fragV() {
            return (y + 0.5f) / height;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 && args[0].equals("--cp")) {
            // Verify mod-jar path extraction for the URL shapes NeoForge uses.
            for (int i = 1; i < args.length; i++) {
                System.out.println(args[i] + "  ->  " + GlslProgramLoader.containerOf(args[i]));
            }
            return;
        }
        if (args.length > 1 && args[0].equals("--loader")) {
            // Exercise the in-game runtime compiler path offline: translate,
            // javac, class-load and instantiate one dumped program.
            Path dir = Path.of(args[1]);
            int programId = args.length > 2 ? Integer.parseInt(args[2]) : 207;
            String prefix = args.length > 3 ? args[3] : "78";
            Path frag = null;
            for (Path p : Files.list(dir).toList()) {
                if (p.getFileName().toString().startsWith(prefix + "-") && p.toString().contains("-fragment")) {
                    frag = p;
                }
            }
            if (frag == null) {
                System.out.println("no program " + prefix + " in " + dir);
                return;
            }
            String vertName = frag.getFileName().toString().replace("-fragment", "-vertex");
            String vert = Files.exists(dir.resolve(vertName)) ? Files.readString(dir.resolve(vertName)) : "";
            GlslProgramLoader.Loaded loaded = GlslProgramLoader.load(programId, Files.readString(frag), vert);
            System.out.println(loaded == null
                ? "LOAD FAILED: " + GlslProgramLoader.failure(programId)
                : "LOAD OK " + loaded.className + " samplers=" + loaded.meta.samplerUnits);
            return;
        }
        Path dump = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int width = args.length > 2 ? Integer.parseInt(args[2]) : 256;
        int height = args.length > 3 ? Integer.parseInt(args[3]) : 144;

        String prefix = args.length > 4 ? args[4] : "76";
        Path frag = null;
        for (Path p : Files.list(dump).toList()) {
            if (p.getFileName().toString().startsWith(prefix + "-") && p.toString().contains("-fragment")) {
                frag = p;
            }
        }
        if (frag == null) {
            System.out.println("fragment program " + prefix + " not found in " + dump);
            return;
        }
        String fragSrc = Files.readString(frag);
        String vertName = frag.getFileName().toString().replace("-fragment", "-vertex");
        String vertSrc = Files.exists(dump.resolve(vertName)) ? Files.readString(dump.resolve(vertName)) : "";

        GlslCompiler compiler = new GlslCompiler();
        GlslCompiler.Result meta = compiler.translate("SelfTest76", fragSrc, vertSrc);
        Path work = Path.of("build", "post-test");
        Files.createDirectories(work.resolve("classes"));
        Path src = work.resolve("SelfTest76.java");
        Files.writeString(src, meta.javaSource);
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, System.out, System.err, List.of(
            "-encoding", "UTF-8", "-proc:none", "-nowarn",
            "-d", work.resolve("classes").toString(),
            "-cp", "build/classes/java/main",
            src.toString()).toArray(new String[0]));
        if (rc != 0) {
            System.out.println("COMPILE FAILED rc=" + rc);
            return;
        }
        URLClassLoader loader = new URLClassLoader(
            new URL[]{work.resolve("classes").toUri().toURL()}, PostChainSelfTest.class.getClassLoader());
        GlslProgram program = (GlslProgram) Class.forName("dev.antimony.gl.glsl.gen.SelfTest76", true, loader)
            .getDeclaredConstructor().newInstance();

        CpuImage scene = new CpuImage(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int o = (y * width + x) * 4;
                float u = x / (float) width;
                float v = y / (float) height;
                scene.rgba[o] = 0.15f + 0.8f * u;
                scene.rgba[o + 1] = 0.55f * v + 0.1f;
                scene.rgba[o + 2] = (x / 32 + y / 32) % 2 == 0 ? 0.75f : 0.1f;
                scene.rgba[o + 3] = 1.0f;
            }
        }

        for (int unit = 0; unit < meta.samplerUnits.size(); unit++) {
            program.getClass().getField("s$" + unit).setInt(program, unit);
        }
        setFloat(program, "exposure", 1.0f);
        setFloat(program, "aspectRatioInverse", height / (float) width);
        setFloat(program, "softLod", 0.0f);
        setFloat(program, "frameMod", 0);
        setFloat(program, "ditherShift", 0.25f);
        setFloat(program, "iris_FogFragCoord", 0.0f);

        State state = new State(width, height);
        for (int unit = 0; unit < meta.samplerUnits.size(); unit++) {
            state.units[unit] = scene;
        }
        float[][] outputs = (float[][]) program.getClass().getField("out").get(program);

        CpuImage target = new CpuImage(width, height);
        long start = System.nanoTime();
        for (int y = 0; y < height; y++) {
            state.y = y;
            float v = (y + 0.5f) / height;
            for (int x = 0; x < width; x++) {
                state.x = x;
                program.setFragment((x + 0.5f) / width, v);
                program.run(state);
                float[] color = outputs[0];
                int o = (y * width + x) * 4;
                if (color != null) {
                    target.rgba[o] = color[0];
                    target.rgba[o + 1] = color[1];
                    target.rgba[o + 2] = color[2];
                    target.rgba[o + 3] = color[3];
                }
            }
        }
        long nanos = System.nanoTime() - start;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[width * height];
        target.readIntoArgb(argb, width, height);
        image.setRGB(0, 0, width, height, argb, 0, width);
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        javax.imageio.ImageIO.write(image, "PNG", new File(out.toString()));

        System.out.printf("program=76 samplers=%s outs=%d varyings=%d%n",
            meta.samplerUnits, meta.outSlots, meta.varyingIns.size());
        System.out.printf("%.2f ms for %dx%d (%d px)%n", nanos / 1_000_000.0, width, height, width * height);
        System.out.println("out[0] centre=" + java.util.Arrays.toString(pixel(target, width / 2, height / 2))
            + " top-left=" + java.util.Arrays.toString(pixel(target, 2, 2)));
    }

    private static float[] pixel(CpuImage image, int x, int y) {
        float[] out = new float[4];
        image.readPixel(x, y, out);
        return out;
    }

    private static void setFloat(GlslProgram program, String name, float value) throws Exception {
        try {
            var field = program.getClass().getField(name);
            if (field.getType() == int.class) {
                field.setInt(program, (int) value);
            } else {
                field.setFloat(program, value);
            }
        } catch (NoSuchFieldException ignored) {
            // uniform absent in this program
        }
    }
}
