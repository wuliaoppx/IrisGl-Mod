package dev.antimony.gl.glsl;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Independent javac + execution probe. No game, Gradle, renderer or pack formula required. */
public final class GlslVertexProbe {
    private static final Path WORK = Path.of("build", "b3-vertex-probe");
    private static int serial;

    public static void main(String[] args) throws Exception {
        GlslCompiler compiler = new GlslCompiler();
        GlslCompiler.Result meta = compiler.translateVertex("VertexProbeInputs", """
            layout(location = 0) in vec3 Position;
            attribute vec4 Color;
            in vec2 UV0;
            in ivec2 UV2;
            in vec3 Normal;
            attribute vec4 mc_Entity;
            in vec2 mc_midTexCoord;
            uniform mat4 mvp;
            uniform vec2 jitter;
            flat out vec4 observed;
            out vec4 details;
            void main() {
                gl_Position = mvp * vec4(Position, 1.0);
                gl_Position.xy += jitter * gl_Position.w;
                gl_Position.z += Normal.z;
                observed = Color + vec4(UV0, mc_midTexCoord);
                details = vec4(float(UV2.x), float(UV2.y), mc_Entity.x, Normal.y);
            }
            """);
        GlslProgram program = compile(meta);
        check(meta.vertexInputs.size() == 7 && program.vertexInputs().equals(meta.vertexInputs), "typed metadata");
        check(meta.vertexInputs.get(0).type() == GlslProgram.VertexInputType.VEC3, "position metadata");
        check(meta.vertexInputs.get(3).type() == GlslProgram.VertexInputType.IVEC2, "integer light UV metadata");
        set(program, "mvp", new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 10, 20, 30, 1});
        set(program, "jitter", new float[]{0.25f, -0.5f});
        VertexState.Attributes first = attributes(1, 2, 3, 7);
        program.runVertex(state(first));
        near(program.vertexPosition(), 11.25f, 21.5f, 34, 1); // translation exactly once + additive jitter
        near(field(program, "observed"), 0.3f, 0.6f, 0.9f, 1.4f);
        near(field(program, "details"), 32, 240, 7, 0);
        float[] saved = program.vertexPosition();
        saved[0] = 999;
        near(program.vertexPosition(), 11.25f, 21.5f, 34, 1); // output is a snapshot
        ((float[]) program.getClass().getField("Position").get(program))[0] = 999;
        program.runVertex(state(attributes(4, 5, 6, 13)));
        near(program.vertexPosition(), 14.25f, 24.5f, 37, 1);
        near(field(program, "details"), 32, 240, 13, 0);
        program.runVertex(state(first));
        near(program.vertexPosition(), 11.25f, 21.5f, 34, 1); // no previous invocation/input-array leakage
        System.out.println("PASS typed attributes, real clip output, jitter accumulation, vertex isolation, translation once");

        GlslProgram vertexBuiltin=compile(compiler.translateVertex("VertexProbeBuiltin", """
            void main(){gl_Position=vec4(float(gl_VertexID),0.0,0.0,1.0);}
            """));
        check(vertexBuiltin.usesVertexId(),"vertex builtin feature metadata");
        check(!program.usesVertexId(),"ordinary vertex has no builtin dependency");
        expectRuntimeFailure(()->vertexBuiltin.runVertex(new VertexState(new CpuImage[0],1,1,0,0)),"No original vertex invocation context");
        VertexState indexed=new VertexState(new CpuImage[0],1,1,0,0).sourceVertexId(12);
        vertexBuiltin.runVertex(indexed);
        near(vertexBuiltin.vertexPosition(),12,0,0,1);
        expectFailure(()->compiler.translate("BuiltinStageProbe","void main(){int x=gl_VertexID;}",""),"vertex builtin outside vertex stage");
        GlslProgram swizzle = compile(compiler.translateVertex("VertexProbeSwizzle", """
            void adjust() { gl_Position.w += 1.0; }
            void main() {
                gl_Position = vec4(1.0, 2.0, 3.0, 4.0);
                gl_Position.xy = gl_Position.yx;
                gl_Position.xy += gl_Position.yx;
                gl_Position += gl_Position.wzyx;
                gl_Position *= vec4(2.0, 3.0, 4.0, 5.0);
                gl_Position.z -= 2.0;
                gl_Position.w /= 2.0;
                gl_Position[0] += 1.0;
                adjust();
            }
            """));
        swizzle.runVertex(new VertexState(new CpuImage[0], 16, 16, 0, 0));
        near(swizzle.vertexPosition(), 15, 18, 22, 18.5f);
        System.out.println("PASS whole/swizzle/index/compound gl_Position reads and writes, function access, RHS snapshot");

        GlslProgram scalarAlias = compile(compiler.translateVertex("VertexProbeScalarAlias", """
            void main() {
                gl_Position = vec4(2.0, 3.0, 4.0, 5.0);
                gl_Position *= gl_Position.x;
                gl_Position.xy *= gl_Position.x;
            }
            """));
        scalarAlias.runVertex(new VertexState(new CpuImage[0], 1, 1, 0, 0));
        near(scalarAlias.vertexPosition(), 16, 24, 8, 10);
        GlslProgram calls = compile(compiler.translateVertex("VertexProbeCallCount", """
            vec4 tick() { gl_Position.w += 1.0; return vec4(7.0); }
            void main() {
                gl_Position = vec4(0.0);
                gl_Position.xy = tick().xy;
                gl_Position += tick() * 2.0;
            }
            """));
        calls.runVertex(new VertexState(new CpuImage[0], 1, 1, 0, 0));
        near(calls.vertexPosition(), 21, 21, 14, 16);
        System.out.println("PASS scalar RHS alias snapshots and vector user calls execute once");

        expectFailure(() -> program.runVertex(state(input -> null)), "missing vertex input 'Position'");
        expectFailure(() -> program.runVertex(state(input -> input.name().equals("Position") ? new float[2] : null)),
            "vertex input 'Position' requires vec3");
        GlslProgram unknown = compile(compiler.translateVertex("VertexProbeUnknown", """
            in vec3 unknownPacked;
            void main() { gl_Position = vec4(unknownPacked, 1.0); }
            """));
        expectFailure(() -> unknown.runVertex(state(first)), "missing vertex input 'unknownPacked'");
        expectFailure(() -> compiler.translateVertex("VertexProbeUint", "in uvec4 packed; void main() {}"), "unsupported vertex input 'packed'");
        System.out.println("PASS missing/unknown/type-mismatched inputs fail explicitly; packed uint unsupported");

        GlslProgram fullscreen = compile(compiler.translateVertex("VertexProbeFullscreen", """
            in vec3 Position;
            in vec2 UV0;
            uniform sampler2D scene;
            out vec4 sampled;
            out vec2 texcoord;
            void main() {
                gl_Position = vec4(Position, 1.0);
                sampled = texture(scene, vec2(0.5));
                texcoord = UV0;
            }
            """));
        CpuImage image = new CpuImage(1, 1);
        image.clear(0.2f, 0.4f, 0.6f, 1);
        fullscreen.runVertex(new VertexState(new CpuImage[]{image}, 32, 24, 0.3f, 0.7f));
        near(field(fullscreen, "sampled"), 0.2f, 0.4f, 0.6f, 1);
        near(field(fullscreen, "texcoord"), 0, 0); // old fullscreen zero-initialized inputs
        near(fullscreen.vertexPosition(), 0, 0, 0, 1);
        GlslProgram fragment = compile(compiler.translate("VertexProbeFragment", """
            in vec2 texcoord;
            layout(location = 0) out vec4 color;
            void main() { color = vec4(texcoord, 0.0, 1.0); }
            """, ""));
        fragment.setFragment(0.3f, 0.7f);
        fragment.run(new VertexState(new CpuImage[0], 32, 24, 0, 0));
        near(((float[][]) fragment.getClass().getField("out").get(fragment))[0], 0.3f, 0.7f, 0, 1);
        System.out.println("PASS fullscreen constructor/default attributes/sampler and fragment varying compatibility");

        // Optional actual transformed fullscreen corpus: translate + compile every supplied vertex file.
        for (String arg : args) {
            Path path = Path.of(arg);
            List<Path> files;
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    files = stream.filter(p -> p.getFileName().toString().contains("-vertex")).sorted().toList();
                }
                check(!files.isEmpty(), "fullscreen corpus is empty: " + path);
            } else {
                files = List.of(path);
            }
            for (Path file : files) {
                GlslCompiler.Result corpusMeta=compiler.translateVertex("VertexCorpus"+serial++,Files.readString(file));
                GlslProgram p = compile(corpusMeta);
                VertexState corpusState=new VertexState(new CpuImage[32],32,24,0.5f,0.5f);
                if(Files.readString(file).contains("gl_VertexID"))corpusState.sourceVertexId(0);
                p.runVertex(corpusState);
                System.out.println("PASS fullscreen corpus " + file.getFileName());
            }
        }
        System.out.println("B3 VERTEX FOUNDATION PROBE OK (not full B3 integration)");
    }

    private static VertexState.Attributes attributes(float x, float y, float z, float material) {
        return new VertexState.Attributes(new float[]{x, y, z}, new float[]{0.1f, 0.2f, 0.3f, 1},
            new float[]{0.2f, 0.4f}, new int[]{32, 240}, new float[]{0, 0, 1}, material, new float[]{0.6f, 0.4f});
    }

    private static VertexState state(VertexState.InputProvider inputs) {
        return new VertexState(new CpuImage[0], 32, 24, 0, 0, inputs);
    }

    private static GlslProgram compile(GlslCompiler.Result meta) throws Exception {
        Files.createDirectories(WORK.resolve("generated"));
        Files.createDirectories(WORK.resolve("generated-classes"));
        String prefix = "public final class ";
        int start = meta.javaSource.indexOf(prefix);
        check(start >= 0, "generated class declaration");
        start += prefix.length();
        int end = meta.javaSource.indexOf(' ', start);
        check(end > start, "generated class name");
        String name = meta.javaSource.substring(start, end);
        Path src = WORK.resolve("generated").resolve(name + ".java");
        Files.writeString(src, meta.javaSource);
        List<String> options = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-cp",
            System.getProperty("java.class.path"), "-d", WORK.resolve("generated-classes").toString(), src.toString()));
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, System.out, System.err,
            options.toArray(new String[0]));
        check(rc == 0, "generated javac rc=" + rc + " for " + name);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{WORK.resolve("generated-classes").toUri().toURL()},
            GlslVertexProbe.class.getClassLoader())) {
            return (GlslProgram) Class.forName("dev.antimony.gl.glsl.gen." + name, true, loader)
                .getDeclaredConstructor().newInstance();
        }
    }

    private static void set(GlslProgram p, String name, Object value) throws Exception {
        p.getClass().getField(name).set(p, value);
    }

    private static float[] field(GlslProgram p, String name) throws Exception {
        return (float[]) p.getClass().getField(name).get(p);
    }

    private static void expectRuntimeFailure(Runnable run,String text){try{run.run();}catch(IllegalStateException ex){check(ex.getMessage().contains(text),"unexpected runtime diagnostic: "+ex.getMessage());return;}throw new AssertionError("expected runtime failure: "+text);}

    private static void expectFailure(Runnable run, String text) {
        try {
            run.run();
        } catch (IllegalArgumentException | GlslCompiler.TranslateException ex) {
            check(ex.getMessage().contains(text), "unexpected diagnostic: " + ex.getMessage());
            return;
        }
        throw new AssertionError("expected failure: " + text);
    }

    private static void near(float[] actual, float... expected) {
        check(actual.length == expected.length, "vector size");
        for (int i = 0; i < actual.length; i++) {
            check(Float.isFinite(actual[i]) && Math.abs(actual[i] - expected[i]) < 0.0001f,
                "component " + i + ": actual=" + actual[i] + " expected=" + expected[i]);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
