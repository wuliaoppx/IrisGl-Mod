package dev.antimony.core.irisgl;

import dev.antimony.AntimonyMod;
import dev.antimony.gl.glsl.ShaderVaryings;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * llvmpipe-style tile-binning rasterizer front end.
 *
 * <p>Triangles are clipped, projected and sorted into 64x64 screen tiles as
 * they are submitted; a fixed pool of worker threads rasterizes disjoint tiles
 * in parallel. Every pixel belongs to exactly one tile and each tile processes
 * its triangles strictly in submission order, so the output is bit-identical
 * to the previous single-threaded immediate path - blending and depth order
 * are preserved - while using every CPU core.</p>
 *
 * <p>The render thread bins geometry during the frame and blocks in
 * {@link #flush()} while the workers rasterize; flushes happen at the same
 * points vanilla rasterization was previously observable (frame begin, colour
 * clear, depth clear, before presenting/screenshotting), so pass semantics
 * (sky, opaque, translucent, GUI) are unchanged.</p>
 */
final class TileRasterEngine {
    static final int TILE = 64;
    /** Clip-space vertex floats; must equal {@link IrisGlContext#STRIDE}. */
    private static final int STRIDE = 17;
    private static final int TRI_FLOATS = 3 * STRIDE;
    /** Below this triangle count a synchronous rasterization is cheaper. */
    private static final int PARALLEL_MIN_TRIS = Integer.getInteger("antimony.raster.parallelMin", 48);

    private final IrisGlContext ctx;
    private final ExecutorService pool;
    private final int workers;

    /** -Dantimony.debug.threads: log how much of the frame really runs in parallel. */
    private static final boolean THREAD_DEBUG = dev.antimony.AntimonyMod.flag("antimony.debug.threads");
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicInteger peakWorkers = new AtomicInteger();
    private long debugFlushes;
    private long debugParallelFlushes;
    private long debugTris;
    private long debugNanos;
    private int debugPeak;

    // Triangle arena, reused across frames.
    private float[] tris = new float[1024 * TRI_FLOATS];
    private StateSnap[] snaps = new StateSnap[1024];
    private ShaderTriangle[] shaderTriangles = new ShaderTriangle[1024];
    record ShaderTriangle(float[] vertices, int stride, RasterShader shader) { }
    private int triCount;

    // Tile bins (one growable index list per tile).
    private int gridW = -1;
    private int gridH = -1;
    private int[][] bins = new int[0][];
    private int[] binLen = new int[0];

    // Scratch: near-plane clip output and per-polygon clip-space vertices.
    private float[] clipScratch = new float[HomogeneousClipper.MAX_VERTICES * STRIDE];
    private float[] clipTemporary = new float[HomogeneousClipper.MAX_VERTICES * STRIDE];
    private float[] polyVerts = new float[16 * STRIDE];
    private float[] flatScratch = new float[3 * STRIDE];
    private ShaderVaryings cachedLayout;
    private byte[] cachedModes;
    boolean tileCulling = !"off".equalsIgnoreCase(System.getProperty("antimony.raster.tileCull", "on"));
    private long submitted, clipRejected, faceRejected, boundsRejected, binned;
    private long tileCandidates, tileReferences, tileRejected, flushes, flushNanos, snapshotNanos;

    void resetStatistics() {
        submitted=clipRejected=faceRejected=boundsRejected=binned=0;
        tileCandidates=tileReferences=tileRejected=flushes=flushNanos=snapshotNanos=0;
    }
    IrisGlContext.RasterStats statistics() {
        return new IrisGlContext.RasterStats(submitted,clipRejected,faceRejected,boundsRejected,binned,
            tileCandidates,tileReferences,tileRejected,flushes,flushNanos,snapshotNanos);
    }
    private byte[] modesFor(RasterShader shader) {
        ShaderVaryings layout=shader.layout();
        if(cachedLayout!=layout){cachedLayout=layout;cachedModes=layout.modes();}
        return cachedModes;
    }

    // State snapshot dedup: consecutive triangles usually share one state.
    private StateSnap lastSnap;

    TileRasterEngine(IrisGlContext ctx) {
        this.ctx = ctx;
        int cores = Runtime.getRuntime().availableProcessors();
        this.workers = Math.max(1, Math.min(cores, 16));
        if (this.workers > 1) {
            this.pool = Executors.newFixedThreadPool(this.workers, task -> {
                Thread t = new Thread(task, "antimony-raster");
                t.setDaemon(true);
                return t;
            });
        } else {
            this.pool = null;
        }
    }

    int workers() {
        return this.workers;
    }

    /**
     * Bins one polygon triangle given clip-space vertex data (STRIDE floats:
     * x,y,z,w,u,v,r,g,b,a,viewDist,lightU,lightV) at three offsets in one
     * array. Handles near-plane clipping for partially behind triangles.
     */
    void binTriangle(float[] buf, int o0, int o1, int o2) {
        binTriangle(buf, o0, o1, o2, STRIDE, null);
    }
    void binTriangle(float[] buf, int o0, int o1, int o2, int stride, RasterShader shader) {
        if (stride != STRIDE + (shader == null ? 0 : shader.layout().components())) throw new IllegalArgumentException("raster payload stride");
        submitted++;
        // Position rejection precedes all payload copies. Vertex image stores already ran.
        int c0=HomogeneousClipper.outcode(buf,o0),c1=HomogeneousClipper.outcode(buf,o1),c2=HomogeneousClipper.outcode(buf,o2);
        if(c0<0||c1<0||c2<0||(c0&c1&c2)!=0){clipRejected++;return;}
        int need = HomogeneousClipper.MAX_VERTICES * stride;
        if (clipScratch.length < need) { clipScratch = new float[need]; clipTemporary = new float[need]; }
        byte[] modes=null;
        // Freeze original provoking values in reusable scratch, never mutate the caller's arena.
        if(shader!=null){
            if(flatScratch.length<3*stride)flatScratch=new float[3*stride];
            System.arraycopy(buf,o0,flatScratch,0,stride);System.arraycopy(buf,o1,flatScratch,stride,stride);
            System.arraycopy(buf,o2,flatScratch,2*stride,stride);
            buf=flatScratch;o0=0;o1=stride;o2=2*stride;modes=modesFor(shader);
            for(int lane=0;lane<modes.length;lane++)if(modes[lane]==1){
                buf[o0+STRIDE+lane]=buf[o2+STRIDE+lane];buf[o1+STRIDE+lane]=buf[o2+STRIDE+lane];
            }
        }
        if ((c0 | c1 | c2) != 0) {
            int n = HomogeneousClipper.triangle(buf, o0, o1, o2, stride, this.clipScratch, this.clipTemporary,modes,STRIDE);
            for (int i = 1; i < n - 1; i++) this.binTri(this.clipScratch, 0, i * stride, (i + 1) * stride, stride, shader);
            return;
        }
        this.binTri(buf, o0, o1, o2, stride, shader);
    }

    private void binTri(float[] buf, int o0, int o1, int o2, int stride, RasterShader shader) {
        IrisGlFramebuffer fb = this.ctx.framebuffer;
        if (fb == null) {
            return;
        }
        float w0 = buf[o0 + 3];
        float w1 = buf[o1 + 3];
        float w2 = buf[o2 + 3];
        if (w0 <= 0.0F || w1 <= 0.0F || w2 <= 0.0F) {
            return;
        }
        float iw0 = 1.0F / w0;
        float iw1 = 1.0F / w1;
        float iw2 = 1.0F / w2;
        // Project via the reciprocal: screenX/screenY divide by w internally,
        // so calling them raw re-ran the same divide per vertex (6 redundant
        // divides per triangle across binning + raster).
        float vx0 = buf[o0] * iw0;
        float vy0 = buf[o0 + 1] * iw0;
        float vx1 = buf[o1] * iw1;
        float vy1 = buf[o1 + 1] * iw1;
        float vx2 = buf[o2] * iw2;
        float vy2 = buf[o2 + 1] * iw2;
        float x0 = this.ctx.viewportX + (vx0 * 0.5F + 0.5F) * this.ctx.viewportW;
        float y0 = this.ctx.viewportY + (1.0F - (vy0 * 0.5F + 0.5F)) * this.ctx.viewportH;
        float x1 = this.ctx.viewportX + (vx1 * 0.5F + 0.5F) * this.ctx.viewportW;
        float y1 = this.ctx.viewportY + (1.0F - (vy1 * 0.5F + 0.5F)) * this.ctx.viewportH;
        float x2 = this.ctx.viewportX + (vx2 * 0.5F + 0.5F) * this.ctx.viewportW;
        float y2 = this.ctx.viewportY + (1.0F - (vy2 * 0.5F + 0.5F)) * this.ctx.viewportH;

        // Cull before binning: the EXACT signed-area expression the pixel
        // loop uses (edge(v0, v1, v2)), not its negation - the first attempt
        // wrote the formula flipped and culled every FRONT face (terrain and
        // nametags vanished, only back faces remained). Positive area in our
        // flipped-Y pixel space is a front face; negative is a back face.
        float area = (x2 - x0) * (y1 - y0) - (y2 - y0) * (x1 - x0);
        if (area == 0.0F || (area < 0.0F && this.ctx.cull)) {
            faceRejected++;
            return;
        }

        int minX = (int) Math.floor(Math.min(x0, Math.min(x1, x2)));
        int maxX = (int) Math.ceil(Math.max(x0, Math.max(x1, x2)));
        int minY = (int) Math.floor(Math.min(y0, Math.min(y1, y2)));
        int maxY = (int) Math.ceil(Math.max(y0, Math.max(y1, y2)));
        minX = Math.max(minX, 0);
        minY = Math.max(minY, 0);
        maxX = Math.min(maxX, fb.width - 1);
        maxY = Math.min(maxY, fb.height - 1);
        // Scissor is a bin-time property (the pixel clamp uses it), so apply
        // it to the bounding box here and never inside the pixel loop.
        if (this.ctx.scissorEnabled) {
            if(ctx.scissorW<=0||ctx.scissorH<=0){boundsRejected++;return;}
            minX = Math.max(minX, this.ctx.scissorX);
            minY = Math.max(minY, this.ctx.scissorY);
            maxX = (int)Math.min(maxX, (long)this.ctx.scissorX + this.ctx.scissorW - 1);
            maxY = (int)Math.min(maxY, (long)this.ctx.scissorY + this.ctx.scissorH - 1);
        }
        if (minX > maxX || minY > maxY) {
            boundsRejected++;
            return;
        }

        int gw = (fb.width + TILE - 1) / TILE;
        int gh = (fb.height + TILE - 1) / TILE;
        if (gw != this.gridW || gh != this.gridH) {
            this.reallocBins(gw, gh);
        }
        int tri = this.triCount;
        int firstTx = minX / TILE;
        int lastTx = maxX / TILE;
        int firstTy = minY / TILE;
        int lastTy = maxY / TILE;
        int acceptedTiles=0;
        // Unsupported blended draw contracts must still reach the normal validation path.
        boolean coverageCull=tileCulling&&!(shader!=null&&shader.fragmentDraw()&&ctx.blend&&!shader.handlesBlend());
        for (int ty = firstTy; ty <= lastTy; ty++) {
            for (int tx = firstTx; tx <= lastTx; tx++) {
                tileCandidates++;
                int left=Math.max(minX,tx*TILE),top=Math.max(minY,ty*TILE);
                int right=Math.min(maxX,(tx+1)*TILE-1),bottom=Math.min(maxY,(ty+1)*TILE-1);
                if(coverageCull&&!TriangleTileCoverage.mayCover(x0,y0,x1,y1,x2,y2,area,left,top,right,bottom)){
                    tileRejected++;continue;
                }
                this.appendBin(ty * gw + tx, tri);tileReferences++;acceptedTiles++;
            }
        }
        if(acceptedTiles==0){boundsRejected++;return;}
        binned++;

        int cap = tri + 1;
        if (this.tris.length < cap * TRI_FLOATS) {
            this.tris = Arrays.copyOf(this.tris, Math.max(cap * TRI_FLOATS, this.tris.length * 2));
            this.snaps = Arrays.copyOf(this.snaps, Math.max(cap, this.snaps.length * 2));
            this.shaderTriangles = Arrays.copyOf(this.shaderTriangles, this.snaps.length);
        }
        int dst = tri * TRI_FLOATS;
        System.arraycopy(buf, o0, this.tris, dst, STRIDE);
        System.arraycopy(buf, o1, this.tris, dst + STRIDE, STRIDE);
        System.arraycopy(buf, o2, this.tris, dst + 2 * STRIDE, STRIDE);
        this.snaps[tri] = this.snap();
        if (shader == null) this.shaderTriangles[tri] = null;
        else {
            float[] data = new float[3 * stride];
            System.arraycopy(buf,o0,data,0,stride); System.arraycopy(buf,o1,data,stride,stride); System.arraycopy(buf,o2,data,2*stride,stride);
            this.shaderTriangles[tri] = new ShaderTriangle(data,stride,shader);
        }
        this.triCount = cap;
    }

    private StateSnap snap() {
        if (this.lastSnap != null && this.lastSnap.matches(this.ctx)) {
            return this.lastSnap;
        }
        StateSnap s = new StateSnap(this.ctx);
        this.lastSnap = s;
        return s;
    }

    private void appendBin(int tile, int tri) {
        int[] bin = this.bins[tile];
        int len = this.binLen[tile];
        if (bin == null) {
            bin = new int[16];
            this.bins[tile] = bin;
        } else if (len == bin.length) {
            bin = Arrays.copyOf(bin, len * 2);
            this.bins[tile] = bin;
        }
        bin[len] = tri;
        this.binLen[tile] = len + 1;
    }

    private void reallocBins(int gw, int gh) {
        this.gridW = gw;
        this.gridH = gh;
        this.bins = new int[gw * gh][];
        this.binLen = new int[gw * gh];
    }

    /** Rasterizes every binned triangle into the framebuffer; blocks until done. */
    void flush() {
        IrisGlFramebuffer fb = this.ctx.framebuffer;
        int count = this.triCount;
        if (fb == null || count == 0) {
            this.triCount = 0;
            return;
        }
        int gw = (fb.width + TILE - 1) / TILE;
        int gh = (fb.height + TILE - 1) / TILE;
        if (gw != this.gridW || gh != this.gridH) {
            this.reallocBins(gw, gh);
        }
        long flushStart=System.nanoTime();
        java.util.Set<RasterShader> transactions=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for(int tri=0;tri<count;tri++) {
            ShaderTriangle shader=shaderTriangles[tri];
            if(shader!=null&&shader.shader().fragmentDraw())transactions.add(shader.shader());
        }
        long snapshotStart=System.nanoTime();
        int[] colorBefore=transactions.isEmpty()?null:fb.color.clone();
        float[] depthBefore=transactions.isEmpty()?null:fb.depth.clone();
        for(RasterShader draw:transactions)draw.beginTransaction();
        snapshotNanos+=System.nanoTime()-snapshotStart;
        int total = gw * gh;
        long startNanos = THREAD_DEBUG ? System.nanoTime() : 0L;
        boolean parallel = this.pool != null && count >= PARALLEL_MIN_TRIS;
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        if (parallel) {
            this.activeWorkers.set(0);
            this.peakWorkers.set(0);
            AtomicInteger cursor = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(this.workers);
            for (int w = 0; w < this.workers; w++) {
                this.pool.execute(() -> {
                    int active = this.activeWorkers.incrementAndGet();
                    this.peakWorkers.accumulateAndGet(active, Math::max);
                    try {
                        int t;
                        while ((t = cursor.getAndIncrement()) < total && failure.get() == null) {
                            this.rasterTile(t, fb, gw);
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null,error);
                    } finally {
                        this.activeWorkers.decrementAndGet();
                        latch.countDown();
                    }
                });
            }
            // Wait until EVERY worker has drained the bin/tri/snaps arena
            // before reusing it. A plain catch-continue on InterruptedException
            // used to fall through to the Arrays.fill below while raster
            // workers were still reading - the next frame then overwrote the
            // arena under them. countDown() sits in the workers' finally
            // block, so the latch always reaches zero; only interrupts can
            // cut await() short, and they retry here.
            boolean interrupted = false;
            while (latch.getCount() > 0) {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } else {
            try {
                for (int t = 0; t < total; t++) this.rasterTile(t, fb, gw);
            } catch (Throwable error) { failure.compareAndSet(null,error); }
        }
        if (THREAD_DEBUG) {
            this.debugFlushes++;
            this.debugTris += count;
            this.debugNanos += System.nanoTime() - startNanos;
            this.debugPeak = Math.max(this.debugPeak, this.peakWorkers.get());
            if (parallel) {
                this.debugParallelFlushes++;
            }
            if (this.debugFlushes % 120 == 0) {
                AntimonyMod.LOG.info(
                        "[Antimony] raster: workers={} flushes={} parallel={} tris={} tiles/flush={} peakThreads={} avg={}ms",
                        this.workers, this.debugFlushes, this.debugParallelFlushes, this.debugTris, total,
                        this.debugPeak, String.format("%.2f", this.debugNanos / 1_000_000.0 / this.debugFlushes));
            }
        }
        boolean success=failure.get()==null;
        for(RasterShader draw:transactions)draw.endTransaction(success);
        if(!success&&colorBefore!=null) {
            System.arraycopy(colorBefore,0,fb.color,0,colorBefore.length);
            System.arraycopy(depthBefore,0,fb.depth,0,depthBefore.length);
        }
        Arrays.fill(this.shaderTriangles, 0, count, null);
        Arrays.fill(this.snaps, 0, count, null);
        Arrays.fill(this.binLen, 0);
        this.triCount = 0;
        flushes++;flushNanos+=System.nanoTime()-flushStart;
        if (failure.get() != null) throw new IllegalStateException("Raster draw failed after worker quiescence",failure.get());
    }

    private void rasterTile(int t, IrisGlFramebuffer fb, int gw) {
        int tx = (t % gw) * TILE;
        int ty = (t / gw) * TILE;
        int minX = tx;
        int minY = ty;
        int maxX = Math.min(fb.width - 1, tx + TILE - 1);
        int maxY = Math.min(fb.height - 1, ty + TILE - 1);
        int[] bin = this.bins[t];
        int n = this.binLen[t];
        for (int i = 0; i < n; i++) {
            int tri = bin[i];
            IrisGlRasterizer.rasterTriangle(this.ctx, this.tris, tri * TRI_FLOATS,
                    this.snaps[tri], minX, minY, maxX, maxY, this.shaderTriangles[tri]);
        }
    }

    private float screenX(float x, float w) {
        float ndc = x / w;
        return this.ctx.viewportX + (ndc * 0.5F + 0.5F) * this.ctx.viewportW;
    }

    private float screenY(float y, float w) {
        float ndc = y / w;
        return this.ctx.viewportY + (1.0F - (ndc * 0.5F + 0.5F)) * this.ctx.viewportH;
    }

    /** Immutable rasterization state captured when a triangle is binned. */
    static final class StateSnap {
        final int viewportX, viewportY, viewportW, viewportH;
        final IrisGlTexture texture;
        final IrisGlTexture lightmap;
        final boolean depthTest;
        final boolean depthWrite;
        final boolean colorWrite;
        final boolean blend;
        final int blendSrc;
        final int blendDst;
        final int blendAlphaSrc;
        final int blendAlphaDst;
        final boolean alphaTest;
        final float alphaRef;
        final boolean cull;
        final boolean scissorEnabled;
        final int scissorX,scissorY,scissorW,scissorH;
        final boolean fogEnabled;
        final int fogColor;
        final float fogStart;
        final float fogEnd;
        final boolean overlay;
        /** B3: 该三角形是否要按 shadowUV 逐像素查阴影深度图。 */
        final boolean shadow;

        StateSnap(IrisGlContext c) {
            viewportX=c.viewportX; viewportY=c.viewportY; viewportW=c.viewportW; viewportH=c.viewportH;
            this.texture = c.textureEnabled ? c.texture : null;
            this.lightmap = c.lightmapEnabled ? c.lightmap : null;
            this.depthTest = c.depthTest;
            this.depthWrite = c.depthWrite;
            this.colorWrite = c.colorWrite;
            this.blend = c.blend;
            this.blendSrc = c.blendSrc;
            this.blendDst = c.blendDst;
            this.blendAlphaSrc = c.blendAlphaSrc;
            this.blendAlphaDst = c.blendAlphaDst;
            this.alphaTest = c.alphaTest;
            this.alphaRef = c.alphaRef;
            this.cull = c.cull;
            this.scissorEnabled=c.scissorEnabled;
            this.scissorX=c.scissorX;this.scissorY=c.scissorY;this.scissorW=c.scissorW;this.scissorH=c.scissorH;
            this.fogEnabled = c.fogEnabled;
            this.fogColor = c.fogColor;
            this.fogStart = c.fogStart;
            this.fogEnd = c.fogEnd;
            this.overlay = c.overlayEnabled;
            // 只有地形会把 shadowVarying 打开；深度图没建好时也直接跳过。
            this.shadow = c.shadowVarying && dev.antimony.core.render.ShadowMap.valid();
        }

        boolean matches(IrisGlContext c) {
            IrisGlTexture tex = c.textureEnabled ? c.texture : null;
            IrisGlTexture lm = c.lightmapEnabled ? c.lightmap : null;
            return viewportX==c.viewportX && viewportY==c.viewportY && viewportW==c.viewportW && viewportH==c.viewportH
                    && this.texture == tex
                    && this.lightmap == lm
                    && this.depthTest == c.depthTest
                    && this.depthWrite == c.depthWrite
                    && this.colorWrite == c.colorWrite
                    && this.blend == c.blend
                    && this.blendSrc == c.blendSrc
                    && this.blendDst == c.blendDst
                    && this.blendAlphaSrc == c.blendAlphaSrc
                    && this.blendAlphaDst == c.blendAlphaDst
                    && this.alphaTest == c.alphaTest
                    && this.alphaRef == c.alphaRef
                    && this.cull == c.cull
                    && this.scissorEnabled == c.scissorEnabled
                    && (!this.scissorEnabled || this.scissorX==c.scissorX && this.scissorY==c.scissorY
                        && this.scissorW==c.scissorW && this.scissorH==c.scissorH)
                    && this.fogEnabled == c.fogEnabled
                    && this.fogColor == c.fogColor
                    && this.fogStart == c.fogStart
                    && this.fogEnd == c.fogEnd
                    && this.overlay == c.overlayEnabled
                    && this.shadow == (c.shadowVarying && dev.antimony.core.render.ShadowMap.valid());
        }
    }
}
