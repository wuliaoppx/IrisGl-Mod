package dev.antimony.gl.glsl;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Isolated synthetic fixture using the real Iris resolver, ProgramSource and TransformPatcher. */
public final class IrisTerrainSourcesProbe {
    private static final String ROOT = "net.irisshaders.iris.";
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
    public static void main(String[] args) throws Exception {
        Class<?> programType = Class.forName(ROOT + "shaderpack.loading.ProgramId");
        Class<?> setType = Class.forName(ROOT + "shaderpack.programs.ProgramSet");
        Class<?> sourceType = Class.forName(ROOT + "shaderpack.programs.ProgramSource");
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        // A clearly isolated in-memory ProgramSet: no shader pack or game is impersonated.
        Object programs = unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, setType);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<Object, Object> available = new EnumMap((Class) programType);
        set(programs, "gbufferPrograms", available);
        String vs = "#version 120\nattribute vec4 mc_Entity;\nattribute vec4 mc_midTexCoord;\n"
            + "varying vec2 texcoord;\nvoid main(){texcoord=gl_MultiTexCoord0.xy;"
            + "gl_Position=gl_ModelViewProjectionMatrix*gl_Vertex+vec4(mc_Entity.x*0.0);}\n";
        String fs = "#version 120\nvarying vec2 texcoord;\nuniform sampler2D texture;\n"
            + "void main(){gl_FragData[0]=texture2D(texture,texcoord);}\n";
        var ctor = sourceType.getDeclaredConstructor(String.class, String.class, String.class,
            String.class, String.class, String.class,
            Class.forName(ROOT + "shaderpack.properties.ProgramDirectives"), setType);
        ctor.setAccessible(true);
        Object source = ctor.newInstance("synthetic_gbuffers_terrain", vs, null, null, null, fs, null, programs);
        available.put(programType.getField("Terrain").get(null), source);
        Object resolver = Class.forName(ROOT + "shaderpack.programs.ProgramFallbackResolver")
            .getConstructor(setType).newInstance(programs);
        for (String requested : List.of("TerrainSolid", "TerrainCutout")) {
            Object resolved = resolver.getClass().getMethod("resolveNullable", programType)
                .invoke(resolver, programType.getField(requested).get(null));
            check(resolved == source, "Real Iris fallback did not resolve " + requested);
        }
        Object attributes = Class.forName(ROOT + "gl.state.ShaderAttributeInputs")
            .getConstructor(boolean.class, boolean.class, boolean.class, boolean.class, boolean.class)
            .newInstance(true, true, false, true, true);
        Object alpha = Class.forName(ROOT + "gl.blending.AlphaTest").getField("ALWAYS").get(null);
        Object textures = Class.forName("it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap")
            .getConstructor().newInstance();
        Map<String, String> stages = IrisTerrainSources.transformProgramSource(source,
            "synthetic_terrain_solid", alpha, attributes, textures);
        String vertex = stages.get("VERTEX"), fragment = stages.get("FRAGMENT");
        check(vertex != null && fragment != null, "Missing transformed stage");
        check(vertex.contains("iris_Position") && vertex.contains("iris_ChunkOffset"), "Not vanilla terrain inputs");
        check(!vertex.contains("_vert_init") && !vertex.contains("_draw_translation"), "Unexpected Sodium packed inputs");
        check(!vertex.contains("gl_Vertex") && !vertex.contains("gl_ModelViewProjectionMatrix"), "Legacy builtins remained");
        var identity = new IrisTerrainSources.Identity("ISOLATED_SYNTHETIC_FIXTURE", "actual-extracted-Iris",
            "fixture:dimension", "fixture-pipeline", "fixture-program-set", "fixture-resolver",
            "TERRAIN_SOLID", "TerrainSolid", "synthetic_gbuffers_terrain", "fixture-source", "ALWAYS",
            "color,tex,light,normal", IrisTerrainSources.TRANSFORM, "fixture", "fixture", "fixture");
        var record = new IrisTerrainSources.Source(identity, vertex, null, null, null, fragment);
        Path directory = IrisTerrainSources.dump(new IrisTerrainSources.Capture(1, "fixture", List.of(record)),
            Path.of(args[0]));
        check(Files.readString(directory.resolve("terrain_solid.vsh")).equals(vertex), "Dump modified source");
        check(Files.readString(directory.resolve("terrain_solid.identity.properties"))
            .contains("SOURCE_CAPTURE_ONLY"), "Missing scope identity");
        check(!record.hasExtraVertexStages(), "Unexpected extra stages");
        System.out.println("PASS actual Iris ProgramSource + TerrainSolid/Cutout fallback resolver + patchVanilla");
        System.out.println("PASS vanilla iris_Position/iris_ChunkOffset; detached stage strings + identity dump: " + directory);
        System.out.println("FIXTURE ONLY: no current pack, runtime capture, CPU compilation, or B3 execution asserted");
    }
}
