package dev.antimony.gl.glsl;

import dev.antimony.core.irisgl.IrisGlContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** First B3 slice: current overworld solid/cutout vertex positions only.
 * Raster varyings, fragments and material lighting still use the ordinary renderer.
 * All state belongs to the render thread; generated instances are never shared with workers.
 */
public final class IrisTerrainVertex {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Antimony");
    private static final Map<String, GlslProgramLoader.Loaded> PROGRAMS = new HashMap<>();
    private static final Set<String> REQUESTED = new HashSet<>();
    private static final Map<String,GlslProgramLoader.Loaded> FRAGMENTS=new HashMap<>();
    private static final Map<String,IrisTerrainSources.Source> SOURCES=new HashMap<>();
    private static final Map<String,ShaderVaryings> LINKS=new HashMap<>();
    private static final Map<GlslProgramLoader.Loaded,dev.antimony.core.irisgl.RasterShader> FRAME_DRAWS=new HashMap<>();
    private static final Map<GlslProgramLoader.Loaded, ShaderVaryings.Access> OUTPUT_ACCESS = new HashMap<>();
    private static final Map<GlslProgramLoader.Loaded, dev.antimony.core.irisgl.RasterShader> PAYLOAD_DRAWS = new HashMap<>();
    private static Object pipeline;
    private static boolean disabled;
    private static String initializationFailure;
    static String initializationFailure(){return initializationFailure;}
    private static GlslProgramLoader.Loaded active;
    private static dev.antimony.core.irisgl.RasterShader activeBlockDraw;
    private static CpuImage[] activeVertexImages = new CpuImage[0];
    private static Integer activeBlockId;
    public static boolean blockEntityActive() { return activeBlockId != null && active != null; }
    public static int currentBlockEntityId() { return activeBlockId == null ? 0 : activeBlockId; }
    private static boolean blockKey(String key) { return key.startsWith("BLOCK_ENTITY") || key.equals("BE_TRANSLUCENT"); }
    private static long vertices;
    private static final boolean DIAG = "on".equalsIgnoreCase(System.getProperty("antimony.terrain.diag", "off"));
    private static final Set<String> DIAG_MATERIALS = new HashSet<>();
    private static long foliageVertices;
    private static final Set<Integer> FOLIAGE = Set.of(10175, 10176, 10031, 10059, 10018, 10032);

    private IrisTerrainVertex() { }

    /** Compiles the real selected Iris vanilla transform once per active pipeline. */
    static void preparePipeline(Object current) {
        endScope();
        if (pipeline == current) return;
        pipeline = current;
        disabled = false;initializationFailure=null;
        PROGRAMS.clear();FRAGMENTS.clear();SOURCES.clear();LINKS.clear();FRAME_DRAWS.clear();
        REQUESTED.clear(); OUTPUT_ACCESS.clear(); PAYLOAD_DRAWS.clear();
        DIAG_MATERIALS.clear();
        vertices = foliageVertices = 0;
        try {
            var capture = IrisTerrainSources.captureTerrainCurrent().orElseThrow(
                () -> new IllegalStateException("No identified pack terrain source"));
            var sources = new java.util.ArrayList<>(capture.sources());
            IrisTerrainSources.captureBlockEntitiesCurrent().ifPresent(blocks -> sources.addAll(blocks.sources()));
            for (IrisTerrainSources.Source source : sources) {
                var identity = source.identity();
                LOG.info("[Antimony] terrain source draw directives key={} contract={} (physical FBO/RT generation not activated)", identity.shaderKey(),source.drawDirectives());
                if (!identity.dimension().equals("minecraft:overworld"))
                    throw new IllegalStateException("Terrain vertex slice currently supports overworld only");
                if (!IrisTerrainSources.TRANSFORM.equals(identity.transform()) || source.hasExtraVertexStages())
                    throw new IllegalStateException("Unsupported terrain transform or extra vertex stages");
                // Separate positive IDs avoid collisions with the shim's GL program namespace.
                int id = switch(identity.shaderKey()) {
                    case "TERRAIN_SOLID" -> 1_500_000_001;
                    case "TERRAIN_CUTOUT" -> 1_500_000_002;
                    case "TERRAIN_TRANSLUCENT" -> 1_500_000_003;
                    case "BLOCK_ENTITY" -> 1_500_000_011;
                    case "BLOCK_ENTITY_DIFFUSE" -> 1_500_000_012;
                    case "BLOCK_ENTITY_BRIGHT" -> 1_500_000_013;
                    case "BE_TRANSLUCENT" -> 1_500_000_014;
                    default -> throw new IllegalStateException("Unknown geometry key " + identity.shaderKey());
                };
                GlslProgramLoader.Loaded loaded = GlslProgramLoader.loadVertex(id, source.vertex());
                if (loaded == null) throw new IllegalStateException(GlslProgramLoader.vertexFailure(id));
                if (!loaded.meta.samplerUnits.isEmpty() && !blockKey(identity.shaderKey()))
                    throw new IllegalStateException("Terrain vertex texture sampling is not yet connected");
                PROGRAMS.put(identity.shaderKey(), loaded);
                ShaderVaryings layout = null;
                try {
                    GlslProgramLoader.Loaded fragmentProgram=GlslProgramLoader.load(id+50_000_000,source.fragment(),source.vertex());
                    if(fragmentProgram==null)throw new IllegalStateException("Terrain FS compile: "+GlslProgramLoader.failure(id+50_000_000));
                    GlslCompiler.Result fragment = fragmentProgram.meta;
                    layout = ShaderVaryings.link(loaded.meta, fragment);
                    FRAGMENTS.put(identity.shaderKey(),fragmentProgram);SOURCES.put(identity.shaderKey(),source);LINKS.put(identity.shaderKey(),layout);
                    REQUESTED.addAll(fragment.usedUniforms);
                    LOG.info("[Antimony] terrain FS link preflight key={} liveInputs={} components={}; resources/attachments lifecycle still required before activation",
                        identity.shaderKey(),fragment.usedVaryings,layout.components());
                } catch (RuntimeException unsupportedFragment) {
                    if(initializationFailure==null)initializationFailure=identity.shaderKey()+": "+unsupportedFragment;
                    LOG.warn("[Antimony] terrain FS not activated key={} sha256={} reason={} (no fabricated input)",
                        identity.shaderKey(),identity.fragmentSha256(),unsupportedFragment.toString());
                    try { layout = ShaderVaryings.outputs(loaded.meta); }
                    catch (RuntimeException unsupportedDiagnostic) {
                        LOG.info("[Antimony] optional VS output transport unavailable key={}; position bridge unaffected: {}",identity.shaderKey(),unsupportedDiagnostic.toString());
                    }
                }
                if (layout != null) {
                    OUTPUT_ACCESS.put(loaded, layout.vertexAccess(loaded.program));
                    PAYLOAD_DRAWS.put(loaded, new VertexPayloadDraw(layout, identity.shaderKey(), DIAG));
                    LOG.info("[Antimony] terrain payload transport compiled key={} components={} lanes={} (FS/RT not activated)",
                        identity.shaderKey(),layout.components(),layout.lanes());
                }
                REQUESTED.addAll(loaded.meta.usedUniforms);
                LOG.info("[Antimony] terrain vertex position slice compiled key={} pack={} dimension={} transform={} sha256={} (varying/fragment/material equivalence NOT implemented)",
                    identity.shaderKey(), identity.packName(), identity.dimension(), identity.transform(), identity.vertexSha256());
            }
            if (!PROGRAMS.keySet().containsAll(Set.of("TERRAIN_SOLID", "TERRAIN_CUTOUT")))
                throw new IllegalStateException("Both solid and cutout pack sources are required");
        } catch (Throwable failure) {
            initializationFailure=failure.toString();
            disable(failure);
        }
    }

    static Set<String> requestedUniforms() { return Set.copyOf(REQUESTED); }
    static void prepareFragmentFrame(){
        FRAME_DRAWS.clear();
        if(disabled)throw new IllegalStateException("Current terrain shader bridge unavailable");
        for(String key:PROGRAMS.keySet()){
            if(key.equals("TERRAIN_TRANSLUCENT") || blockKey(key))continue;
            if(!FRAGMENTS.containsKey(key)||!LINKS.containsKey(key))throw new IllegalStateException("Missing linked terrain fragment "+key);
            FRAME_DRAWS.put(PROGRAMS.get(key),IrisPostChain.terrainFragmentDraw(SOURCES.get(key),FRAGMENTS.get(key),LINKS.get(key),false));
        }
    }

    static void prepareTranslucentFrame(){
        if(disabled)throw new IllegalStateException("Current terrain shader bridge unavailable");
        String key="TERRAIN_TRANSLUCENT";
        if(!PROGRAMS.containsKey(key)||!FRAGMENTS.containsKey(key)||!LINKS.containsKey(key))
            throw new IllegalStateException("Missing linked transparent terrain stages");
        FRAME_DRAWS.put(PROGRAMS.get(key),IrisPostChain.terrainFragmentDraw(SOURCES.get(key),FRAGMENTS.get(key),LINKS.get(key),true));
    }

    /** Open around one section/layer after its section-to-camera translation is installed. */
    public static void beginScope(String key, IrisGlContext context) {
        active = null;
        if (disabled || pipeline == null || !IrisPostChain.terrainFrameReady(pipeline)) return;
        try {
            GlslProgramLoader.Loaded loaded = PROGRAMS.get(key.equals("TERRAIN_CUTOUT_MIPPED") ? "TERRAIN_CUTOUT" : key);
            if (loaded == null) throw new IllegalStateException("Unknown terrain layer " + key);
            if(IrisPostChain.gbufferActive()&&!FRAME_DRAWS.containsKey(loaded))throw new IllegalStateException("Missing current terrain fragment draw "+key);
            IrisPostChain.applyTerrainUniforms(loaded, matrices(context));
            active = loaded;
        } catch (Throwable failure) {
            disable(failure);
        }
    }

    /** One material/texture batch, after vanilla has baked its PoseStack into mesh vertices. */
    public static void beginBlockEntity(String key, int material, dev.antimony.core.irisgl.IrisGlTexture texture,
            dev.antimony.core.irisgl.IrisGlTexture overlay, IrisGlContext context) {
        endScope();
        if (!IrisPostChain.gbufferActive()) return;
        try {
            if (disabled || !IrisPostChain.terrainFrameReady(pipeline)) throw new IllegalStateException("No current geometry pipeline");
            var loaded = PROGRAMS.get(key);
            if (loaded == null || !blockKey(key)) throw new IllegalStateException("Uncaptured block-entity shader " + key);
            var matrices = matrices(context);
            matrices.put("iris_ChunkOffset", new float[]{0, 0, 0});
            // Block entity positions/normals already contain the per-entity PoseStack.
            matrices.put("iris_TextureMat", com.mojang.blaze3d.systems.RenderSystem.getTextureMatrix().get(new float[16]));
            IrisPostChain.applyTerrainUniforms(loaded, matrices);
            activeVertexImages = blockVertexImages(loaded, overlay);
            activeBlockDraw = IrisPostChain.blockEntityFragmentDraw(SOURCES.get(key), FRAGMENTS.get(key), LINKS.get(key), texture, matrices, context);
            activeBlockId = material;
            active = loaded;
        } catch (RuntimeException failure) {
            endScope();
            IrisPostChain.cpuImageProducerFailure("Block-entity draw rejected: " + failure);
            throw failure;
        }
    }

    static CpuImage[] blockVertexImages(GlslProgramLoader.Loaded loaded, dev.antimony.core.irisgl.IrisGlTexture overlay) {
        CpuImage[] images = new CpuImage[loaded.meta.samplerUnits.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1];
        for (String sampler : loaded.meta.usedSamplers) {
            if (!sampler.equals("iris_overlay") || !"sampler2D".equals(loaded.meta.samplerTypes.get(sampler)) || overlay == null)
                throw new IllegalStateException("Unsupported/missing block vertex sampler " + sampler);
            int unit = loaded.meta.samplerUnits.get(sampler);
            images[unit] = IrisPostChain.cpuTextureImage(overlay);
        }
        for (int unit : loaded.meta.samplerUnits.values()) try {
            loaded.program.getClass().getField("s$" + unit).setInt(loaded.program, unit);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Block vertex sampler slot", e); }
        return images;
    }

    public static void endScope() { active = null; activeBlockId = null; activeBlockDraw = null; activeVertexImages = new CpuImage[0]; }

    public static boolean active() { return active != null; }
    public static dev.antimony.core.irisgl.RasterShader rasterShader() {
        if (activeBlockId != null) return activeBlockDraw;
        return IrisPostChain.gbufferActive()?FRAME_DRAWS.get(active):PAYLOAD_DRAWS.get(active);
    }

    /** Context discarded all staged vertices before raster submission. */
    public static void rejectPrimitive(Throwable failure) { disable(failure); }

    /** Writes final homogeneous clip exactly once; false explicitly uses ordinary renderer MVP. */
    public static boolean clipVertex(IrisGlContext context, VertexState.InputProvider provider,
                                     float[] destination, int offset) {
        GlslProgramLoader.Loaded loaded = active;
        if (loaded == null) return false;
        try {
            if (provider == null) throw new IllegalStateException("Terrain mesh has no explicit input provider");
            VertexState state = new VertexState(activeVertexImages, context.framebuffer.width,
                context.framebuffer.height, 0, 0, provider);
            if(loaded.program.usesVertexId()){
                Object originalId=provider.value(new GlslProgram.VertexInput("gl_VertexID","",GlslProgram.VertexInputType.INT));
                if(!(originalId instanceof Integer id)||id<0)throw new IllegalStateException("Missing original draw vertex ID");
                state.sourceVertexId(id);
            }
            loaded.program.runVertex(state);
            float[] clip = loaded.program.vertexPosition();
            if (clip.length != 4) throw new IllegalStateException("Invalid terrain clip arity");
            for (float value : clip) if (!Float.isFinite(value))
                throw new IllegalStateException("Nonfinite terrain clip position");
            System.arraycopy(clip, 0, destination, offset, 4);
            ShaderVaryings.Access output = OUTPUT_ACCESS.get(loaded);
            if (output != null && destination.length >= offset + 17 + PAYLOAD_DRAWS.get(loaded).layout().components()) {
                try { output.capture(destination, offset + 17); }
                catch (RuntimeException invalidPayload) {
                    if(IrisPostChain.gbufferActive())throw invalidPayload;
                    OUTPUT_ACCESS.remove(loaded); PAYLOAD_DRAWS.remove(loaded);
                    LOG.warn("[Antimony] optional VS payload transport rejected; gl_Position bridge retained: {}",invalidPayload.toString());
                }
            }
            if (++vertices == 1) LOG.info("[Antimony] terrain vertex position slice executing original mesh vertices; current uniform binding succeeded live={} declared={}; clip consumed without second MVP; generated varying payload transported separately; fragment shading remains ordinary renderer",
                loaded.meta.usedUniforms.size(), loaded.meta.uniformFields.size());
            if (DIAG) diagnostic(context, loaded, provider, clip);
            return true;
        } catch (Throwable failure) {
            disable(failure);
            return false;
        }
    }

    /** Bounded diagnostic only: delta excludes declared taaOffset so jitter is not called foliage displacement. */
    private static void diagnostic(IrisGlContext context, GlslProgramLoader.Loaded loaded,
                                   VertexState.InputProvider provider, float[] clip) {
        try {
            float[] entity = (float[]) provider.value(new GlslProgram.VertexInput("mc_Entity", "", GlslProgram.VertexInputType.VEC4));
            int material = (int) entity[0];
            if (FOLIAGE.contains(material)) foliageVertices++;
            if (vertices == 1) LOG.info("[Antimony] terrain current bound uniforms frameTimeCounter={} rainStrength={} taaOffset={}",
                boundDiagnosticValue(loaded, "frameTimeCounter"), boundDiagnosticValue(loaded, "rainStrength"),
                boundDiagnosticValue(loaded, "taaOffset"));
            if (vertices == 1 || vertices == 10000 || vertices == 1000000)
                LOG.info("[Antimony] terrain vertex execution count={} foliageCount={} pipeline={}", vertices, foliageVertices, pipeline.getClass().getName());
            String key = loaded.className + ":" + material;
            if (DIAG_MATERIALS.size() >= 24 || !DIAG_MATERIALS.add(key)) return;
            float[] p = (float[]) provider.value(new GlslProgram.VertexInput("iris_Position", "", GlslProgram.VertexInputType.VEC3));
            org.joml.Vector4f base = new org.joml.Vector4f(p[0], p[1], p[2], 1);
            context.model.transform(base); context.view.transform(base); context.projection.transform(base);
            float jitterX = 0, jitterY = 0;
            String jitterField = loaded.meta.uniformFields.get("taaOffset");
            if (jitterField != null) {
                float[] jitter = (float[]) loaded.program.getClass().getField(jitterField).get(loaded.program);
                jitterX = jitter[0] * base.w; jitterY = jitter[1] * base.w;
            }
            LOG.info("[Antimony] terrain vertex sample program={} material={} count={} foliageCount={} clip={} baseMvp={} declaredTaaClip=[{},{}] residualClip=[{},{},{},{}] (residual excludes taaOffset; other pack position effects remain)",
                loaded.className, material, vertices, foliageVertices, java.util.Arrays.toString(clip), base,
                jitterX, jitterY, clip[0]-base.x-jitterX, clip[1]-base.y-jitterY, clip[2]-base.z, clip[3]-base.w);
        } catch (Throwable diagnosticFailure) {
            // Diagnostics never affect shader execution or become a per-vertex exception stream.
            if (DIAG_MATERIALS.add("diagnostic-error")) LOG.warn("[Antimony] terrain vertex diagnostic unavailable: {}", diagnosticFailure.toString());
        }
    }

    private static Object boundDiagnosticValue(GlslProgramLoader.Loaded loaded, String name) throws ReflectiveOperationException {
        if (!loaded.meta.usedUniforms.contains(name)) return "not-live";
        Object value = loaded.program.getClass().getField(loaded.meta.uniformFields.get(name)).get(loaded.program);
        return value instanceof float[] floats ? java.util.Arrays.toString(floats) : value;
    }

    private static void disable(Throwable failure) {
        active = null;
        if (!disabled) LOG.warn("[Antimony] terrain vertex bridge fail-closed for this pipeline; ordinary renderer position fallback: {}", failure.toString());
        disabled = true;
        if(IrisPostChain.gbufferActive())IrisPostChain.cpuImageProducerFailure("Terrain vertex/fragment rejected: "+failure);
    }

    /** patchVanilla inserts ChunkOffset itself. Keep section translation out of ModelViewMat. */
    static Map<String, float[]> matrices(IrisGlContext context) {
        Matrix4f model = context.model;
        if (model.m00() != 1 || model.m11() != 1 || model.m22() != 1 || model.m33() != 1
                || model.m01() != 0 || model.m02() != 0 || model.m03() != 0
                || model.m10() != 0 || model.m12() != 0 || model.m13() != 0
                || model.m20() != 0 || model.m21() != 0 || model.m23() != 0)
            throw new IllegalStateException("Terrain model must be a section-to-camera translation");
        Map<String, float[]> values = new HashMap<>();
        values.put("iris_ChunkOffset", new float[]{model.m30(), model.m31(), model.m32()});
        values.put("iris_ModelViewMat", context.view.get(new float[16]));
        values.put("iris_ModelViewMatInverse", new Matrix4f(context.view).invert().get(new float[16]));
        values.put("iris_ProjMat", context.projection.get(new float[16]));
        values.put("iris_ProjMatInverse", new Matrix4f(context.projection).invert().get(new float[16]));
        values.put("iris_NormalMat", new Matrix3f(context.view).invert().transpose().get(new float[9]));
        values.put("iris_TextureMat", new Matrix4f().get(new float[16]));
        // Vanilla extended terrain UV2 is an integer 0..240; legacy texture matrix maps to texel centres.
        values.put("iris_LightmapTextureMatrix", new Matrix4f().translation(1.0f / 32, 1.0f / 32, 0)
            .scale(1.0f / 256, 1.0f / 256, 1).get(new float[16]));
        values.put("iris_ColorModulator", new float[]{1, 1, 1, 1});
        // Terrain executes at framebuffer resolution; post has its separate scaled viewport owner.
        float width = context.framebuffer.width, height = context.framebuffer.height;
        values.put("viewWidth", new float[]{width});
        values.put("viewHeight", new float[]{height});
        values.put("aspectRatio", new float[]{width / height});
        values.put("aspectRatioInverse", new float[]{height / width});
        values.put("pixelSize", new float[]{1 / width, 1 / height});
        values.put("pixelSizeX", new float[]{1 / width});
        values.put("pixelSizeY", new float[]{1 / height});
        values.put("viewWidthInverse", new float[]{1 / width});
        values.put("viewHeightInverse", new float[]{1 / height});
        for (var entry : values.entrySet()) for (float value : entry.getValue())
            if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite terrain matrix " + entry.getKey());
        return values;
    }
}
