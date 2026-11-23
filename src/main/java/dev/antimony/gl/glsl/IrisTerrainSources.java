package dev.antimony.gl.glsl;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B3 source acquisition only. Iris owns include/options preprocessing and fallback
 * resolution. The CPU deliberately selects Iris' vanilla-input transform; these
 * strings are not Sodium's submitted packed-input shaders and execute no draw.
 * All Iris types stay reflective so an installation without Iris still loads.
 */
public final class IrisTerrainSources {
    public static final String TRANSFORM = "CPU_SELECTED_IRIS_PATCH_VANILLA";
    private static final String ROOT = "net.irisshaders.iris.";
    private static final AtomicLong CAPTURES = new AtomicLong();

    private IrisTerrainSources() { }

    public record Identity(String packName, String irisVersion, String dimension,
                           String pipeline, String programSet, String resolver,
                           String shaderKey, String requestedProgram, String resolvedProgram,
                           String programSource, String alphaTest, String attributes,
                           String transform, String resolvedVertexSha256,
                           String vertexSha256, String fragmentSha256) { }

    /** Final transformed stages; vertex/fragment can be handed straight to a compiler probe. */
    /** Source directives only: physical FBO side/resource generation is a separate draw-time contract. */
    public record DrawDirectives(List<Integer> drawBuffers, boolean unknownDrawBuffers,
                                 String alphaFunction, float alphaReference,
                                 String blendOverride, List<String> bufferBlendOverrides) {
        public DrawDirectives { drawBuffers=List.copyOf(drawBuffers); bufferBlendOverrides=List.copyOf(bufferBlendOverrides); }
    }
    public record Source(Identity identity, String vertex, String geometry,
                         String tessControl, String tessEval, String fragment, DrawDirectives drawDirectives) {
        public Source(Identity identity,String vertex,String geometry,String tessControl,String tessEval,String fragment) {
            this(identity,vertex,geometry,tessControl,tessEval,fragment,null);
        }
        public boolean hasExtraVertexStages() {
            return geometry != null || tessControl != null || tessEval != null;
        }
    }

    public record Capture(long sequence, String pipelineIdentity, List<Source> sources) {
        public Capture { sources = List.copyOf(sources); }
    }

    /**
     * Read-only identity lookup for a caller's once-per-pipeline diagnostic hook.
     * Does not prepare/rebuild a pipeline, update uniforms, or transform sources.
     */
    public static Object currentPipeline() throws ReflectiveOperationException {
        Class<?> iris;
        try { iris = Class.forName(ROOT + "Iris"); }
        catch (ClassNotFoundException absent) { return null; }
        Object manager = invokeStatic(iris, "getPipelineManager");
        return manager == null ? null : invoke(manager, "getPipelineNullable");
    }

    /**
     * Empty means no pack or active shader pipeline. A mismatch/API/transform
     * failure throws rather than publishing unidentified or partially captured
     * sources. Call on the render thread, after Iris has prepared its pipeline.
     */
    public static Optional<Capture> captureCurrent() throws ReflectiveOperationException {
        return captureKeys(List.of("TERRAIN_SOLID", "TERRAIN_CUTOUT"));
    }

    public static Optional<Capture> captureTerrainCurrent() throws ReflectiveOperationException {
        return captureKeys(List.of("TERRAIN_SOLID","TERRAIN_CUTOUT","TERRAIN_TRANSLUCENT"));
    }

    /** Block-entity variants selected by Iris' vanilla shader getters. */
    public static Optional<Capture> captureBlockEntitiesCurrent() throws ReflectiveOperationException {
        return captureKeys(List.of("BLOCK_ENTITY", "BLOCK_ENTITY_DIFFUSE", "BLOCK_ENTITY_BRIGHT", "BE_TRANSLUCENT"));
    }

    /** Same current pack/dimension resolver and vanilla transform, using Iris' actual shadow terrain keys. */
    public static Optional<Capture> captureShadowCurrent() throws ReflectiveOperationException {
        return captureKeys(List.of("SHADOW_TERRAIN_CUTOUT", "SHADOW_TRANSLUCENT"));
    }

    private static Optional<Capture> captureKeys(List<String> keyNames) throws ReflectiveOperationException {
        Object pipeline = currentPipeline();
        if (pipeline == null) return Optional.empty();
        Class<?> iris = Class.forName(ROOT + "Iris");
        Object pack = optional(invokeStatic(iris, "getCurrentPack"));
        if (pack == null || !Class.forName(ROOT + "pipeline.IrisRenderingPipeline").isInstance(pipeline))
            return Optional.empty();
        Object dimension = invokeStatic(iris, "getCurrentDimension");
        Object manager = invokeStatic(iris, "getPipelineManager");
        Object byDimension = field(manager, "pipelinesPerDimension");
        require(byDimension instanceof Map<?, ?> map && map.get(dimension) == pipeline,
            "Active Iris pipeline is not registered for the current dimension");
        Object resolver = field(pipeline, "resolver");
        Object programs = field(resolver, "programs");
        Object selected = pack.getClass().getMethod("getProgramSet",
            Class.forName(ROOT + "shaderpack.materialmap.NamespacedId")).invoke(pack, dimension);
        require(programs == selected && invoke(programs, "getPack") == pack,
            "Pipeline resolver ProgramSet does not belong to the current pack/dimension");
        Object textures = invoke(pipeline, "getTextureMap");
        String packName = String.valueOf(invokeStatic(iris, "getCurrentPackName"));
        String version = String.valueOf(invokeStatic(iris, "getVersion"));
        List<Source> sources = new ArrayList<>();
        Class<?> keys = Class.forName(ROOT + "pipeline.programs.ShaderKey");
        for (String name : keyNames) {
            Object key = keys.getField(name).get(null);
            Object requested = invoke(key, "getProgram");
            Object source = optional(resolver.getClass().getMethod("resolve", requested.getClass())
                .invoke(resolver, requested));
            // No pack source means Iris uses ShaderSynthesizer. Do not label it pack terrain.
            if (source == null) continue;
            require(invoke(source, "getParent") == programs,
                "Resolved terrain ProgramSource belongs to a different ProgramSet");
            Object format = invoke(key, "getVertexFormat");
            Object attributes = Class.forName(ROOT + "gl.state.ShaderAttributeInputs")
                .getConstructor(Class.forName("com.mojang.blaze3d.vertex.VertexFormat"),
                    boolean.class, boolean.class, boolean.class, boolean.class, boolean.class)
                .newInstance(format, invoke(key, "shouldIgnoreLightmap"), false,
                    invoke(key, "isGlint"), invoke(key, "isText"), false);
            Object directives = invoke(source, "getDirectives");
            Object alpha = optional(invoke(directives, "getAlphaTestOverride"));
            if (alpha == null) alpha = invoke(key, "getAlphaTest");
            Map<String, String> stages = transformProgramSource(source,
                String.valueOf(invoke(key, "getName")), alpha, attributes, textures);
            String vertex = stages.get("VERTEX"), fragment = stages.get("FRAGMENT");
            require(vertex != null && !vertex.isBlank() && fragment != null && !fragment.isBlank(),
                "Iris vanilla transform returned incomplete terrain stages");
            Identity identity = new Identity(packName, version, String.valueOf(dimension),
                identity(pipeline), identity(programs), identity(resolver), name,
                String.valueOf(requested), String.valueOf(invoke(source, "getName")), identity(source),
                String.valueOf(alpha), describeAttributes(attributes), TRANSFORM,
                sha256(stage(source, "getVertexSource")), sha256(vertex), sha256(fragment));
            Object drawValue=invoke(directives,"getDrawBuffers");
            require(drawValue instanceof int[],"Missing resolved terrain draw buffers");
            List<Integer> drawBuffers=java.util.Arrays.stream((int[])drawValue).boxed().toList();
            Object unknown=invoke(directives,"hasUnknownDrawBuffers");
            require(unknown instanceof Boolean,"Missing terrain draw buffer certainty");
            Object blend=optional(invoke(directives,"getBlendModeOverride"));
            Object overrides=invoke(directives,"getBufferBlendOverrides");
            require(overrides instanceof List<?>,"Missing terrain buffer blend directives");
            DrawDirectives draw=new DrawDirectives(drawBuffers,(Boolean)unknown,
                String.valueOf(invoke(alpha,"function")),((Number)invoke(alpha,"reference")).floatValue(),
                blend==null?"NONE":String.valueOf(blend),((List<?>)overrides).stream().map(String::valueOf).toList());
            sources.add(new Source(identity, vertex, stages.get("GEOMETRY"), stages.get("TESS_CONTROL"),
                stages.get("TESS_EVAL"), fragment, draw));
        }
        require(currentPipeline() == pipeline && optional(invokeStatic(iris, "getCurrentPack")) == pack
                && dimension.equals(invokeStatic(iris, "getCurrentDimension")),
            "Iris pack/pipeline/dimension changed during terrain capture");
        return Optional.of(new Capture(CAPTURES.incrementAndGet(), identity(pipeline), sources));
    }

    /** Exact ShaderCreator.create patchVanilla argument order, without GL shader creation. */
    static Map<String, String> transformProgramSource(Object source, String transformName,
            Object alpha, Object attributes, Object textures) throws ReflectiveOperationException {
        require(Boolean.TRUE.equals(invoke(source, "isValid")), "Invalid Iris ProgramSource");
        Class<?> patcher = Class.forName(ROOT + "pipeline.transform.TransformPatcher");
        Method patch = patcher.getMethod("patchVanilla", String.class, String.class, String.class,
            String.class, String.class, String.class, Class.forName(ROOT + "gl.blending.AlphaTest"),
            boolean.class, boolean.class, Class.forName(ROOT + "gl.state.ShaderAttributeInputs"),
            Class.forName("it.unimi.dsi.fastutil.objects.Object2ObjectMap"));
        Object result = patch.invoke(null, transformName, stage(source, "getVertexSource"),
            stage(source, "getGeometrySource"), stage(source, "getTessControlSource"),
            stage(source, "getTessEvalSource"), stage(source, "getFragmentSource"),
            alpha, false, true, attributes, textures);
        require(result instanceof Map<?, ?>, "Iris transform returned no stage map");
        Map<String, String> stages = new LinkedHashMap<>();
        for (var entry : ((Map<?, ?>) result).entrySet()) {
            if (entry.getValue() instanceof String text)
                stages.put(((Enum<?>) entry.getKey()).name(), text);
        }
        return Map.copyOf(stages);
    }

    /**
     * Optional disk diagnostic, separate from capture so an I/O failure cannot
     * discard the source record. Pass Minecraft.gameDirectory.toPath(). A unique
     * subdirectory retains each capture's identity; stage text is never prefixed
     * before #version or modified for the CPU compiler.
     */
    public static Path dump(Capture capture, Path gameDirectory) throws IOException {
        Path root = gameDirectory.resolve("antimony-shader-dump");
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "b3-vanilla-" + capture.sequence() + "-");
        for (Source source : capture.sources()) {
            Identity id = source.identity();
            String stem = id.shaderKey().toLowerCase(java.util.Locale.ROOT);
            Properties metadata = new Properties();
            metadata.setProperty("scope", "IRIS_TRANSFORMED_SOURCE; CPU_COMPILER_PREFLIGHT_ONLY; NO_SHADER_DRAW; NO_IMAGE_WRITES; NOT_PACK_CONVERTED; NOT_B3_COMPLETE");
            metadata.setProperty("originalPackModified", "false");
            metadata.setProperty("cpuRuntimeStatus", "SOURCE_ONLY; NOT_ACTIVATED");
            metadata.setProperty("executionEquivalence", "UNVERIFIED");
            metadata.setProperty("transform", id.transform());
            metadata.setProperty("sodiumSubmittedSource", "false");
            metadata.setProperty("pipeline", id.pipeline());
            metadata.setProperty("programSet", id.programSet());
            metadata.setProperty("resolver", id.resolver());
            metadata.setProperty("programSource", id.programSource());
            metadata.setProperty("pack", id.packName());
            metadata.setProperty("irisVersion", id.irisVersion());
            metadata.setProperty("dimension", id.dimension());
            metadata.setProperty("shaderKey", id.shaderKey());
            metadata.setProperty("requestedProgram", id.requestedProgram());
            metadata.setProperty("resolvedProgram", id.resolvedProgram());
            metadata.setProperty("alphaTest", id.alphaTest());
            metadata.setProperty("attributes", id.attributes());
            metadata.setProperty("patchVanilla.isLines", "false");
            metadata.setProperty("patchVanilla.hasChunkOffset", "true");
            metadata.setProperty("resolvedVertexSha256", id.resolvedVertexSha256());
            metadata.setProperty("vertexSha256", id.vertexSha256());
            metadata.setProperty("fragmentSha256", id.fragmentSha256());
            metadata.setProperty("hasExtraVertexStages", Boolean.toString(source.hasExtraVertexStages()));
            if(source.drawDirectives()!=null) {
                DrawDirectives draw=source.drawDirectives();
                metadata.setProperty("drawBuffers",draw.drawBuffers().toString());
                metadata.setProperty("unknownDrawBuffers",Boolean.toString(draw.unknownDrawBuffers()));
                metadata.setProperty("alphaFunction",draw.alphaFunction());
                metadata.setProperty("alphaReference",Float.toString(draw.alphaReference()));
                metadata.setProperty("blendOverride",draw.blendOverride());
                metadata.setProperty("bufferBlendOverrides",draw.bufferBlendOverrides().toString());
                metadata.setProperty("drawScope","SOURCE_DIRECTIVES_ONLY; PHYSICAL_FBO_SIDE_RESOURCES_NOT_CAPTURED");
            }
            writeStage(directory, stem + ".vsh", source.vertex());
            writeStage(directory, stem + ".gsh", source.geometry());
            writeStage(directory, stem + ".tcs", source.tessControl());
            writeStage(directory, stem + ".tes", source.tessEval());
            writeStage(directory, stem + ".fsh", source.fragment());
            try (var writer = Files.newBufferedWriter(directory.resolve(stem + ".identity.properties"),
                    StandardCharsets.UTF_8)) {
                metadata.store(writer, "Iris CPU-selected vanilla-input terrain source identity");
            }
        }
        return directory;
    }

    private static void writeStage(Path directory, String name, String text) throws IOException {
        if (text != null) Files.writeString(directory.resolve(name), text, StandardCharsets.UTF_8);
    }

    private static String describeAttributes(Object inputs) throws ReflectiveOperationException {
        List<String> enabled = new ArrayList<>();
        for (String flag : List.of("hasColor", "hasTex", "hasOverlay", "hasLight", "hasNormal",
                "isNewLines", "isGlint", "isText", "isIE"))
            if (Boolean.TRUE.equals(invoke(inputs, flag))) enabled.add(flag);
        return String.join(",", enabled);
    }

    private static String stage(Object source, String getter) throws ReflectiveOperationException {
        Object value = optional(invoke(source, getter));
        return value == null ? null : (String) value;
    }

    private static Object optional(Object value) {
        if (!(value instanceof Optional<?> result))
            throw new IllegalStateException("Expected Iris Optional, received " + value);
        return result.orElse(null);
    }

    private static Object invoke(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getMethod(name).invoke(owner);
    }

    private static Object invokeStatic(Class<?> type, String name) throws ReflectiveOperationException {
        return type.getMethod(name).invoke(null);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(owner.getClass().getName() + "." + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String identity(Object object) {
        return object.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(object));
    }

    private static String sha256(String text) {
        if (text == null) return "absent";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
