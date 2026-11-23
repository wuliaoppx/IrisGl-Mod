package dev.antimony.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.antimony.AntimonyMod;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Builds section meshes off the render thread.
 *
 * <p>The render thread takes an immutable {@link MeshSnapshot} (cheap copy of
 * the 27 surrounding {@code PalettedContainer}s) and hands it to a worker;
 * the worker tessellates into private {@link MeshBuffer}s, and the finished
 * {@link SectionMesh} is picked up by the render thread on a later frame. This
 * is the same shape as vanilla's own pipeline (SectionRenderDispatcher
 * compiles sections on worker threads against a frozen RenderChunkRegion), so
 * the block model renderer is used exactly the way it is designed to be used.</p>
 *
 * <p>Before this change every section was tessellated synchronously between
 * GameRenderer.render and the rasterizer flush, which serialized the two
 * biggest CPU costs of a software renderer on one thread.</p>
 */
public final class MeshBuildPool {
    /** At most this many snapshots in flight: unbounded work inflates latency for moving players. */
    private static final int MAX_IN_FLIGHT = 12;
    /** -Dantimony.debug.threads: report off-thread mesh builds with the raster stats. */
    private static final boolean THREAD_DEBUG = dev.antimony.AntimonyMod.flag("antimony.debug.threads");

    private static final AtomicInteger POOL_SIZE = new AtomicInteger(-1);
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    // Render thread only: snapshots waiting for a free worker.
    private static final java.util.ArrayDeque<Job> QUEUE = new java.util.ArrayDeque<>();
    private static int inFlight;

    // Worker writes / render thread reads (guarded by PICKUP_LOCK).
    private static final Object PICKUP_LOCK = new Object();
    private static final List<Completed> READY = new ArrayList<>();
    /** Keys with a finished-but-not-yet-picked-up mesh (also "in flight" marker). */
    private static final ConcurrentMap<Long, Job> ACTIVE = new ConcurrentHashMap<>();
    private static final java.util.Set<Long> FAILED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentMap<Long,Long> REVISIONS = new ConcurrentHashMap<>();
    private static volatile long epoch;

    private static long buildsSubmitted;
    private static long buildsCompleted;

    private MeshBuildPool() {
    }

    private static int poolSize() {
        int size = POOL_SIZE.get();
        if (size < 0) {
            size = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 2, 6));
            POOL_SIZE.set(size);
        }
        return size;
    }

    /**
     * Starts the (lazily created, daemon) worker threads. Called once per
     * frame from the render thread; cheap after the first call.
     */
    private static void ensureStarted() {
        if (STARTED.compareAndSet(false, true)) {
            for (int i = 0; i < poolSize(); i++) {
                Thread t = new Thread(MeshBuildPool::workerLoop, "antimony-mesher-" + i);
                t.setDaemon(true);
                t.start();
            }
            if (THREAD_DEBUG) {
                AntimonyMod.LOG.info("[Antimony] mesh pool: {} worker threads", poolSize());
            }
        }
    }

    /**
     * Queues a section build. Returns false when the in-flight window is full
     * (caller retries next frame); true when the job was accepted.
     */
    static boolean submit(MeshSnapshot snapshot, long key) {
        ensureStarted();
        synchronized (QUEUE) {
            if (inFlight >= MAX_IN_FLIGHT || ACTIVE.containsKey(key)) {
                return false;
            }
            inFlight++;
            buildsSubmitted++;
            Job job = new Job(snapshot, key, IrisTerrainMaterials.snapshot(), epoch, revision(key));
            ACTIVE.put(key, job);
            QUEUE.addLast(job);
            QUEUE.notify();
        }
        return true;
    }

    private static void workerLoop() {
        while (true) {
            Job job;
            synchronized (QUEUE) {
                job = QUEUE.pollFirst();
                if (job == null) {
                    try {
                        QUEUE.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                    job = QUEUE.pollFirst();
                    if (job == null) {
                        continue;
                    }
                }
            }
            SectionMesh mesh = null;
            Throwable failure = null;
            try {
                mesh = build(job.snapshot, job.materials);
            } catch (Throwable t) {
                failure = t;
                AntimonyMod.LOG.error("[Antimony] async mesh build failed for section {},{},{}",
                        job.snapshot.sectionX(), job.snapshot.sectionY(), job.snapshot.sectionZ(), t);
            } finally {
                if (mesh == null) {
                    mesh = new SectionMesh(job.snapshot.sectionX(), job.snapshot.sectionY(), job.snapshot.sectionZ());
                }
                mesh.sourceRevision=job.revision;mesh.sourceEpoch=job.epoch;mesh.materialGeneration=job.materials.generation();
                synchronized (PICKUP_LOCK) {
                    READY.add(new Completed(mesh, job, failure == null));
                    buildsCompleted++;
                }
                synchronized (QUEUE) {
                    inFlight--;
                    QUEUE.notifyAll();
                }
            }
        }
    }

    /**
     * Takes every mesh the workers finished since the last call. Render
     * thread only; costs a list swap under a short lock.
     */
    static List<SectionMesh> pickup() {
        synchronized (PICKUP_LOCK) {
            if (READY.isEmpty()) {
                return List.of();
            }
            List<SectionMesh> out = new ArrayList<>(READY.size());
            long generation = IrisTerrainMaterials.snapshot().generation();
            for (Completed completed : READY) {
                Job job=completed.job;
                ACTIVE.remove(job.key,job);
                if (job.materials.generation()==generation && job.epoch==epoch && job.revision==revision(job.key)) {
                    if (completed.success) {FAILED.remove(job.key);out.add(completed.mesh);}
                    else {FAILED.add(job.key);completed.mesh.recycle();}
                } else completed.mesh.recycle();
            }
            READY.clear();
            return out;
        }
    }

    /** Queued + in-flight job count (for the F3 debug line and logging). */
    static int outstanding() {
        synchronized (QUEUE) {
            return inFlight;
        }
    }

    /** True while the given key's snapshot is queued or being tessellated. */
    static boolean inFlight(long key) {
        return ACTIVE.containsKey(key);
    }

    static long submittedRevision(long key) {
        Job job = ACTIVE.get(key);
        return job == null ? -1L : job.revision;
    }

    static boolean failed(long key) { return FAILED.contains(key); }
    static long revision(long key){return REVISIONS.getOrDefault(key,0L);}
    static void invalidate(long key){REVISIONS.merge(key,1L,Long::sum);}
    static boolean current(SectionMesh mesh,long key){return mesh!=null&&mesh.sourceEpoch==epoch
        &&mesh.sourceRevision==revision(key)&&mesh.materialGeneration==IrisTerrainMaterials.snapshot().generation();}
    static void invalidateChunk(int cx,int cz){for(long key:ACTIVE.keySet())if(SectionPos.x(key)==cx&&SectionPos.z(key)==cz)invalidate(key);}

    static void reset() {
        synchronized (QUEUE) {
            epoch++;
            inFlight -= QUEUE.size();
            QUEUE.clear();
            // Running jobs retain the previous epoch and are discarded on pickup.
        }
        pickup();
        ACTIVE.clear();
        FAILED.clear();
        REVISIONS.clear();
        buildsSubmitted = 0;
        buildsCompleted = 0;
    }

    static long submittedCount() {
        return buildsSubmitted;
    }

    static long completedCount() {
        return buildsCompleted;
    }

    private record Completed(SectionMesh mesh, Job job, boolean success) { }

    private static final class Job {
        final MeshSnapshot snapshot;
        final long key;
        final IrisTerrainMaterials.Snapshot materials;
        final long epoch,revision;
        Job(MeshSnapshot snapshot,long key,IrisTerrainMaterials.Snapshot materials,long epoch,long revision){
            this.snapshot=snapshot;this.key=key;this.materials=materials;this.epoch=epoch;this.revision=revision;
        }
    }

    /**
     * Tessellates one section against the frozen snapshot. Runs on a worker
     * thread; same vanilla model pipeline as the old synchronous path, fed a
     * {@code BlockAndTintGetter} snapshot instead of the live chunk.
     */
    private static SectionMesh build(MeshSnapshot snapshot, IrisTerrainMaterials.Snapshot materials) {
        if (snapshot.centerEmpty()) {
            return null;
        }
        SectionMesh mesh = new SectionMesh(snapshot.sectionX(), snapshot.sectionY(), snapshot.sectionZ());
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        PoseStack pose = new PoseStack();
        RandomSource random = RandomSource.create(SectionPos.asLong(
                snapshot.sectionX(), snapshot.sectionY(), snapshot.sectionZ()));
        BlockPos origin = snapshot.origin();
        boolean lightVoxels=materials.enabled()&&IrisTerrainMaterials.voxelizeLightBlocks();

        for (BlockPos pos : (Iterable<BlockPos>) () -> BlockPos.betweenClosed(origin, origin.offset(15, 15, 15)).iterator()) {
            BlockState state = snapshot.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if(lightVoxels&&state.getBlock() instanceof net.minecraft.world.level.block.LightBlock)
                mesh.lightVoxels.add(new SectionMesh.LightVoxel(pos.getX()&15,pos.getY()&15,pos.getZ()&15,materials.materialId(state)));
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                RenderType layer = ItemBlockRenderTypes.getRenderLayer(fluid);
                MeshBuffer buffer = bufferFor(mesh, layer);
                if (materials.enabled())
                    buffer.terrainBlock(materials.materialId(fluid.createLegacyBlock()),SectionPos.sectionRelative(pos.getX()),
                        SectionPos.sectionRelative(pos.getY()),SectionPos.sectionRelative(pos.getZ()),terrainStage(layer));
                dispatcher.renderLiquid(pos, snapshot, buffer, state, fluid);
            }
            if (state.getRenderShape() == RenderShape.MODEL) {
                RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);
                MeshBuffer buffer = bufferFor(mesh, layer);
                if (materials.enabled())
                    buffer.terrainBlock(materials.materialId(state),SectionPos.sectionRelative(pos.getX()),
                        SectionPos.sectionRelative(pos.getY()),SectionPos.sectionRelative(pos.getZ()),terrainStage(layer));
                pose.pushPose();
                pose.translate(
                        SectionPos.sectionRelative(pos.getX()),
                        SectionPos.sectionRelative(pos.getY()),
                        SectionPos.sectionRelative(pos.getZ()));
                dispatcher.renderBatched(state, pos, snapshot, pose, buffer, true, random);
                pose.popPose();
            }
        }
        mesh.solid.finish();
        mesh.cutoutMipped.finish();
        mesh.cutout.finish();
        mesh.translucent.finish();
        return mesh.isEmpty() ? null : mesh;
    }

    private static TerrainVoxelAttributes.Stage terrainStage(RenderType type) {
        if(type==RenderType.solid())return TerrainVoxelAttributes.Stage.TERRAIN_SOLID;
        if(type==RenderType.cutoutMipped())return TerrainVoxelAttributes.Stage.TERRAIN_CUTOUT_MIPPED;
        if(type==RenderType.cutout())return TerrainVoxelAttributes.Stage.TERRAIN_CUTOUT;
        if(type==RenderType.translucent()||type==RenderType.translucentMovingBlock())return TerrainVoxelAttributes.Stage.TERRAIN_TRANSLUCENT;
        return TerrainVoxelAttributes.Stage.OTHER;
    }
    private static MeshBuffer bufferFor(SectionMesh mesh, RenderType type) {
        if (type == RenderType.translucent() || type == RenderType.translucentMovingBlock()) {
            return mesh.translucent;
        }
        if (type == RenderType.cutoutMipped()) return mesh.cutoutMipped;
        if (type == RenderType.cutout()) return mesh.cutout;
        return mesh.solid;
    }
}
