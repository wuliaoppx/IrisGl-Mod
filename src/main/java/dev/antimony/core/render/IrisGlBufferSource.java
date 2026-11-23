package dev.antimony.core.render;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlContext;
import dev.antimony.core.irisgl.IrisGlTexture;
import it.unimi.dsi.fastutil.objects.Object2ObjectSortedMaps;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * MultiBufferSource that replays every RenderType batch through IRIS GL.
 */
public final class IrisGlBufferSource extends MultiBufferSource.BufferSource {
    /**
     * One instance reused by every pass. The base class allocates a 768 KB
     * direct ByteBuffer in its constructor; constructing a fresh source per
     * frame (~3.8 MB/frame across five call sites) exhausts direct memory in
     * ~4.5 minutes (observed OutOfMemoryError at frame ~7300), so every pass
     * shares this instance and resets it per frame instead.
     */
    private static IrisGlBufferSource shared;

    public static synchronized IrisGlBufferSource shared() {
        if (shared == null) {
            shared = new IrisGlBufferSource(null, false);
        }
        return shared;
    }

    private final Map<RenderType, MeshBuffer> buffers = new LinkedHashMap<>();
    private IrisGlTexture lightmap;
    private boolean depth;
    private boolean inBatch;
    private int drawnQuads;
    private Integer blockEntityMaterial;
    private IrisGlTexture blockOverlay;

    public void beginBlockEntity(int material) {
        if (blockEntityMaterial != null || !buffers.isEmpty()) throw new IllegalStateException("Overlapping block-entity batch");
        blockEntityMaterial = material;
        if (dev.antimony.gl.glsl.IrisPostChain.blockEntityFrameActive()) blockOverlay = BlockEntityShaderInputs.overlayTexture();
    }
    public void endBlockEntity() {
        try { endBatch(); }
        finally { blockEntityMaterial = null; blockOverlay = null; dev.antimony.gl.glsl.IrisTerrainVertex.endScope(); }
    }

    public IrisGlBufferSource(IrisGlTexture lightmap, boolean depth) {
        super(new ByteBufferBuilder(786432), Object2ObjectSortedMaps.emptyMap());
        this.lightmap = lightmap;
        this.depth = depth;
    }

    /** Buffers drained by endBatch are recycled instead of rebuilt per frame. */
    private final java.util.ArrayDeque<MeshBuffer> POOL = new java.util.ArrayDeque<>();

    /** Resets the shared instance for a new pass; stale geometry is dropped. */
    public void startFrame(IrisGlTexture lightmap, boolean depth) {
        for (MeshBuffer buffer : this.buffers.values()) {
            buffer.clear();
            this.POOL.add(buffer);
        }
        this.buffers.clear();
        this.lightmap = lightmap;
        this.depth = depth;
        this.drawnQuads = 0;
        this.blockEntityMaterial = null;
        this.blockOverlay = null;
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return this.buffers.computeIfAbsent(type, t -> {
            MeshBuffer buffer = this.POOL.poll();
            if (buffer == null) {
                buffer = new MeshBuffer();
            } else {
                buffer.clear();
            }
            // Keep vanilla's primitive mode: QUADS for terrain/entities/GUI,
            // LINES for the block outline and every debug overlay. Forcing
            // everything through quads mangled line batches (F3+B/F3+G).
            buffer.mode(t.mode());
            if (blockEntityMaterial != null && dev.antimony.gl.glsl.IrisPostChain.blockEntityFrameActive()
                    && BlockEntityShaderInputs.shaderKey(t) != null) buffer.terrainMaterial(blockEntityMaterial);
            return buffer;
        });
    }

    @Override
    public void endBatch() {
        if (this.inBatch) {
            return;
        }
        this.inBatch = true;
        try {
            for (RenderType type : this.buffers.keySet().toArray(new RenderType[0])) {
                this.endBatch(type);
            }
        } finally {
            this.inBatch = false;
        }
    }

    @Override
    public void endBatch(RenderType type) {
        MeshBuffer buffer = this.buffers.remove(type);
        if (buffer == null) {
            return;
        }
        buffer.finish();
        if (buffer.isEmpty()) {
            return;
        }
        StateProbe probe = probeState(type);
        IrisGl.zbuffer(probe.depthTest && this.depth);
        // Vanilla's shaders only discard alpha below 0.1 in the entity/item
        // shader family (NEW_ENTITY format) and keep a coarse discard in the
        // terrain/cutout family. GUI shaders (rendertype_gui etc.) have no
        // discard at all - they multiply into the framebuffer - so firing the
        // alpha test there paints translucent texels opaque black (the
        // inventory black-squares bug). Key the test on format/render-type
        // semantics instead of the depth flag.
        boolean entityFormat = type.format() == DefaultVertexFormat.NEW_ENTITY;
        boolean terrainFormat = type.format() == DefaultVertexFormat.BLOCK
                || type.toString().contains("cutout");
        IrisGl.alphatest(entityFormat || (terrainFormat && this.depth), 0.1F);
        if (probe.blend) {
            // Use the exact factor pair vanilla installed through
            // GlStateManager (the vignette needs ZERO/ONE_MINUS_SRC_COLOR to
            // multiply, a plain alpha blend painted it as a black sheet).
            if (probe.blendAlphaSrc != 0 || probe.blendAlphaDst != 0) {
                IrisGl.blendfuncseparate(probe.blendSrc, probe.blendDst,
                        probe.blendAlphaSrc, probe.blendAlphaDst);
            } else {
                IrisGl.blendfunction(probe.blendSrc, probe.blendDst);
            }
        } else {
            IrisGl.ctx().blend = false;
        }
        // Opaque batches write depth like vanilla's GPU pipeline; translucent
        // batches must not (without this entities sort incorrectly against
        // each other and against terrain).
        IrisGl.ctx().depthWrite = this.depth && probe.depthWrite;
        this.drawnQuads += buffer.quadCount();
        // Vanilla shades only NEW_ENTITY-format batches (entities, item
        // icons) with the two directional lights; terrain and GUI quads carry
        // pre-baked brightness.
        var context = IrisGl.ctx();
        boolean previousColorWrite = context.colorWrite, previousFarDepth = context.forceFarDepth;
        boolean farDepth = !probe.colorWrite && probe.depthWrite && XaeroCpuCompatibility.isDepthClear(type);
        context.colorWrite = probe.colorWrite;
        context.forceFarDepth = farDepth;
        if (farDepth) context.depthTest = false; // Writing 1 is equivalent to GREATER against every existing depth < 1.
        String blockKey = blockEntityMaterial == null ? null : BlockEntityShaderInputs.shaderKey(type);
        boolean packBlock = blockKey != null && dev.antimony.gl.glsl.IrisPostChain.blockEntityFrameActive();
        try {
            IrisGlTexture surface = textureFor(type);
            if (packBlock) {
                dev.antimony.gl.glsl.IrisTerrainVertex.beginBlockEntity(blockKey, blockEntityMaterial, surface, blockOverlay, context);
                IrisGl.alphatest(false, 0); // Captured pack FS owns its exact alpha test.
            }
            buffer.draw(surface, this.lightmap, probe.cull, entityFormat && !packBlock);
        } finally {
            if (packBlock) dev.antimony.gl.glsl.IrisTerrainVertex.endScope();
            context.colorWrite = previousColorWrite;
            context.forceFarDepth = previousFarDepth;
        }
        if (DEBUG_STATE) {
            logState(type, probe, entityFormat);
        }
        IrisGl.ctx().blend = false;
        IrisGl.ctx().depthWrite = true;
        IrisGl.alphatest(false, 0.0F);
    }

    private static final boolean DEBUG_STATE = dev.antimony.AntimonyMod.flag("antimony.debug.state");
    private static final java.util.Set<String> LOGGED = new java.util.HashSet<>();

    private static void logState(RenderType type, StateProbe probe, boolean entityFormat) {
        String key = type.toString() + "|" + probe.cull + "|" + probe.blend + "|" + entityFormat;
        if (LOGGED.size() > 200 || !LOGGED.add(key)) {
            return;
        }
        dev.antimony.AntimonyMod.LOG.info("[Antimony] batch {} format={} cull={} blend={} depthTest={} depthWrite={}",
                type, type.format(), probe.cull, probe.blend, probe.depthTest, probe.depthWrite);
    }

    /**
     * Replays a RenderType's GL state shards once and snapshots the parts our
     * rasterizer honours: depth test/write, blending and back-face culling.
     * The enable bits are read from the GlStateManager mirrors, exactly the
     * values vanilla's GPU pipeline would use at draw time.
     */
    private static StateProbe probeState(RenderType type) {
        StateProbe probe = new StateProbe();
        try {
            IrisGlTextures.rememberShaderTexture(0, null);
            GlStateTracker.reset();
            type.setupRenderState();
            probe.cull = GlStateTracker.cull();
            probe.depthTest = GlStateTracker.depthTest();
            probe.depthWrite = GlStateTracker.depthWrite();
            probe.colorWrite = GlStateTracker.colorWrite();
            probe.blend = GlStateTracker.blend();
            probe.blendSrc = GlStateTracker.blendSrc();
            probe.blendDst = GlStateTracker.blendDst();
            probe.blendAlphaSrc = GlStateTracker.blendAlphaSrc();
            probe.blendAlphaDst = GlStateTracker.blendAlphaDst();
            type.clearRenderState();
        } catch (Throwable t) {
            // A shard that throws (modded RenderType, missing atlas) must NOT
            // fall back to blend=false: GUI overlays like the inventory's
            // fullscreen darkening gradient would paint opaque near-black
            // instead of alpha-blended. RenderType.gui() and guiOverlay() are
            // both blended, so keep blending with the vanilla alpha pair.
            probe.cull = !type.toString().contains("no_cull");
            probe.blend = true;
            probe.blendSrc = dev.antimony.core.irisgl.IrisGlContext.BF_SRC_ALPHA;
            probe.blendDst = dev.antimony.core.irisgl.IrisGlContext.BF_ONE_MINUS_SRC_ALPHA;
            if (FAILED_SHARDS.add(type.toString())) {
                // log once per render type
                dev.antimony.AntimonyMod.LOG.warn("[Antimony] render state probe failed for {}, using alpha blend", type, t);
            }
        }
        GlStateTracker.reset();
        return probe;
    }

    private static final java.util.Set<String> FAILED_SHARDS = new java.util.HashSet<>();

    private static final class StateProbe {
        boolean depthTest = true;
        boolean depthWrite = true;
        boolean colorWrite = true;
        boolean blend;
        boolean cull = true;
        int blendSrc = dev.antimony.core.irisgl.IrisGlContext.BF_SRC_ALPHA;
        int blendDst = dev.antimony.core.irisgl.IrisGlContext.BF_ONE_MINUS_SRC_ALPHA;
        int blendAlphaSrc;
        int blendAlphaDst;
    }

    public int drawnQuads() {
        return this.drawnQuads;
    }

    private static IrisGlTexture textureFor(RenderType type) {
        try {
            IrisGlTextures.rememberShaderTexture(0, null);
            type.setupRenderState();
            ResourceLocation location = IrisGlTextures.lastShaderTexture(0);
            type.clearRenderState();
            if (location == null) {
                return null;
            }
            return IrisGlTextures.texture(location);
        } catch (Throwable t) {
            return null;
        }
    }
}
