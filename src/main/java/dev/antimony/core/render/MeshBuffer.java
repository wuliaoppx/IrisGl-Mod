package dev.antimony.core.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlTexture;

/**
 * Accumulates Minecraft vertices (position, uv0, colour, lightmap uv, normal)
 * and replays them through the IRIS GL immediate-mode API.
 *
 * <p>Vanilla emits several primitive modes through the same {@link VertexConsumer}
 * interface: QUADS for terrain, entities, particles and GUI; LINES for the
 * block outline and every debug overlay (F3+B hitboxes, F3+G chunk borders,
 * structure bounds); TRIANGLES for a few special renderers. The buffer keeps
 * the mode of the RenderType it belongs to so each batch is replayed with the
 * matching IRIS GL primitive instead of being forced through the quad path
 * (lines used to be silently mangled into quads, which is why the debug
 * overlays looked broken).</p>
 *
 * <p>Normals are forwarded through {@code n3f}: the rasterizer shades
 * geometry carrying normals with vanilla's two directional lights
 * ({@code minecraft_mix_light}), while terrain quads - whose shading is
 * pre-baked into the vertex colour by the mesher - simply never set one.</p>
 */
public final class MeshBuffer implements VertexConsumer {
    /** Per-vertex float count: x,y,z,u,v,r,g,b,a,lu,lv,nx,ny,nz,overlay. */
    public static final int STRIDE = 15;
    /** Backing arrays start small: most GUI batches stay under 16 vertices, and a grown buffer is recycled by the pool, not reallocated per frame. */
    private static final int INITIAL_FLOATS = 16 * STRIDE;
    private float[] data = new float[INITIAL_FLOATS];
    private int floats;
    private int vertices;
    private VertexFormat.Mode mode = VertexFormat.Mode.QUADS;

    private boolean active;
    private float px;
    private float py;
    private float pz;
    private float u;
    private float v;
    private float r = 1.0F;
    private float g = 1.0F;
    private float b = 1.0F;
    private float a = 1.0F;
    private float lu;
    private float lv;
    private float nx;
    private float ny;
    private float nz;
    /** Vanilla UV1 overlay, packed (v << 4) | u; 0 = none. */
    private float overlayCode;
    private boolean hasOverlay;

    // Opt-in terrain sidecar: no change to STRIDE or GUI/entity allocations.
    private boolean terrainAttributes;
    private int terrainMaterial = -1;
    private int[] terrainMaterials;
    private float[] terrainMidUvs;
    private float[] terrainTangents;
    private boolean[] terrainTangentPresent;
    private int terrainBlockContext = -1;
    private TerrainVoxelAttributes.Stage terrainStage;
    private int[] terrainBlocks;
    private byte[] terrainStages;
    private int solasVoxelInputsValid = -1;

    /** Capture the real block being tessellated; the previous pending vertex keeps its preceding block context. */
    public void terrainBlock(int materialId,int x,int y,int z,TerrainVoxelAttributes.Stage stage) {
        int packed=TerrainVoxelAttributes.packBlock(x,y,z);
        java.util.Objects.requireNonNull(stage);
        this.terrainMaterial(materialId);
        if(this.mode!=VertexFormat.Mode.QUADS||(this.vertices&3)!=0)throw new IllegalStateException("Terrain block context requires complete source quads");
        this.terrainBlockContext=packed;this.terrainStage=stage;this.solasVoxelInputsValid=-1;
    }
    /** Enable before the first vertex; material changes finish the preceding pending vertex. */
    public void terrainMaterial(int materialId) {
        this.flushCurrent();
        if (!this.terrainAttributes && this.vertices != 0)
            throw new IllegalStateException("Terrain attributes must be enabled before vertices");
        this.solasVoxelInputsValid = -1;
        this.terrainAttributes = true;
        this.terrainMaterial = materialId;
        this.terrainBlockContext=-1;this.terrainStage=null;
    }

    public boolean hasTerrainAttributes() { return this.terrainAttributes; }
    public int terrainMaterialId(int vertex) { return this.terrainMaterials[vertex]; }
    public float terrainMidU(int vertex) { return this.terrainMidUvs[vertex * 2]; }
    public float terrainMidV(int vertex) { return this.terrainMidUvs[vertex * 2 + 1]; }

    public TerrainVoxelAttributes.Vertex terrainVoxelVertex(int vertex) {
        if(vertex<0||vertex>=this.vertices)throw new IndexOutOfBoundsException(vertex);
        if(!this.terrainAttributes||this.terrainBlocks==null||this.terrainBlocks[vertex]<0)
            throw new IllegalStateException("Original block context unavailable");
        return TerrainVoxelAttributes.vertex(vertex,this.terrainBlocks[vertex],TerrainVoxelAttributes.Stage.values()[this.terrainStages[vertex]]);
    }

    public boolean hasSolasVoxelInputs() {
        this.finish();
        if (this.vertices == 0) return true;
        if (this.solasVoxelInputsValid >= 0) return this.solasVoxelInputsValid == 1;
        if (this.mode != VertexFormat.Mode.QUADS || (this.vertices & 3) != 0
                || !this.terrainAttributes || this.terrainBlocks == null || this.terrainStages == null
                || this.terrainMaterials == null) {
            this.solasVoxelInputsValid = 0;
            return false;
        }
        for (int i = 0; i < this.vertices; i += 4) {
            int block = this.terrainBlocks[i], stage = this.terrainStages[i], material = this.terrainMaterials[i];
            if (block < 0 || stage < 0 || stage >= TerrainVoxelAttributes.Stage.OTHER.ordinal()) {
                this.solasVoxelInputsValid = 0;
                return false;
            }
            for (int q = 1; q < 4; q++)
                if (this.terrainBlocks[i + q] != block || this.terrainStages[i + q] != stage
                        || this.terrainMaterials[i + q] != material) {
                    this.solasVoxelInputsValid = 0;
                    return false;
                }
        }
        this.solasVoxelInputsValid = 1;
        return true;
    }

    private void storeTerrainAttributes() {
        if (!this.terrainAttributes) return;
        int size = this.data.length / STRIDE;
        if (this.terrainMaterials == null || this.terrainMaterials.length < size) {
            this.terrainMaterials = this.terrainMaterials == null ? new int[size]
                    : java.util.Arrays.copyOf(this.terrainMaterials, size);
            this.terrainMidUvs = this.terrainMidUvs == null ? new float[size * 2]
                    : java.util.Arrays.copyOf(this.terrainMidUvs, size * 2);
        }
        if (this.terrainTangents == null || this.terrainTangents.length < size * 4) {
            this.terrainTangents = this.terrainTangents == null ? new float[size * 4] : java.util.Arrays.copyOf(this.terrainTangents, size * 4);
            this.terrainTangentPresent = this.terrainTangentPresent == null ? new boolean[size] : java.util.Arrays.copyOf(this.terrainTangentPresent, size);
        }
        if((this.terrainBlockContext>=0||this.terrainBlocks!=null)&&(this.terrainBlocks==null||this.terrainBlocks.length<size)){
            int previous=this.terrainBlocks==null?0:this.terrainBlocks.length;
            this.terrainBlocks=this.terrainBlocks==null?new int[size]:java.util.Arrays.copyOf(this.terrainBlocks,size);
            java.util.Arrays.fill(this.terrainBlocks,previous,size,-1);
            this.terrainStages=this.terrainStages==null?new byte[size]:java.util.Arrays.copyOf(this.terrainStages,size);
        }
        if(this.terrainBlocks!=null){this.terrainBlocks[this.vertices]=this.terrainBlockContext;this.terrainStages[this.vertices]=(byte)(this.terrainStage==null?-1:this.terrainStage.ordinal());}
        this.terrainTangentPresent[this.vertices] = false;
        this.terrainMaterials[this.vertices] = this.terrainMaterial;
        // The four source UVs, before the rasterizer fans the quad into triangles.
        if (this.mode == VertexFormat.Mode.QUADS && (this.vertices & 3) == 3) {
            int first = this.vertices - 3;
            float midU = 0, midV = 0;
            for (int i = first; i <= this.vertices; i++) {
                midU += this.data[i * STRIDE + 3];
                midV += this.data[i * STRIDE + 4];
            }
            boolean tangentReady = IrisTerrainTangents.fill(this.data, first, this.terrainTangents);
            for (int i = first; i <= this.vertices; i++) {
                this.terrainTangentPresent[i] = tangentReady;
                this.terrainMidUvs[i * 2] = midU * 0.25F;
                this.terrainMidUvs[i * 2 + 1] = midV * 0.25F;
            }
        }
    }

    /** Selects how the accumulated vertices are replayed (QUADS by default). */
    public void mode(VertexFormat.Mode mode) {
        this.mode = mode == null ? VertexFormat.Mode.QUADS : mode;
        this.solasVoxelInputsValid = -1;
    }

    public VertexFormat.Mode mode() {
        return this.mode;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        this.flushCurrent();
        this.px = x;
        this.py = y;
        this.pz = z;
        this.active = true;
        // A vertex that never calls setUv1 must read overlay 0, exactly like an
        // unwritten attribute in vanilla's NEW_ENTITY buffer. Without this reset
        // a chest/block-entity vertex following a hurt mob would inherit the
        // mob's red overlay code and get tinted too.
        this.overlayCode = 0.0F;
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        float[] inv = dev.antimony.core.irisgl.Inv255.INV_255;
        this.r = inv[red & 0xFF];
        this.g = inv[green & 0xFF];
        this.b = inv[blue & 0xFF];
        this.a = inv[alpha & 0xFF];
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.u = u;
        this.v = v;
        return this;
    }

    /**
     * Vanilla's entity shader reads UV1 as a texel coordinate into the 16x16
     * overlay texture: row 3 is the red damage flash, row 10 the white flash
     * (row 10 / column 0 = fully transparent, i.e. {@code NO_OVERLAY}).
     * Ignoring this call (the previous behaviour) silently dropped the red
     * "entity was hit" tint completely.
     */
    @Override
    public VertexConsumer setUv1(int u, int v) {
        int ui = u & 0xF;
        int vi = v & 0xF;
        int code = (vi << 4) | ui;
        this.overlayCode = code;
        // 0xA0 = white overlay at its most transparent column: a no-op.
        if (code != 0xA0) {
            this.hasOverlay = true;
        }
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        this.lu = u;
        this.lv = v;
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        this.nx = x;
        this.ny = y;
        this.nz = z;
        return this;
    }

    private void flushCurrent() {
        if (!this.active) {
            return;
        }
        this.active = false;
        this.solasVoxelInputsValid = -1;
        int need = this.floats + STRIDE;
        if (need > this.data.length) {
            float[] bigger = new float[Math.max(need, this.data.length * 2)];
            System.arraycopy(this.data, 0, bigger, 0, this.floats);
            this.data = bigger;
        }
        int o = this.floats;
        this.data[o] = this.px;
        this.data[o + 1] = this.py;
        this.data[o + 2] = this.pz;
        this.data[o + 3] = this.u;
        this.data[o + 4] = this.v;
        this.data[o + 5] = this.r;
        this.data[o + 6] = this.g;
        this.data[o + 7] = this.b;
        this.data[o + 8] = this.a;
        this.data[o + 9] = this.lu;
        this.data[o + 10] = this.lv;
        this.data[o + 11] = this.nx;
        this.data[o + 12] = this.ny;
        this.data[o + 13] = this.nz;
        this.data[o + 14] = this.overlayCode;
        this.floats = need;
        this.storeTerrainAttributes();
        this.vertices++;
    }

    public void finish() {
        this.flushCurrent();
    }

    /** Number of complete primitives (quads, triangles or line segments). */
    public int quadCount() {
        return switch (this.mode) {
            case LINE_STRIP, DEBUG_LINE_STRIP -> Math.max(0, this.vertices - 1);
            case TRIANGLE_STRIP -> Math.max(0, this.vertices - 2);
            case TRIANGLE_FAN -> Math.max(0, this.vertices - 2);
            default -> this.vertices / Math.max(1, this.mode.primitiveLength);
        };
    }

    public int vertexCount() {
        return this.vertices;
    }

    /**
     * B2：阴影贴图直接读原始顶点数组光栅化，不走 IRIS GL 的立即模式。
     * 顶点布局见 {@link #STRIDE}，坐标是<b>区段局部</b>坐标，阴影端自行加上
     * {@code sectionOrigin - eye} 的平移。
     */
    public float[] rawData() {
        return this.data;
    }

    public int rawVertices() {
        return this.vertices;
    }

    public boolean isEmpty() {
        return this.quadCount() == 0;
    }

    public void clear() {
        this.floats = 0;
        this.vertices = 0;
        this.active = false;
        this.hasOverlay = false;
        this.terrainAttributes = false;
        this.terrainMaterial = -1;
        this.terrainBlockContext=-1;this.terrainStage=null;
        this.solasVoxelInputsValid = -1;
    }

    /**
     * Releases oversized backing arrays (pool returns a buffer to service).
     * 1806 loaded sections used to retain three 240 KB arrays each (~333 MB)
     * even when every layer was empty; called after each worker build so an
     * idle section costs nothing.
     */
    public void trimToSize() {
        this.terrainMaterials = null;
        this.terrainMidUvs = null;
        this.terrainTangents = null;
        this.terrainTangentPresent = null;
        this.terrainBlocks=null;this.terrainStages=null;this.solasVoxelInputsValid=-1;
        if (this.data.length > INITIAL_FLOATS) {
            this.data = new float[INITIAL_FLOATS];
        }
    }

    public void draw(IrisGlTexture atlas, IrisGlTexture lightmap) {
        this.draw(atlas, lightmap, false, false);
    }

    public void draw(IrisGlTexture atlas, IrisGlTexture lightmap, boolean cull) {
        this.draw(atlas, lightmap, cull, false);
    }

    /**
     * @param cull        mirrors vanilla's GL cull-face for this batch:
     *                   triangles whose window-space winding is clockwise
     *                   (negative area in this flipped-Y rasterizer) are
     *                   dropped, matching the GPU pipeline's treatment of back
     *                   faces.
     * @param shadeLights vanilla shades only NEW_ENTITY-format geometry with
     *                   the two directional lights (entities, item icons);
     *                   terrain brightness is pre-baked into the vertex colour
     *                   and its normals must be ignored.
     */
    public void draw(IrisGlTexture atlas, IrisGlTexture lightmap, boolean cull, boolean shadeLights) {
        this.finish();
        if (this.vertices == 0) {
            return;
        }
        IrisGl.texture(atlas != null);
        IrisGl.texbind(atlas);
        IrisGl.lightmap(lightmap);
        IrisGl.cullface(cull);
        IrisGl.ctx().lightShade = shadeLights;
        IrisGl.overlayEnable(this.hasOverlay);
        int n = this.vertices;
        IrisGl.ctx().clearTerrainVertexAttributes();
        try {
        switch (this.mode) {
            case LINES, DEBUG_LINES -> {
                for (int i = 0; i + 1 < n; i += 2) {
                    emitLine(i, i + 1);
                }
            }
            case LINE_STRIP, DEBUG_LINE_STRIP -> {
                for (int i = 0; i + 1 < n; i++) {
                    emitLine(i, i + 1);
                }
            }
            case TRIANGLES -> {
                for (int i = 0; i + 2 < n; i += 3) {
                    emitTriangle(i, i + 1, i + 2);
                }
            }
            case TRIANGLE_STRIP -> {
                for (int i = 0; i + 2 < n; i++) {
                    if ((i & 1) == 0) {
                        emitTriangle(i, i + 1, i + 2);
                    } else {
                        emitTriangle(i + 1, i, i + 2);
                    }
                }
            }
            case TRIANGLE_FAN -> {
                for (int i = 1; i + 1 < n; i++) {
                    emitTriangle(0, i, i + 1);
                }
            }
            default -> {
                // QUADS: two triangles per quad.
                for (int q = 0; q < n; q += 4) {
                    if (q + 3 >= n) {
                        break;
                    }
                    IrisGl.bgnpolygon();
                    emitVertex(q);
                    emitVertex(q + 1);
                    emitVertex(q + 2);
                    emitVertex(q + 3);
                    IrisGl.endpolygon();
                }
            }
        }
        } finally {
            IrisGl.ctx().clearTerrainVertexAttributes();
            IrisGl.ctx().lightShade = false;
            IrisGl.overlayEnable(false);
            IrisGl.cullface(false);
        }
    }

    private void emitVertex(int i) {
        if (this.terrainAttributes) {
            if(this.terrainBlocks!=null&&this.terrainBlocks[i]>=0)IrisGl.ctx().terrainVoxelVertexContext(this.terrainVoxelVertex(i));
            if (this.terrainTangentPresent[i]) IrisGl.ctx().terrainVertexAttributes(this.terrainMaterials[i],
                this.terrainMidUvs[i * 2], this.terrainMidUvs[i * 2 + 1],
                this.terrainTangents[i * 4], this.terrainTangents[i * 4 + 1], this.terrainTangents[i * 4 + 2], this.terrainTangents[i * 4 + 3]);
            else IrisGl.ctx().terrainVertexAttributes(this.terrainMaterials[i], this.terrainMidUvs[i * 2], this.terrainMidUvs[i * 2 + 1]);
        }
        int o = i * STRIDE;
        IrisGl.t2f(this.data[o + 3], this.data[o + 4]);
        IrisGl.c4f(this.data[o + 5], this.data[o + 6], this.data[o + 7], this.data[o + 8]);
        IrisGl.l2f(this.data[o + 9], this.data[o + 10]);
        IrisGl.n3f(this.data[o + 11], this.data[o + 12], this.data[o + 13]);
        IrisGl.overlay(this.data[o + 14]);
        IrisGl.v3f(this.data[o], this.data[o + 1], this.data[o + 2]);
    }

    private void emitLine(int a, int b) {
        int oa = a * STRIDE;
        int ob = b * STRIDE;
        IrisGl.bgnline();
        IrisGl.t2f(this.data[oa + 3], this.data[oa + 4]);
        IrisGl.c4f(this.data[oa + 5], this.data[oa + 6], this.data[oa + 7], this.data[oa + 8]);
        IrisGl.v3f(this.data[oa], this.data[oa + 1], this.data[oa + 2]);
        IrisGl.t2f(this.data[ob + 3], this.data[ob + 4]);
        IrisGl.c4f(this.data[ob + 5], this.data[ob + 6], this.data[ob + 7], this.data[ob + 8]);
        IrisGl.v3f(this.data[ob], this.data[ob + 1], this.data[ob + 2]);
        IrisGl.endline();
    }

    private void emitTriangle(int a, int b, int c) {
        IrisGl.bgnpolygon();
        emitVertex(a);
        emitVertex(b);
        emitVertex(c);
        IrisGl.endpolygon();
    }
}
