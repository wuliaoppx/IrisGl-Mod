package dev.antimony.gl.glsl;

import com.sun.management.ThreadMXBean;
import dev.antimony.core.render.TerrainVoxelAttributes;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.tools.ToolProvider;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Full unchanged Solas shadow source, old JAR compiler versus current compiler.
 * Inputs are a deterministic CPU LAB corpus, not captured game vertices or a FPS test. */
public final class SolasShadowVertexABProbe {
    private static final String SOURCE_SHA = "482fa5ed48b9ae71fee5dc42cbfc890a09ad296b05813c3d18c417318e4ed76c";
    private static final String BASELINE_SHA = "dc9bbcb7fc9e344293be05c8033e28c46f3d7eb517e3ba6b74adccb067fa88ef";
    private static final int CORPUS = 4096, FRAMES = 16, ITERATIONS = 131072, ROUNDS = 7;
    private static volatile long sink;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("baseline JAR, unchanged shadow VS, new evidence directory required");
        Path baseline = Path.of(args[0]), sourcePath = Path.of(args[1]), evidence = Path.of(args[2]);
        check(sha(Files.readAllBytes(baseline)).equals(BASELINE_SHA), "baseline must be the verified round25 JAR, not current classes");
        byte[] bytes = Files.readAllBytes(sourcePath);
        check(sha(bytes).equals(SOURCE_SHA), "complete unchanged actual shadow source identity");
        String source = new String(bytes, StandardCharsets.UTF_8);
        Files.createDirectories(evidence);
        var afterMeta = new GlslCompiler().translateVertex("SolasShadowABAfter", source);
        String beforeJava;
        try (var compilerLoader = new BaselineCompilerLoader(baseline)) {
            Class<?> compilerClass = compilerLoader.loadClass(GlslCompiler.class.getName());
            check(compilerClass.getClassLoader() == compilerLoader, "old compiler must not resolve from the current parent classpath");
            Object oldMeta = compilerClass.getMethod("translateVertex", String.class, String.class)
                .invoke(compilerClass.getDeclaredConstructor().newInstance(), "SolasShadowABBefore", source);
            beforeJava = (String) oldMeta.getClass().getField("javaSource").get(oldMeta);
            for (Field f : GlslCompiler.Result.class.getFields()) {
                if (f.getName().equals("javaSource")) continue;
                check(Objects.deepEquals(f.get(afterMeta), oldMeta.getClass().getField(f.getName()).get(oldMeta)),
                    "identical metadata: " + f.getName());
            }
        }
        check(!beforeJava.replace("SolasShadowABBefore", "SolasShadowABAfter").equals(afterMeta.javaSource),
            "A/B must execute distinct old/new translations");
        check(afterMeta.usedImages.equals(Set.of("voxel_img")) && afterMeta.usedSamplers.isEmpty(), "actual image-only vertex contract");
        var before = new Execution(compile("SolasShadowABBefore", beforeJava, evidence), afterMeta);
        var after = new Execution(compile("SolasShadowABAfter", afterMeta.javaSource, evidence), afterMeta);
        long comparisons = 0, writes = 0, offscreenWrites = 0;
        for (int frame = 0; frame < FRAMES; frame++) {
            before.bind(frame); after.bind(frame);
            for (int vertex = 0; vertex < CORPUS; vertex++) {
                before.run(vertex, true); after.run(vertex, true);
                exact(before.payload, after.payload, "clip/all declared varyings frame=" + frame + " vertex=" + vertex);
                check(before.stores.count == after.stores.count, "imageStore count");
                for (int i = 0; i < before.stores.count * 8; i++)
                    check(before.stores.trace[i] == after.stores.trace[i], "imageStore unit/coordinate/all values/order at " + i);
                if ((vertex & 3) != 0 || before.inputs.material == 10001 || before.stage == 0 || before.stage == 23)
                    check(before.stores.count == 0, "original vertex/stage/water filter");
                writes += before.stores.count;
                if (outside(before.payload)) offscreenWrites += before.stores.count;
                comparisons++;
            }
        }
        check(writes > 0 && offscreenWrites > 0, "exercise actual stores including offscreen producers");
        System.out.println("PASS real unchanged Solas shadow A/B: vertices=" + comparisons + " floatLanes=" + before.payload.length
            + " imageStores=" + writes + " offscreenStores=" + offscreenWrites
            + "; raw-bit-identical clip, every declared varying and every imageStore argument/order");

        ThreadMXBean bean = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
        check(bean != null && bean.isThreadAllocatedMemorySupported(), "JDK allocated-byte measurements available");
        bean.setThreadAllocatedMemoryEnabled(true);
        // Alternate order, warm both generated classes and output transport before measuring.
        before.bind(0); after.bind(0);
        for (int i = 0; i < 8; i++) { measure(before, 32768, bean); measure(after, 32768, bean); }
        var oldSamples = new ArrayList<Measurement>(); var newSamples = new ArrayList<Measurement>();
        for (int round = 0; round < ROUNDS; round++) {
            before.bind(round); after.bind(round);
            Measurement oldResult, newResult;
            if ((round & 1) == 0) { oldResult = measure(before, ITERATIONS, bean); newResult = measure(after, ITERATIONS, bean); }
            else { newResult = measure(after, ITERATIONS, bean); oldResult = measure(before, ITERATIONS, bean); }
            check(oldResult.checksum == newResult.checksum && oldResult.stores == newResult.stores, "measured outputs/stores agree");
            oldSamples.add(oldResult); newSamples.add(newResult);
            System.out.println("CPU LAB round=" + round + " vertices=" + ITERATIONS + " old=" + oldResult + " new=" + newResult);
        }
        long oldNanos = median(oldSamples.stream().mapToLong(Measurement::nanos).toArray());
        long newNanos = median(newSamples.stream().mapToLong(Measurement::nanos).toArray());
        long oldBytes = median(oldSamples.stream().mapToLong(Measurement::bytes).toArray());
        long newBytes = median(newSamples.stream().mapToLong(Measurement::bytes).toArray());
        String report = String.format(Locale.ROOT,
            "Full unchanged Solas shadow VS, deterministic synthetic inputs; NOT game FPS.%n"
                + "baselineJarSha256=%s%nsourceSha256=%s%nverticesCompared=%d%nfloatLanes=%d%nimageStoresCompared=%d%noffscreenStoresCompared=%d%n"
                + "iterationsPerSample=%d%nrounds=%d%noldMedianMs=%.6f%nnewMedianMs=%.6f%noldMedianBytes=%d%nnewMedianBytes=%d%n"
                + "timeReductionPercent=%.4f%nallocationReductionPercent=%.4f%n",
            BASELINE_SHA, SOURCE_SHA, comparisons, before.payload.length, writes, offscreenWrites, ITERATIONS, ROUNDS,
            oldNanos / 1e6, newNanos / 1e6, oldBytes, newBytes,
            100.0 * (oldNanos - newNanos) / oldNanos, 100.0 * (oldBytes - newBytes) / oldBytes);
        Files.writeString(evidence.resolve("result.txt"), report, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.print(report);
        System.out.println("SOLAS SHADOW VERTEX A/B PROBE OK");
    }

    /** Isolate only the compiler and its nested types; service/value types stay identical for both executables. */
    private static final class BaselineCompilerLoader extends URLClassLoader {
        BaselineCompilerLoader(Path jar) throws Exception { super(new URL[]{jar.toUri().toURL()}, GlslCompiler.class.getClassLoader()); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            String compiler = GlslCompiler.class.getName();
            if (!name.equals(compiler) && !name.startsWith(compiler + "$")) return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name);
            if (type == null) type = findClass(name);
            if (resolve) resolveClass(type);
            return type;
        }
    }

    private static GlslProgram compile(String name, String java, Path evidence) throws Exception {
        Path source = evidence.resolve(name + ".java"), classes = evidence.resolve("classes");
        Files.writeString(source, java, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.createDirectories(classes);
        var javac = ToolProvider.getSystemJavaCompiler();
        check(javac != null, "full JDK required");
        int code = javac.run(null, System.out, System.err, "-encoding", "UTF-8", "-proc:none", "-d", classes.toString(),
            "-classpath", System.getProperty("java.class.path"), source.toString());
        check(code == 0, "generated " + name + " javac exit=" + code);
        try (var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, GlslProgram.class.getClassLoader())) {
            return (GlslProgram) loader.loadClass("dev.antimony.gl.glsl.gen." + name).getDeclaredConstructor().newInstance();
        }
    }

    private static final class Inputs implements VertexState.InputProvider {
        private static final int[] MATERIALS = {10005, 10001, -1, 10000, 10201, 10217, 10304, 10305, 10306, 10307, 10314, 10315, 10035, 10040};
        final float[] position = new float[3], color = new float[4], uv = new float[2], normal = {0, 1, 0},
            entity = new float[4], midUV = {.5f, .5f, 0, 1}, midBlock = new float[3];
        final int[] light = new int[2]; int material;
        void set(int index) {
            int id = index & (CORPUS - 1), q = id & 3, block = id >>> 2;
            int x = block & 15, y = (block >>> 4) & 15, z = (block >>> 8) & 15;
            position[0] = x + (q & 1); position[1] = y + (q >>> 1); position[2] = z + .25f;
            color[0] = .25f + .15f * q; color[1] = .7f; color[2] = .9f; color[3] = 1;
            uv[0] = .1f + .2f * q; uv[1] = (q & 2) == 0 ? .1f : .8f;
            light[0] = (block & 1) == 0 ? 240 : 16; light[1] = (block & 2) == 0 ? 240 : 0;
            material = MATERIALS[block % MATERIALS.length]; entity[0] = material;
            float[] mid = TerrainVoxelAttributes.midBlock(new TerrainVoxelAttributes.Vertex(id, x, y, z, TerrainVoxelAttributes.Stage.TERRAIN_SOLID),
                position[0], position[1], position[2]);
            System.arraycopy(mid, 0, midBlock, 0, 3);
        }
        public Object value(GlslProgram.VertexInput input) {
            return switch (input.name()) {
                case "iris_Position" -> position; case "iris_Normal" -> normal; case "iris_Color" -> color;
                case "iris_UV0" -> uv; case "iris_UV2" -> light; case "mc_Entity" -> entity;
                case "mc_midTexCoord" -> midUV; case "at_midBlock" -> midBlock;
                default -> throw new AssertionError("Explicit LAB input missing: " + input);
            };
        }
    }

    private static final class Stores implements FragState {
        final int unit; int count; long total, hash; boolean recording;
        final int[] trace = new int[128];
        Stores(int unit) { this.unit = unit; }
        public float width() { return 2048; } public float height() { return 2048; }
        public float[] sample(int unit, float u, float v) { throw new AssertionError("No shadow sampler expected"); }
        public void storeUnsignedImage3D(int actual, int[] p, int[] value) {
            check(actual == unit && p.length == 3 && value.length == 4, "actual unsigned image binding/arity");
            if (recording) {
                int o = count * 8;
                check(o + 8 <= trace.length, "unexpectedly many image writes");
                trace[o] = actual; System.arraycopy(p, 0, trace, o + 1, 3); System.arraycopy(value, 0, trace, o + 4, 4);
                count++;
            }
            total++; hash = hash * 31 + actual;
            for (int v : p) hash = hash * 31 + v;
            for (int v : value) hash = hash * 31 + v;
        }
    }

    private static final class Execution {
        final GlslProgram program; final GlslCompiler.Result meta; final Inputs inputs = new Inputs();
        final Stores stores; final VertexState state; final ShaderVaryings.Access outputs; final float[] payload;
        int stage;
        Execution(GlslProgram program, GlslCompiler.Result meta) throws Exception {
            this.program = program; this.meta = meta;
            var layout = ShaderVaryings.outputs(meta); outputs = layout.vertexAccess(program);
            payload = new float[4 + layout.components()]; stores = new Stores(meta.imageUnits.get("voxel_img"));
            program.getClass().getField("i$" + stores.unit).setInt(program, stores.unit);
            state = new VertexState(new CpuImage[0], 2048, 2048, 0, 0, inputs).imageServices(stores);
        }
        void bind(int frame) throws Exception {
            Matrix4f view = new Matrix4f().rotateX(.7f + .01f * frame).rotateZ(-1.1f).translate(.25f, -.5f, .75f);
            Matrix4f projection = (frame & 3) == 3 ? new Matrix4f().perspective(1.2f, 1.3f, .1f, 512)
                : new Matrix4f().ortho(-64, 64, -64, 64, -256, 256);
            float[][] offsets = {{-1.25f, -.5f, -.75f}, {-55, 25, -50}, {-110, 0, 0}, {0, 60, 0},
                {0, -70, 0}, {80, 0, 80}, {192, 0, 0}, {0, 0, 192}};
            int[] stages = {8, 17, 10, 9, 0, 23, 8, 9}; stage = stages[frame % stages.length];
            for (String name : meta.usedUniforms) {
                Object value = switch (name) {
                    case "iris_ModelViewMat", "shadowModelView" -> view.get(new float[16]);
                    case "iris_ModelViewMatInverse", "shadowModelViewInverse" -> new Matrix4f(view).invert().get(new float[16]);
                    case "iris_ProjMat", "shadowProjection" -> projection.get(new float[16]);
                    case "iris_ProjMatInverse", "shadowProjectionInverse" -> new Matrix4f(projection).invert().get(new float[16]);
                    case "iris_NormalMat" -> new Matrix3f(view).invert().transpose().get(new float[9]);
                    case "iris_LightmapTextureMatrix" -> new Matrix4f().translation(1f / 32, 1f / 32, 0).scale(1f / 256, 1f / 256, 1).get(new float[16]);
                    case "iris_TextureMat" -> new Matrix4f().get(new float[16]);
                    case "iris_ColorModulator" -> new float[]{.95f, .9f, .85f, 1};
                    case "cameraPosition" -> new float[]{33.25f + frame * .125f, -15.5f, 48.75f};
                    case "iris_ChunkOffset" -> offsets[frame % offsets.length].clone();
                    case "renderStage" -> stage;
                    case "frameTimeCounter" -> .25f + frame * 1.375f;
                    default -> throw new AssertionError("Explicit LAB uniform missing: " + name);
                };
                program.getClass().getField(meta.uniformFields.get(name)).set(program, value);
            }
        }
        void run(int index, boolean record) {
            inputs.set(index); stores.recording = record; stores.count = 0;
            program.runVertex(state.sourceVertexId(index & (CORPUS - 1)));
            System.arraycopy(program.vertexPosition(), 0, payload, 0, 4); outputs.capture(payload, 4);
        }
    }

    private record Measurement(long nanos, long bytes, long checksum, long stores) { }
    private static Measurement measure(Execution execution, int count, ThreadMXBean bean) {
        execution.stores.total = execution.stores.hash = 0;
        long tid = Thread.currentThread().threadId(), allocated = bean.getThreadAllocatedBytes(tid), start = System.nanoTime(), checksum = 0;
        for (int i = 0; i < count; i++) {
            execution.run(i, false);
            for (float v : execution.payload) checksum = checksum * 31 + Float.floatToRawIntBits(v);
        }
        long nanos = System.nanoTime() - start, bytes = bean.getThreadAllocatedBytes(tid) - allocated;
        checksum ^= execution.stores.hash; sink = checksum;
        return new Measurement(nanos, bytes, checksum, execution.stores.total);
    }
    private static boolean outside(float[] p) { return p[0] < -p[3] || p[0] > p[3] || p[1] < -p[3] || p[1] > p[3] || p[2] < -p[3] || p[2] > p[3]; }
    private static void exact(float[] a, float[] b, String label) {
        for (int i = 0; i < a.length; i++) check(Float.floatToRawIntBits(a[i]) == Float.floatToRawIntBits(b[i]), label + " lane=" + i);
        for (int i = 0; i < 4; i++) check(Float.isFinite(a[i]), "finite clip position");
    }
    private static long median(long[] values) { Arrays.sort(values); return values[values.length / 2]; }
    private static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
