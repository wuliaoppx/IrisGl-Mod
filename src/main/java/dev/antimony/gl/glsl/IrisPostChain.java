package dev.antimony.gl.glsl;

import dev.antimony.AntimonyMod;
import dev.antimony.core.irisgl.IrisGlFramebuffer;
import dev.antimony.gl.shim.GlShimState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Phase A3: the CPU fullscreen post-processing chain (docs section 19).
 *
 * <p>Iris builds its composite/deferred/final programs on the real driver
 * during pipeline setup; phase 6 already captured every linked program's GLSL
 * ({@link GlShimState}). This class re-runs that same pass graph on the CPU:
 * for every pass it asks Iris to {@code Program.use()} (which uploads the
 * per-frame uniforms through the phase-6 shim, where they are recorded), binds
 * the pass' render targets to the CPU mirror images, executes the A2-translated
 * fragment program once per pixel and writes the results into the target
 * mirror. The final pass is blitted back into the software framebuffer.</p>
 *
 * <p>Passes whose inputs are not available on the CPU yet (shadow maps, the
 * pack's gbuffer outputs, custom textures) are skipped with a log line instead
 * of producing garbage; phase B fills those inputs in and the same executor
 * runs them unchanged.</p>
 *
 * <p>Everything here is reflective: Iris is a separate mod that is not on this
 * mod's compile classpath, and the whole layer must do nothing when Iris is
 * absent.</p>
 */
public final class IrisPostChain {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Antimony");
    // Mutable (not final) so the A4 Sodium options page can flip them live;
    // initialized from the system property (command line) and overridden by
    // config/antimony-render.json at loadFromDisk time.
    private static volatile boolean enabled = !"off".equalsIgnoreCase(System.getProperty("antimony.post", "on"));
    private static volatile float scale = Float.parseFloat(System.getProperty("antimony.post.scale", "0.5"));
    private static final boolean DIAG =
        "on".equalsIgnoreCase(System.getProperty("antimony.post.diag", "off"));
    private static final float EXPOSURE =
        Float.parseFloat(System.getProperty("antimony.post.exposure", "1.0"));

    /** Applies the persisted GUI settings once, at client-setup time. */
    public static void loadRenderOptions(boolean postEnabled, float scalePercent) {
        enabled = postEnabled;
        scale = Math.max(0.25f, Math.min(1.0f, scalePercent / 100.0f));
    }
    /**
     * Fraction of the window resolution the chain evaluates at. A CPU pixel
     * costs ~0.4-1.2 us, so a full-resolution chain would halve the frame rate
     * for a bloom that is low-frequency anyway; the final image is upscaled
     * with a bilinear pass. 1.0 = native.
     */
    private static final int MAX_UNITS = 16;
    /**
     * Passes excluded from execution, comma-separated program ids. Phase B1
     * translated the fullscreen passes' vertex stages, so every corpus stage
     * runs now; the property stays for debugging a single pass.
     */
    private static final Set<Integer> SKIP_PROGRAMS = parseIds(
        System.getProperty("antimony.post.skip", ""));
    /** Render target indices seeded with the software framebuffer before the chain runs. */
    private static final Set<Integer> SEED_TARGETS = parseSeeds(
        System.getProperty("antimony.post.seed", "0,1"));
    /**
     * Render target holding the pack's exposure persistence value (gaux3 =
     * colortex6): its centre pixel must read a sane exposure (1.0) or the
     * vertex-stage auto-exposure mix collapses the frame to black.
     */
    private static final int EXPOSURE_SEED_RT =
        Integer.getInteger("antimony.post.exposureSeed", 6);
    private static final String[] COMPOSITE_FIELDS = {
        "prepareRenderer", "deferredRenderer", "compositeRenderer"
    };
    private static final int THREADS = Math.max(1,
        Math.min(8, Runtime.getRuntime().availableProcessors() - 1));

    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "antimony-post");
        t.setDaemon(true);
        return t;
    });

    private IrisPostChain() {
    }

    private static Set<Integer> parseSeeds(String spec) {
        Set<Integer> out = new HashSet<>();
        for (String part : spec.split(",")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // keep the default set
            }
        }
        if (out.isEmpty()) {
            out.add(0);
            out.add(1);
        }
        return out;
    }

    private static Set<Integer> parseIds(String spec) {
        Set<Integer> out = new HashSet<>();
        for (String part : spec.split(",")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // keep the default set
            }
        }
        return out;
    }

    /**
     * B1 sky-pixel write masks (docs section 21): passes whose fragment has no
     * depth guard (the GPU relies on the depth test instead). Writes land only
     * where the seeded depth says "far plane" (>= 0.9999), so terrain keeps
     * the gbuffer content underneath. Keyed by PASS NAME, not program id:
     * the shim's fake program ids shift between launches (they count every
     * intercepted object creation), so an id-keyed mask silently guarded the
     * wrong pass and let the sky paint over the whole screen.
     */
    private static final Set<String> SKY_MASK_PASSES = parseNames(
        System.getProperty("antimony.post.skymask", "prepare"));

    private static Set<String> parseNames(String spec) {
        Set<String> out = new HashSet<>();
        for (String part : spec.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Diagnostics / counters
    // ------------------------------------------------------------------
    private static volatile boolean failed;
    /**
     * P-01: consecutive-frame failure counter. {@code failed} used to be set by
     * the first exception of any kind and never cleared, so one transient blip
     * (a resize landing between two passes, a texture upload arriving late)
     * silently removed the whole post chain for the rest of the session - and
     * because {@link #enabled()} then reports false, vanilla's clouds and the
     * pre-pack sky came back, which looked like "the shader randomly stopped
     * working". Now a failure skips a frame, five failures in a row disable the
     * chain, and a successful frame resets the counter.
     */
    private static int consecutiveFailures;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** Pipeline object that was running when the chain gave up (P-01 recovery). */
    private static Object failedPipeline;
    private static boolean loggedMissing, loggedInactiveCapture;
    private static long frames;
    /** Never-reset frame ordinal, for one-shot diagnostics. */
    private static long frameNo;
    private static boolean firstCompletedFrameLogged;
    private static final String DIAG_RUN = java.util.UUID.randomUUID().toString();
    private static long chainNanos;
    private static long lastStatsFrame;

    public static boolean enabled() {
        return enabled && !failed;
    }

    public static boolean failed() {
        return failed;
    }

    public static boolean hasPipeline() {
        Object current = enabled() ? pipeline() : null;
        return current != null && field(current, "renderTargets") != null;
    }

    public static int declaredPassCount() {
        Chain current = chain;
        return current == null ? 0 : current.passes.size();
    }

    public static String startupWaitDetail() {
        if (failed) return "光影链遇到错误，查看游戏日志";
        String failure = cpuImageFailure;
        if (failure != null) {
            if (failure.contains("Shadow section is pending")) {
                return dev.antimony.core.render.IrisGlRenderer.startupSceneDetail();
            }
            if (failure.contains("Solas shadow producer")) return "正在准备 Solas 阴影与体素数据";
            return "准备场景数据：" + failure;
        }
        return "准备场景、阴影和光影资源";
    }

    private static final DepthFrame DEPTH_FRAME = new DepthFrame();

    /** Starts before any world geometry, including early-return frames. No depth work without a pack. */
    public static long beginDepthFrame(Object world, IrisGlFramebuffer framebuffer) {
        CPU_IMAGES.invalidateCurrent();cpuImageFrame=null;
        frameStateReady = false;
        frameCaptureAttempted = false;
        preparedPipeline = null;
        uniformFrame = IrisUniformFrame.empty();
        cpuFrameInputs=null;shadowFrame=null;
        gbufferFrame=gbufferComplete=deferredComplete=false;frameContent.clear();earlyPasses.clear();
        IrisTerrainVertex.endScope();
        Object current = enabled() ? pipeline() : null;
        boolean active = current != null && field(current, "renderTargets") != null;
        return DEPTH_FRAME.begin(world, current, framebuffer, active);
    }

    /** Flush is owned by the boundary caller, so no queued triangles can leak into an earlier copy. */
    public static void captureDepth(long generation, DepthFrame.Stage stage, Object world,
            IrisGlFramebuffer framebuffer) {
        DEPTH_FRAME.capture(generation, stage, world, framebuffer);
    }

    public static void invalidateDepthFrame() {
        DEPTH_FRAME.invalidate();
    }

    // ------------------------------------------------------------------
    // Reflection bridge to Iris
    // ------------------------------------------------------------------
    private static Object pipeline() {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object manager = iris.getMethod("getPipelineManager").invoke(null);
            if (manager == null) {
                return null;
            }
            return manager.getClass().getMethod("getPipelineNullable").invoke(manager);
        } catch (ClassNotFoundException t) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object field(Object owner, String name) {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Object call(Object owner, String name, Class<?>[] types, Object... args) {
        try {
            Method m = owner.getClass().getMethod(name, types);
            m.setAccessible(true);
            return m.invoke(owner, args);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Chain model
    // ------------------------------------------------------------------
    private static final class RtDef {
        int index;
        int mainTexture;
        int altTexture;
        int width;
        int height;
        TerrainFragmentDraw.Encoding encoding;
    }

    private static final class PassDef {
        String name;
        String renderer;
        String textureStage;
        Object customTextureIds;
        Object flippedAtLeastOnce;
        Object stageReadsFromAlt;
        final Map<String, Integer> samplerGlUnits = new HashMap<>();
        Object program;
        int programId;
        Object framebuffer;
        int[] drawBuffers;
        Set<Integer> mipmappedBuffers=Set.of();
        final Map<CpuImage,CpuImage> mipViews=new java.util.IdentityHashMap<>();
        boolean finalPass;
        boolean reportedSkips;
    }

    private static final class Chain {
        final List<RtDef> targets = new ArrayList<>();
        final List<PassDef> passes = new ArrayList<>();
        Object renderTargets;
        Object finalRenderer;
        String targetLayout;
        final Map<Integer, CpuImage> historySnapshots = new HashMap<>();
        int historyWidth, historyHeight;
        boolean fullClearPending;
        int[] presentationBefore;
        final Map<Integer, Object> resourceAccess = new HashMap<>();
        final Map<Integer, IrisSamplerBridge.Filtering> resourceFiltering = new HashMap<>();
        final Map<Integer, CpuImage> resourceImages = new HashMap<>();
        final Map<Integer, String> resourceSources = new HashMap<>();
        int depthTexture;
        int depthNoTranslucentsTexture;
        int depthNoHandTexture;
        int noiseTexture;
    }

    private static Object cachedPipeline;
    private static Chain chain;
    private static IrisComputeGraph.Snapshot computeGraph;
    private static String computeGraphFailure;
    private static String pipelineFailure;
    private static String cpuImageFailure;
    private static List<IrisImageDescriptors.Image> imageDescriptors=List.of();
    private static final IrisCpuImageResources CPU_IMAGES=new IrisCpuImageResources();
    private static volatile IrisCpuImageResources.Frame cpuImageFrame;
    private static final IrisCpuFrameInputs CPU_FRAME_INPUTS=new IrisCpuFrameInputs();
    private static volatile IrisCpuFrameInputs.Snapshot cpuFrameInputs;
    private static IrisShadowFrame shadowFrame;
    public static IrisShadowFrame shadowFrame(){return shadowFrame;}
    public static boolean preparedShadowFrame(){return frameStateReady&&preparedPipeline==cachedPipeline&&shadowFrame!=null&&shadowFrame.pipeline()==cachedPipeline;}
    public static long currentShadowOrdinal(){return cpuImageFrame==null?cpuImageFrameOrdinal:cpuImageFrame.frameOrdinal();}
    private static CpuComputeDispatch cpuComputeDispatch;
    private static Object cpuComputePipeline;
    private static int cpuComputeProgramId=-1;
    private static long cpuComputeGeneration;
    private static long cpuComputeFrameOrdinal=-1;
    private static long cpuComputeSuccesses,rejectedPostFrames,lastResourceWarning;
    private static String lastResourceReason;
    private static long cpuImageFrameOrdinal;
    private static boolean gbufferFrame,gbufferComplete,deferredComplete;
    private static final Set<Integer> frameContent=new HashSet<>();
    private static final Set<PassDef> earlyPasses=new HashSet<>();
    private static long gbufferFrames;

    static boolean gbufferActive(){return gbufferFrame&&cpuImageFailure==null;}
    private static boolean currentComputeReady(){
        return computeGraph==null||!computeGraph.requiresExecution()||cpuImageFrame!=null
            &&cpuComputePipeline==cachedPipeline&&cpuComputeGeneration==cpuImageFrame.generation()
            &&cpuComputeFrameOrdinal==cpuImageFrame.frameOrdinal();
    }
    private static void beginGbufferResources(){
        if(chain==null||!currentComputeReady()||!IrisCpuShadowTargets.ready(cachedPipeline,currentShadowOrdinal()))throw new IllegalStateException("No completed current world resource schedule");
        var fb=dev.antimony.core.irisgl.IrisGl.framebuffer();
        dev.antimony.core.irisgl.IrisGl.flushRaster();
        chainW=fb.width;chainH=fb.height;refreshTargetLayout(chain,chainW,chainH);
        for(RtDef rt:chain.targets){
            if(dev.antimony.core.render.RenderQualitySettings.mapWidth(rt.width)!=chainW||dev.antimony.core.render.RenderQualitySettings.mapHeight(rt.height)!=chainH)throw new IllegalStateException("Scaled terrain attachment requires its own viewport: rt"+rt.index);
            CpuImage main=imageFor(IMAGE_POOL,rt.mainTexture,chainW,chainH);
            if(main!=null)main.storageEncoding(rt.encoding);
            if(rt.altTexture>0){
                CpuImage alt=imageFor(IMAGE_POOL,rt.altTexture,chainW,chainH);
                if(alt!=null)alt.storageEncoding(rt.encoding);
            }
        }
        for(int texture:depthTextures(chain))imageFor(IMAGE_POOL,texture,chainW,chainH);
        var fog=dev.antimony.core.render.FogFrame.current();
        var clears=IrisClearBridge.plan(cachedPipeline,chain.fullClearPending,fog.red(),fog.green(),fog.blue());
        IrisClearBridge.apply(clears,IMAGE_POOL);chain.fullClearPending=false;
        for(var clear:clears){frameContent.add(clear.texture());markWritten(clear.texture());}
        gbufferFrame=true;
        for(PassDef pass:chain.passes)if("prepareRenderer".equals(pass.renderer)){
            if(!runCompositePass(pass,chain,IMAGE_POOL,frameContent,chainW,chainH))throw new IllegalStateException("Required prepare pass failed: "+pass.name);
            earlyPasses.add(pass);
        }
        IrisTerrainVertex.prepareFragmentFrame();
    }
    private static void captureAttachments(String stage) {
        if (chain == null) return;
        for (RtDef rt : chain.targets) {
            CpuPassDiagnostics.image(frameNo, stage + "-rt" + rt.index + "-main", IMAGE_POOL.get(rt.mainTexture));
            CpuPassDiagnostics.image(frameNo, stage + "-rt" + rt.index + "-alt", IMAGE_POOL.get(rt.altTexture));
        }
    }
    public static void beforeTranslucents(IrisGlFramebuffer fb){
        if(!gbufferActive())return;
        try{
            dev.antimony.core.irisgl.IrisGl.flushRaster();
            for(int texture:depthTextures(chain)){
                seedDepth(IMAGE_POOL.get(texture),fb.depth,fb.width,fb.height);frameContent.add(texture);markWritten(texture);
            }
            captureAttachments("opaque");
            for(PassDef pass:chain.passes)if("deferredRenderer".equals(pass.renderer)){
                if(!runCompositePass(pass,chain,IMAGE_POOL,frameContent,chainW,chainH))throw new IllegalStateException("Required deferred pass failed: "+pass.name);
                earlyPasses.add(pass);
            }
            IrisTerrainVertex.prepareTranslucentFrame();
            deferredComplete=true;
        }catch(RuntimeException failure){cpuImageProducerFailure("CPU deferred boundary: "+failure);LOG.warn("[Antimony] CPU deferred boundary failed",failure);}
    }
    public static void finishGbuffers(){
        if(!gbufferActive())return;
        dev.antimony.core.irisgl.IrisGl.flushRaster();
        gbufferComplete=deferredComplete;
        captureAttachments("gbuffer");
        if(++gbufferFrames<=3)for(RtDef rt:chain.targets)if(Set.of(0,3,6,7).contains(rt.index))
            LOG.info("[Antimony] CPU terrain HDR run={} imageFrame={} rt{} main={} {} alt={} {}",DIAG_RUN,currentShadowOrdinal(),
                rt.index,rt.mainTexture,stats(IMAGE_POOL.get(rt.mainTexture)),rt.altTexture,stats(IMAGE_POOL.get(rt.altTexture)));
    }
    static TerrainFragmentDraw terrainFragmentDraw(IrisTerrainSources.Source source,GlslProgramLoader.Loaded fragment,ShaderVaryings layout,boolean translucent){
        var inherited=translucent?new TerrainFragmentDraw.Blend(true,0x0302,0x0303,1,0x0303):TerrainFragmentDraw.Blend.OFF;
        return geometryFragmentDraw(source,fragment,layout,translucent,currentAtlasImage(),
            IrisTerrainVertex.matrices(dev.antimony.core.irisgl.IrisGl.ctx()),inherited);
    }
    static TerrainFragmentDraw blockEntityFragmentDraw(IrisTerrainSources.Source source,GlslProgramLoader.Loaded fragment,ShaderVaryings layout,
            dev.antimony.core.irisgl.IrisGlTexture texture,Map<String,float[]> matrices,dev.antimony.core.irisgl.IrisGlContext context){
        // Block translucency is drawn before deferred; blend enable is not the FBO phase.
        var inherited=dev.antimony.core.render.BlockEntityShaderInputs.blend(context);
        return geometryFragmentDraw(source,fragment,layout,false,cpuTextureImage(texture),matrices,inherited);
    }
    private static TerrainFragmentDraw geometryFragmentDraw(IrisTerrainSources.Source source,GlslProgramLoader.Loaded fragment,ShaderVaryings layout,
            boolean afterTranslucents,CpuImage surface,Map<String,float[]> matrices,TerrainFragmentDraw.Blend inherited){
        if(!gbufferActive())throw new IllegalStateException("No current terrain attachment frame");
        String key=source.identity().shaderKey();Object shader=IrisCpuShadowTargets.shader(cachedPipeline,key);
        int id=intOf(IrisSamplerBridge.call(shader,"getId"));
        if(id<=0)throw new IllegalStateException("Missing physical terrain shader "+key);
        var directives=source.drawDirectives();
        if(directives==null||directives.unknownDrawBuffers())throw new IllegalStateException("Unsupported terrain attachment directives "+key);
        int[] buffers=directives.drawBuffers().stream().mapToInt(Integer::intValue).toArray();
        var blends=IrisBlendState.capture(shader,fragment.meta.outSlots,inherited);
        Object fbo=IrisSamplerBridge.field(shader,afterTranslucents?"writingToAfterTranslucent":"writingToBeforeTranslucent");
        if(fbo==null)throw new IllegalStateException("Missing current terrain framebuffer "+key);
        Map<Integer,TerrainFragmentDraw.Attachment> targets=new LinkedHashMap<>();
        for(int location=0;location<buffers.length;location++){
            int texture=intOf(call(fbo,"getColorAttachment",new Class<?>[]{int.class},location));
            Object rt=call(chain.renderTargets,"get",new Class<?>[]{int.class},buffers[location]);
            CpuImage image=IMAGE_POOL.get(texture);Object format=IrisSamplerBridge.call(rt,"getInternalFormat");
            if(image==null||format==null)throw new IllegalStateException("Terrain attachment identity mismatch "+key+" location="+location);
            var encoding=IrisCpuShadowTargets.encoding(String.valueOf(format));image.storageEncoding(encoding);
            if(location<fragment.meta.outSlots)targets.put(buffers[location],new TerrainFragmentDraw.Attachment(image,encoding,blends.get(location)));
            frameContent.add(texture);markWritten(texture);
        }
        Map<String,CpuImage> textures=new LinkedHashMap<>();
        for(String name:fragment.meta.usedSamplers)if(Set.of("sampler2D","sampler2DShadow").contains(fragment.meta.samplerTypes.get(name))){
            CpuImage image;
            if(name.equals("gtexture")||name.equals("tex"))image=surface;
            else{
                var binding=IrisSamplerBridge.namedBinding(shader,id,name);
                if(binding==null)throw new IllegalStateException("Missing actual terrain sampler supplier "+name);
                image=IrisCpuShadowTargets.resolve(cachedPipeline,currentShadowOrdinal(),binding.texture());
                if(image==null)image=IMAGE_POOL.get(binding.texture());
                if(image==null)image=resourceImage(chain,binding.texture());
                if(image==null)throw new IllegalStateException("Missing current terrain sampler "+name+" texture="+binding.texture());
            }
            textures.put(name,image);
        }
        Map<String,float[]> overrides=new LinkedHashMap<>(matrices);
        overrides.put("iris_currentAlphaTest",new float[]{directives.alphaReference()});
        Map<String,Object> uniforms=new LinkedHashMap<>(currentUniformValues(fragment,overrides));
        if(fragment.meta.usedUniforms.contains("atlasSize"))uniforms.put("atlasSize",new int[]{surface.width,surface.height});
        return new TerrainFragmentDraw(fragment.program,fragment.meta,layout,uniforms,textures,
            resolveVolumes(fragment.meta,shader,id),buffers,targets,chainW,chainH,null);
    }
    public static IrisCpuImageResources.Frame cpuImageFrame(){return cpuImageFrame;}
    public static int[] cpuImageDimensions(String name){IrisCpuImageResources.Frame frame=cpuImageFrame;return frame==null?null:frame.dimensions(name);}
    public static IrisCpuFrameInputs.Snapshot cpuFrameInputs(){return cpuFrameInputs;}
    public static void cpuImageProducerFailure(String reason){if(cpuImageFailure==null)cpuImageFailure=java.util.Objects.requireNonNull(reason);}
    private static long ordinaryShadowSuccesses;
    public static void afterShadowProducer(dev.antimony.core.render.IrisShadowProducer.Receipt proof){
        if(!frameStateReady||preparedPipeline!=cachedPipeline||!imageDescriptors.isEmpty()
                ||computeGraph!=null&&computeGraph.requiresExecution())
            throw new IllegalStateException("Ordinary shadow frame cannot replace a required image/compute schedule");
        proof.verify(cachedPipeline,currentShadowOrdinal());
        beginGbufferResources();
        if(++ordinaryShadowSuccesses<=5&&dev.antimony.AntimonyMod.flag("antimony.profile"))
            LOG.info("[Antimony] CPU ordinary shadow profile frame={} sections={} vertexCalls={} shadowMs={} raster={}",
                currentShadowOrdinal(),proof.sections(),proof.vertices(),proof.nanos()/1_000_000.0,proof.raster());
    }
    public static void afterShadowProducer(dev.antimony.core.render.SolasVoxelProducer.Receipt proof){
        IrisCpuImageResources.Frame frame=cpuImageFrame;
        if(!frameStateReady||frame==null||cpuFrameInputs==null)throw new IllegalStateException("No prepared image frame");
        proof.verify(cachedPipeline,frame.generation(),frame.frameOrdinal());
        if(computeGraphFailure!=null||computeGraph==null||computeGraph.pipeline()!=cachedPipeline||computeGraph.nodes().size()!=1)
            throw new IllegalStateException("Unsupported current CPU compute schedule");
        var node=computeGraph.nodes().get(0);
        if(!node.stage().equals("shadowCompositeRenderer")||node.loaded()==null||node.failure()!=null
            ||!node.sha256().equals("6ef50d6ef0d8694e9d2996bbd4054524d95881c3bc168b29737cb1213e4a7d39"))
            throw new IllegalStateException("Unverified current shadow compute schedule: "+node.location());
        if(cpuComputeDispatch==null||cpuComputePipeline!=cachedPipeline||cpuComputeProgramId!=node.programId()){
            if(cpuComputeDispatch!=null)cpuComputeDispatch.close();
            cpuComputeDispatch=new CpuComputeDispatch(cachedPipeline,node.loaded().program,node.loaded().meta);
            cpuComputePipeline=cachedPipeline;cpuComputeProgramId=node.programId();
        }
        IrisCpuFrameInputs.Snapshot inputs=cpuFrameInputs;
        Map<String,Object> uniforms=Map.of("frameCounter",inputs.frameCounter(),"frameTimeCounter",inputs.frameTimeCounter(),
            "cameraPosition",inputs.cameraPosition(),"previousCameraPosition",inputs.previousCameraPosition());
        String output=(inputs.frameCounter()&1)==0?"floodfill_img_copy":"floodfill_img";
        var receipt=frame.dispatchAndCommit(cpuComputeDispatch,uniforms,node.dispatch().groups(chainW,chainH),Set.of(output),inputs.frameCounter());
        cpuComputeGeneration=frame.generation();cpuComputeFrameOrdinal=frame.frameOrdinal();
        beginGbufferResources();
        if(++cpuComputeSuccesses<=3||DIAG&&cpuComputeSuccesses%60==0){
            LOG.info("[Antimony] CPU world shadow/compute run={} frame={} voxelUniqueStores={} output={} invocations={} computeMs={}",
                DIAG_RUN,frame.frameOrdinal(),proof.uniqueWrites(),output,receipt.invocations(),receipt.nanos()/1_000_000.0);
            if(dev.antimony.AntimonyMod.flag("antimony.profile"))LOG.info(
                "[Antimony] CPU shadow profile frame={} sections={} vertexCalls={} shadowMs={} raster={}",
                frame.frameOrdinal(),proof.sections(),proof.vertices(),proof.nanos()/1_000_000.0,proof.raster());
        }
    }
    public static IrisCpuImageResources.ImageReceipt cpuImageReceipt(String name){
        IrisCpuImageResources.Frame frame=cpuImageFrame;if(frame==null)throw new IllegalStateException("No current CPU image frame");return frame.receipt(name);
    }
    /** Iris cache refresh belongs to the render-thread frame boundary, never a leaf read. */
    private static volatile Object customUniforms;
    private static java.lang.reflect.Field CU_DECLARED;
    /**
     * Uniforms this class computes itself from the real chain resolution. The
     * pack expression for them uses Iris' window size, which is wrong for a
     * chain running at {@code scale}, so the chain's own value must win.
     */
    private static final Set<String> CHAIN_OWNED_UNIFORMS = Set.of(
        "pixelSizeX", "pixelSizeY", "aspectRatioInverse", "viewWidth", "viewHeight", "shadowDistance", "softLod");
    /** GlTexMirror counters at the last stats line, for per-interval deltas. */
    private static long lastMirrorUp = -1;
    private static long lastMirrorAbsorbed = -1;
    /** Render target images reused across frames (key = GL texture id). */
    private static final Map<Integer, CpuImage> IMAGE_POOL = new HashMap<>();
    /**
     * B1 ping-pong emulation: per-texture write recency. Iris alternates a
     * render target's main/alt buffer between passes (the FBO attachments
     * have the flip baked in), so the buffer a pass must READ is the one
     * written LAST, not always the main texture. Tier-3 fallback picks the
     * max-stamped candidate; without this the chain read the stale/black
     * buffer and the final pass wrote a constant black frame.
     */
    private static final Map<Integer, Long> WRITE_STAMP = new HashMap<>();
    private static long stampCounter;

    private static void markWritten(int texture) {
        if (texture != 0) {
            WRITE_STAMP.put(texture, ++stampCounter);
        }
    }

    /**
     * Reusable full-frame copies backing the read-before-write alias fix. Keyed
     * by identity because the same CpuImage instance is reused for a render
     * target across frames; the entries are dropped whenever the pipeline (and
     * therefore the render targets) is rebuilt.
     */
    private static final java.util.IdentityHashMap<CpuImage, CpuImage> ALIAS_SNAPSHOT =
        new java.util.IdentityHashMap<>();

    private static CpuImage snapshotOf(CpuImage target) {
        CpuImage snap = ALIAS_SNAPSHOT.get(target);
        if (snap == null || !snap.matches(target.width, target.height)) {
            snap = new CpuImage(target.width, target.height);
            ALIAS_SNAPSHOT.put(target, snap);
        }
        snap.copyFrom(target);
        return snap;
    }

    private static Chain buildChain(Object pipeline) {
        Chain c = new Chain();
        Object renderTargets = field(pipeline, "renderTargets");
        if (renderTargets == null) {
            return null;
        }
        c.renderTargets = renderTargets;
        Object countObj = call(renderTargets, "getRenderTargetCount", new Class<?>[0]);
        int count = countObj instanceof Integer i ? i : 0;
        for (int i = 0; i < count; i++) {
            Object target = call(renderTargets, "get", new Class<?>[]{int.class}, i);
            if (target == null) {
                continue;
            }
            RtDef def = new RtDef();
            def.index = i;
            def.mainTexture = intOf(call(target, "getMainTexture", new Class<?>[0]));
            def.altTexture = intOf(call(target, "getAltTexture", new Class<?>[0]));
            def.width = intOf(call(target, "getWidth", new Class<?>[0]));
            def.height = intOf(call(target, "getHeight", new Class<?>[0]));
            Object format = call(target, "getInternalFormat", new Class<?>[0]);
            if (format == null) throw new IllegalStateException("Iris target format unavailable: " + i);
            def.encoding = IrisCpuShadowTargets.encoding(String.valueOf(format));
            c.targets.add(def);
        }
        refreshDepthTextures(c);
        Object textureManager = field(pipeline, "customTextureManager");
        c.resourceAccess.putAll(IrisSamplerBridge.collectResources(IrisSamplerBridge.call(textureManager,"getCustomTextureIdMap")));
        c.resourceAccess.putAll(IrisSamplerBridge.collectResources(IrisSamplerBridge.call(textureManager,"getIrisCustomTextures")));
        Object pack = IrisSamplerBridge.currentPack();
        IrisSamplerBridge.collectFiltering(IrisSamplerBridge.call(pack, "getCustomTextureDataMap"),
            IrisSamplerBridge.call(textureManager, "getCustomTextureIdMap"), c.resourceFiltering);
        IrisSamplerBridge.collectFiltering(IrisSamplerBridge.call(pack, "getIrisCustomTextureDataMap"),
            IrisSamplerBridge.call(textureManager, "getIrisCustomTextures"), c.resourceFiltering);
        // TextureAccess.getTextureId returns an IntSupplier, including the generated noise texture.
        Object noise = IrisSamplerBridge.call(textureManager, "getNoiseTexture");
        if (noise == null) noise = field(pipeline, "noiseTexture");
        Object noiseData = IrisSamplerBridge.call(pack, "getCustomNoiseTexture");
        if (noise != null && noiseData != null) {
            IrisSamplerBridge.collectFiltering(Map.of("noisetex", noiseData),
                Map.of("noisetex", noise), c.resourceFiltering);
        }
        c.noiseTexture = IrisSamplerBridge.textureId(noise);
        if (c.noiseTexture > 0) c.resourceAccess.put(c.noiseTexture, noise);
        if (noise != null && c.noiseTexture == 0) {
            Object glId = call(noise, "getId", new Class<?>[0]);
            if (glId == null) {
                glId = call(noise, "texture", new Class<?>[0]);
            }
            c.noiseTexture = intOf(glId);
        }

        for (String fieldName : COMPOSITE_FIELDS) {
            Object renderer = field(pipeline, fieldName);
            if (renderer == null) {
                continue;
            }
            IrisSamplerBridge.collect(field(renderer, "customTextureIds"), c.resourceAccess);
            IrisSamplerBridge.collect(field(renderer, "irisCustomTextures"), c.resourceAccess);
            Object passes = field(renderer, "passes");
            if (!(passes instanceof List<?> list)) {
                continue;
            }
            int index = 0;
            for (Object pass : list) {
                PassDef def = readPass(pass, fieldName + "[" + index++ + "]");
                if (def != null) {
                    def.renderer = fieldName;
                    def.customTextureIds = field(renderer, "customTextureIds");
                    def.textureStage = String.valueOf(field(renderer, "textureStage"));
                    c.passes.add(def);
                }
            }
        }

        Object finalRenderer = field(pipeline, "finalPassRenderer");
        c.finalRenderer = finalRenderer;
        if (finalRenderer != null) {
            IrisSamplerBridge.collect(field(finalRenderer, "customTextureIds"), c.resourceAccess);
            IrisSamplerBridge.collect(field(finalRenderer, "irisCustomTextures"), c.resourceAccess);
            Object finalPass = field(finalRenderer, "finalPass");
            if (finalPass != null) {
                PassDef def = readPass(finalPass, "final");
                if (def != null) {
                    def.finalPass = true;
                    def.renderer = "finalPassRenderer";
                    def.customTextureIds = field(finalRenderer, "customTextureIds");
                    def.textureStage = "COMPOSITE_AND_FINAL";
                    c.passes.add(def);
                }
            }
        }
        return c;
    }

    private static PassDef readPass(Object pass, String label) {
        Object program = field(pass, "program");
        if (program == null) {
            return null;
        }
        PassDef def = new PassDef();
        def.program = program;
        def.flippedAtLeastOnce = field(pass, "flippedAtLeastOnce");
        def.stageReadsFromAlt = field(pass, "stageReadsFromAlt");
        def.programId = intOf(call(program, "getProgramId", new Class<?>[0]));
        if (def.programId <= 0) {
            return null;
        }
        Object name = field(pass, "name");
        def.name = name instanceof String s ? s : label;
        def.framebuffer = field(pass, "framebuffer");
        Object mipmapped=field(pass,"mipmappedBuffers");
        if(mipmapped instanceof Set<?> entries){
            Set<Integer> indices=new HashSet<>();
            for(Object entry:entries){if(!(entry instanceof Integer index))throw new IllegalStateException("Invalid Iris mipmap target");indices.add(index);}
            def.mipmappedBuffers=Set.copyOf(indices);
        }
        Object buffers = field(pass, "drawBuffers");
        def.drawBuffers = buffers instanceof int[] ints ? ints : new int[0];
        return def;
    }

    /** Iris can resize an existing pipeline and replace IDs and swap FBOs in place. */
    private static boolean refreshTargetLayout(Chain c, int width, int height) {
        StringBuilder layout = new StringBuilder();
        for (RtDef rt : c.targets) {
            Object target = call(c.renderTargets, "get", new Class<?>[]{int.class}, rt.index);
            if (target == null) throw new IllegalStateException("Iris target unavailable: " + rt.index);
            rt.mainTexture = intOf(call(target, "getMainTexture", new Class<?>[0]));
            rt.altTexture = intOf(call(target, "getAltTexture", new Class<?>[0]));
            rt.width = intOf(call(target, "getWidth", new Class<?>[0]));
            rt.height = intOf(call(target, "getHeight", new Class<?>[0]));
            Object format = call(target, "getInternalFormat", new Class<?>[0]);
            if (format == null) throw new IllegalStateException("Iris target format unavailable: " + rt.index);
            rt.encoding = IrisCpuShadowTargets.encoding(String.valueOf(format));
            layout.append(rt.index).append(':').append(rt.mainTexture).append('/').append(rt.altTexture)
                .append('@').append(rt.width).append('x').append(rt.height).append(':').append(rt.encoding).append(';');
        }
        refreshDepthTextures(c);
        layout.append("depth=").append(c.depthTexture).append('/').append(c.depthNoTranslucentsTexture)
            .append('/').append(c.depthNoHandTexture);
        boolean reset = width != c.historyWidth || height != c.historyHeight || !layout.toString().equals(c.targetLayout);
        if (reset) {
            IMAGE_POOL.clear(); EXPOSURE_SEEDED.clear(); WRITE_STAMP.clear(); ALIAS_SNAPSHOT.clear();
            c.historySnapshots.clear();
            c.historyWidth = width; c.historyHeight = height; c.targetLayout = layout.toString();
            c.fullClearPending = true;
        }
        return reset;
    }

    private static void refreshDepthTextures(Chain c) {
        c.depthTexture = intOf(IrisSamplerBridge.call(c.renderTargets, "getDepthTexture"));
        c.depthNoTranslucentsTexture = intOf(IrisSamplerBridge.call(
            IrisSamplerBridge.call(c.renderTargets, "getDepthTextureNoTranslucents"), "getTextureId"));
        c.depthNoHandTexture = intOf(IrisSamplerBridge.call(
            IrisSamplerBridge.call(c.renderTargets, "getDepthTextureNoHand"), "getTextureId"));
    }

    private static Set<Integer> depthTextures(Chain c) {
        Set<Integer> ids = new HashSet<>();
        if (c.depthTexture > 0) ids.add(c.depthTexture);
        if (c.depthNoTranslucentsTexture > 0) ids.add(c.depthNoTranslucentsTexture);
        if (c.depthNoHandTexture > 0) ids.add(c.depthNoHandTexture);
        return ids;
    }

    /** Read actual copy objects, including Iris' clear exclusions and resize replacements. */
    private static List<IrisRenderTargetHistory.Copy> historyCopies(Chain c) {
        List<IrisRenderTargetHistory.Copy> copies = new ArrayList<>();
        if (c.finalRenderer == null) return copies;
        Object swaps = field(c.finalRenderer, "swapPasses");
        if (!(swaps instanceof Iterable<?> list)) throw new IllegalStateException("Iris swapPasses unavailable");
        for (Object swap : list) {
            Object from = field(swap, "from");
            int source = from == null ? 0 : intOf(call(from, "getColorAttachment", new Class<?>[]{int.class}, 0));
            int destination = intOf(field(swap, "targetTexture"));
            if (source <= 0 || destination <= 0 || !isRenderTexture(c, source) || !isRenderTexture(c, destination))
                throw new IllegalStateException("Invalid Iris history copy " + source + " -> " + destination);
            copies.add(new IrisRenderTargetHistory.Copy(source, destination));
        }
        return copies;
    }

    private static int intOf(Object value) {
        return value instanceof Integer i ? i : 0;
    }

    // ------------------------------------------------------------------
    // Frame entry point
    // ------------------------------------------------------------------

    /**
     * Run the CPU post chain for one frame, writing the final image back into
     * {@code fb}. Returns true when the frame went through a post chain (the
     * caller may treat false as "no pack effects this frame").
     */
    private static boolean frameStateReady;
    private static boolean frameCaptureAttempted;
    private static Object preparedPipeline;
    private static float cpuNear, cpuFar;
    private static org.joml.Matrix4f cpuProjection;

    public static void captureProjection(float near, float far) {
        cpuNear = near;
        cpuFar = far;
        cpuProjection = new org.joml.Matrix4f(dev.antimony.core.irisgl.IrisGl.ctx().projection);
    }

    /** Called once after terrain projection/view/fog/shadow, before the first opaque draw. */
    public static void prepareFrameState(float partialTick) {
        if (frameCaptureAttempted) return;
        frameCaptureAttempted = true;
        frameStateReady = false;cpuImageFailure=null;
        if (!enabled() || !dev.antimony.gl.GlCompat.intercepting()) return;
        try {
            Object current = pipeline();
            if (current == null || field(current, "renderTargets") == null) return;
            dev.antimony.core.win32.SplashOverlay.updateStage(4, "Iris 光影管线已启动");
            ensurePipeline(current);
            if (pipelineFailure != null || computeGraphFailure != null) return;
            cpuFrameInputs=null;
            IrisTerrainVertex.preparePipeline(current);
            if(IrisTerrainVertex.initializationFailure()!=null){
                pipelineFailure="Terrain shader initialization: "+IrisTerrainVertex.initializationFailure();
                dev.antimony.core.win32.SplashOverlay.fail("\u5730\u5f62\u7740\u8272\u5668\u7f16\u8bd1\u5931\u8d25\uff0c\u8bf7\u67e5\u770b\u65e5\u5fd7");
                LOG.error("[Antimony] CPU terrain initialization cannot finish by waiting: {}",pipelineFailure);
                return;
            }
            var framebuffer = dev.antimony.core.irisgl.IrisGl.framebuffer();
            chainW = Math.max(1, Math.round(framebuffer.width * scale));
            chainH = Math.max(1, Math.round(framebuffer.height * scale));
            var fog = dev.antimony.core.render.FogFrame.current();
            if (fog == null) throw new IllegalStateException("Terrain fog not captured this frame");
            fog.restoreRenderSystem();
            Class<?> type = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            Object state = type.getField("INSTANCE").get(null);
            if (fog.rawDensity() != null)
                type.getMethod("setFogDensity", float.class).invoke(state, fog.rawDensity());
            type.getMethod("setFogColor", float.class, float.class, float.class)
                .invoke(state, fog.red(), fog.green(), fog.blue());
            type.getMethod("setTickDelta", float.class).invoke(state, partialTick);
            type.getMethod("setRealTickDelta", float.class).invoke(state, partialTick);
            float[] view = reconstructModelView("gbufferModelView", 16);
            if (view != null) type.getMethod("setGbufferModelView", org.joml.Matrix4fc.class)
                .invoke(state, new org.joml.Matrix4f().set(view));
            if (cpuProjection == null) throw new IllegalStateException("Terrain projection not captured");
            if (!(Float.isFinite(cpuNear) && Float.isFinite(cpuFar) && cpuNear > 0 && cpuFar > cpuNear))
                throw new IllegalStateException("Invalid CPU depth projection");
            type.getMethod("setGbufferProjection", org.joml.Matrix4f.class)
                .invoke(state, new org.joml.Matrix4f(cpuProjection));
            shadowFrame = IrisShadowFrame.capture(current, dev.antimony.core.render.IrisGlRenderer.lastCameraPos());
            Set<String> requested = new HashSet<>(IrisTerrainVertex.requestedUniforms());
            if (chain != null) for (PassDef pass : chain.passes) {
                GlslProgramLoader.Loaded loaded = load(pass);
                if (loaded != null) requested.addAll(loaded.meta.uniformFields.keySet());
                GlShimState.ShaderRecord record = GlShimState.program(pass.programId);
                GlslProgramLoader.Loaded vertex = record != null && record.vertexSource != null
                    ? GlslProgramLoader.loadVertex(pass.programId, record.vertexSource) : null;
                if (vertex != null) requested.addAll(vertex.meta.uniformFields.keySet());
            }
            if(computeGraph!=null&&computeGraph.pipeline()==current)requested.addAll(computeGraph.requestedUniforms());
            if(imageDescriptors.stream().anyMatch(image->image.name().equals("voxel_img")))
                requested.addAll(dev.antimony.core.render.SolasVoxelProducer.requestedUniforms(current));
            else if(shadowFrame!=null)
                requested.addAll(dev.antimony.core.render.IrisShadowProducer.requestedUniforms(current));
            requested.addAll(Set.of("worldTime", "cameraPosition", "previousCameraPosition"));
            if (DIAG) requested.addAll(Set.of("dayMoment", "dayMixer", "nightMixer", "dayNightMix", "volumetricDayMixer"));
            Object notifier = call(current, "getFrameUpdateNotifier", new Class<?>[0]);
            if (customUniforms != null && notifier == null) throw new IllegalStateException("Iris frame notifier unavailable");
            uniformFrame = IrisUniformFrame.capture(customUniforms, notifier, requested, DIAG, DIAG_TIME_OF_DAY);
            cpuFrameInputs = CPU_FRAME_INPUTS.capture(current, uniformFrame);
            preparedPipeline = current;
            ++cpuImageFrameOrdinal;
            if(computeGraph!=null&&computeGraph.pipeline()==current&&computeGraphFailure==null
                    &&imageDescriptors.stream().anyMatch(image->image.target()==0x806F)){
                try{cpuImageFrame=CPU_IMAGES.beginFrame(current,cpuImageFrameOrdinal,imageDescriptors);cpuImageFrame.importIrisAllocationClear();}
                catch(RuntimeException imageFailure){cpuImageFrame=null;cpuImageFailure="CPU image resource initialization failed: "+imageFailure;LOG.warn("[Antimony] CPU image owner rejected current Iris frame; compute remains unavailable this frame: {}",imageFailure.toString());}
            }
            frameStateReady = true;
            if (DIAG && frameNo == 10) for (String name : requested) {
                IrisUniformFrame.Value value = uniformFrame.get(name);
                if (value != null) LOG.info("[Antimony] uniform-frame run={} postFrame={} {} = {} {}", DIAG_RUN, frameNo, name,
                    java.util.Arrays.toString(value.floats()), java.util.Arrays.toString(value.ints()));
            }
        } catch (ClassNotFoundException absent) {
            // Iris is optional.
        } catch (Throwable failure) {
            LOG.warn("[Antimony] CPU Iris frame state unavailable", failure);
        }
    }

    public static boolean render(IrisGlFramebuffer fb) {
        if (!enabled || fb == null) {
            return false;
        }
        if (failed) {
            // P-01: only a different shader pipeline (pack reload, world change)
            // may revive the chain; within one pipeline the failure is assumed
            // to be deterministic and retrying every frame would only spam.
            Object current = pipeline();
            if (current == null || current == failedPipeline) {
                return false;
            }
            failed = false;
            consecutiveFailures = 0;
            LOG.info("[Antimony] post chain retrying with a new shader pipeline");
        }
        long start = System.nanoTime();
        try {
            Object pipeline = pipeline();
            if (pipeline == null || field(pipeline, "renderTargets") == null) {
                if (!loggedMissing) {
                    loggedMissing = true;
                    LOG.info("[Antimony] post chain idle: Iris pipeline not present");
                }
                dev.antimony.core.win32.SplashOverlay.updateStage(3, "未发现启用中的 Iris 光影管线");
                return false;
            }
            dev.antimony.core.win32.SplashOverlay.updateStage(4, "已发现 Iris 光影管线");
            if (!dev.antimony.gl.GlCompat.intercepting()) {
                dev.antimony.core.win32.SplashOverlay.fail("CPU 捕获未开启：" + dev.antimony.gl.GlCompat.mode() + "；需重启为 ON");
                if (!loggedInactiveCapture) {
                    loggedInactiveCapture = true;
                    LOG.error("[Antimony] CPU shader startup blocked: glcompat={} disables shader lifecycle/source capture; remove the explicit diagnostic override or use -Dantimony.glcompat=on and restart. Waiting cannot fix missing startup sources.", dev.antimony.gl.GlCompat.mode());
                }
                return false;
            }
            if (pipelineFailure != null) {
                dev.antimony.core.win32.SplashOverlay.fail("光影管线初始化失败，请查看日志");
                return false;
            }
            if (computeGraphFailure != null) {
                dev.antimony.core.win32.SplashOverlay.fail("计算着色器初始化失败，请查看日志");
                return false;
            }
            if (!frameStateReady || preparedPipeline != pipeline) {
                dev.antimony.core.win32.SplashOverlay.updateStage(5, "准备光影场景参数");
                return false;
            }
            if (chain == null || chain.passes.isEmpty()) {
                return false;
            }
            long rendererBefore = dev.antimony.core.render.IrisGlRenderer.frameCount();
            if (DIAG && (frameNo == 10 || rendererBefore == 59 || rendererBefore == 299))
                LOG.info("[Antimony] frame-map run={} rendererBefore={} rendererScreenshot={} postFrame={} phase=preGUI", DIAG_RUN,
                    rendererBefore, rendererBefore + 1, frameNo);
            boolean ok = execute(chain, fb);
            if(!ok){
                dev.antimony.core.win32.SplashOverlay.updateStage(5, startupWaitDetail());
                rejectedPostFrames++;frameNo++;
                if(rejectedPostFrames==1||rejectedPostFrames%120==0)
                    LOG.info("[Antimony] CPU post status: rejectedAttempts={} completedInWindow={} (rejected time is not shader FPS)",rejectedPostFrames,frames);
                return false;
            }
            try {
            if (DIAG && (!firstCompletedFrameLogged || frameNo == 10)) {
                var image = dev.antimony.core.present.CpuScreenImage.rgb(fb);
                var dir = new java.io.File(net.minecraft.client.Minecraft.getInstance().gameDirectory, "antimony-shots");
                dir.mkdirs();
                var out = new java.io.File(dir, "run-" + DIAG_RUN + "-post-" + frameNo + "-renderer-" + (rendererBefore + 1) + "-preGUI.png");
                javax.imageio.ImageIO.write(image, "PNG", out);
                LOG.info("[Antimony] same-frame run={} postFrame={} rendererScreenshot={} phase=preGUI size={}x{} argbHash={} projection={} near={} far={} screenshot={}", DIAG_RUN,
                    frameNo, rendererBefore + 1, fb.width, fb.height, java.util.Arrays.hashCode(fb.color), java.util.Arrays.toString(cpuProjection.get(new float[16])), cpuNear, cpuFar, out);
            }
            } catch (Throwable diagnosticFailure) {
                LOG.warn("[Antimony] post diagnostic capture failed after completed frame", diagnosticFailure);
            }
            if (!firstCompletedFrameLogged) {
                firstCompletedFrameLogged = true;
                LOG.info("[Antimony] CPU shader frame completed run={} postFrame={} rendererScreenshot={} size={}x{} gbufferComplete={} computeReady={} passes={}",
                    DIAG_RUN, frameNo, rendererBefore + 1, fb.width, fb.height, gbufferComplete, currentComputeReady(), chain.passes.size());
            }
            dev.antimony.core.win32.SplashOverlay.complete();
            consecutiveFailures = 0;
            frames++;
            frameNo++;
            chainNanos += System.nanoTime() - start;
            if (frames - lastStatsFrame >= 120) {
                lastStatsFrame = frames;
                double ms = chainNanos / 1_000_000.0 / Math.max(frames, 1);
                long up = dev.antimony.gl.hook.GlTexMirror.mirroredUploads();
                long absorbed = dev.antimony.gl.hook.GlTexMirror.absorbedUploads();
                long dUp = lastMirrorUp < 0 ? up : up - lastMirrorUp;
                long dAbs = lastMirrorAbsorbed < 0 ? absorbed : absorbed - lastMirrorAbsorbed;
                lastMirrorUp = up;
                lastMirrorAbsorbed = absorbed;
                LOG.info("[Antimony] CPU post chain: {} frames, {} ms/frame avg, texMirror uploads +{} (absorbed +{})",
                    frames, String.format(java.util.Locale.ROOT, "%.2f", ms), dUp, dAbs);
                chainNanos = 0;
                frames = 0;
                lastStatsFrame = 0;
            }
            return ok;
        } catch (Throwable t) {
            consecutiveFailures++;
            if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
                // P-01: skip this frame, keep the chain alive. Most of these are
                // one-frame races (a pass reading a target the resize just
                // replaced); the next frame rebuilds and carries on.
                LOG.warn("[Antimony] CPU post chain skipped frame {} ({}/{})", frameNo,
                        consecutiveFailures, MAX_CONSECUTIVE_FAILURES, t);
                return false;
            }
            failed = true;
            failedPipeline = pipeline();
            LOG.error("[Antimony] CPU post chain disabled after {} consecutive failures", consecutiveFailures, t);
            return false;
        }
    }

    private static void ensurePipeline(Object pipeline) {
            if (pipeline != cachedPipeline) {
                if (field(pipeline, "renderTargets") != null)
                    dev.antimony.core.win32.SplashOverlay.beginPipeline(pipeline);
                // A new pipeline must never observe resources from the previous pack,
                // even when its first target format or shader fails to initialize.
                cachedPipeline = pipeline;
                firstCompletedFrameLogged = false;
                pipelineFailure=null;chain=null;customUniforms=null;
                computeGraph=null;computeGraphFailure=null;imageDescriptors=List.of();
                CPU_IMAGES.invalidateCurrent();cpuImageFrame=null;cpuFrameInputs=null;
                cpuImageFailure=null;preparedPipeline=null;frameStateReady=false;
                if(cpuComputeDispatch!=null){cpuComputeDispatch.close();cpuComputeDispatch=null;}
                cpuComputePipeline=null;cpuComputeProgramId=-1;cpuComputeFrameOrdinal=-1;
                IMAGE_POOL.clear();EXPOSURE_SEEDED.clear();WRITE_STAMP.clear();ALIAS_SNAPSHOT.clear();
                passDiagLogged.clear();PACK_UNIFORM_MISSES.clear();UNIFORM_DIAGNOSTICS.clear();
                GlslProgramLoader.reset();
                try { chain = buildChain(pipeline); }
                catch(RuntimeException|LinkageError failure){
                    pipelineFailure=failure.toString();
                    dev.antimony.core.win32.SplashOverlay.fail("光影管线初始化失败，请查看日志");
                    LOG.error("[Antimony] CPU pipeline initialization failed; old pack state was discarded: {}",pipelineFailure,failure);
                    return;
                }
                customUniforms = call(pipeline, "getCustomUniforms", new Class<?>[0]);
                if (customUniforms != null) {
                    Object declared = null;
                    try {
                        java.lang.reflect.Field vf = customUniforms.getClass().getDeclaredField("variables");
                        vf.setAccessible(true);
                        declared = vf.get(customUniforms);
                    } catch (Throwable t) {
                        LOG.warn("[Antimony] pack custom uniforms: no variables map ({})", t.toString());
                    }
                    LOG.info("[Antimony] pack custom uniforms bridged: {} declared={}", customUniforms.getClass().getName(),
                        declared instanceof java.util.Map<?, ?> m ? m.keySet() : "?");
                }
                GlslProgramLoader.reset();
                computeGraph=null;computeGraphFailure=null;imageDescriptors=List.of();
                if(pipeline.getClass().getName().equals("net.irisshaders.iris.pipeline.IrisRenderingPipeline"))try{
                    computeGraphFailure="Current pipeline compute/image capture has not completed";
                    computeGraph=IrisComputeGraph.capture(pipeline);
                    imageDescriptors=IrisImageDescriptors.capture(pipeline);
                    for(var image:imageDescriptors)LOG.info("[Antimony] CPU image declaration name={} sampler={} texture={} target={} format={} size={}x{}x{} clear={} relative={}; declaration only, CPU world producer not ready",
                        image.name(),image.samplerName(),image.textureId(),image.target(),image.cpuFormat(),image.width(),image.height(),image.depth(),image.clear(),image.relative());
                    for(var node:computeGraph.nodes())LOG.info("[Antimony] CPU compute graph {} program={} sha256={} local={} absolute={} relative={} indirect={} compiled={} diagnostic={}; world resource producer not ready, no dispatch",
                        node.location(),node.programId(),node.sha256(),node.dispatch().local(),node.dispatch().absolute(),node.dispatch().relative(),node.dispatch().indirect(),node.loaded()!=null,node.failure());
                    computeGraphFailure=null;
                }catch(ReflectiveOperationException|RuntimeException|LinkageError captureFailure){
                    computeGraphFailure=captureFailure.toString();
                    dev.antimony.core.win32.SplashOverlay.fail("计算着色器初始化失败，请查看日志");
                    LOG.error("[Antimony] CPU compute graph initialization failed; this pipeline cannot complete without reload/restart: {}",computeGraphFailure);
                }
                CPU_IMAGES.invalidateCurrent();cpuImageFrame=null;
                 IMAGE_POOL.clear();
                EXPOSURE_SEEDED.clear();
                WRITE_STAMP.clear();
                ALIAS_SNAPSHOT.clear();
                describe(chain);
            }
    }

    private static void describe(Chain c) {
        if (c == null) {
            LOG.info("[Antimony] post chain: no chain (pipeline has no composite renderer)");
            return;
        }
        StringBuilder sb = new StringBuilder("[Antimony] post chain: ")
            .append(c.passes.size()).append(" passes, ").append(c.targets.size()).append(" render targets")
            .append(", scale=").append(scale).append(", threads=").append(THREADS);
        for (PassDef p : c.passes) {
            sb.append("\n    ").append(p.finalPass ? "final" : p.name)
                .append(" program=").append(p.programId);
            if (p.drawBuffers.length > 0) {
                sb.append(" drawBuffers=").append(java.util.Arrays.toString(p.drawBuffers));
            }
        }
        LOG.info(sb.toString());
        if (DIAG) {
            for (RtDef rt : c.targets) {
                LOG.info("[Antimony]   rt{} main={} alt={} {}x{}", rt.index, rt.mainTexture, rt.altTexture,
                    rt.width, rt.height);
            }
        }
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    private static final class CpuPassState implements FragState {
        final CpuImage[] unitImages = new CpuImage[MAX_UNITS];
        final CpuVolume[] unitVolumes = new CpuVolume[MAX_UNITS];
        private CpuVolume volume(int unit){CpuVolume volume=unit>=0&&unit<unitVolumes.length?unitVolumes[unit]:null;if(volume==null)throw new IllegalStateException("Unbound current post 3D sampler "+unit);return volume;}
        public float[] sample3D(int unit,float u,float v,float w){return volume(unit).sample(u,v,w);}
        public float[] sample3DLod(int unit,float u,float v,float w,float lod){return volume(unit).sampleLod(u,v,w,lod);}
        public float[] fetch3D(int unit,int x,int y,int z,int lod){return volume(unit).fetch(x,y,z,lod);}
        public int[] fetchUnsigned3D(int unit,int x,int y,int z,int lod){return volume(unit).fetchUnsigned(x,y,z,lod);}
        final int width;
        final int height;
        int x;
        int y;

        CpuPassState(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public float[] sample(int unit, float u, float v) {
            CpuImage image = unit >= 0 && unit < unitImages.length ? unitImages[unit] : null;
            if (image == null) {
                return new float[]{0.0f, 0.0f, 0.0f, 1.0f};
            }
            return image.sample(u, v);
        }
        public float[] fetch2D(int unit,int x,int y,int lod){return image(unit).fetch(x,y,lod);}
        public float sampleChannel(int unit,float[] coordinate,int channel){return image(unit).sampleChannel(coordinate[0],coordinate[1],channel);}
        public float sampleLodChannel(int unit,float[] coordinate,float lod,int channel){return image(unit).sampleLodChannel(coordinate[0],coordinate[1],lod,channel);}
        public float sampleBiasChannel(int unit,float[] coordinate,float bias,int channel){return image(unit).sampleBiasChannel(coordinate[0],coordinate[1],bias,channel);}
        public float[] sampleLod(int unit,float[] coordinate,float lod){return image(unit).sampleLod(coordinate[0],coordinate[1],lod);}
        public float[] sampleBias(int unit,float[] coordinate,float bias){return image(unit).sampleBias(coordinate[0],coordinate[1],bias);}
        private CpuImage image(int unit){CpuImage image=unit>=0&&unit<unitImages.length?unitImages[unit]:null;if(image==null)throw new IllegalStateException("Unbound current post image "+unit);return image;}
        @Override
        public float sampleShadow(int unit,float u,float v,float reference){
            CpuImage image=unit>=0&&unit<unitImages.length?unitImages[unit]:null;
            if(image==null)throw new IllegalStateException("Unbound current post comparison sampler "+unit);
            return image.sampleCompare(u,v,reference);
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
            // gl_FragCoord.y is measured from the BOTTOM edge in GL; our rows
            // run top-down. Returning the top-down value here made every
            // screen-space ray point the wrong way, so the pack's sky gradient
            // ran inverted (horizon colour on top, zenith below).
            return (height - y - 0.5f) / height;
        }
    }

    static boolean postInputsSupported(GlslCompiler.Result meta){
        if(!meta.derivativeInputs.isEmpty()||!meta.usedImages.isEmpty())return false;
        for(String name:meta.usedSamplers)if(!Set.of("sampler2D","sampler2DShadow","sampler3D","usampler3D").contains(meta.samplerTypes.getOrDefault(name,"sampler2D")))return false;
        return true;
    }
    private static Map<String,CpuVolume> resolveVolumes(GlslCompiler.Result meta,Object program,int id){
        Map<String,CpuVolume> result=new LinkedHashMap<>();
        for(String name:meta.usedSamplers){
            String type=meta.samplerTypes.getOrDefault(name,"sampler2D");
            if(type.equals("sampler2D")||type.equals("sampler2DShadow"))continue;
            if(!Set.of("sampler3D","usampler3D").contains(type)||cpuImageFrame==null)
                throw new IllegalStateException("No current typed volume owner for "+name);
            IrisImageDescriptors.Image descriptor=null;
            for(var image:imageDescriptors)if(name.equals(image.samplerName())){
                if(descriptor!=null)throw new IllegalStateException("Ambiguous image sampler "+name);descriptor=image;
            }
            IrisSamplerBridge.Binding binding=IrisSamplerBridge.namedBinding(program,id,name);
            if(descriptor==null||descriptor.pipeline()!=cachedPipeline||binding==null||binding.texture()!=descriptor.textureId())
                throw new IllegalStateException("3D sampler supplier differs from current image owner: "+name);
            CpuVolume volume=cpuImageFrame.readableSnapshot(descriptor.name());
            if(volume.integer()!=type.equals("usampler3D"))throw new IllegalStateException("3D sampler type mismatch "+name);
            result.put(name,volume);
        }
        return Map.copyOf(result);
    }
    private static CpuVolume[] volumeUnits(GlslCompiler.Result meta,Map<String,CpuVolume> volumes){
        CpuVolume[] units=new CpuVolume[MAX_UNITS];
        for(var entry:volumes.entrySet()){
            Integer unit=meta.samplerUnits.get(entry.getKey());
            if(unit!=null){if(unit<0||unit>=MAX_UNITS)throw new IllegalStateException("Too many typed sampler units");units[unit]=entry.getValue();}
        }
        return units;
    }
    static boolean postStagesSupported(GlslCompiler.Result fragment,GlslCompiler.Result vertex){
        return postInputsSupported(fragment)&&(vertex==null||postInputsSupported(vertex));
    }
    private static boolean execute(Chain c, IrisGlFramebuffer fb) {
        // A shader chain is a dependency graph, not independent optional filters.
        // Preflight all fragment programs before touching history/RTs; never feed a
        // final tonemap a half-executed chain when a required upstream stage is absent.
        boolean supported = !c.passes.isEmpty();
        if(computeGraphFailure!=null||cpuImageFailure!=null||computeGraph!=null&&(computeGraph.pipeline()!=cachedPipeline||computeGraph.requiresExecution()&&(!currentComputeReady()||!gbufferComplete))){
            supported=false;
            String reason=String.valueOf(computeGraphFailure)+"|"+String.valueOf(cpuImageFailure);
            long now=System.nanoTime();
            if(!reason.equals(lastResourceReason)||now-lastResourceWarning>5_000_000_000L){
                lastResourceReason=reason;lastResourceWarning=now;
                LOG.warn("[Antimony] CPU compute/world resources unavailable; nodes={} captureFailure={} frameFailure={}; reject chain before RT clear/seed",
                    computeGraph==null?0:computeGraph.nodes().size(),computeGraphFailure,cpuImageFailure);
            }
        }
        for (PassDef pass : c.passes) {
            GlslProgramLoader.Loaded loaded=load(pass);
            GlShimState.ShaderRecord source=GlShimState.program(pass.programId);
            boolean hasVertex=source!=null&&source.vertexSource!=null&&!source.vertexSource.isBlank();
            GlslProgramLoader.Loaded vertex=hasVertex?GlslProgramLoader.loadVertex(pass.programId,source.vertexSource):null;
            if(loaded==null||hasVertex&&vertex==null||SKIP_PROGRAMS.contains(pass.programId))supported=false;
            else if(!postStagesSupported(loaded.meta,vertex==null?null:vertex.meta)) {
                supported=false;
                if(passDiagLogged.add("resource-capability#"+cachedPipeline+"#"+pass.name))
                    LOG.warn("[Antimony] post pass '{}' VS/FS require typed 3D/derivative inputs with no current producer binding: FS types={} derivatives={} VS types={} derivatives={}; reject chain before RT clear/seed",
                        pass.name,loaded.meta.samplerTypes,loaded.meta.derivativeInputs,vertex==null?"none":vertex.meta.samplerTypes,vertex==null?"none":vertex.meta.derivativeInputs);
            }
        }
        if (!supported) {
            if (passDiagLogged.add("incomplete-chain#" + cachedPipeline))
                LOG.warn("[Antimony] CPU shader chain incomplete: preserving ordinary scene, no partial post presentation; required unsupported stages listed above");
            return false;
        }

        // Reject missing/stale copies before clearing or seeding any persistent RT.
        DepthFrame.Snapshot depths = DEPTH_FRAME.consume(
            net.minecraft.client.Minecraft.getInstance().level, cachedPipeline, fb);
        int chainWidth = gbufferFrame?fb.width:Math.max(1, Math.round(fb.width * scale));
        int chainHeight = gbufferFrame?fb.height:Math.max(1, Math.round(fb.height * scale));
        if(!gbufferFrame)refreshTargetLayout(c, chainWidth, chainHeight);
        chainW = chainWidth;
        chainH = chainHeight;
        if (!frameStateReady || preparedPipeline != cachedPipeline) return false;
        Map<Integer, CpuImage> images = IMAGE_POOL;
        Set<Integer> content = new HashSet<>(gbufferFrame?frameContent:Set.of());

        // Every render target gets a CPU image up front: passes that sample a
        // target nothing has written yet then read black (matching a freshly
        // cleared GL texture) instead of skipping the whole pass. The images
        // are pooled per GL texture id, so this is allocation-free after the
        // first frame.
        for (RtDef rt : c.targets) {
            CpuImage main=imageFor(images, rt.mainTexture, chainWidth, chainHeight);
            if(main!=null)main.storageEncoding(rt.encoding);
            if (rt.altTexture != 0) {
                CpuImage alt=imageFor(images, rt.altTexture, chainWidth, chainHeight);
                if(alt!=null)alt.storageEncoding(rt.encoding);
            }
        }
        for (int texture : depthTextures(c)) imageFor(images, texture, chainWidth, chainHeight);

        if(!gbufferFrame){
        // Legacy chains still start from the ordinary software scene.
        var fog = dev.antimony.core.render.FogFrame.current();
        var clears = IrisClearBridge.plan(cachedPipeline, c.fullClearPending,
            fog == null ? 0 : fog.red(), fog == null ? 0 : fog.green(), fog == null ? 0 : fog.blue());
        IrisClearBridge.apply(clears, images);
        c.fullClearPending = false;
        for (var clear : clears) markWritten(clear.texture());
        // Cleared allocations are known values, but are not successful frame history producers.

        // Seed: the software framebuffer is the pack's scene colour. Iris
        // expects it in colortex0/colortex1 after the gbuffer pass.
        for (RtDef rt : c.targets) {
            if (!SEED_TARGETS.contains(rt.index)) {
                continue;
            }
            CpuImage main = imageFor(images, rt.mainTexture, chainWidth, chainHeight);
            if (main == null) {
                continue;
            }
            main.seedFromArgb(fb.color, fb.width, fb.height);
            content.add(rt.mainTexture);
            markWritten(rt.mainTexture);
            if (rt.altTexture != 0) {
                CpuImage alt = imageFor(images, rt.altTexture, chainWidth, chainHeight);
                if (alt != null) {
                    System.arraycopy(main.rgba, 0, alt.rgba, 0, main.rgba.length);
                    content.add(rt.altTexture);
                    markWritten(rt.altTexture);
                }
            }
        }
        }
        // Use this generation's independent boundary copies and the real Iris texture IDs.
        seedDepthTexture(images, content, c.depthTexture, depths.finalDepth(), depths, chainWidth, chainHeight);
        seedDepthTexture(images, content, c.depthNoTranslucentsTexture, depths.preTranslucent(), depths, chainWidth, chainHeight);
        seedDepthTexture(images, content, c.depthNoHandTexture, depths.preHand(), depths, chainWidth, chainHeight);
        if (passDiagLogged.add("depth-stage-snapshots#" + c.targetLayout))
            LOG.info("[Antimony] CPU depth snapshots: depthtex0={} final, depthtex1={} pre-translucent, depthtex2={} pre-opaque-hand; CPU opaque hand stage not yet split",
                c.depthTexture, c.depthNoTranslucentsTexture, c.depthNoHandTexture);

        // Seed the auto-exposure persistence buffer (gaux3 = colortex6, OptiFine
        // convention colortex(3+N)): the pack's vertex stages read the PREVIOUS
        // frame's exposure from its centre pixel and mix it into the new value.
        // Nothing on the CPU wrote it yet, so a 0 there collapses the exposure
        // mix to ~0.04 and the tonemap multiplies the whole frame to black
        // (B1 round 10). Seeding 1.0 keeps the mix anchored until B2 seeds the
        // full persistence buffer.
        for (RtDef rt : c.targets) {
            if (gbufferFrame || rt.index != EXPOSURE_SEED_RT) {
                continue;
            }
            seedExposurePixel(images, rt.mainTexture, chainWidth, chainHeight);
            if (rt.altTexture != 0) {
                seedExposurePixel(images, rt.altTexture, chainWidth, chainHeight);
            }
        }

        List<IrisRenderTargetHistory.Copy> copies = historyCopies(c);
        IrisRenderTargetHistory.Transaction history =
            new IrisRenderTargetHistory.Transaction(copies, images, c.historySnapshots);
        Map<Integer, Long> stampsBefore = new HashMap<>(WRITE_STAMP);
        boolean complete = true, finalSucceeded = false, committed = false;
        if (c.presentationBefore == null || c.presentationBefore.length != fb.color.length) c.presentationBefore = new int[fb.color.length];
        System.arraycopy(fb.color, 0, c.presentationBefore, 0, fb.color.length);
        try {
            for (PassDef pass : c.passes) {
                if(gbufferFrame&&earlyPasses.contains(pass))continue;
                boolean ok = pass.finalPass
                    ? runFinalPass(pass, c, images, content, fb, chainWidth, chainHeight)
                    : runCompositePass(pass, c, images, content, chainWidth, chainHeight);
                if (!ok) { complete = false; break; }
                if (pass.finalPass) finalSucceeded = true;
            }
            if (complete && c.passes.stream().noneMatch(pass -> pass.finalPass) && c.finalRenderer != null) {
                Object baseline = field(c.finalRenderer, "baseline");
                int texture = baseline == null ? 0 : intOf(call(baseline, "getColorAttachment", new Class<?>[]{int.class}, 0));
                CpuImage source = images.get(texture);
                if (source != null && content.contains(texture)) {
                    source.readNormalizedIntoArgb(fb.color, fb.width, fb.height);
                    finalSucceeded = true;
                }
            }
            if (complete && finalSucceeded) {
                if (DIAG && (frameNo == 9 || frameNo == 10)) for (var copy : copies)
                    LOG.info("[Antimony] history-copy phase=before postFrame={} source={} destination={} sourceCentre={} destinationCentre={}",
                        frameNo, copy.source(), copy.destination(), arr(images.get(copy.source()).sample(.5f,.5f)),
                        arr(images.get(copy.destination()).sample(.5f,.5f)));
                committed = history.commit(images, content);
                if (!committed) LOG.warn("[Antimony] history commit rejected: invalid or unwritten swap source");
                if (committed) for (var copy : copies) {
                    markWritten(copy.destination());
                    if (DIAG && (frameNo == 9 || frameNo == 10)) try {
                        LOG.info("[Antimony] history-copy phase=after postFrame={} source={} destination={} destinationCentre={}",
                            frameNo, copy.source(), copy.destination(), arr(images.get(copy.destination()).sample(.5f,.5f)));
                    } catch (Throwable diagnosticFailure) {
                        LOG.warn("[Antimony] history-copy diagnostic failed after commit", diagnosticFailure);
                    }
                }
            }
            return complete && finalSucceeded && committed;
        } finally {
            if (!committed) {
                System.arraycopy(c.presentationBefore, 0, fb.color, 0, fb.color.length);
                history.rollback(images);
                WRITE_STAMP.clear(); WRITE_STAMP.putAll(stampsBefore);
            }
        }
    }

    private static boolean runCompositePass(PassDef pass, Chain c, Map<Integer, CpuImage> images,
                                            Set<Integer> content, int width, int height) {
        pass.mipViews.clear();
        GlslProgramLoader.Loaded loaded = load(pass);
        if (loaded == null) {
            return false;
        }
        if (SKIP_PROGRAMS.contains(pass.programId)) {
            // Its render targets keep their previous content (seed/other
            // passes) - the chain treats the pass as a no-op, matching what a
            // pack without this composite stage would look like.
            return skip(pass, "excluded by -Dantimony.post.skip");
        }
        int[] targets = targetTextures(pass);
        if (targets.length == 0) {
            return skip(pass, "no colour attachment");
        }
        // Bind the pass' state so Iris uploads this pass' uniforms and sampler
        // bindings through the phase-6 shim (values are then recorded there).
        bind(pass);
        Set<Integer> exclude = new HashSet<>();
        for (int t : targets) {
            exclude.add(t);
        }
        Map<String, CpuImage> samplerImages = resolveSamplers(loaded, c, images, content, pass, width, height, exclude);
        if (samplerImages == null) {
            return skip(pass, "input render target not available on CPU");
        }
        if (DIAG && frameNo == 10) {
            StringBuilder sb = new StringBuilder("[Antimony] samplers '").append(pass.name).append("' draws[");
            for (int t : targets) {
                sb.append(' ').append(t);
            }
            sb.append(" ] reads:");
            for (Map.Entry<String, CpuImage> e : samplerImages.entrySet()) {
                int tex = 0;
                for (Map.Entry<Integer, CpuImage> ie : images.entrySet()) {
                    if (ie.getValue() == e.getValue()) {
                        tex = ie.getKey();
                        break;
                    }
                }
                sb.append(' ').append(e.getKey()).append("=tex").append(tex)
                    .append('(').append(stats(e.getValue())).append(')');
            }
            LOG.info(sb.toString());
        }
        CpuImage[] targetImages = new CpuImage[targets.length];
        for (int i = 0; i < targets.length; i++) {
            targetImages[i] = imageFor(images, targets[i], width, height);
        }
        if (targetImages[0] == null) {
            return skip(pass, "target texture has no CPU image");
        }
        try {
            // B1: vertex stage runs once before the fan-out; failure falls
            // back to the phase-A varying defaults.
            GlShimState.ShaderRecord record = GlShimState.program(pass.programId);
            GlslProgramLoader.Loaded vertexLoaded = record != null && record.vertexSource != null
                ? GlslProgramLoader.loadVertex(pass.programId, record.vertexSource)
                : null;
            if (vertexLoaded != null) {
                samplerImages = resolveVertexSamplers(vertexLoaded, samplerImages, c, images, pass, width, height);
            }
            float[] skyMask = SKY_MASK_PASSES.contains(pass.name.toLowerCase(java.util.Locale.ROOT))
                ? buildSkyMask(c, images, width, height) : null;
            if (skyMask != null && DIAG && frameNo == 10) {
                int ones = 0;
                for (float m : skyMask) {
                    ones += m > 0.0f ? 1 : 0;
                }
                CpuImage depthImg = c.depthTexture != 0 ? images.get(c.depthTexture) : null;
                float[] d = depthImg != null ? depthImg.sample(0.5f, 0.5f) : null;
                IrisGlFramebuffer fbd = dev.antimony.core.irisgl.IrisGl.framebuffer();
                float fdCentre = fbd.depth[(fbd.height / 2) * fbd.width + fbd.width / 2];
                float fdCorner = fbd.depth[fbd.width + 10];
                LOG.info("[Antimony] skymask-dbg: maskOnes={} / {} depthImgCentre={} fbDepthCentre={} fbDepthTop={}",
                    ones, skyMask.length,
                    d == null ? "null" : String.format("%.5f", d[0]),
                    String.format("%.5f", fdCentre), String.format("%.5f", fdCorner));
            }
            long passStart = System.nanoTime();
            executeProgram(loaded, vertexLoaded, pass.programId, targetImages, samplerImages, images,
                width, height, skyMask,pass.program);
            CpuPassDiagnostics.program(frameNo, pass.name, pass.programId);
            for (int i = 0; i < targetImages.length; i++)
                CpuPassDiagnostics.image(frameNo, pass.name + "-rt" + pass.drawBuffers[i], targetImages[i]);
            if (DIAG && frameNo == 10) {
                LOG.info("[Antimony] passtime postFrame={} '{}' {} ms", frameNo, pass.name,
                    String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - passStart) / 1.0e6));
                // B4 diag: what each stage actually produced. Reading the mean
                // per stage localises a washed-out frame to one pass instead of
                // guessing at the shader maths.
                for (int i = 0; i < targetImages.length; i++) {
                    LOG.info("[Antimony] passout run={} postFrame={} '{}' pid={} slot{} tex{} {}", DIAG_RUN, frameNo, pass.name, pass.programId, i,
                        targets[i], stats(targetImages[i]));
                }
            }
        } catch (Throwable t) {
            // The frame transaction rejects this chain: no dependent/final stage
            // may consume the partly written target after a worker failure.
            LOG.error("[Antimony] post pass '{}' crashed, skipping from next frame", pass.name, t);
            return skip(pass, "program crashed: " + t);
        }
        for (int i = 0; i < targets.length; i++) {
            if (targetImages[i] != null) {
                content.add(targets[i]);
                markWritten(targets[i]);
            }
        }
        if (DIAG && frameNo == 10) {
            for (int i = 0; i < targets.length; i++) {
                LOG.info("[Antimony] passstats postFrame={} '{}' slot{} tex{} {}", frameNo, pass.name, i, targets[i],
                    stats(targetImages[i]));
            }
        }
        if (DIAG && frameNo == 10) {
            // Distinguish "the write target stayed black because the attachment
            // never got bound" from "the pass genuinely evaluated to zero".
            // A missing binding shows up as targets.length == 0 and a skip()
            // line; a genuine zero shows up here with a live CPU image that the
            // shader itself filled. Both look identical in a screenshot, which is
            // exactly the confusion this line removes.
            for (int i = 0; i < targets.length; i++) {
                CpuImage img = targetImages[i];
                if (img == null) {
                    continue;
                }
                boolean black = isUniformBlack(img);
                LOG.info("[Antimony] targetcheck '{}' slot{} rt{} tex{} bound={} black={} firstPixel={}",
                    pass.name, i, pass.drawBuffers[i], targets[i], true, black,
                    String.format(java.util.Locale.ROOT, "%.4f/%.4f/%.4f/%.4f",
                        img.rgba[0], img.rgba[1], img.rgba[2], img.rgba[3]));
            }
        }
        return true;
    }

    /** One-line mean/max/non-finite summary of a render target, for diagnostics. */
    private static String stats(CpuImage image) {
        if (image == null) {
            return "null";
        }
        double r = 0;
        double g = 0;
        double b = 0;
        float max = 0;
        int nan = 0;
        int n = 0;
        for (int i = 0; i < image.rgba.length; i += 4) {
            float vr = image.rgba[i];
            float vg = image.rgba[i + 1];
            float vb = image.rgba[i + 2];
            if (!Float.isFinite(vr) || !Float.isFinite(vg) || !Float.isFinite(vb)) {
                nan++;
                continue;
            }
            r += vr;
            g += vg;
            b += vb;
            max = Math.max(max, Math.max(vr, Math.max(vg, vb)));
            n++;
        }
        if (n == 0) {
            return "all-non-finite";
        }
        return String.format(java.util.Locale.ROOT,
            "avg=(%.4f,%.4f,%.4f) max=%.4f nonFinite=%d", r / n, g / n, b / n, max, nan);
    }

    /**
     * True when every colour component of {@code image} is exactly 0.
     *
     * <p>Used by the {@code targetcheck} diag line to separate "nothing was
     * bound / nothing was written" from "the shader evaluated to black". An
     * all-zero image is what both look like on screen, so the distinction has
     * to be made on the CPU buffer, not in a screenshot.</p>
     */
    private static boolean isUniformBlack(CpuImage image) {
        if (image == null || image.rgba == null) {
            return true;
        }
        float[] px = image.rgba;
        for (int i = 0; i + 3 < px.length; i += 4) {
            if (px[i] != 0.0f || px[i + 1] != 0.0f || px[i + 2] != 0.0f) {
                return false;
            }
        }
        return true;
    }

    /** B1 round-14 probe helper: unit images for a vertex instance. */
    private static CpuImage[] buildVertexUnits(Map<String, CpuImage> samplerImages,
                                               GlslProgramLoader.Loaded vertexLoaded) {
        CpuImage[] units = new CpuImage[MAX_UNITS];
        for (Map.Entry<String, CpuImage> e : samplerImages.entrySet()) {
            Integer u = vertexLoaded.meta.samplerUnits.get(e.getKey());
            if (u != null && u < MAX_UNITS) {
                units[u] = e.getValue();
            }
        }
        return units;
    }

    /**
     * B1: vertex stages sample textures the fragment stage never touches
     * (p24/p207 read the exposure persistence buffer gaux3). Extend the
     * fragment-resolved sampler map with the vertex stage's own reads:
     * freshest shim unit bind, then any candidate CPU image, then the upload
     * mirror (resource textures). Unresolvable vertex-only samplers stay
     * absent - the generated vertex code reads black for them, which is what
     * the GPU would show for a fresh texture.
     */
    private static Map<String, CpuImage> resolveVertexSamplers(GlslProgramLoader.Loaded vertexLoaded,
                                                               Map<String, CpuImage> samplerImages, Chain c,
                                                               Map<Integer, CpuImage> images, PassDef pass,
                                                               int width, int height) {
        Map<String, CpuImage> resolved = new LinkedHashMap<>(samplerImages);
        for (String name : vertexLoaded.meta.usedSamplers) {
            if(!Set.of("sampler2D","sampler2DShadow").contains(vertexLoaded.meta.samplerTypes.getOrDefault(name,"sampler2D")))continue;
            if (resolved.containsKey(name) && resolved.get(name) != null) {
                continue;
            }
            CpuImage image = resolveSampler(name, c, images, pass);
            if (image == null) throw new IllegalStateException("Vertex sampler unavailable: " + name);
            resolved.put(name, image);
        }
        return resolved;
    }

    private static boolean runFinalPass(PassDef pass, Chain c, Map<Integer, CpuImage> images,
                                        Set<Integer> content, IrisGlFramebuffer fb, int width, int height) {
        pass.mipViews.clear();
        GlslProgramLoader.Loaded loaded = load(pass);
        if (loaded == null) {
            return false;
        }
        bind(pass);
        Map<String, CpuImage> samplerImages = resolveSamplers(loaded, c, images, content, pass, width, height,
            java.util.Set.of());
        if (samplerImages == null) {
            return skip(pass, "input render target not available on CPU");
        }
        CpuImage screen = new CpuImage(width, height);
        try {
            GlShimState.ShaderRecord record = GlShimState.program(pass.programId);
            GlslProgramLoader.Loaded vertexLoaded = record != null && record.vertexSource != null
                ? GlslProgramLoader.loadVertex(pass.programId, record.vertexSource)
                : null;
            if (vertexLoaded != null) {
                // B1 (round 10): the FINAL pass' vertex stage also samples
                // textures the fragment never touches (p207 reads the
                // exposure buffer gaux3). Without the union resolution its
                // exposure read returned black, the tonemap multiplied the
                // whole frame by ~0.04 and every screenshot came out black.
                samplerImages = resolveVertexSamplers(vertexLoaded, samplerImages, c, images, pass, width, height);
            }
            // B1 round-14 probe: one frame of hard numbers for the final
            // pass (scene colour centre pixel, copied exposure, output
            // centre pixel). Diagnoses a constant-black final image.
            if (DIAG && frameNo == 10) {
                CpuImage scene = samplerImages.get("colortex1");
                float[] s = scene != null ? scene.sample(0.5f, 0.5f) : null;
                float exp = -1;
                try {
                    Object vInst = newInstance(vertexLoaded != null ? vertexLoaded.program.getClass() : loaded.program.getClass());
                    GlslProgram v = (GlslProgram) vInst;
                    if (vertexLoaded != null) {
                        applyUniforms(pass.programId, vertexLoaded, v);
                        v.runVertex(new VertexState(buildVertexUnits(samplerImages, vertexLoaded), width, height, 0.5f, 0.5f));
                        exp = v.getClass().getField("exposure").getFloat(v);
                    }
                } catch (Throwable ignored) {
                }
                LOG.info("[Antimony] final-dbg postFrame={}: sceneCentre={} exposure={} out0 will be tonemapped", frameNo,
                    s == null ? "null" : String.format("r=%.4f g=%.4f b=%.4f a=%.4f", s[0], s[1], s[2], s[3]), exp);
            }
            long finalStart = System.nanoTime();
            executeProgram(loaded, vertexLoaded, pass.programId, new CpuImage[]{screen}, samplerImages,
                images, width, height, null,pass.program);
            CpuPassDiagnostics.program(frameNo, "final", pass.programId);
            CpuPassDiagnostics.image(frameNo, "final", screen);
            if (DIAG && frameNo == 10) {
                LOG.info("[Antimony] passtime postFrame={} 'final' {} ms", frameNo,
                    String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - finalStart) / 1.0e6));
                LOG.info("[Antimony] passout run={} postFrame={} 'final' pid={} slot0 {}", DIAG_RUN, frameNo, pass.programId, stats(screen));
            }
            if (DIAG && frameNo == 10) {
                float[] o = new float[4];
                screen.readPixel(width / 2, height / 2, o);
                double sum = 0;
                float max = 0;
                int nonzero = 0;
                for (int i = 0; i < width * height; i += 7) {
                    float v = screen.rgba[i * 4];
                    sum += v;
                    max = Math.max(max, v);
                    nonzero += v > 0.003f ? 1 : 0;
                }
                LOG.info("[Antimony] final-dbg postFrame={}: OUTPUT centre r={} g={} b={} avgR={} maxR={} nonzero={}", frameNo,
                    String.format("%.4f", o[0]), String.format("%.4f", o[1]), String.format("%.4f", o[2]),
                    String.format("%.5f", sum / (width * height / 7.0)),
                    String.format("%.4f", max), nonzero);
            }
        } catch (Throwable t) {
            LOG.error("[Antimony] post pass 'final' crashed, writing unprocessed frame", t);
            return false;
        }
        screen.readNormalizedIntoArgb(fb.color, fb.width, fb.height);
        return true;
    }

    /**
     * Run one translated fragment program over one render target, split across
     * the post worker pool. Every worker gets its own program instance because
     * the generated class keeps varyings/hoisted locals in fields; the target
     * images are partitioned by row, so writes never overlap.
     *
     * <p>B1: when {@code vertexLoaded} is present the vertex stage runs ONCE
     * (at the quad centre, before the fan-out) and its declared outputs are
     * copied into every fragment instance - in the captured corpus the
     * fullscreen passes' varyings are constant across the quad (sky/cloud
     * colours, light vectors, matrices; docs section 21).</p>
     */
    private static void executeProgram(GlslProgramLoader.Loaded loaded, GlslProgramLoader.Loaded vertexLoaded,
                                       int programId, CpuImage[] targets,
                                       Map<String, CpuImage> samplerImages, Map<Integer, CpuImage> images,
                                       int width, int height, float[] skyMask,Object resourceProgram) {
        Map<String,CpuVolume> volumes=new LinkedHashMap<>(resolveVolumes(loaded.meta,resourceProgram,programId));
        if(vertexLoaded!=null)volumes.putAll(resolveVolumes(vertexLoaded.meta,resourceProgram,programId));
        CpuVolume[] fragmentVolumes=volumeUnits(loaded.meta,volumes);
        // The generated program indexes unitImages through its public s$N
        // int fields (N = the sampler's compile-order index assigned by A2);
        // they default to 0, so each must carry its OWN index or every
        // sampler reads slot 0. Images are resolved by NAME upstream, so no
        // GL unit indirection remains.
        // Read-before-write. A pass may sample a render target it also draws to
        // (MakeUp: every composite stage does this with colortex1, and
        // composite2 additionally samples its own neighbourhoods). On the GPU
        // that read returns the pre-pass texel; here the live image is about to
        // be overwritten, so aliased targets are frozen first.
        java.util.IdentityHashMap<CpuImage, CpuImage> aliases = new java.util.IdentityHashMap<>();
        for (CpuImage target : targets) {
            if (target == null) {
                continue;
            }
            for (CpuImage sampled : samplerImages.values()) {
                if (sampled == target) {
                    aliases.put(target, snapshotOf(target));
                    break;
                }
            }
        }

        CpuImage[] unitImages = new CpuImage[MAX_UNITS];
        for (Map.Entry<String, CpuImage> e : samplerImages.entrySet()) {
            Integer compileUnit = loaded.meta.samplerUnits.get(e.getKey());
            if (compileUnit == null || compileUnit >= MAX_UNITS) {
                continue;
            }
            CpuImage img = e.getValue();
            CpuImage frozen = img == null ? null : aliases.get(img);
            unitImages[compileUnit] = frozen != null ? frozen : img;
        }

        // B1: run the vertex stage once and keep its instance as the copy
        // template for the varyings below. Failure is non-fatal: fragments
        // then run with the phase-A varying defaults.
        GlslProgram vertexInstance = null;
        java.util.Set<String> vertexOutNames = null;
        if (vertexLoaded != null) {
            try {
                CpuImage[] vertexUnits = new CpuImage[MAX_UNITS];
                for (Map.Entry<String, CpuImage> e : samplerImages.entrySet()) {
                    Integer compileUnit = vertexLoaded.meta.samplerUnits.get(e.getKey());
                    if (compileUnit == null || compileUnit >= MAX_UNITS) {
                        continue;
                    }
                    CpuImage img = e.getValue();
                    CpuImage frozen = img == null ? null : aliases.get(img);
                    vertexUnits[compileUnit] = frozen != null ? frozen : img;
                }
                vertexInstance = newInstance(vertexLoaded.program.getClass());
                if (vertexInstance == null) {
                    vertexInstance = vertexLoaded.program;
                }
                applyUniforms(programId, vertexLoaded, vertexInstance);
                CpuPassState vertexResources=new CpuPassState(width,height);
                System.arraycopy(volumeUnits(vertexLoaded.meta,volumes),0,vertexResources.unitVolumes,0,MAX_UNITS);
                vertexInstance.runVertex(new VertexState(vertexUnits, width, height, 0.5f, 0.5f).imageServices(vertexResources));
                vertexOutNames = vertexLoaded.meta.vertexOutputs.keySet();
                if (DIAG && frameNo == 10 && programId == 18) {
                    try {
                        Object up = vertexInstance.getClass().getField("upVector").get(vertexInstance);
                        Object zen = vertexInstance.getClass().getField("zenithSkyColor").get(vertexInstance);
                        Object hor = vertexInstance.getClass().getField("horizonSkyColor").get(vertexInstance);
                        LOG.info("[Antimony] skyvary-dbg up={} zenith={} horizon={}", arr(up), arr(zen), arr(hor));
                    } catch (Throwable ignored) {
                    }
                }
                if (DIAG && frameNo == 10 && passDiagLogged.add("vary#" + programId)) {
                    // B4 diag: every varying the vertex stage produced. These
                    // are the values the fragment stage actually consumes, so a
                    // wrong exposure/light position shows up here as a number
                    // instead of a washed-out picture.
                    StringBuilder vb = new StringBuilder("[Antimony] varyings-dbg ").append(programId).append(':');
                    for (String outName : vertexOutNames) {
                        try {
                            Field vf = vertexInstance.getClass().getField(
                                vertexLoaded.meta.vertexOutputs.get(outName));
                            if (vf.getType() == float.class) {
                                vb.append(' ').append(outName).append('=')
                                    .append(String.format(java.util.Locale.ROOT, "%.4f", vf.getFloat(vertexInstance)));
                            } else if (vf.getType() == float[].class) {
                                vb.append(' ').append(outName).append('=').append(arr(vf.get(vertexInstance)));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    LOG.info(vb.toString());
                }
                if (DIAG && passDiagLogged.add("vexec#" + programId)) {
                    LOG.info("[Antimony] post vertex {} executed once, outputs {}", programId, vertexOutNames);
                }
            } catch (Throwable t) {
                throw new IllegalStateException("post vertex " + programId + " failed", t);
            }
        }

        int threads = Math.min(THREADS, height);
        List<GlslProgram> instances = new ArrayList<>(threads);
        instances.add(loaded.program);
        for (int i = 1; i < threads; i++) {
            GlslProgram extra = newInstance(loaded.program.getClass());
            if (extra == null) {
                threads = 1;
                break;
            }
            instances.add(extra);
        }
        for (GlslProgram instance : instances) {
            applyUniforms(programId, loaded, instance);
            applyVaryingDefaults(loaded, instance);
            if (DIAG && frameNo == 10 && instance == loaded.program) {
                StringBuilder sb = new StringBuilder("[Antimony] uniforms-dbg ").append(programId).append(':');
                for (Map.Entry<String, String> e : loaded.meta.uniformFields.entrySet()) {
                    try {
                        Field uf = instance.getClass().getField(e.getValue());
                        Class<?> ut = uf.getType();
                        if (ut == float.class) {
                            sb.append(' ').append(e.getKey()).append('=')
                                .append(String.format(java.util.Locale.ROOT, "%.4f", uf.getFloat(instance)));
                        } else if (ut == float[].class) {
                            // B4 diag: vectors and matrices too, otherwise the
                            // TAA/projection uniforms (which are exactly the
                            // ones that go wrong) stay invisible.
                            sb.append(' ').append(e.getKey()).append('=')
                                .append(arr(uf.get(instance)));
                        }
                    } catch (Throwable ignored) {
                    }
                }
                LOG.info(sb.toString());
            }
            if (vertexInstance != null) {
                copyVertexOutputs(vertexInstance, instance, vertexOutNames);
            }
        }

        int rowsPer = (height + threads - 1) / threads;
        if (threads == 1) {
            runRows(loaded.program, targets, unitImages, fragmentVolumes,width, height, 0, height, skyMask);
            return;
        }
        List<Future<?>> futures = new ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            int y0 = t * rowsPer;
            int y1 = Math.min(height, y0 + rowsPer);
            GlslProgram instance = instances.get(t);
            futures.add(POOL.submit(() -> runRows(instance, targets, unitImages, fragmentVolumes,width, height, y0, y1, skyMask)));
        }
        Throwable workerFailure = null;
        boolean interrupted = false;
        for (Future<?> future : futures) {
            boolean joined = false;
            while (!joined) {
                try {
                    future.get();
                    joined = true;
                } catch (InterruptedException e) {
                    interrupted = true;
                    if (workerFailure == null) workerFailure = e;
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) {
                    if (workerFailure == null) workerFailure = e;
                    joined = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        // All workers must be quiescent before history rollback can safely restore the images.
        if (workerFailure != null) throw new IllegalStateException("post worker failed", workerFailure);
    }

    /**
     * Copy the vertex stage's declared outputs into one fragment instance by
     * reflection. Field names match by construction: the fragment compiler
     * names a varying "in" field with the same mangle() as the vertex
     * compiler names the corresponding "out" field.
     */
    private static void copyVertexOutputs(GlslProgram vertexInstance, GlslProgram fragmentInstance,
                                          java.util.Set<String> names) {
        if (names == null) {
            return;
        }
        Class<?> vc = vertexInstance.getClass();
        Class<?> fc = fragmentInstance.getClass();
        for (String name : names) {
            if (name.equals("texcoord") || name.equals("texCoord")) {
                continue; // per-fragment; setFragment fills it
            }
            try {
                Field vf = vc.getField(name);
                Field ff = fieldOf(fc, name);
                if (ff == null) {
                    continue;
                }
                Class<?> ft = ff.getType();
                if (ft == float.class) {
                    ff.setFloat(fragmentInstance, vf.getFloat(vertexInstance));
                } else if (ft == int.class) {
                    ff.setInt(fragmentInstance, vf.getInt(vertexInstance));
                } else if (ft == boolean.class) {
                    ff.setBoolean(fragmentInstance, vf.getBoolean(vertexInstance));
                } else if (ft == float[].class) {
                    float[] src = (float[]) vf.get(vertexInstance);
                    if (src == null) {
                        continue;
                    }
                    float[] dst = (float[]) ff.get(fragmentInstance);
                    if (dst == null || dst.length != src.length) {
                        dst = new float[src.length];
                        ff.set(fragmentInstance, dst);
                    }
                    System.arraycopy(src, 0, dst, 0, src.length);
                } else if (ft == int[].class) {
                    int[] src = (int[]) vf.get(vertexInstance);
                    if (src == null) {
                        continue;
                    }
                    int[] dst = (int[]) ff.get(fragmentInstance);
                    if (dst == null || dst.length != src.length) {
                        dst = new int[src.length];
                        ff.set(fragmentInstance, dst);
                    }
                    System.arraycopy(src, 0, dst, 0, src.length);
                }
            } catch (Throwable ignored) {
                // fragment stage does not read this varying: skip
            }
        }
    }

    private static void runRows(GlslProgram program, CpuImage[] targets, CpuImage[] unitImages,CpuVolume[] unitVolumes,
                                int width, int height, int y0, int y1, float[] skyMask) {
        CpuPassState state = new CpuPassState(width, height);
        System.arraycopy(unitImages, 0, state.unitImages, 0, Math.min(unitImages.length, MAX_UNITS));
        System.arraycopy(unitVolumes,0,state.unitVolumes,0,Math.min(unitVolumes.length,MAX_UNITS));
        float[][] outputs = outputs(program);
        int outCount = Math.min(targets.length, Math.min(outputs.length, MAX_UNITS));
        float invW = 1.0f / width;
        for (int y = y0; y < y1; y++) {
            state.y = y;
            // GL window space: v = 0 at the bottom row. Our storage is
            // top-down, so row y maps to v = (height - y - 0.5) / height; that
            // also makes gl_FragCoord.y (fragV * height) match the value a GPU
            // would hand the shader.
            float v = (height - y - 0.5f) / height;
            int row = y * width * 4;
            for (int x = 0; x < width; x++) {
                state.x = x;
                program.setFragment((x + 0.5f) * invW, v);
                program.run(state);
                int o = row + x * 4;
                // Sky-pass depth guard (B1): the GPU's depth test keeps the
                // sky pass off terrain pixels; the fragment itself has no
                // guard, so the executor drops writes where the scene depth
                // is closer than the far plane.
                if (skyMask != null && skyMask[y * width + x] <= 0.0f) {
                    continue;
                }
                for (int i = 0; i < outCount; i++) {
                    CpuImage target = targets[i];
                    float[] color = outputs[i];
                    if (target == null || color == null) {
                        continue;
                    }
                    // B1 NaN firewall (round 14): one NaN poisons every later
                    // pass and finally renders as (int)NaN = 0 = black. A
                    // single finite guard here keeps the chain alive.
                    float r = color[0];
                    float g = color[1];
                    float b = color[2];
                    float a = color[3];
                    if (!(r > -Float.MAX_VALUE && r < Float.MAX_VALUE)) {
                        r = 0.0f;
                    }
                    if (!(g > -Float.MAX_VALUE && g < Float.MAX_VALUE)) {
                        g = 0.0f;
                    }
                    if (!(b > -Float.MAX_VALUE && b < Float.MAX_VALUE)) {
                        b = 0.0f;
                    }
                    if (!(a > -Float.MAX_VALUE && a < Float.MAX_VALUE)) {
                        a = 1.0f;
                    }
                    target.writePixel(o, r, g, b, a);
                }
            }
        }
    }

    private static GlslProgram newInstance(Class<?> type) {
        try {
            return (GlslProgram) type.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Pass plumbing
    // ------------------------------------------------------------------

    private static GlslProgramLoader.Loaded load(PassDef pass) {
        GlShimState.ShaderRecord record = GlShimState.program(pass.programId);
        if (record == null || record.fragmentSource == null) {
            return skipLoad(pass, "no captured fragment source");
        }
        GlslProgramLoader.Loaded loaded =
            GlslProgramLoader.load(pass.programId, patchConstants(record.fragmentSource),
                patchConstants(record.vertexSource));
        if (loaded == null) {
            return skipLoad(pass, "translate/compile failed: " + GlslProgramLoader.failure(pass.programId));
        }
        return loaded;
    }

    /**
     * B2锛氭妸鍖呴噷缂栬瘧鏈熷啓姝荤殑 {@code shadowMapResolution} 鏀规垚 CPU 闃村奖璐村浘鐨勭湡瀹?
     * 杈归暱銆傚寘閲岀殑杞槾褰卞亸绉绘槸 {@code offset / shadowMapResolution}锛堜互绾圭礌涓哄崟浣嶏級锛?
     * 甯搁噺瀵逛笉涓婃椂妯＄硦鍗婂緞灏变細鎸夋瘮渚嬬缉鏀撅紝褰卞瓙杈圭紭鍙樼‖鎴栫硦鎴愪竴鍥€?
     */
    private static String patchConstants(String source) {
        if (source == null) {
            return null;
        }
        if(ownedShadowPath())return source; // Current Iris shadow targets use the pack's real resolution.
        int size = dev.antimony.core.render.ShadowMap.RESOLUTION;
        String patched = source.replaceAll(
            "shadowMapResolution\\s*=\\s*\\d+", "shadowMapResolution = " + size);
        return patched;
    }

    private static GlslProgramLoader.Loaded skipLoad(PassDef pass, String reason) {
        if (!pass.reportedSkips) {
            pass.reportedSkips = true;
            LOG.warn("[Antimony] post pass '{}' skipped: {}", pass.name, reason);
        }
        return null;
    }

    private static boolean skip(PassDef pass, String reason) {
        if (!pass.reportedSkips) {
            pass.reportedSkips = true;
            LOG.info("[Antimony] post pass '{}' skipped: {}", pass.name, reason);
        }
        return false;
    }

    private static void bind(PassDef pass) {
        GlShimState.useProgram(pass.programId);
        call(pass.program, "use", new Class<?>[0]);
    }

    private static int[] targetTextures(PassDef pass) {
        if (pass.drawBuffers.length == 0) {
            skip(pass, "no draw buffers");
            return new int[0];
        }
        if (pass.framebuffer == null) {
            skip(pass, "framebuffer field is null");
            return new int[0];
        }
        int[] out = new int[pass.drawBuffers.length];
        StringBuilder diag = DIAG ? new StringBuilder() : null;
        for (int i = 0; i < pass.drawBuffers.length; i++) {
            // Iris keys the FBO's colour attachments by POSITION in the
            // drawBuffers array (RenderTargets.createColorFramebuffer calls
            // addColorAttachment(i, ...) while walking the array); the value
            // is the render target's texture (main or alt, flip baked in at
            // creation). Indexing by the render-target number misses the map
            // and reads as 0, which skipped every composite pass.
            Object texture = call(pass.framebuffer, "getColorAttachment",
                new Class<?>[]{int.class}, i);
            int id = intOf(texture);
            if (DIAG) {
                diag.append(" pos").append(i).append("(rt").append(pass.drawBuffers[i]).append(")=").append(id);
            }
            if (id == 0) {
                skip(pass, "attachment slot " + i + " missing in pass framebuffer");
                return new int[0];
            }
            out[i] = id;
        }
        if (DIAG && passDiagLogged.add("targets#" + pass.programId)) {
            LOG.info("[Antimony] post pass '{}' targets:{}", pass.name, diag);
        }
        return out;
    }

    /** Resolve only samplers used by this program; draw aliases are frozen in executeProgram. */
    private static Map<String, CpuImage> resolveSamplers(GlslProgramLoader.Loaded loaded, Chain c,
                                                         Map<Integer, CpuImage> images, Set<Integer> content,
                                                         PassDef pass, int width, int height,
                                                         Set<Integer> exclude) {
        Map<String, CpuImage> out = new LinkedHashMap<>();
        for (String name : loaded.meta.usedSamplers) {
            if(!Set.of("sampler2D","sampler2DShadow").contains(loaded.meta.samplerTypes.getOrDefault(name,"sampler2D")))continue;
            CpuImage image = resolveSampler(name, c, images, pass);
            if (image == null) return null;
            out.put(name, image);
        }
        return out;
    }

    /** Both stages obey the program's actual sampler supplier, including a draw attachment alias. */
    private static CpuImage resolveSampler(String name, Chain c, Map<Integer, CpuImage> images, PassDef pass) {
        IrisSamplerBridge.Binding binding = effectiveBinding(pass, name);
        if (binding != null) {
            int texture = binding.texture();
            CpuImage image;
            String source;
            if (isRenderTexture(c, texture)) {
                image = images.get(texture);
                source = "render-target/effective-supplier";
            } else {
                image = IrisCpuShadowTargets.resolve(cachedPipeline,currentShadowOrdinal(),texture);
                if(image==null&&!gbufferFrame)image=shadowSampler(name);
                source = "CPU-shadow/current-supplier";
                if (image == null) {
                    image = resourceImage(c, texture);
                    source = c.resourceSources.get(texture);
                }
            }
            samplerDiagnostic(pass, name, binding, image, source);
            return mipSamplerImage(pass,c,texture,image); // Never replace an explicit unavailable binding with another texture.
        }
        CpuImage shadow = shadowSampler(name);
        if (shadow != null) return shadow;
        int[] candidates = candidateTextures(name, c);
        int texture = 0;
        String reason = "unavailable";
        // These sets are immutable build-time read sides, not a run-time ping-pong counter.
        if (pass.stageReadsFromAlt instanceof Set<?> reads && candidates.length > 0
                && !name.equals("noisetex") && !name.equals("gdepthtex") && !name.startsWith("depthtex")) {
            for (RtDef rt : c.targets) if (rt.mainTexture == candidates[0]) {
                texture = reads.contains(rt.index) ? rt.altTexture : rt.mainTexture;
                reason = "build-stageReadsFromAlt";
                break;
            }
        } else {
            texture = IrisRenderTargetHistory.uniqueWritten(candidates, WRITE_STAMP.keySet());
            reason = texture == 0 ? "ambiguous-or-unwritten-CPU-candidates" : "unique-CPU-written-candidate";
        }
        CpuImage image = isRenderTexture(c, texture) ? images.get(texture) : resourceImage(c, texture);
        if (passDiagLogged.add("rt-fallback#" + pass.programId + "#" + name + "#" + reason))
            LOG.warn("[Antimony] sampler fallback pass={} sampler={} texture={} reason={} candidates={}",
                pass.name, name, texture, reason, java.util.Arrays.toString(candidates));
        return mipSamplerImage(pass,c,texture,image);
    }
    private static CpuImage mipSamplerImage(PassDef pass,Chain c,int texture,CpuImage image){
        if(pass==null||image==null||pass.mipmappedBuffers.isEmpty())return image;
        for(RtDef target:c.targets)if(pass.mipmappedBuffers.contains(target.index)
                &&(texture==target.mainTexture||texture==target.altTexture))
            return pass.mipViews.computeIfAbsent(image,CpuImage::mipmappedView);
        return image;
    }
    private static IrisSamplerBridge.Binding effectiveBinding(PassDef pass, String name) {
        if (pass == null) return null;
        int driverUnit = pass.samplerGlUnits.computeIfAbsent(name,
            sampler -> IrisSamplerBridge.samplerUnit(pass.program,pass.programId,sampler));
        IrisSamplerBridge.Binding binding = driverUnit >= 0 ? IrisSamplerBridge.binding(pass.program, driverUnit) : null;
        if (binding != null) return binding;
        int shimLocation = GlShimState.uniformLocation(pass.programId, name);
        GlShimState.UniformValue value = GlShimState.uniformValue(pass.programId, shimLocation);
        if (value != null && value.i.length > 0) binding = IrisSamplerBridge.binding(pass.program, value.i[0]);
        if (binding == null) binding = IrisSamplerBridge.stageBinding(pass.program, pass.customTextureIds, pass.flippedAtLeastOnce, name);
        if (DIAG && passDiagLogged.add("binding-fallback#" + pass.programId + "#" + name))
            LOG.info("[Antimony] sampler-binding-fallback pass={} sampler={} program={} programType={} driverUnit={} shimLoc={} shimValue={} samplerState={} resolved={}",
                pass.name, name, pass.programId, pass.program.getClass().getName(), driverUnit, shimLocation,
                value == null ? "missing" : java.util.Arrays.toString(value.i),
                String.valueOf(field(pass.program, "samplers")), binding);
        return binding;
    }

    private static boolean isRenderTexture(Chain c, int texture) {
        if (texture != 0 && (texture == c.depthTexture || texture == c.depthNoTranslucentsTexture
                || texture == c.depthNoHandTexture)) return true;
        for (RtDef rt : c.targets)
            if (texture != 0 && (texture == rt.mainTexture || texture == rt.altTexture)) return true;
        return false;
    }

    private static CpuImage resourceImage(Chain c, int texture) {
        if (texture <= 0 || isRenderTexture(c, texture)) return null;
        CpuImage cached = c.resourceImages.get(texture);
        if (cached != null) return cached;
        IrisSamplerBridge.Filtering metadata = c.resourceFiltering.get(texture);
        Object access = c.resourceAccess.get(texture);
        // Iris' built-in NoiseTexture is an RGBA NativeImage with fixed upload
        // flags (blur=true, clamp=false), i.e. linear + repeat. Capture this
        // only for that exact Iris class; do not guess for arbitrary textures.
        if (metadata == null && access != null
                && access.getClass().getName().equals("net.irisshaders.iris.targets.backed.NativeImageBackedNoiseTexture")) {
            metadata = new IrisSamplerBridge.Filtering(true, true, "iris-native-noisetex-upload-contract");
        }
        int filter = metadata != null ? (metadata.linear() ? 9729 : 9728) : IrisSamplerBridge.parameter(texture, 10240);
        int wrapS = metadata != null ? (metadata.repeat() ? 10497 : 33071) : IrisSamplerBridge.parameter(texture, 10242);
        int wrapT = metadata != null ? (metadata.repeat() ? 10497 : 33071) : IrisSamplerBridge.parameter(texture, 10243);
        // Unknown configuration must not silently turn a repeating noise texture into a clamped one.
        if ((filter != 9728 && filter != 9729) || (wrapS != 10497 && wrapS != 33071)
                || (wrapT != 10497 && wrapT != 33071)) {
            c.resourceSources.put(texture, "unavailable-texture-parameters");
            return null;
        }
        boolean linear = filter == 9729, repeatS = wrapS == 10497, repeatT = wrapT == 10497;
        CpuImage image = IrisSamplerBridge.nativePixels(access, linear, repeatS, repeatT);
        String source = "Iris TextureAccess CPU pixels/RGBA8";
        if (image == null) {
            var mirror = dev.antimony.gl.hook.GlTexMirror.mirror(texture);
            if (mirror == null || mirror.width <= 0 || mirror.height <= 0) {
                c.resourceSources.put(texture, "unavailable-resource-pixels");
                return null;
            }
            image = new CpuImage(mirror.width, mirror.height, linear, repeatS, repeatT, true);
            image.copyRgba8(mirror.pixels, false); // GlTexMirror stores ARGB, not NativeImage ABGR.
            source = "upload-mirror/RGBA8";
        }
        c.resourceImages.put(texture, image);
        c.resourceSources.put(texture, source + " filter=" + filter + " wrap=" + wrapS + "/" + wrapT
            + " config=" + (metadata != null ? metadata.source() : "direct-driver-DSA"));
        return image;
    }

    private static void samplerDiagnostic(PassDef pass, String name, IrisSamplerBridge.Binding binding,
                                           CpuImage image, String source) {
        if (!DIAG || !passDiagLogged.add("effective#" + pass.programId + "#" + name + "#" + binding.texture())) return;
        int nonzero = 0;
        double sum = 0;
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        if (image != null) for (int i = 0; i < image.rgba.length; i += 4) {
            float r = image.rgba[i];
            min = Math.min(min, r); max = Math.max(max, r); sum += r;
            if (r != 0) nonzero++;
        }
        float periodicDelta = image == null ? Float.NaN
            : Math.abs(image.sample(.173f, .287f)[0] - image.sample(1.173f, -1.713f)[0]);
        LOG.info("[Antimony] sampler-effective pass={} stage={} sampler={} unit={} texture={} type={} samplerObject={} source={} size={}x{} R[min={},max={},mean={}] nonzero={} periodicDelta={}",
            pass.name, pass.textureStage, name, binding.unit(), binding.texture(), binding.type(), binding.sampler(), source,
            image == null ? 0 : image.width, image == null ? 0 : image.height, min, max,
            image == null ? Double.NaN : sum / (image.width * image.height), nonzero, periodicDelta);
    }

    /** One-shot diag log dedup (programId#name). */
    private static final Set<String> passDiagLogged = new HashSet<>();

    /**
     * B2锛氶槾褰遍噰鏍峰櫒鐩磋繛 CPU 闃村奖璐村浘銆倇@code shadowtex0/1} 閮芥槸娣卞害鍥撅紙鎴戜滑
     * 涓嶅仛"姘?鐜荤拑閫忓厜"鐨勫尯鍒紝鍖呴噷鏈潵涔熺敤 {@code discard} 澶勭悊浜嗭級锛?
     * {@code shadowcolor0/1} 缁欏叏鐧?鈥斺€?鍖呴噷 {@code alpha_complement = 1 - a = 0}
     * 浜庢槸褰╄壊闃村奖閫€鍖栨垚绾粦鐧介槾褰憋紝姝ｆ槸娌℃湁鏌撹壊鏁版嵁鏃剁殑姝ｇ‘琛屼负銆?
     */
    private static boolean ownedShadowPath(){return shadowFrame!=null&&shadowFrame.pipeline()==cachedPipeline;}
    private static CpuImage shadowSampler(String name) {
        if(ownedShadowPath())return null; // Never replace a missing current supplier with estimated depth or white color.
        if (!dev.antimony.core.render.ShadowMap.ENABLED) {
            return null;
        }
        return switch (name) {
            case "shadowtex0", "shadowtex1" -> dev.antimony.core.render.ShadowMap.depthImage();
            case "shadowcolor0", "shadowcolor1" -> dev.antimony.core.render.ShadowMap.whiteImage();
            default -> null;
        };
    }

    /** Texture ids a sampler name refers to (main+alt of its render target). */
    private static int[] candidateTextures(String name, Chain c) {        int index = -1;
        if (name.startsWith("colortex")) {
            index = parseIntSuffix(name, "colortex");
        } else if (name.startsWith("gaux")) {
            // OptiFine convention: gaux1..gaux4 alias colortex4..colortex7.
            int aux = parseIntSuffix(name, "gaux");
            index = aux >= 1 && aux <= 4 ? 3 + aux : -1;
        } else if (name.equals("depthtex1")) {
            return c.depthNoTranslucentsTexture != 0 ? new int[]{c.depthNoTranslucentsTexture} : new int[0];
        } else if (name.equals("depthtex2")) {
            return c.depthNoHandTexture != 0 ? new int[]{c.depthNoHandTexture} : new int[0];
        } else if (name.equals("depthtex0") || name.equals("gdepthtex")) {
            return c.depthTexture != 0 ? new int[]{c.depthTexture} : new int[0];
        } else if (name.equals("noisetex")) {
            return c.noiseTexture != 0 ? new int[]{c.noiseTexture} : new int[0];
        } else {
            return new int[0];
        }
        if (index < 0) {
            return new int[0];
        }
        for (RtDef rt : c.targets) {
            if (rt.index == index) {
                if (rt.altTexture != 0) {
                    return new int[]{rt.mainTexture, rt.altTexture};
                }
                return new int[]{rt.mainTexture};
            }
        }
        return new int[0];
    }

    private static int parseIntSuffix(String name, String prefix) {
        try {
            return Integer.parseInt(name.substring(prefix.length()));
        } catch (NumberFormatException t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // Uniforms and varyings
    // ------------------------------------------------------------------

    /** Generated class names repeat after reload; reflection ownership follows Class identity. */
    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        @Override protected Map<String, Field> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };
    private static final Map<String, Integer> LOCATIONS = new ConcurrentHashMap<>();

    /** B2: the four shadow matrices, in the space the shadow map was rasterised in. */
    private static float[] shadowMatrix(String name) {
        if (ownedShadowPath() && shadowFrame != null && shadowFrame.pipeline() == cachedPipeline) {
            float[] actual = shadowFrame.matrix(name);
            if (actual != null) return actual;
        }
        if (!dev.antimony.core.render.ShadowMap.ENABLED || !dev.antimony.core.render.ShadowMap.valid()) {
            return null;
        }
        // Clone: the field holds this array for the whole pass, and generated
        // code assigns mat4 uniforms around freely - aliasing ShadowMap's live
        // matrix would corrupt the next frame's rasterisation. Before the first
        // shadow build `valid()` is false and the getters still return their
        // 4-element placeholder, which would poison a 16-element mat4 field.
        return switch (name) {
            case "shadowProjection" -> dev.antimony.core.render.ShadowMap.projection().clone();
            case "shadowProjectionInverse" -> dev.antimony.core.render.ShadowMap.projectionInverse().clone();
            case "shadowModelView" -> dev.antimony.core.render.ShadowMap.modelView().clone();
            case "shadowModelViewInverse" -> dev.antimony.core.render.ShadowMap.modelViewInverse().clone();
            default -> null;
        };
    }

    /** Every freshly created VS/FS instance needs its own compile-order sampler slots. */
    private static void bindSamplerSlots(GlslProgramLoader.Loaded loaded, GlslProgram instance) {
        for (var sampler : loaded.meta.samplerUnits.entrySet()) {
            int unit = sampler.getValue();
            if (unit < 0 || unit >= MAX_UNITS) throw new IllegalStateException("Post sampler unit out of range: " + sampler);
            Field field = fieldOf(instance.getClass(), "s$" + unit);
            if (field == null || field.getType() != int.class)
                throw new IllegalStateException("Post sampler field missing: " + sampler.getKey());
            try { field.setInt(instance, unit); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Post sampler binding failed: " + sampler.getKey(), failure); }
        }
    }

    private static void applyUniforms(int programId, GlslProgramLoader.Loaded loaded, GlslProgram instance) {
        bindSamplerSlots(loaded, instance);
        Class<?> type = instance.getClass();
        for (Map.Entry<String, String> e : loaded.meta.uniformFields.entrySet()) {
            String name = e.getKey();
            GlShimState.UniformValue value = GlShimState.uniformValue(programId, locationOf(programId, name));
            if (CHAIN_OWNED_UNIFORMS.contains(name)) {
                value = null;
            }
            String signature = loaded.meta.uniformTypes.get(name);
            if (signature == null) {
                continue;
            }
            String javaType = signature.substring(0, signature.indexOf('#'));
            int components = Integer.parseInt(signature.substring(signature.indexOf('#') + 1));
            // CPU-owned data wins before all evaluated/uploaded early returns.
            UniformSnapshot owned = ownedUniform(name, javaType, components);
            if (owned != null && assignSnapshot(instance, e.getValue(), javaType, components, owned)) {
                uniformDiagnostic(name, owned.source, "CPU frame ownership");
                continue;
            }
            // Pack expressions and engine uniforms have different owners. Prefer
            // Iris' evaluated pack value, then an actual upload, then a live CPU
            // reconstruction. Every branch labels its provenance in diagnostics.
            if (!CHAIN_OWNED_UNIFORMS.contains(name)) {
                UniformSnapshot packed = packUniform(name);
                if (packed != null) {
                    if (assignSnapshot(instance, e.getValue(), javaType, components, packed)) {
                        uniformDiagnostic(name, "evaluated", packed.source + " components=" + componentCount(packed));
                        continue;
                    }
                    uniformDiagnostic(name, "unsupported", "evaluated value incompatible with " + javaType + "#" + components);
                }
            }
            if (value != null && !CHAIN_OWNED_UNIFORMS.contains(name)) {
                UniformSnapshot uploaded = new UniformSnapshot("uploaded", value.f, value.i);
                if (assignSnapshot(instance, e.getValue(), javaType, components, uploaded)) {
                    uniformDiagnostic(name, "uploaded", value.kind + " components=" + componentCount(uploaded));
                    continue;
                }
                uniformDiagnostic(name, "unsupported", "uploaded " + value.kind + " incompatible with " + javaType + "#" + components);
                value = null;
            }
            if (value == null && DIAG && !CHAIN_OWNED_UNIFORMS.contains(name)
                    && PACK_UNIFORM_MISSES.add(programId + "#" + name)) {
                Object declared = declaredCustomUniform(name);
                uniformDiagnostic(name, declared == null ? "undeclared" : "not-uploaded",
                    declared == null ? "not declared by pack or GL" : "declared pack uniform missing a compatible current value");
            }
            // B1 (round 14): Iris feeds some standard uniforms (near/far plane,
            // frameTime, view size, ...) through its own uniform updater, not
            // the rewritten GL calls, so the shim never records them. A 0 far
            // plane makes the pack's ld() = 0/0 = NaN and one NaN pixel poisons
            // every later pass (final wrote NaN, (int) NaN = 0 = black frame).
            // Defaults below are real values, not placeholders.
            if (value == null) {
                UniformSnapshot reconstructed = frameUniform(name, javaType, components);
                if (reconstructed != null && assignSnapshot(instance, e.getValue(), javaType, components, reconstructed)) {
                    uniformDiagnostic(name, "reconstructed", "current-frame components=" + componentCount(reconstructed));
                    continue;
                }
                Float dflt = defaultUniform(name, javaType);
                if (dflt == null) {
                    if (javaType.equals("float[]") && components == 16) {
                        Field mf = fieldOf(type, e.getValue());
                        if (mf != null) {
                            try {
                                mf.set(instance, identityMatrix());
                                uniformDiagnostic(name, "fallback", "identity matrix");
                            } catch (Throwable ignored) {
                                // never break the frame here
                            }
                        }
                    } else {
                        uniformDiagnostic(name, "unsupported", javaType + "#" + components);
                    }
                    continue;
                }
                Field field = fieldOf(type, e.getValue());
                if (field == null) continue;
                try {
                    if (javaType.equals("float")) {
                        field.setFloat(instance, dflt);
                        uniformDiagnostic(name, "fallback", "default=" + dflt);
                    } else if (javaType.equals("float[]") && components == 2 && name.equals("pixelSize")) {
                        field.set(instance, new float[]{1.0f / chainW, 1.0f / chainH});
                        uniformDiagnostic(name, "fallback", "chain-resolution");
                    } else {
                        uniformDiagnostic(name, "unsupported", javaType + "#" + components);
                    }
                } catch (Throwable ignored) {
                    // never break the frame here
                }
                continue;
            }
            Field field = fieldOf(type, e.getValue());
            if (field == null) {
                continue;
            }
            // B2: 闃村奖鐭╅樀蹇呴』鏉ヨ嚜鎴戜滑鑷繁閭ｄ竴閬嶉槾褰卞厜鏍呭寲銆侷ris 涓婁紶鐨?
            // shadowModelView/shadowProjection 瀵瑰簲鐨勬槸瀹冮偅涓€閬嶏紙姘歌繙绌虹殑锛?
            // 闃村奖缁樺埗锛屽潗鏍囩┖闂村拰鎴戜滑鐨勬繁搴﹀浘涓嶆槸鍚屼竴涓紝鐢ㄥ畠浼氭暣浣撻敊浣嶃€?
            float[] forcedMatrix = shadowMatrix(name);
            if (forcedMatrix != null && javaType.equals("float[]") && components == 16) {
                try {
                    field.set(instance, forcedMatrix);
                } catch (Throwable ignored) {
                    // never break the frame here
                }
                continue;
            }
            try {
                UniformSnapshot uploaded = new UniformSnapshot("uploaded", value.f, value.i);
                if (!assignSnapshot(instance, e.getValue(), javaType, components, uploaded)) {
                    uniformDiagnostic(name, "unsupported", javaType + "#" + components);
                    continue;
                }
                if (javaType.equals("float")) {
                    // The fullscreen passes run at the CHAIN resolution, not at
                    // the window's: pixelSize/viewWidth must describe the render
                    // target the fragment coordinate lives in. Iris may have
                    // uploaded the window size, which rescales every
                    // screen-space ray and flattens the sky gradient to one hue.
                    float forced = switch (name) {
                        case "pixelSizeX" -> 1.0f / chainW;
                        case "pixelSizeY" -> 1.0f / chainH;
                        case "viewWidth" -> chainW;
                        case "viewHeight" -> chainH;
                        case "aspectRatio" -> (float) chainW / chainH;
                        case "aspectRatioInverse" -> (float) chainH / chainW;
                        case "viewWidthInverse" -> 1.0f / chainW;
                        case "viewHeightInverse" -> 1.0f / chainH;
                        case "shadowDistance" -> dev.antimony.core.render.ShadowMap.distance();
                        case "softLod" -> Math.max(0.0f, (float) (Math.log(Math.max(1.0f, chainH / 128.0f)) / Math.log(2.0)));
                        default -> Float.NaN;
                    };
                    if (!Float.isNaN(forced)) {
                        field.setFloat(instance, forced);
                    }
                }
                if (javaType.equals("float[]") && components == 16) {
                    // A recorded-but-all-zero matrix is never a real projection
                    // or model-view; substituting identity keeps the pack's
                    // ray reconstruction finite instead of collapsing the sky
                    // to a single flat colour.
                    float[] mv = (float[]) field.get(instance);
                    boolean allZero = mv != null;
                    if (mv != null) {
                        for (float f : mv) {
                            if (f != 0.0f) {
                                allZero = false;
                                break;
                            }
                        }
                    }
                    if (allZero) {
                        field.set(instance, new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
                    }
                }
                if (DIAG && frameNo == 10 && javaType.equals("float[]") && components == 16
                    && uniformDumped.add(programId + "#" + name)) {
                    LOG.info("[Antimony] matuniform-dbg p{} {}={}", programId, name, arr(field.get(instance)));
                }
            } catch (Throwable t) {
                // a bad upload must never break the frame
            }
        }
    }

    /**
     * Current chain resolution. The pack's fullscreen passes derive their ray
     * direction from {@code gl_FragCoord.xy * pixelSize}, so these defaults MUST
     * be the real render-target size: with a hardcoded 1920x1080 while the
     * chain runs at 427x240 the product stays below 0.4, ndc.y is always
     * negative and the sky collapses to a single horizon colour.
     */
    private static int chainW = 1920;
    private static int chainH = 1080;
    /** Render distance the software rasterizer is driven with (chunks). */
    private static final float DEFAULT_RENDER_DISTANCE = 12.0f;

    /**
     * B1 (round 14): sane defaults for standard uniforms Iris uploads outside
     * the shim (real values, not placeholders). null = no default known, leave
     * the generated zero.
     */
    /**
     * B4: evaluate one of the pack's {@code uniform.*} expressions through
     * Iris' own stareval tree. Returns null when the pack does not declare that
     * uniform (a plain engine uniform) or when evaluation fails, in which case
     * the recorded value / synthetic default is used as before.
     */
    private record UniformSnapshot(String source, float[] floats, int[] ints) { }

    /** Strict current-frame live terrain binding; never old GL uploads or invented defaults. */
    public static void applyTerrainUniforms(GlslProgramLoader.Loaded loaded, Map<String, float[]> matrices) {
        if (!frameStateReady || preparedPipeline != cachedPipeline)
            throw new IllegalStateException("No prepared current Iris frame");
        for (String name : loaded.meta.usedUniforms) {
            String fieldName = loaded.meta.uniformFields.get(name);
            String signature = loaded.meta.uniformTypes.get(name);
            if (fieldName == null || signature == null)
                throw new IllegalStateException("Invalid live terrain uniform metadata " + name);
            int separator = signature.indexOf('#');
            String javaType = signature.substring(0, separator);
            int components = Integer.parseInt(signature.substring(separator + 1));
            float[] matrix = matrices.get(name);
            UniformSnapshot value = matrix == null ? ownedUniform(name, javaType, components)
                : new UniformSnapshot("cpu-terrain", matrix, null);
            if (value == null) value = packUniform(name);
            if (value == null && name.equals("iris_FogDensity"))
                value = new UniformSnapshot("cpu-fog", new float[]{dev.antimony.core.render.FogFrame.current().density()}, null);
            if(value==null&&name.equals("atlasSize")){
                var atlas=dev.antimony.core.render.IrisGlTextures.blockAtlas();
                if(atlas!=null)value=new UniformSnapshot("cpu-atlas",null,new int[]{atlas.width,atlas.height});
            }
            if (value == null || !assignSnapshot(loaded.program, fieldName, javaType, components, value))
                throw new IllegalStateException("Missing current terrain uniform " + name + " (" + signature + ")");
        }
    }

    public static Map<String,Object> currentUniformValues(GlslProgramLoader.Loaded loaded,Map<String,float[]> overrides){
        applyTerrainUniforms(loaded,overrides);
        Map<String,Object> values=new LinkedHashMap<>();
        for(String name:loaded.meta.usedUniforms)try{
            Object value=loaded.program.getClass().getField(loaded.meta.uniformFields.get(name)).get(loaded.program);
            values.put(name,value instanceof float[] a?a.clone():value instanceof int[] a?a.clone():value instanceof boolean[] a?a.clone():value);
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Current shader uniform snapshot "+name,failure);}
        return Map.copyOf(values);
    }
    static CpuImage cpuTextureImage(dev.antimony.core.irisgl.IrisGlTexture texture){
        if(texture==null)throw new IllegalStateException("Current CPU surface texture unavailable");
        CpuImage image=new CpuImage(texture.width,texture.height,false,texture.wrapRepeat,texture.wrapRepeat,true);
        image.copyRgba8(texture.pixels,false);return image;
    }
    public static boolean blockEntityFrameActive(){return gbufferActive();}
    private static CpuImage currentAtlasImage(){
        var atlas=dev.antimony.core.render.IrisGlTextures.blockAtlas();
        if(atlas==null)throw new IllegalStateException("Current CPU block atlas unavailable");
        CpuImage image=new CpuImage(atlas.width,atlas.height,false,false,false,true);image.copyRgba8(atlas.pixels,false);return image;
    }
    public static Map<String,CpuImage> shadowTextures(Object shader,GlslCompiler.Result meta){
        if(chain==null)throw new IllegalStateException("No current shader resources");
        Object idValue=IrisSamplerBridge.call(shader,"getId");
        if(!(idValue instanceof Integer id))throw new IllegalStateException("Missing shader resource owner ID");
        Map<String,CpuImage> textures=new LinkedHashMap<>();
        for(String name:meta.usedSamplers){
            if(!meta.samplerTypes.get(name).equals("sampler2D"))throw new IllegalStateException("Unexpected shadow sampler "+name);
            CpuImage image;
            if(name.equals("gtexture")||name.equals("tex")){
                var atlas=dev.antimony.core.render.IrisGlTextures.blockAtlas();
                if(atlas==null)throw new IllegalStateException("Current CPU block atlas unavailable");
                image=new CpuImage(atlas.width,atlas.height,false,false,false,true);image.copyRgba8(atlas.pixels,false);
            }else{
                IrisSamplerBridge.Binding binding=IrisSamplerBridge.namedBinding(shader,id,name);
                if(binding==null)throw new IllegalStateException("Missing actual shadow sampler supplier "+name);
                image=resourceImage(chain,binding.texture());
                if(image==null)throw new IllegalStateException("Missing shadow resource pixels "+name+" texture="+binding.texture()
                    +" reason="+chain.resourceSources.get(binding.texture())+" access="
                    +(chain.resourceAccess.get(binding.texture())==null?"missing":chain.resourceAccess.get(binding.texture()).getClass().getName())
                    +" mag/wrapS/wrapT="+IrisSamplerBridge.parameter(binding.texture(),10240)+"/"
                    +IrisSamplerBridge.parameter(binding.texture(),10242)+"/"+IrisSamplerBridge.parameter(binding.texture(),10243));
                if(DIAG&&passDiagLogged.add("shadow-resource#"+id+"#"+name))
                    LOG.info("[Antimony] CPU shadow resource sampler={} texture={} type={} source={} size={}x{}",name,binding.texture(),binding.type(),
                        chain.resourceSources.get(binding.texture()),image.width,image.height);
            }
            textures.put(name,image);
        }
        return Map.copyOf(textures);
    }

    static boolean terrainFrameReady(Object pipeline) {
        return frameStateReady && preparedPipeline == pipeline;
    }

    private static final Set<String> PACK_UNIFORM_MISSES = ConcurrentHashMap.newKeySet();
    private static final Set<String> UNIFORM_DIAGNOSTICS = ConcurrentHashMap.newKeySet();

    private static volatile IrisUniformFrame uniformFrame = IrisUniformFrame.empty();

    private static UniformSnapshot packUniform(String name) {
        IrisUniformFrame.Value value = uniformFrame.get(name);
        return value == null ? null : new UniformSnapshot("evaluated", value.floats(), value.ints());
    }
    private static boolean finite(float[] values) {
        if (values == null) return false;
        for (float value : values) if (!Float.isFinite(value)) return false;
        return true;
    }

    private static void uniformDiagnostic(String name, String status, String detail) {
        if (DIAG && UNIFORM_DIAGNOSTICS.add(status + ":" + name + ":" + detail)) {
            LOG.info("[Antimony] uniform {} {} ({})", status, name, detail);
        }
    }
    private static Field fieldOf(Class<?> type, String fieldName) {
        return FIELDS.get(type).computeIfAbsent(fieldName, k -> {
            try {
                Field f = type.getField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (Throwable t) {
                return null;
            }
        });
    }

    private static int componentCount(UniformSnapshot snapshot) {
        return snapshot == null ? 0 : snapshot.floats != null ? snapshot.floats.length
            : snapshot.ints != null ? snapshot.ints.length : 0;
    }

    private static float[] identityMatrix() {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static boolean assignSnapshot(Object instance, String fieldName, String javaType,
                                          int components, UniformSnapshot snapshot) {
        Field field = fieldOf(instance.getClass(), fieldName);
        if (field == null || snapshot == null) return false;
        try {
            if (javaType.equals("float")) {
                if (snapshot.floats != null && snapshot.floats.length > 0) field.setFloat(instance, snapshot.floats[0]);
                else if (snapshot.ints != null && snapshot.ints.length > 0) field.setFloat(instance, snapshot.ints[0]);
                else return false;
                return true;
            }
            if (javaType.equals("int")) {
                if (snapshot.ints != null && snapshot.ints.length > 0) field.setInt(instance, snapshot.ints[0]);
                else if (snapshot.floats != null && snapshot.floats.length > 0) field.setInt(instance, (int) snapshot.floats[0]);
                else return false;
                return true;
            }
            if (javaType.equals("boolean")) {
                int v = snapshot.ints != null && snapshot.ints.length > 0 ? snapshot.ints[0]
                    : snapshot.floats != null && snapshot.floats.length > 0 ? (int) snapshot.floats[0] : 0;
                field.setBoolean(instance, v != 0);
                return true;
            }
            if (javaType.equals("float[]")) {
                if (snapshot.floats == null || snapshot.floats.length < components) return false;
                float[] out = new float[components];
                System.arraycopy(snapshot.floats, 0, out, 0, components);
                field.set(instance, out);
                return true;
            }
            if (javaType.equals("int[]")) {
                if (snapshot.ints == null || snapshot.ints.length < components) return false;
                int[] out = new int[components];
                System.arraycopy(snapshot.ints, 0, out, 0, components);
                field.set(instance, out);
                return true;
            }
        } catch (Throwable ignored) {
            // A malformed pack uniform must never abort the frame.
        }
        return false;
    }

    private static Object declaredCustomUniform(String name) {
        try {
            Object cu = customUniforms;
            if (cu == null) return null;
            if (CU_DECLARED == null) {
                CU_DECLARED = cu.getClass().getDeclaredField("variables");
                CU_DECLARED.setAccessible(true);
            }
            if (CU_DECLARED.get(cu) instanceof Map<?, ?> declared) return declared.get(name);
        } catch (Throwable ignored) {
            // diagnostics only
        }
        return null;
    }

    private static UniformSnapshot ownedUniform(String name, String javaType, int components) {
        IrisCpuFrameInputs.Snapshot inputs = cpuFrameInputs;
        if (inputs != null) {
            if (name.equals("frameCounter") && javaType.equals("int"))
                return new UniformSnapshot("iris-frame-hook", null, new int[]{inputs.frameCounter()});
            if (name.equals("frameTimeCounter") && javaType.equals("float"))
                return new UniformSnapshot("iris-frame-hook", new float[]{inputs.frameTimeCounter()}, null);
            if (javaType.equals("float[]") && components == 3) {
                if (name.equals("cameraPosition")) return new UniformSnapshot("iris-camera-tracker", inputs.cameraPosition(), null);
                if (name.equals("previousCameraPosition")) return new UniformSnapshot("iris-camera-tracker", inputs.previousCameraPosition(), null);
            }
        }
        var fog = dev.antimony.core.render.FogFrame.current();
        if (fog != null) {
            // Engine fog is owned by the terrain frame, before evaluated pack
            // values or intercepted uploads (both may describe a skipped pass).
            if (javaType.equals("float")) {
                Float scalar = switch (name) {
                    case "iris_FogDensity", "fogDensity" -> fog.rawDensity() == null ? null : fog.density();
                    case "iris_FogStart", "fogStart" -> fog.start();
                    case "iris_FogEnd", "fogEnd" -> fog.end();
                    default -> null;
                };
                if (scalar != null) return new UniformSnapshot("cpu-fog", new float[]{scalar}, null);
            }
            // fogMode/fogShape can be disabled per program (OFF gives
            // 0/-1). Leave those to Iris' real program uniform updater.
            if (javaType.equals("float[]")) {
                if (name.equals("iris_FogColor") && components == 4)
                    return new UniformSnapshot("cpu-fog", new float[]{fog.red(), fog.green(), fog.blue(), fog.alpha()}, null);
                if (name.equals("fogColor") && components == 3)
                    return new UniformSnapshot("cpu-fog", new float[]{fog.red(), fog.green(), fog.blue()}, null);
            }
        }
        if (javaType.equals("float[]") && components == 16) {
            float[] shadow = shadowMatrix(name);
            if (shadow != null) return new UniformSnapshot("cpu-shadow", shadow, null);
        }
        if (name.equals("worldTime")) return packUniform(name);
        if (name.equals("cameraPosition") || name.equals("sunPosition") || name.startsWith("gbuffer"))
            return frameUniform(name, javaType, components);
        if (javaType.equals("float")) {
            float value = switch (name) {
                case "near" -> cpuNear;
                case "far" -> cpuFar;
                case "pixelSizeX", "viewWidthInverse" -> 1.0f / chainW;
                case "pixelSizeY", "viewHeightInverse" -> 1.0f / chainH;
                case "viewWidth" -> chainW;
                case "viewHeight" -> chainH;
                case "aspectRatio" -> (float) chainW / chainH;
                case "aspectRatioInverse" -> (float) chainH / chainW;
                case "shadowDistance" -> dev.antimony.core.render.ShadowMap.distance();
                case "softLod" -> Math.max(0, (float)(Math.log(Math.max(1, chainH / 128.0f)) / Math.log(2)));
                default -> Float.NaN;
            };
            if (Float.isFinite(value)) return new UniformSnapshot("cpu-chain", new float[]{value}, null);
        }
        if (name.equals("pixelSize") && javaType.equals("float[]") && components == 2)
            return new UniformSnapshot("cpu-chain", new float[]{1.0f / chainW, 1.0f / chainH}, null);
        return null;
    }
    private static UniformSnapshot frameUniform(String name, String javaType, int components) {
        try {
            if (name.equals("worldTime")) return packUniform(name);
            if (javaType.equals("float") && name.equals("softLod")) {
                float scale = Math.max(1.0f, chainH / 128.0f);
                return new UniformSnapshot("reconstructed", new float[]{(float) (Math.log(scale) / Math.log(2.0))}, null);
            }
            if (javaType.equals("float[]")) {
                if (name.equals("cameraPosition")) {
                    var p = dev.antimony.core.render.IrisGlRenderer.lastCameraPos();
                    return new UniformSnapshot("reconstructed", new float[]{(float) p.x, (float) p.y, (float) p.z}, null);
                }
                if (name.equals("sunPosition")) {
                    var s = dev.antimony.core.render.SkyPass.SUN_CAMERA;
                    return new UniformSnapshot("reconstructed", new float[]{s.x, s.y, s.z}, null);
                }
                if (components == 16 && (name.equals("gbufferModelView") || name.equals("gbufferModelViewInverse")
                        || name.equals("gbufferProjection") || name.equals("gbufferProjectionInverse"))) {
                    org.joml.Matrix4f m = new org.joml.Matrix4f();
                    if (name.contains("Projection")) {
                        // The projection is authored by the terrain pass and nothing
                        // resets it before the chain runs, so the live context is the
                        // right source.
                        m.set(cpuProjection);
                    } else {
                        // gbufferModelView MUST be rebuilt from the camera rotation,
                        // never taken from ctx().view: HandPass calls viewidentity()
                        // just before WORLD.render returns (HandPass:41), and the chain
                        // runs after that (IrisGlRenderer:153 -> :159), so ctx().view
                        // is the IDENTITY by then. Reading it silently gave every pack
                        // an identity model-view, collapsing the sky ray / shadow
                        // direction onto a fixed axis. WorldRenderer snapshots the real
                        // rotation into lastCameraRotation() at lookat() time (:134),
                        // so that snapshot is still valid here.
                        float[] rebuilt = reconstructModelView(name, components);
                        if (rebuilt == null) {
                            // Genuinely no camera available (title screen, camera not yet
                            // set up). Identity is a last resort and must be reported as
                            // "fallback", never as a reconstruction.
                            return new UniformSnapshot("fallback", identityMatrix(), null);
                        }
                        return new UniformSnapshot("reconstructed", rebuilt, null);
                    }
                    if (name.endsWith("Inverse")) m.invert();
                    float[] out = new float[16];
                    m.get(out);
                    return finite(out) && !allZero(out) ? new UniformSnapshot("reconstructed", out, null) : null;
                }
            }
        } catch (Throwable t) {
            uniformDiagnostic(name, "reconstruction-failed", t.getClass().getSimpleName());
        }
        return null;
    }

    private static boolean allZero(float[] values) {
        if (values == null) return true;
        for (float value : values) if (value != 0.0f) return false;
        return true;
    }

    /**
     * Parsed {@code -Dantimony.diag.timeOfDay}: {@code null} unless diag mode is
     * on and the value is a finite tick in {@code [0, 24000)}.
     *
     * <p>Out-of-range and non-numeric values are ignored with a warning rather
     * than silently clamped, so a typo surfaces instead of quietly turning a
     * comparison run into what looks like a renderer bug.</p>
     */
    private static final Float DIAG_TIME_OF_DAY = parseDiagTimeOfDay();

    private static Float parseDiagTimeOfDay() {
        if (!DIAG) {
            return null;
        }
        String raw = System.getProperty("antimony.diag.timeOfDay");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            float value = Float.parseFloat(raw.trim());
            if (!Float.isFinite(value) || value < 0.0f || value >= 24000.0f) {
                LOG.warn("[Antimony] ignoring -Dantimony.diag.timeOfDay={} (expected 0..23999)", raw);
                return null;
            }
            LOG.info("[Antimony] diag: timeOfDay pinned to {} ticks", value);
            return value;
        } catch (NumberFormatException e) {
            LOG.warn("[Antimony] ignoring -Dantimony.diag.timeOfDay={} (not a number)", raw);
            return null;
        }
    }

    /**
     * Rebuilds {@code gbufferModelView} (and its inverse) from the camera
     * rotation that {@code WorldRenderer} snapshotted while it ran
     * {@code lookat()}.
     *
     * <p>Why not read {@code IrisGl.ctx().view}: every world vertex is submitted
     * camera-relative (the view matrix is rotation-only), so the rotation matrix
     * is exactly what the pack needs - but {@code HandPass} resets the context
     * view to identity before the world pass returns, so by chain time the live
     * matrix is identity rather than the camera. The snapshot is taken at the
     * only moment the real matrix exists.</p>
     *
     * <p>The result is the 4x4 {@code Translation(S) * rotX(pitch) * rotY(yaw)}
     * form: a pure rotation with no translation, since the geometry is already
     * eye-relative (S = 0, the camera sits at the origin of that space). JOML's
     * {@code getLookAt(eye=(0,0,0), center, up)} produces the identical
     * rotation the rasterizer used, so the two never disagree.</p>
     *
     * @return column-major 16 floats, or {@code null} when no camera rotation
     *         has been captured yet (the caller then falls back to identity and
     *         labels it as such).
     */
    private static float[] reconstructModelView(String name, int components) {
        try {
            org.joml.Matrix4f rotation =
                    new org.joml.Matrix4f(dev.antimony.core.render.IrisGlRenderer.lastCameraRotation());
            // A rotation with no translation: the camera is the origin of the
            // camera-relative space the rasterizer submits in.
            rotation.m30(0.0f).m31(0.0f).m32(0.0f);
            // Guard against an uninitialised snapshot (all zeros) - that is a
            // "no camera yet" signal, not a rotation.
            if (allZero(new float[]{rotation.m00(), rotation.m11(), rotation.m22()})) {
                return null;
            }
            if (name.endsWith("Inverse")) {
                rotation.invert();
            }
            float[] out = new float[16];
            rotation.get(out);
            if (!finite(out) || allZero(out)) {
                return null;
            }
            return out;
        } catch (Throwable t) {
            uniformDiagnostic(name, "reconstruction-failed", t.getClass().getSimpleName());
            return null;
        }
    }

    private static Float defaultUniform(String name, String javaType) {
        if (!javaType.equals("float")) {
            return null;
        }
        return switch (name) {
            // ld() divides by far + near - d*(far-near): with far=0 that is 0/0.
            case "near" -> 0.05f;
            case "far" -> currentRenderFar();
            case "frameTime" -> 0.05f;
            case "frameTimeCounter" -> (System.nanoTime() % 3_600_000_000_000L) / 1.0e9f;
            case "viewWidth" -> (float) chainW;
            case "viewHeight" -> (float) chainH;
            case "aspectRatio" -> (float) chainW / chainH;
            case "aspectRatioInverse" -> (float) chainH / chainW;
            case "viewWidthInverse" -> 1.0f / chainW;
            case "viewHeightInverse" -> 1.0f / chainH;
            case "pixelSizeX" -> 1.0f / chainW;
            case "pixelSizeY" -> 1.0f / chainH;
            case "eyeBrightnessHalflife" -> 3.0f;
            case "centerDepthHalflife" -> 0.66f;
            case "sunPathRotation" -> -25.0f;
            case "shadowDistance" -> dev.antimony.core.render.ShadowMap.distance();
            case "blindness", "darknessFactor", "rainStrength", "wetness", "nightVision",
                 "screenBrightness", "playerMood", "constantMood" -> 0.0f;
            default -> null;
        };
    }

    private static float currentRenderFar() {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            return Math.max(2.0f, Math.min(32.0f, mc.options.getEffectiveRenderDistance())) * 16.0f * 4.0f;
        } catch (Throwable ignored) {
            return DEFAULT_RENDER_DISTANCE * 16.0f * 4.0f;
        }
    }

    private static float[] arrayOf(GlShimState.UniformValue value, int components) {
        float[] out = new float[components];
        int n = Math.min(components, Math.max(value.f.length, value.i.length));
        for (int i = 0; i < n; i++) {
            out[i] = value.f.length > 0 ? value.f[i] : value.i[i];
        }
        return out;
    }

    private static int[] intArrayOf(GlShimState.UniformValue value, int components) {
        int[] out = new int[components];
        int n = Math.min(components, value.i.length);
        for (int i = 0; i < n; i++) {
            out[i] = value.i[i];
        }
        return out;
    }

    /** Compact float[]/float rendering for diagnostics. */
    private static String arr(Object v) {
        if (v instanceof float[] a) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < Math.min(a.length, 16); i++) {
                sb.append(i > 0 ? "," : "").append(String.format(java.util.Locale.ROOT, "%.3f", a[i]));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Float f) {
            return String.format(java.util.Locale.ROOT, "%.3f", f);
        }
        return String.valueOf(v);
    }

    /** One-shot diag dedup for uniform dumps. */
    private static final Set<String> uniformDumped = new HashSet<>();

    private static int locationOf(int programId, String name) {
        // Locations must come from the SAME registry the shim handed out when
        // Iris queried them (GlShim.glGetUniformLocation -> uniformLocation).
        // Asking the real driver returns -1 for our fake program ids, so every
        // uniform lookup missed and generated fields kept their null defaults
        // (Post18 NPE: gbufferProjectionInverse).
        return GlShimState.uniformLocation(programId, name);
    }


    /** Fill non-texcoord varyings (exposure comes from the pack's prepare pass). */
    private static void applyVaryingDefaults(GlslProgramLoader.Loaded loaded, GlslProgram instance) {
        for (String name : loaded.meta.varyingIns) {
            if (name.equals("texcoord") || name.equals("texCoord")) {
                continue;
            }
            try {
                Field field = instance.getClass().getField(name);
                Class<?> fieldType = field.getType();
                if (fieldType == float.class) {
                    field.setFloat(instance, name.contains("exposure") ? EXPOSURE : 0.0f);
                } else if (fieldType == int.class) {
                    field.setInt(instance, 0);
                } else if (fieldType == float[].class) {
                    // Matrix/varying vectors the vertex stage would have
                    // produced (modeli_times_projectioni, upVector, ...):
                    // identity for 4x4 so transform math stays finite, zeros
                    // otherwise.
                    float[] v = (float[]) field.get(instance);
                    if (v == null) {
                        v = new float[name.startsWith("modeli") || name.endsWith("times_projectioni") ? 16 : 3];
                        field.set(instance, v);
                    }
                    if (v.length == 16) {
                        v[0] = v[5] = v[10] = v[15] = 1.0f;
                    }
                }
            } catch (Throwable ignored) {
                // varying not a plain field: leave as is
            }
        }
    }

    /** The generated program's {@code public final float[][] out} field. */
    private static final Map<Class<?>, Field> OUT_FIELDS = new ConcurrentHashMap<>();

    private static float[][] outputs(GlslProgram program) {
        Field field = OUT_FIELDS.computeIfAbsent(program.getClass(), c -> {
            try {
                return c.getField("out");
            } catch (Throwable t) {
                return null;
            }
        });
        if (field == null) {
            return new float[0][];
        }
        try {
            return (float[][]) field.get(program);
        } catch (Throwable t) {
            return new float[0][];
        }
    }

    private static CpuImage imageFor(Map<Integer, CpuImage> images, int texture, int width, int height) {
        if (texture == 0) {
            return null;
        }
        CpuImage existing = images.get(texture);
        if (existing != null && existing.matches(width, height)) {
            return existing;
        }
        CpuImage created = new CpuImage(width, height);
        images.put(texture, created);
        return created;
    }

    /**
     * Copy the software rasterizer's depth buffer into a linear-light image
     * (R=G=B=depth, A=1), downscaled by nearest sampling to the chain
     * resolution. Iris depth textures store 0 at the near plane, 1 far; the
     * CPU framebuffer keeps the same convention.
     */
    private static void seedDepthTexture(Map<Integer, CpuImage> images, Set<Integer> content,
            int texture, float[] source, DepthFrame.Snapshot snapshot, int width, int height) {
        if (texture <= 0) return;
        CpuImage depth = imageFor(images, texture, width, height);
        seedDepth(depth, source, snapshot.width(), snapshot.height());
        content.add(texture);
        markWritten(texture);
    }

    private static void seedDepth(CpuImage depth, float[] source, int width, int height) {
        for (int y = 0; y < depth.height; y++) {
            int sy = Math.min(height - 1, (int) ((y + 0.5f) * height / depth.height));
            int row = y * depth.width;
            for (int x = 0; x < depth.width; x++) {
                int sx = Math.min(width - 1, (int) ((x + 0.5f) * width / depth.width));
                float d = source[sy * width + sx];
                int o = (row + x) * 4;
                depth.rgba[o] = d;
                depth.rgba[o + 1] = d;
                depth.rgba[o + 2] = d;
                depth.rgba[o + 3] = 1.0f;
            }
        }
    }

    /**
     * Legacy seeded-scene mask only. With real gbuffer scheduling, prepare runs
     * before terrain and Iris' fullscreen quad disables depth testing. Masking
     * it with last frame's depth preserves stale transparent pixels, feeding
     * their reflection/bloom back into the next frame indefinitely.
     */
    private static float[] buildSkyMask(Chain c, Map<Integer, CpuImage> images, int width, int height) {
        if (gbufferFrame) return null;
        if (c.depthTexture == 0) {
            return null;
        }
        CpuImage depth = images.get(c.depthTexture);
        if (depth == null) {
            return null;
        }
        float[] mask = new float[width * height];
        float stepX = depth.width / (float) width;
        float stepY = depth.height / (float) height;
        for (int y = 0; y < height; y++) {
            int dy = Math.min(depth.height - 1, (int) ((y + 0.5f) * stepY));
            int row = y * width;
            int drow = dy * depth.width;
            for (int x = 0; x < width; x++) {
                int dx = Math.min(depth.width - 1, (int) ((x + 0.5f) * stepX));
                mask[row + x] = depth.rgba[(drow + dx) * 4] >= 0.9999f ? 1.0f : 0.0f;
            }
        }
        return mask;
    }

    /**
     * B1: set the complete exposure persistence plane to neutral red 1.0
     * (neutral exposure) ONCE per texture: afterwards the pack's own writes
     * (composite stores exposure into colortex6 = iris_FragData2) keep the
     * persistence chain real across frames. Seeding every frame would break
     * that persistence by overwriting the stored value with 1.0 again.
     */
    private static void seedExposurePixel(Map<Integer, CpuImage> images, int texture, int width, int height) {
        if (texture == 0 || !EXPOSURE_SEEDED.add(texture)) {
            return;
        }
        CpuImage image = imageFor(images, texture, width, height);
        if (image == null) {
            return;
        }
        // A centre sample uses four texels at even resolutions and textureLod may average more.
        // Initialize the complete persistence plane once, then let pack writes own subsequent frames.
        image.clear(1.0f, 0.0f, 0.0f, 1.0f);
    }

    /** Textures whose exposure pixel got the neutral seed (reset on pipeline swap). */
    private static final Set<Integer> EXPOSURE_SEEDED = new HashSet<>();
}
