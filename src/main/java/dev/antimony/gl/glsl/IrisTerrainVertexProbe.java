package dev.antimony.gl.glsl;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Executes the complete captured shader. Test inputs are explicitly synthetic; wave code is never copied. */
public final class IrisTerrainVertexProbe {
    private static GlslCompiler.Result meta;
    private static GlslProgram program;
    private static Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(73), 16f/9, .05f, 512);
    private static Matrix4f view = new Matrix4f().rotateY(.4f).rotateX(-.2f);
    private static float[] chunk = {10, 20, -30};
    private static float[] camera = {1000, 63, -2000};

    public static void main(String[] args) throws Exception {
        Path source = Path.of(args.length == 0 ? "logs/round7-evidence/terrain-source/terrain_solid.vsh" : args[0]);
        meta = new GlslCompiler().translateVertex("TerrainPositionProbe", Files.readString(source));
        Path work = Path.of("build/tmp/terrain-position-probe");
        Files.createDirectories(work);
        Path generated = work.resolve("TerrainPositionProbe.java");
        Files.writeString(generated, meta.javaSource);
        int result = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-proc:none", "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"),
            "-d", work.toString(), generated.toString());
        check(result == 0, "generated Java compile");
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{work.toUri().toURL()}, IrisTerrainVertexProbe.class.getClassLoader())) {
            program = (GlslProgram) loader.loadClass("dev.antimony.gl.glsl.gen.TerrainPositionProbe").getConstructor().newInstance();
            initialize();
            float[] stone = run(0, .2f, 1, 0);
            Vector4f expected = new Vector4f(11, 22, -27, 1);
            view.transform(expected); projection.transform(expected);
            near(stone, new float[]{expected.x, expected.y, expected.z, expected.w});
            near(stone, run(0, .2f, 8, 1));
            float[] root = run(10175, .8f, 1, 0);
            near(root, stone);
            float[] top = run(10175, .2f, 1, 0);
            check(distance(top, stone) > .0001f, "grass top deforms");
            check(distance(top, run(10175, .2f, 2, 0)) > .0001f, "shader time changes displacement");
            check(distance(top, run(10175, .2f, 1, 1)) > .0001f, "shader rain changes displacement");
            float[] leafA = run(10018, .2f, 1, 0), leafB = run(10018, .8f, 1, 0);
            near(leafA, leafB);
            check(distance(leafA, stone) > .0001f, "leaves fixed weight deforms");
            float[] base = run(10175, .2f, 1, 0);
            camera[0] += 11; camera[2] -= 5;
            check(distance(base, run(10175, .2f, 1, 0)) > .0001f, "world camera phase changes wave");
            camera[0] -= 11; camera[2] += 5;
            // Same absolute vertex represented by a neighbouring section must have identical phase and clip.
            chunk[0] -= 16;
            float[] moved = runAt(17, 2, 3, 10175, .2f, 1, 0);
            near(base, moved);
            chunk[0] += 16;
            set("taaOffset", new float[]{.003f, -.002f});
            float[] jitter = run(0, .2f, 1, 0);
            near(jitter, new float[]{stone[0] + .003f*stone[3], stone[1] - .002f*stone[3], stone[2], stone[3]});
            System.out.println("PASS complete captured terrain source: stone stable; grass root/top; leaves; two times/rain; camera world phase; section rebase; projection/view/chunk once; homogeneous jitter");
            System.out.println("source=" + source + " sha256=" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
            System.out.println("stone=" + Arrays.toString(stone) + " grassTop=" + Arrays.toString(top) + " leaf=" + Arrays.toString(leafA));
            System.out.println("Synthetic explicit uniforms/material IDs; no in-game or fragment/varying/material-equivalence claim.");
        }
    }

    private static void initialize() throws Exception {
        // Every declared uniform gets an explicit typed synthetic value before known matrices/light vectors below.
        for (var entry : meta.uniformTypes.entrySet()) {
            String[] type = entry.getValue().split("#"); int size = Integer.parseInt(type[1]);
            Object value = switch (type[0]) {
                case "float" -> Float.valueOf(1);
                case "int" -> Integer.valueOf(0);
                case "boolean" -> Boolean.FALSE;
                case "float[]" -> new float[size];
                case "int[]" -> new int[size];
                default -> throw new IllegalStateException("Unsupported probe uniform " + entry);
            };
            set(entry.getKey(), value);
        }
        set("iris_ProjMat", projection.get(new float[16]));
        set("iris_ProjMatInverse", new Matrix4f(projection).invert().get(new float[16]));
        set("gbufferProjectionInverse", new Matrix4f(projection).invert().get(new float[16]));
        set("iris_ModelViewMat", view.get(new float[16]));
        set("gbufferModelView", view.get(new float[16]));
        set("iris_ModelViewMatInverse", new Matrix4f(view).invert().get(new float[16]));
        set("gbufferModelViewInverse", new Matrix4f(view).invert().get(new float[16]));
        set("iris_NormalMat", new org.joml.Matrix3f(view).invert().transpose().get(new float[9]));
        set("iris_TextureMat", new Matrix4f().get(new float[16]));
        set("iris_LightmapTextureMatrix", new Matrix4f().translation(1f/32,1f/32,0).scale(1f/256,1f/256,1).get(new float[16]));
        set("shadowModelView", new Matrix4f().get(new float[16]));
        set("shadowProjection", new Matrix4f().get(new float[16]));
        set("iris_ColorModulator", new float[]{1,1,1,1});
        set("sunPosition", new float[]{0,100,0}); set("shadowLightPosition", new float[]{0,100,0});
        set("eyeBrightnessSmooth", new int[]{240,240}); set("far", 512f);
    }
    private static float[] run(float material, float v, float time, float rain) throws Exception {
        return runAt(1,2,3,material,v,time,rain);
    }
    private static float[] runAt(float x,float y,float z,float material,float v,float time,float rain) throws Exception {
        set("iris_ChunkOffset",chunk.clone()); set("cameraPosition",camera.clone());
        set("frameTimeCounter",time); set("rainStrength",rain);
        var attributes = new VertexState.Attributes(new float[]{x,y,z},new float[]{1,1,1,1},
            new float[]{.2f,v},new int[]{240,240},new float[]{0,1,0},material,new float[]{.5f,.5f});
        program.runVertex(new VertexState(new CpuImage[0],854,480,0,0,
            input -> input.name().equals("blockEntityId") ? Integer.valueOf(0) : attributes.value(input)));
        float[] result = program.vertexPosition();
        for(float value:result) check(Float.isFinite(value),"finite clip");
        return result;
    }
    private static void set(String name,Object value) throws Exception {
        String field=meta.uniformFields.get(name);
        if(field!=null) program.getClass().getField(field).set(program,value);
    }
    private static float distance(float[] a,float[] b) {float sum=0; for(int i=0;i<4;i++)sum+=Math.abs(a[i]-b[i]);return sum;}
    private static void near(float[] a,float[] b) {check(distance(a,b)<.0002f,"clip expected="+Arrays.toString(b)+" actual="+Arrays.toString(a));}
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
