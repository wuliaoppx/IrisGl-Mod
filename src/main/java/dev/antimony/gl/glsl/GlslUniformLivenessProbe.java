package dev.antimony.gl.glsl;

import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Isolated javac/CPU regression: actual terrain sources and production live-uniform binding.
 * Frame ownership and values are explicitly synthetic; does not launch Minecraft or claim runtime success. */
public final class GlslUniformLivenessProbe {
    private static final Path WORK = Path.of("build/tmp/uniform-liveness-probe/generated");
    private static int serial;
    private static final Object PIPELINE = new Object();

    public static void main(String[] args) throws Exception {
        frameReady(true);
        syntheticRegressions();
        Path sourceDir = Path.of(args.length == 0 ? "logs/round7-evidence/terrain-source" : args[0]);
        for (String stage : List.of("terrain_solid.vsh", "terrain_cutout.vsh")) actualSource(sourceDir.resolve(stage));
        System.out.println("UNIFORM LIVENESS PROBE OK: synthetic frame/values, no Minecraft/GL/runtime claim");
    }

    private static void syntheticRegressions() throws Exception {
        GlslProgramLoader.Loaded direct = vertex("""
            uniform vec2 fixedTaaCorrection;
            uniform vec2 taaOffset;
            uniform float x;
            uniform float initializerOnly;
            struct Parameters { float p; };
            Parameters ignored = Parameters(initializerOnly);
            float dormant() { return fixedTaaCorrection.x; }
            void main() {
                // fixedTaaCorrection must not become live via this comment.
                float fixedTaaCorrectionNear = 3.0;
                vec2 localValue = vec2(fixedTaaCorrectionNear);
                gl_Position = vec4(localValue.x, 0.0, 0.0, 1.0);
                gl_Position.xy += taaOffset * gl_Position.w;
            }
            """);
        live(direct, "taaOffset");
        check(direct.meta.uniformFields.size() == 4, "all declarations preserved for post");
        bind(direct, Map.of("taaOffset", new float[]{0, 0}), Map.of());
        runVertex(direct);
        near(direct.program.vertexPosition()[0], 3);
        expectFailure(() -> bind(direct, Map.of(), Map.of()), "Missing current terrain uniform taaOffset");
        // A prior successful zero value does not authorize a missing value next frame.
        bind(direct, Map.of("taaOffset", new float[]{0, 0}), Map.of());
        expectFailure(() -> bind(direct, Map.of("taaOffset", new float[]{1}), Map.of()), "Missing current terrain uniform taaOffset");
        frameReady(false);
        expectFailure(() -> bind(direct, Map.of("taaOffset", new float[]{0, 0}), Map.of()), "No prepared current Iris frame");
        frameReady(true);
        setStatic("preparedPipeline", new Object());
        expectFailure(() -> bind(direct, Map.of("taaOffset", new float[]{0, 0}), Map.of()), "No prepared current Iris frame");
        frameReady(true);
        System.out.println("PASS unused declarations/helper/comments/identifier suffix/swizzle/struct initializer excluded; zero live accepted; missing/malformed/stale/pipeline-mismatch strict");

        GlslProgramLoader.Loaded helpers = vertex("""
            uniform float leafUniform;
            uniform float deadUniform;
            uniform float vectorOverloadOnly;
            float leaf() { return leafUniform; }
            float middle() { return leaf(); }
            float chosen(float p) { return middle() + p; }
            float chosen(vec2 p) { return vectorOverloadOnly + p.x; }
            float dormant() { return deadUniform; }
            void main() { gl_Position = vec4(chosen(2.0), 0.0, 0.0, 1.0); }
            """);
        live(helpers, "leafUniform");
        bind(helpers, Map.of("leafUniform", new float[]{5}), Map.of());
        runVertex(helpers); near(helpers.program.vertexPosition()[0], 7);
        expectFailure(() -> bind(helpers, Map.of(), Map.of()), "Missing current terrain uniform leafUniform");
        System.out.println("PASS transitive called helpers and chosen overload live; emitted but unreachable helpers/overload excluded; missing helper input strict");

        GlslProgramLoader.Loaded nestedVector = vertex("""
            uniform float scalarOnly;
            uniform float vectorOnly;
            vec2 make() { return vec2(1.0, 2.0); }
            float pick(float p) { return scalarOnly + p; }
            float pick(vec2 p) { return vectorOnly + p.x; }
            void main() { gl_Position = vec4(pick(make()), 0.0, 0.0, 1.0); }
            """);
        live(nestedVector, "vectorOnly");
        bind(nestedVector, Map.of("vectorOnly", new float[]{5}), Map.of());
        runVertex(nestedVector); near(nestedVector.program.vertexPosition()[0], 6);
        expectFailure(() -> bind(nestedVector, Map.of("scalarOnly", new float[]{5}), Map.of()),
            "Missing current terrain uniform vectorOnly");
        GlslProgramLoader.Loaded nestedMatrix = vertex("""
            uniform float scalarOnly;
            uniform float matrixOnly;
            mat4 make() { return mat4(1.0); }
            float pick(float p) { return scalarOnly + p; }
            float pick(mat4 p) { return matrixOnly + p[0].x; }
            void main() { gl_Position = vec4(pick(make()), 0.0, 0.0, 1.0); }
            """);
        live(nestedMatrix, "matrixOnly");
        bind(nestedMatrix, Map.of("matrixOnly", new float[]{5}), Map.of());
        runVertex(nestedMatrix); near(nestedMatrix.program.vertexPosition()[0], 6);
        expectFailure(() -> bind(nestedMatrix, Map.of("scalarOnly", new float[]{5}), Map.of()),
            "Missing current terrain uniform matrixOnly");
        System.out.println("PASS nested vector/matrix results select typed overload; actual read uniform bound; scalar-only provider rejected");

        GlslProgramLoader.Loaded global = vertex("""
            uniform float u;
            float readGlobal() { return u; }
            void main() { float u = 2.0; gl_Position = vec4(readGlobal() + u, 0.0, 0.0, 1.0); }
            """);
        live(global, "u"); bind(global, Map.of("u", new float[]{5}), Map.of()); runVertex(global);
        near(global.program.vertexPosition()[0], 7);
        GlslProgramLoader.Loaded parameter = vertex("""
            uniform float u;
            float f(float u) { return u; }
            void main() { gl_Position = vec4(f(2.0), 0.0, 0.0, 1.0); }
            """);
        live(parameter); bind(parameter, Map.of(), Map.of()); runVertex(parameter); near(parameter.program.vertexPosition()[0], 2);
        GlslProgramLoader.Loaded nested = vertex("""
            uniform float u;
            void main() { float x = u; { float u = 3.0; x += u; } gl_Position = vec4(x + u, 0.0, 0.0, 1.0); }
            """);
        live(nested, "u"); bind(nested, Map.of("u", new float[]{2}), Map.of()); runVertex(nested); near(nested.program.vertexPosition()[0], 7);
        GlslProgramLoader.Loaded mangledParameter = vertex("""
            uniform float class;
            float f(float class) { return class; }
            void main() { gl_Position = vec4(f(2.0), 0.0, 0.0, 1.0); }
            """);
        live(mangledParameter); bind(mangledParameter, Map.of(), Map.of()); runVertex(mangledParameter);
        near(mangledParameter.program.vertexPosition()[0], 2);
        System.out.println("PASS global vs main-local vs parameter vs nested shadow; original identifier resolution survives Java name mangling");

        GlslCompiler reusable = new GlslCompiler();
        var recursive = reusable.translateVertex("UniformRecursive" + serial++, """
            uniform float reached;
            uniform float unused;
            float f(float p) { if (p > 0.0) return f(p - 1.0); return reached; }
            void main() { gl_Position = vec4(f(1.0), 0.0, 0.0, 1.0); }
            """);
        check(recursive.usedUniforms.equals(Set.of("reached")), "call-graph cycle terminates");
        compile(recursive);
        var reset = reusable.translateVertex("UniformReset" + serial++, "uniform float reached; void main() { gl_Position = vec4(0.0); }");
        check(reset.usedUniforms.isEmpty() && reset.uniformFields.containsKey("reached"), "reused compiler reset");
        compile(reset);
        var fragment = reusable.translate("UniformFragment" + serial++, """
            uniform float live;
            uniform float dead;
            layout(location = 0) out vec4 color;
            float helper() { return live; }
            float dormant() { return dead; }
            void main() { float live = 2.0; color = vec4(helper() + live); }
            """, "");
        var fragmentLoaded = compile(fragment);
        live(fragmentLoaded, "live"); check(fragment.uniformFields.size() == 2, "fragment declarations retained");
        fragmentLoaded.program.getClass().getField(fragment.uniformFields.get("live")).setFloat(fragmentLoaded.program, 5);
        fragmentLoaded.program.run(new VertexState(new CpuImage[0], 1, 1, 0, 0));
        float[][] out = (float[][]) fragmentLoaded.program.getClass().getField("out").get(fragmentLoaded.program);
        near(out[0][0], 7);
        System.out.println("PASS recursive reachability terminates, compiler reuse resets metadata, fragment helper/shadow/declaration compatibility");
    }

    private static void actualSource(Path source) throws Exception {
        String text = Files.readString(source);
        var loaded = compile(new GlslCompiler().translateVertex("UniformActual" + serial++, text));
        check(loaded.meta.uniformFields.containsKey("fixedTaaCorrection"), "actual unused uniform declared");
        check(!loaded.meta.usedUniforms.contains("fixedTaaCorrection"), "actual fixedTaaCorrection not live");
        check(loaded.meta.usedUniforms.containsAll(Set.of("taaOffset", "frameTimeCounter", "dayMoment", "dayMixer", "nightMixer")), "actual main/transitive helper uniforms retained");
        Map<String, Object> supplied = new LinkedHashMap<>();
        for (String name : loaded.meta.usedUniforms) {
            String[] sig = loaded.meta.uniformTypes.get(name).split("#");
            int size = Integer.parseInt(sig[1]);
            supplied.put(name, switch (sig[0]) {
                case "float" -> Float.valueOf(1);
                case "int" -> Integer.valueOf(0);
                case "boolean" -> Boolean.FALSE;
                case "int[]" -> new int[size];
                case "float[]" -> size == 16 ? identity() : size == 9 ? new float[]{1,0,0,0,1,0,0,0,1} : new float[size];
                default -> throw new AssertionError("probe does not support " + sig[0]);
            });
        }
        supplied.put("far", 512f);
        supplied.put("eyeBrightnessSmooth", new int[]{240,240});
        supplied.put("sunPosition", new float[]{0,100,0});
        supplied.put("shadowLightPosition", new float[]{0,100,0});
        supplied.put("iris_ColorModulator", new float[]{1,1,1,1});
        supplied.put("taaOffset", new float[]{.003f,-.002f});
        // Per-section CPU values bypass ownership reconstruction; all other values use the
        // production IrisUniformFrame snapshot path with a minimal reflection-compatible model.
        Map<String, float[]> cpu = new LinkedHashMap<>();
        for (var entry : supplied.entrySet()) {
            String name = entry.getKey();
            if (entry.getValue() instanceof float[] f && !name.equals("taaOffset")) cpu.put(name, f);
            if (name.equals("far")) cpu.put(name, new float[]{512});
        }
        bind(loaded, cpu, supplied);
        check(frame().get("fixedTaaCorrection") == null, "unknown unused input omitted by production snapshot");
        for (String name : loaded.meta.uniformFields.keySet()) if (!loaded.meta.usedUniforms.contains(name)) {
            check(!supplied.containsKey(name), "no unused fixture value invented");
        }
        var attributes = new VertexState.Attributes(new float[]{1,2,3}, new float[]{1,1,1,1},
            new float[]{.2f,.3f}, new int[]{240,240}, new float[]{0,1,0}, 0, new float[]{.5f,.5f});
        loaded.program.runVertex(new VertexState(new CpuImage[0], 854,480,0,0,
            input -> input.name().equals("blockEntityId") ? Integer.valueOf(0) : attributes.value(input)));
        float[] clip = loaded.program.vertexPosition();
        near(clip[0], 1.003f); near(clip[1], 1.998f); near(clip[2], 3); near(clip[3], 1);
        Map<String, Object> missing = new LinkedHashMap<>(supplied); missing.remove("taaOffset");
        expectFailure(() -> bind(loaded, cpu, missing), "Missing current terrain uniform taaOffset");
        Map<String, Object> missingHelper = new LinkedHashMap<>(supplied); missingHelper.remove("frameTimeCounter");
        expectFailure(() -> bind(loaded, cpu, missingHelper), "Missing current terrain uniform frameTimeCounter");
        System.out.println("PASS actual " + source.getFileName() + " declared=" + loaded.meta.uniformFields.size()
            + " live=" + loaded.meta.usedUniforms.size() + " liveNames=" + loaded.meta.usedUniforms);
        System.out.println("  original full source sha256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)))
            + " bound live-only; unused values absent; finite jittered clip=" + Arrays.toString(clip) + "; missing taa/helper still strict");
    }

    private static GlslProgramLoader.Loaded vertex(String text) throws Exception {
        return compile(new GlslCompiler().translateVertex("UniformSynthetic" + serial++, text));
    }
    private static GlslProgramLoader.Loaded compile(GlslCompiler.Result meta) throws Exception {
        Files.createDirectories(WORK);
        String name = meta.javaSource.split("public final class ", 2)[1].split(" ", 2)[0];
        Path java = WORK.resolve(name + ".java"); Files.writeString(java, meta.javaSource);
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, System.out, System.err,
            "-proc:none", "-encoding", "UTF-8", "-cp", System.getProperty("java.class.path"), "-d", WORK.toString(), java.toString());
        check(rc == 0, "generated javac " + name + " rc=" + rc);
        try (var loader = new URLClassLoader(new java.net.URL[]{WORK.toUri().toURL()}, GlslUniformLivenessProbe.class.getClassLoader())) {
            return new GlslProgramLoader.Loaded((GlslProgram) loader.loadClass("dev.antimony.gl.glsl.gen." + name).getConstructor().newInstance(), meta, name);
        }
    }
    private static void runVertex(GlslProgramLoader.Loaded loaded) {
        loaded.program.runVertex(new VertexState(new CpuImage[0], 1, 1, 0, 0));
    }
    private static void live(GlslProgramLoader.Loaded loaded, String... names) {
        check(loaded.meta.usedUniforms.equals(new LinkedHashSet<>(List.of(names))), "expected live=" + Arrays.toString(names) + " actual=" + loaded.meta.usedUniforms);
    }
    private static void bind(GlslProgramLoader.Loaded loaded, Map<String, float[]> cpu, Map<String, Object> supplied) throws Exception {
        Set<String> requested = new LinkedHashSet<>(loaded.meta.usedUniforms);
        requested.add("unknownUnusedInput");
        setStatic("uniformFrame", IrisUniformFrame.capture(new Custom(supplied), null, requested, false, null));
        IrisPostChain.applyTerrainUniforms(loaded, cpu);
    }
    private static IrisUniformFrame frame() throws Exception {
        Field f = IrisPostChain.class.getDeclaredField("uniformFrame"); f.setAccessible(true); return (IrisUniformFrame) f.get(null);
    }
    private static void frameReady(boolean ready) throws Exception {
        setStatic("cachedPipeline", PIPELINE); setStatic("preparedPipeline", PIPELINE); setStatic("frameStateReady", ready);
    }
    private static void setStatic(String name, Object value) throws Exception {
        Field f = IrisPostChain.class.getDeclaredField(name); f.setAccessible(true); f.set(null, value);
    }
    private static float[] identity() { return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}; }
    private static void near(float actual, float expected) { check(Float.isFinite(actual) && Math.abs(actual-expected)<.0001f, "expected="+expected+" actual="+actual); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void expectFailure(Checked action, String diagnostic) throws Exception {
        try { action.run(); } catch (IllegalStateException expected) { check(expected.getMessage().contains(diagnostic), "unexpected failure " + expected); return; }
        throw new AssertionError("expected strict failure " + diagnostic);
    }

    public static final class Node {
        public Object cached;
        Node(Object value) { cached = value; }
        public void update() { }
    }
    public static final class Holder { public final Map<String, Node> inputVariables = new LinkedHashMap<>(); }
    public static final class Custom {
        public final Map<String, Node> variables = new LinkedHashMap<>();
        public final Map<Node, List<Node>> dependsOn = new IdentityHashMap<>();
        public final Holder inputHolder = new Holder();
        public final List<Node> uniformOrder = new ArrayList<>();
        Custom(Map<String, Object> values) { values.forEach((name, value) -> inputHolder.inputVariables.put(name, new Node(value))); }
    }
}
