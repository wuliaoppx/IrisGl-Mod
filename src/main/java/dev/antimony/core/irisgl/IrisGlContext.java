package dev.antimony.core.irisgl;

import java.util.ArrayDeque;
import java.util.Deque;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Immediate-mode IRIS GL 4.0 style context. All drawing goes through this class;
 * there is no OpenGL involvement whatsoever.
 */
public final class IrisGlContext {
    /**
     * Clip-space vertex float count:
     * x,y,z,w,u,v,r,g,b,a,viewDist,lu,lv,overlay + B3 shadowUV(u,v,refZ).
     * The three shadow floats are a real GLSL-style varying: computed per
     * vertex in {@link #clipVertex} from the camera-relative position, then
     * interpolated perspective-correctly by the rasterizer exactly like the
     * pack's {@code shadowPos} varying.
     */
    static final int STRIDE = 17;
    /** Staging vertex float count: x,y,z,u,v,r,g,b,a,lu,lv,nx,ny,nz,overlay. */
    private static final int VERTEX_FLOATS = 15;
    public static final int BF_ZERO = 0;
    public static final int BF_ONE = 1;
    public static final int BF_SRC_ALPHA = 2;
    public static final int BF_ONE_MINUS_SRC_ALPHA = 3;
    public static final int BF_DST_ALPHA = 4;
    public static final int BF_ONE_MINUS_DST_ALPHA = 5;
    public static final int BF_SRC_COLOR = 6;
    public static final int BF_ONE_MINUS_SRC_COLOR = 7;
    public static final int BF_DST_COLOR = 8;
    public static final int BF_ONE_MINUS_DST_COLOR = 9;

    private static final int PRIM_NONE = 0;
    private static final int PRIM_POLYGON = 1;
    private static final int PRIM_LINE = 2;
    private static final int PRIM_POINT = 3;

    public IrisGlFramebuffer framebuffer;
    public final IrisGlRasterizer rasterizer = new IrisGlRasterizer(this);
    /** Tile-binning parallel rasterizer front end for polygons. */
    private final TileRasterEngine engine = new TileRasterEngine(this);
    /** Counts describe this frame's CPU work, not GPU work or a frame-rate measurement. */
    public record RasterStats(long submitted,long clipRejected,long faceRejected,long boundsRejected,long binned,
                              long tileCandidates,long tileReferences,long tileRejected,long flushes,
                              long flushNanos,long snapshotNanos) { }
    public RasterStats rasterStatistics(){return engine.statistics();}
    public void setTileCulling(boolean enabled){engine.flush();engine.tileCulling=enabled;}

    public final Matrix4f projection = new Matrix4f();
    public final Matrix4f view = new Matrix4f();
    public final Matrix4f model = new Matrix4f();
    private final Deque<Matrix4f> modelStack = new ArrayDeque<>();
    private final Deque<Matrix4f> projectionStack = new ArrayDeque<>();

    // Current state, read by the rasterizer.
    public boolean depthTest = true;
    public boolean depthWrite = true;
    /** false = depth-only pass (vanilla's cloudsDepthOnly / shadow-style masks). */
    public boolean colorWrite = true;
    /** Explicit constant far-depth output for a CPU-adapted GUI depth-clear shader. */
    public boolean forceFarDepth = false;
    public boolean blend = false;
    public int blendSrc = BF_SRC_ALPHA;
    public int blendDst = BF_ONE_MINUS_SRC_ALPHA;
    /** Separate alpha pair (0 = derive from the colour pair). */
    public int blendAlphaSrc = 0;
    public int blendAlphaDst = 0;
    public boolean alphaTest = false;
    public float alphaRef = 0.1F;
    public boolean cull = false;
    public boolean scissorEnabled = false;
    public int scissorX, scissorY, scissorW, scissorH;
    public int viewportX, viewportY, viewportW, viewportH;
    public boolean textureEnabled = false;
    public IrisGlTexture texture;
    public boolean lightmapEnabled = false;
    public IrisGlTexture lightmap;
    public boolean fogEnabled = false;
    /**
     * B3: 这个批次要不要算 shadow varying（只对经过光影包的地形打开）。
     * 关掉时 {@link #clipVertex} 不做那次矩阵乘法，非地形几何零开销。
     */
    public boolean shadowVarying = false;
    public int fogColor = 0xFFC0D8FF;
    public float fogStart = 64.0F;
    public float fogEnd = 160.0F;
    /**
     * Vanilla {@code FogShape}: false = SPHERE (length), true = CYLINDER
     * (max(length(xz), |y|) of the raw, camera-relative vertex - exactly what
     * {@code fog.glsl}'s fog_distance does on the untransformed Position).
     * Vanilla uses CYLINDER for the sky and the terrain.
     */
    public boolean fogCylindrical = false;
    public float tintR = 1.0F, tintG = 1.0F, tintB = 1.0F, tintA = 1.0F;
    public int clearColor = 0xFF000000;

    // Current vertex attribute state.
    private float cr = 1.0F, cg = 1.0F, cb = 1.0F, ca = 1.0F;
    private float tu, tv;
    private float lu, lv;
    private float nx, ny, nz;

    /** Iris CapturedRenderingState and its BE-dispatcher reset both use zero outside a BE draw. */
    public static final int NO_BLOCK_ENTITY_ID = 0;
    private boolean terrainAttributes;
    private int terrainMaterial;
    private float terrainU, terrainV;
    // Optional staging sidecar; the ordinary vertex and clip layouts stay unchanged.
    private boolean[] terrainPresent;
    private int[] terrainMaterials;
    private float[] terrainMidUvs;
    private boolean terrainTangentProvided;
    private float tx, ty, tz, tw;
    private float[] terrainTangents;
    private boolean[] terrainTangentsPresent;
    private dev.antimony.core.render.TerrainVoxelAttributes.Vertex[] terrainVoxelVertices;
    private dev.antimony.core.render.TerrainVoxelAttributes.Vertex pendingVoxelVertex;

    public void terrainVoxelVertexContext(dev.antimony.core.render.TerrainVoxelAttributes.Vertex vertex) {
        this.pendingVoxelVertex=java.util.Objects.requireNonNull(vertex);
    }
    public dev.antimony.core.render.TerrainVoxelAttributes.Vertex terrainVoxelVertex(int index) {
        if(terrainVoxelVertices==null||index<0||index>=vertCount||terrainVoxelVertices[index]==null)
            throw new IllegalStateException("Original terrain vertex context unavailable");
        return terrainVoxelVertices[index];
    }
    private static dev.antimony.core.render.TerrainVoxelAttributes.Vertex[] ensure(dev.antimony.core.render.TerrainVoxelAttributes.Vertex[] a,int n){return a==null?new dev.antimony.core.render.TerrainVoxelAttributes.Vertex[Math.max(16,n)]:a.length>=n?a:java.util.Arrays.copyOf(a,Math.max(n,a.length*2));}

    public void terrainVertexAttributes(int materialId, float midU, float midV) {
        this.terrainAttributes = true;
        this.terrainTangentProvided = false;
        this.terrainMaterial = materialId;
        this.terrainU = midU;
        this.terrainV = midV;
    }

    public void terrainVertexAttributes(int materialId, float midU, float midV, float tangentX, float tangentY, float tangentZ, float tangentW) {
        terrainVertexAttributes(materialId, midU, midV);
        this.terrainTangentProvided = true;
        this.tx = tangentX; this.ty = tangentY; this.tz = tangentZ; this.tw = tangentW;
    }

    public void clearTerrainVertexAttributes() {
        this.terrainAttributes = false;
        this.terrainTangentProvided = false;
        this.terrainMaterial = -1;
        this.terrainU = this.terrainV = 0.0F;
    }

    /** Read by clipVertex's terrain hook, indexed in the current begin/end primitive. */
    public boolean terrainVertexAttributesEnabled(int index) {
        return index >= 0 && index < this.vertCount && this.terrainPresent != null
                && index < this.terrainPresent.length && this.terrainPresent[index];
    }

    public int terrainMaterialId(int index) {
        return this.terrainVertexAttributesEnabled(index) ? this.terrainMaterials[index] : -1;
    }

    public float terrainMidU(int index) {
        return this.terrainVertexAttributesEnabled(index) ? this.terrainMidUvs[index * 2] : 0.0F;
    }

    public float terrainMidV(int index) {
        return this.terrainVertexAttributesEnabled(index) ? this.terrainMidUvs[index * 2 + 1] : 0.0F;
    }

    private void storeTerrainVertexAttributes(int index) {
        if (this.terrainAttributes) {
            if (this.terrainPresent == null || index >= this.terrainPresent.length) {
                int size = Math.max(index + 1, this.terrainPresent == null ? 16 : this.terrainPresent.length * 2);
                this.terrainPresent = this.terrainPresent == null ? new boolean[size]
                        : java.util.Arrays.copyOf(this.terrainPresent, size);
                this.terrainMaterials = this.terrainMaterials == null ? new int[size]
                        : java.util.Arrays.copyOf(this.terrainMaterials, size);
                this.terrainMidUvs = this.terrainMidUvs == null ? new float[size * 2]
                        : java.util.Arrays.copyOf(this.terrainMidUvs, size * 2);
            }
            if (this.terrainTangentsPresent == null || index >= this.terrainTangentsPresent.length) {
                int size = this.terrainPresent.length;
                this.terrainTangentsPresent = this.terrainTangentsPresent == null ? new boolean[size] : java.util.Arrays.copyOf(this.terrainTangentsPresent, size);
                this.terrainTangents = this.terrainTangents == null ? new float[size * 4] : java.util.Arrays.copyOf(this.terrainTangents, size * 4);
            }
            this.terrainTangentsPresent[index] = this.terrainTangentProvided;
            this.terrainTangents[index * 4] = this.tx;
            this.terrainTangents[index * 4 + 1] = this.ty;
            this.terrainTangents[index * 4 + 2] = this.tz;
            this.terrainTangents[index * 4 + 3] = this.tw;
            this.terrainPresent[index] = true;
            this.terrainMaterials[index] = this.terrainMaterial;
            this.terrainMidUvs[index * 2] = this.terrainU;
            this.terrainMidUvs[index * 2 + 1] = this.terrainV;
        } else if (this.terrainPresent != null && index < this.terrainPresent.length) {
            this.terrainPresent[index] = false;
        }
    }

    /** Vanilla entity shader light directions (view/model normal space). */
    public float light0x, light0y, light0z;
    public float light1x, light1y, light1z;
    /**
     * Vanilla shades only NEW_ENTITY-format batches with the directional
     * lights (entity and item sheets); terrain and GUI quads carry pre-baked
     * brightness and their normals are ignored. MeshBuffer sets this per
     * batch.
     */
    public boolean lightShade = false;

    /**
     * Vanilla entity shader overlay (UV1). The vertex shader fetches the RGBA
     * from the 16x16 overlay texture and the fragment shader runs
     * {@code color.rgb = mix(overlay.rgb, color.rgb, overlay.a)} after the
     * texture/vertex-colour multiply and before the lightmap multiply - this is
     * what paints the red damage flash and the white death flash. Packed as
     * {@code (v << 4) | u} (both 0..15), 0 = no overlay.
     */
    private float overlayCode;
    /** True while the current batch contains at least one non-trivial overlay. */
    public boolean overlayEnabled = false;

    private int primitive = PRIM_NONE;
    private float[] verts = new float[4096 * VERTEX_FLOATS];
    private int vertCount;

    /** Clip-space polygon staging (see STRIDE), reused and grown as needed. */
    private float[] polyVerts = new float[64 * STRIDE];
    private int polyStride = STRIDE;
    private RasterShader primitiveShader;
    public void submitShaderQuad(float[] vertices,int stride,RasterShader shader){
        if(primitive!=PRIM_NONE)throw new IllegalStateException("Shader draw during immediate primitive");
        if(vertices.length!=4*stride)throw new IllegalArgumentException("Shader quad arity");
        engine.binTriangle(vertices,0,stride,2*stride,stride,shader);
        engine.binTriangle(vertices,0,2*stride,3*stride,stride,shader);
    }
    private final Vector4f tmp4 = new Vector4f();
    private final Vector3f tmp3 = new Vector3f();

    /** Explicit reusable target for scene/UI scaling; queued work drains into its original target first. */
    public void beginFrame(IrisGlFramebuffer target){
        if(target==null)throw new IllegalArgumentException("Missing CPU framebuffer");
        this.engine.flush();this.framebuffer=target;
        this.viewportX=this.viewportY=0;this.viewportW=target.width;this.viewportH=target.height;
        beginFrame(target.width,target.height);
    }

    public void beginFrame(int width, int height) {
        this.engine.flush(); // drain leftovers into the old framebuffer before swapping
        this.engine.resetStatistics();
        if (this.framebuffer == null || this.framebuffer.width != width || this.framebuffer.height != height) {
            this.framebuffer = new IrisGlFramebuffer(width, height);
            this.viewportX = 0;
            this.viewportY = 0;
            this.viewportW = width;
            this.viewportH = height;
        }
        this.projection.identity();
        this.view.identity();
        this.model.identity();
        this.matrixCacheDirty = true;
        this.modelStack.clear();
        this.projectionStack.clear();
        this.depthTest = true;
        this.depthWrite = true;
        this.colorWrite = true;
        this.forceFarDepth = false;
        this.blend = false;
        this.alphaTest = false;
        this.cull = false;
        this.scissorEnabled = false;
        this.textureEnabled = false;
        this.texture = null;
        this.lightmapEnabled = false;
        this.lightmap = null;
        this.fogEnabled = false;
        this.tintR = this.tintG = this.tintB = this.tintA = 1.0F;
        this.cr = this.cg = this.cb = this.ca = 1.0F;
        this.tu = this.tv = 0.0F;
        this.lu = this.lv = 0.0F;
        this.nx = this.ny = this.nz = 0.0F;
        this.clearTerrainVertexAttributes();
        this.light0x = this.light0y = this.light0z = 0.0F;
        this.light1x = this.light1y = this.light1z = 0.0F;
        this.primitive = PRIM_NONE;
        this.overlayCode = 0.0F;
        this.overlayEnabled = false;
        this.vertCount = 0;
        if(this.terrainVoxelVertices!=null)java.util.Arrays.fill(this.terrainVoxelVertices,null);
        this.pendingVoxelVertex=null;
        this.clearColor = 0xFF000000;
        this.framebuffer.clearDepth();
    }

    public void setClearColor(int argb) {
        this.clearColor = argb;
    }

    public void clear() {
        this.engine.flush();
        this.framebuffer.clearColor(this.clearColor);
    }

    public void zclear() {
        this.engine.flush();
        this.framebuffer.clearDepth();
    }

    /** Forces all binned polygons to be rasterized now (presentation boundary). */
    public void flushRaster() {
        this.engine.flush();
    }

    public void zbuffer(boolean on) {
        this.depthTest = on;
        this.depthWrite = on;
    }

    public void blendfunction(int src, int dst) {
        this.blend = true;
        this.blendSrc = src;
        this.blendDst = dst;
        this.blendAlphaSrc = 0;
        this.blendAlphaDst = 0;
    }

    /**
     * GL blendFuncSeparate: colour and alpha channels use independent factor
     * pairs. Vanilla's vignette needs (ZERO, ONE_MINUS_SRC_COLOR) for RGB
     * while keeping alpha untouched (ONE, ZERO); a plain alpha blend paints
     * the vignette as an opaque black sheet over the world instead.
     */
    public void blendfuncseparate(int src, int dst, int alphaSrc, int alphaDst) {
        this.blend = true;
        this.blendSrc = src;
        this.blendDst = dst;
        this.blendAlphaSrc = alphaSrc;
        this.blendAlphaDst = alphaDst;
    }

    public void blendmode(boolean on) {
        this.blend = on;
    }

    /** GL color mask: false keeps depth writes but leaves the colour buffer alone. */
    public void colorwrite(boolean on) {
        this.colorWrite = on;
    }

    public void cpack(int rgb) {
        this.cr = ((rgb >> 16) & 0xFF) / 255.0F;
        this.cg = ((rgb >> 8) & 0xFF) / 255.0F;
        this.cb = (rgb & 0xFF) / 255.0F;
        this.ca = 1.0F;
    }

    public void c3f(float r, float g, float b) {
        this.cr = r;
        this.cg = g;
        this.cb = b;
        this.ca = 1.0F;
    }

    public void c4f(float r, float g, float b, float a) {
        this.cr = r;
        this.cg = g;
        this.cb = b;
        this.ca = a;
    }

    public void t2f(float u, float v) {
        this.tu = u;
        this.tv = v;
    }

    public void l2f(float u, float v) {
        this.lu = u;
        this.lv = v;
    }

    /** IRIS GL n3f: current vertex normal (model space). */
    public void n3f(float x, float y, float z) {
        this.nx = x;
        this.ny = y;
        this.nz = z;
    }

    /**
     * Vanilla entity overlay coordinate (UV1) packed as {@code (v << 4) | u};
     * 0 disables the overlay for this vertex.
     */
    public void overlay(float code) {
        this.overlayCode = code;
    }

    /** Per-batch switch: skip the per-pixel overlay branch when nothing uses it. */
    public void overlayEnable(boolean on) {
        this.overlayEnabled = on;
    }

    public void texture(boolean on) {
        this.textureEnabled = on;
    }

    public void texbind(IrisGlTexture texture) {
        this.texture = texture;
        this.textureEnabled = texture != null;
    }

    public void lightmap(IrisGlTexture texture) {
        this.lightmap = texture;
        this.lightmapEnabled = texture != null;
    }

    public void fog(boolean on) {
        this.fogEnabled = on;
    }

    public void fogcolor(int rgb) {
        this.fogColor = 0xFF000000 | (rgb & 0xFFFFFF);
    }

    public void fogrange(float start, float end) {
        this.fogStart = start;
        this.fogEnd = end;
    }

    /** GL fog shape: true = GL_LINEAR-style cylinder, false = sphere. */
    public void fogshape(boolean cylindrical) {
        this.fogCylindrical = cylindrical;
    }

    public void scissor(int x, int y, int w, int h) {
        this.scissorEnabled = true;
        this.scissorX = x;
        this.scissorY = y;
        this.scissorW = w;
        this.scissorH = h;
    }

    public void scissorOff() {
        this.scissorEnabled = false;
    }

    public void viewport(int x, int y, int w, int h) {
        this.viewportX = x;
        this.viewportY = y;
        this.viewportW = w;
        this.viewportH = h;
    }

    public void perspective(float fovYDegrees, float aspect, float zNear, float zFar) {
        this.projection.setPerspective((float) Math.toRadians(fovYDegrees), aspect, zNear, zFar);
        this.matrixCacheDirty = true;
    }

    public void ortho(float left, float right, float bottom, float top, float zNear, float zFar) {
        this.projection.setOrtho(left, right, bottom, top, zNear, zFar);
        this.matrixCacheDirty = true;
    }

    public void lookat(float eyeX, float eyeY, float eyeZ, float centerX, float centerY, float centerZ, float upX, float upY, float upZ) {
        this.view.setLookAt(eyeX, eyeY, eyeZ, centerX, centerY, centerZ, upX, upY, upZ);
        this.matrixCacheDirty = true;
    }

    public void pushmatrix() {
        this.modelStack.push(new Matrix4f(this.model));
    }

    public void popmatrix() {
        if (!this.modelStack.isEmpty()) {
            this.model.set(this.modelStack.pop());
            this.matrixCacheDirty = true;
        }
    }

    public void loadidentity() {
        this.model.identity();
        this.matrixCacheDirty = true;
    }

    /**
     * Resets the view matrix to identity and, crucially, invalidates the cached
     * MVP. Passes that author geometry directly in camera space (the
     * first-person hand, the GUI) must call this instead of writing
     * {@code ctx().view.identity()} directly: the raw field write leaves the
     * cached matrix from the previous world pass in place, which pinned the
     * held item to the world instead of the camera (observed bug: the hand did
     * not turn with the view).
     */
    public void viewidentity() {
        this.view.identity();
        this.matrixCacheDirty = true;
    }

    /**
     * Swaps the view matrix wholesale (camera-space passes like the sky) and
     * invalidates the cached MVP, restoring the private invariant that raw
     * field writes would break.
     */
    public void replaceView(Matrix4f replacement) {
        this.view.set(replacement);
        this.matrixCacheDirty = true;
    }

    public void translate(float x, float y, float z) {
        this.model.translate(x, y, z);
        this.matrixCacheDirty = true;
    }

    public void scale(float x, float y, float z) {
        this.model.scale(x, y, z);
        this.matrixCacheDirty = true;
    }

    public void rot(float angleDegrees, float ax, float ay, float az) {
        this.model.rotate((float) Math.toRadians(angleDegrees), ax, ay, az);
        this.matrixCacheDirty = true;
    }

    public void rot(float angleDegrees, char axis) {
        switch (axis) {
            case 'x' -> this.model.rotate((float) Math.toRadians(angleDegrees), 1.0F, 0.0F, 0.0F);
            case 'y' -> this.model.rotate((float) Math.toRadians(angleDegrees), 0.0F, 1.0F, 0.0F);
            case 'z' -> this.model.rotate((float) Math.toRadians(angleDegrees), 0.0F, 0.0F, 1.0F);
            default -> throw new IllegalArgumentException("axis must be x/y/z");
        }
        this.matrixCacheDirty = true;
    }

    public void pushProjection() {
        this.projectionStack.push(new Matrix4f(this.projection));
    }

    public void popProjection() {
        if (!this.projectionStack.isEmpty()) {
            this.projection.set(this.projectionStack.pop());
            this.matrixCacheDirty = true;
        }
    }

    public void bgnpolygon() {
        begin(PRIM_POLYGON);
    }

    public void endpolygon() {
        end();
    }

    public void bgnline() {
        begin(PRIM_LINE);
    }

    public void endline() {
        end();
    }

    public void bgnpoint() {
        begin(PRIM_POINT);
    }

    public void endpoint() {
        end();
    }

    private void begin(int mode) {
        this.primitive = mode;
        this.vertCount = 0;
        if(this.terrainVoxelVertices!=null)java.util.Arrays.fill(this.terrainVoxelVertices,null);
        this.pendingVoxelVertex=null;
    }

    public void v3f(float x, float y, float z) {
        this.vertex(x, y, z);
    }

    public void v2f(float x, float y) {
        this.vertex(x, y, 0.0F);
    }

    public void vertex(float x, float y, float z) {
        if (this.primitive == PRIM_NONE) {
            throw new IllegalStateException("vertex outside begin/end");
        }
        int need = (this.vertCount + 1) * VERTEX_FLOATS;
        if (need > this.verts.length) {
            float[] bigger = new float[Math.max(need, this.verts.length * 2)];
            System.arraycopy(this.verts, 0, bigger, 0, this.verts.length);
            this.verts = bigger;
        }
        int o = this.vertCount * VERTEX_FLOATS;
        this.verts[o] = x;
        this.verts[o + 1] = y;
        this.verts[o + 2] = z;
        this.verts[o + 3] = this.tu;
        this.verts[o + 4] = this.tv;
        this.verts[o + 5] = this.cr * this.tintR;
        this.verts[o + 6] = this.cg * this.tintG;
        this.verts[o + 7] = this.cb * this.tintB;
        this.verts[o + 8] = this.ca * this.tintA;
        this.verts[o + 9] = this.lu;
        this.verts[o + 10] = this.lv;
        this.verts[o + 11] = this.nx;
        this.verts[o + 12] = this.ny;
        this.verts[o + 13] = this.nz;
        this.verts[o + 14] = this.overlayCode;
        this.storeTerrainVertexAttributes(this.vertCount);
        if(this.pendingVoxelVertex!=null){this.terrainVoxelVertices=ensure(this.terrainVoxelVertices,this.vertCount+1);this.terrainVoxelVertices[this.vertCount]=this.pendingVoxelVertex;this.pendingVoxelVertex=null;}
        this.vertCount++;
    }

    private void end() {
        if (this.primitive == PRIM_POLYGON && this.vertCount >= 3) {
            this.refreshMatrixCache();
            this.primitiveShader = dev.antimony.gl.glsl.IrisTerrainVertex.rasterShader();
            this.polyStride = STRIDE + (primitiveShader == null ? 0 : primitiveShader.layout().components());
            int need = this.vertCount * polyStride;
            if (this.polyVerts.length < need) this.polyVerts = new float[Math.max(need, this.polyVerts.length * 2)];
            this.clipPrimitive();
            if (primitiveShader != null) primitiveShader.layout().flatten(polyVerts,vertCount,polyStride,STRIDE,vertCount-1);
            for (int i = 1; i < this.vertCount - 1; i++) {
                this.engine.binTriangle(this.polyVerts, 0, i * polyStride, (i + 1) * polyStride, polyStride, primitiveShader);
            }
        } else if (this.primitive == PRIM_LINE && this.vertCount >= 2) {
            this.polyStride = STRIDE; this.primitiveShader = null;
            this.refreshMatrixCache();
            int need = this.vertCount * STRIDE;
            if (this.polyVerts.length < need) {
                this.polyVerts = new float[Math.max(need, this.polyVerts.length * 2)];
            }
            this.clipPrimitive();
            for (int i = 0; i < this.vertCount - 1; i++) {
                this.drawLine(i, i + 1);
            }
        } else if (this.primitive == PRIM_POINT && this.vertCount >= 1) {
            this.polyStride = STRIDE; this.primitiveShader = null;
            this.refreshMatrixCache();
            int need = this.vertCount * STRIDE;
            if (this.polyVerts.length < need) this.polyVerts = new float[need];
            this.clipPrimitive();
            for (int i = 0; i < this.vertCount; i++) this.drawPoint(i);
        }
        this.primitive = PRIM_NONE;
        this.vertCount = 0;
    }

    private void drawTriangle(int a, int b, int c) {
        // Unused: triangles are fanned and binned directly in end().
    }

    private void drawLine(int a, int b) {
        this.rasterizer.line(
                java.util.Arrays.copyOfRange(this.polyVerts, a * STRIDE, (a + 1) * STRIDE),
                java.util.Arrays.copyOfRange(this.polyVerts, b * STRIDE, (b + 1) * STRIDE));
    }

    private void drawPoint(int a) {
        this.rasterizer.point(java.util.Arrays.copyOfRange(this.polyVerts, a * STRIDE, (a + 1) * STRIDE));
    }

    /** Matrix cache: recomputed when any matrix changed since the last use. */
    private final Matrix4f cachedMvp = new Matrix4f();
    private final Matrix4f cachedVm = new Matrix4f();
    private boolean matrixCacheDirty = true;

    private void refreshMatrixCache() {
        if (this.matrixCacheDirty) {
            this.cachedMvp.set(this.projection).mul(this.view).mul(this.model);
            this.cachedVm.set(this.view).mul(this.model);
            this.matrixCacheDirty = false;
        }
    }

    /** Stage every original vertex before submission. A bridge failure discards the entire primitive. */
    private void clipPrimitive() {
        boolean terrain = dev.antimony.gl.glsl.IrisTerrainVertex.active();
        try {
            for (int i = 0; i < this.vertCount; i++) this.clipVertex(i, i * polyStride);
        } catch (RuntimeException invalidInput) {
            if (!terrain) throw invalidInput;
            dev.antimony.gl.glsl.IrisTerrainVertex.rejectPrimitive(invalidInput);
        }
        if (terrain && (!dev.antimony.gl.glsl.IrisTerrainVertex.active()
                || primitiveShader != null && dev.antimony.gl.glsl.IrisTerrainVertex.rasterShader() != primitiveShader)) {
            this.primitiveShader = null; this.polyStride = STRIDE;
            for (int i = 0; i < this.vertCount; i++) this.clipVertex(i, i * STRIDE);
        }
    }

    /** Clip-space vertex layout: x,y,z,w,u,v,r,g,b,a,dist,lu,lv,overlay,shadowUV (see STRIDE). */
    private void clipVertex(int index, int outOffset) {
        int o = index * VERTEX_FLOATS;
        float x = this.verts[o];
        float y = this.verts[o + 1];
        float z = this.verts[o + 2];
        dev.antimony.gl.glsl.VertexState.InputProvider terrainInputs = null;
        dev.antimony.core.render.TerrainVoxelAttributes.Vertex foundVoxelVertex=null;
        if (dev.antimony.gl.glsl.IrisTerrainVertex.active() && this.terrainVertexAttributesEnabled(index)) {
            if(this.terrainVoxelVertices!=null)foundVoxelVertex=this.terrainVoxelVertices[index];
            var attributes = new dev.antimony.gl.glsl.VertexState.Attributes(
                new float[]{x, y, z},
                new float[]{this.verts[o + 5], this.verts[o + 6], this.verts[o + 7], this.verts[o + 8]},
                new float[]{this.verts[o + 3], this.verts[o + 4]},
                new int[]{(int) this.verts[o + 9], (int) this.verts[o + 10]},
                new float[]{this.verts[o + 11], this.verts[o + 12], this.verts[o + 13]},
                this.terrainMaterialId(index), new float[]{this.terrainMidU(index), this.terrainMidV(index)});
            // Iris CapturedRenderingState's non-block-entity engine sentinel is 0.
            final var voxelVertex=foundVoxelVertex;
            terrainInputs = input -> {
                if (input.name().equals("blockEntityId")) return Integer.valueOf(dev.antimony.gl.glsl.IrisTerrainVertex.currentBlockEntityId());
                if (input.name().equals("gl_VertexID")) {if(voxelVertex==null)throw new IllegalStateException("Original draw vertex ID unavailable");return Integer.valueOf(voxelVertex.sourceVertex());}
                if (input.name().equals("at_midBlock")) {
                    if(voxelVertex==null)throw new IllegalStateException("Original terrain block center unavailable");
                    return dev.antimony.core.render.TerrainVoxelAttributes.midBlock(voxelVertex,x,y,z);
                }
                if (input.name().equals("at_tangent")) {
                    if (this.terrainTangentsPresent == null || !this.terrainTangentsPresent[index])
                        throw new IllegalStateException("Terrain tangent has no current Iris builder result");
                    return new float[]{this.terrainTangents[index * 4], this.terrainTangents[index * 4 + 1], this.terrainTangents[index * 4 + 2], this.terrainTangents[index * 4 + 3]};
                }
                return attributes.value(input);
            };
            if (dev.antimony.gl.glsl.IrisTerrainVertex.blockEntityActive())
                terrainInputs = dev.antimony.core.render.BlockEntityShaderInputs.attributes(terrainInputs,
                    dev.antimony.gl.glsl.IrisTerrainVertex.currentBlockEntityId(), (int)this.verts[o + 14]);
        }
        if (!dev.antimony.gl.glsl.IrisTerrainVertex.clipVertex(this, terrainInputs, this.polyVerts, outOffset)) {
            this.tmp4.set(x, y, z, 1.0F);
            this.cachedMvp.transform(this.tmp4);
            this.polyVerts[outOffset] = this.tmp4.x;
            this.polyVerts[outOffset + 1] = this.tmp4.y;
            this.polyVerts[outOffset + 2] = this.tmp4.z;
            this.polyVerts[outOffset + 3] = this.tmp4.w;
        }
        if (this.forceFarDepth) this.polyVerts[outOffset + 2] = this.polyVerts[outOffset + 3];
        float r = this.verts[o + 5];
        float g = this.verts[o + 6];
        float b = this.verts[o + 7];
        // minecraft_mix_light: shade entity/item geometry by two fixed
        // directional lights, normals transformed into view space by
        // view*model exactly like the shader's Position-space normals. Only
        // NEW_ENTITY-format batches opt in (terrain/GUI brightness is
        // pre-baked); a zero normal means the source never set one.
        float nx = this.verts[o + 11];
        float ny = this.verts[o + 12];
        float nz = this.verts[o + 13];
        if (this.lightShade && (nx != 0.0F || ny != 0.0F || nz != 0.0F)) {
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6F) {
                nx /= len;
                ny /= len;
                nz /= len;
                this.tmp3.set(nx, ny, nz);
                this.cachedVm.transformDirection(this.tmp3);
                // vanilla: each dot is clamped to >= 0 independently, then the
                // two accumulate (a negative contribution from one light must
                // not subtract from the other's).
                float dot0 = this.light0x * this.tmp3.x + this.light0y * this.tmp3.y + this.light0z * this.tmp3.z;
                float dot1 = this.light1x * this.tmp3.x + this.light1y * this.tmp3.y + this.light1z * this.tmp3.z;
                float accum = Math.max(0.0F, dot0) + Math.max(0.0F, dot1);
                accum = Math.min(1.0F, accum);
                float factor = accum * 0.6F + 0.4F;
                r *= factor;
                g *= factor;
                b *= factor;
            }
        }
        this.polyVerts[outOffset + 4] = this.verts[o + 3];
        this.polyVerts[outOffset + 5] = this.verts[o + 4];
        this.polyVerts[outOffset + 6] = r;
        this.polyVerts[outOffset + 7] = g;
        this.polyVerts[outOffset + 8] = b;
        this.polyVerts[outOffset + 9] = this.verts[o + 8];
        // Fog distance: vanilla's fog_distance(Position, FogShape) runs on the
        // raw (camera-relative) vertex, never on the view-transformed one, so
        // the cylindrical form is rotation independent.
        if (this.fogCylindrical) {
            float xz = (float) Math.sqrt(x * x + z * z);
            this.polyVerts[outOffset + 10] = Math.max(xz, Math.abs(y));
        } else {
            this.tmp3.set(x, y, z);
            this.cachedVm.transformPosition(this.tmp3);
            this.polyVerts[outOffset + 10] = this.tmp3.length();
        }
        this.polyVerts[outOffset + 11] = this.verts[o + 9];
        this.polyVerts[outOffset + 12] = this.verts[o + 10];
        this.polyVerts[outOffset + 13] = this.verts[o + 14];
        if (this.shadowVarying) {
            // B3: shadowPos varying. The pack computes it per vertex from the
            // camera-relative position; `cachedVm` = view*model is exactly that
            // (the view matrix is rotation-only, the model matrix only carries
            // the section translation), so one transform here replaces what
            // gbuffers_terrain.vsh does in src/shadow_src_vertex.glsl.
            this.tmp4.set(x, y, z, 1.0F);
            this.cachedVm.transform(this.tmp4);
            dev.antimony.core.render.ShadowMap.projectCameraRelative(
                    this.tmp4.x, this.tmp4.y, this.tmp4.z, this.polyVerts, outOffset + 14);
        } else {
            this.polyVerts[outOffset + 14] = 0.0F;
            this.polyVerts[outOffset + 15] = 0.0F;
            this.polyVerts[outOffset + 16] = 0.0F;
        }
    }

    /** B3: 打开/关闭 shadow varying 计算（只在地形批次前后切换）。 */
    public void shadow(boolean on) {
        this.shadowVarying = on;
    }

    /** Debug helper: project a model-space point to screen coordinates. */
    public float[] projectToScreen(float x, float y, float z) {
        Matrix4f mvp = new Matrix4f(this.projection).mul(this.view).mul(this.model);
        Vector4f p = mvp.transform(new Vector4f(x, y, z, 1.0F));
        float sx = this.viewportX + (p.x / p.w * 0.5F + 0.5F) * this.viewportW;
        float sy = this.viewportY + (1.0F - (p.y / p.w * 0.5F + 0.5F)) * this.viewportH;
        return new float[]{sx, sy, p.z / p.w, p.w};
    }

    public IrisGlFramebuffer framebuffer() {
        return this.framebuffer;
    }
}
