package dev.antimony.core.render;

import dev.antimony.AntimonyMod;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongConsumer;
import net.minecraft.core.SectionPos;

/** Receives the exact vanilla/Sodium section dependency notifications. Invalidation
 * happens at notification time; queue draining is scheduling only, never a new dirty event. */
public final class ChunkUpdateHooks {
    private static final ConcurrentLinkedQueue<Long> DIRTY_SECTIONS = new ConcurrentLinkedQueue<>();
    private static final Set<Long> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Long> REPLACED_CHUNKS = new ConcurrentLinkedQueue<>();
    private static final int MAX_REMESHES_PER_FRAME = 3;
    private static int budgetLogCooldown;

    private ChunkUpdateHooks() { }

    public static void onSectionDirty(int x, int y, int z) {
        long key = SectionPos.asLong(x, y, z);
        synchronized (PENDING) {
            MeshBuildPool.invalidate(key);
            if (PENDING.add(key)) DIRTY_SECTIONS.add(key);
        }
    }

    public static void onChunkReplaced(int chunkX, int chunkZ) {
        REPLACED_CHUNKS.add(SectionPos.asLong(chunkX, 0, chunkZ));
    }

    public static void drain(WorldRenderer world) {
        Long chunkKey;
        while ((chunkKey = REPLACED_CHUNKS.poll()) != null)
            world.dropChunk(SectionPos.x(chunkKey), SectionPos.z(chunkKey));
        var player = net.minecraft.client.Minecraft.getInstance().player;
        int x = player == null ? 0 : player.getBlockX() >> 4;
        int y = player == null ? 0 : player.getBlockY() >> 4;
        int z = player == null ? 0 : player.getBlockZ() >> 4;
        int selected = drainNearest(key -> world.remeshSection(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key)), x, y, z);
        int remaining = pendingCount();
        if (remaining > 50 && budgetLogCooldown-- <= 0) {
            budgetLogCooldown = 40;
            AntimonyMod.LOG.info("[Antimony] chunk updates: {} scheduled this frame, {} still queued (notifications invalidated once; shadow inputs have priority)", selected, remaining);
        }
    }

    /** Production nearest-first selector, separated from Minecraft position lookup for regression. */
    static int drainNearest(LongConsumer schedule, int x, int y, int z) {
        java.util.PriorityQueue<Long> heap;
        synchronized (PENDING) {
            int count = Math.min(MAX_REMESHES_PER_FRAME, PENDING.size());
            if (count == 0) return 0;
            heap = new java.util.PriorityQueue<>(count,
                    (a, b) -> Long.compare(dist2(b, x, y, z), dist2(a, x, y, z)));
            int scan = DIRTY_SECTIONS.size();
            Long key;
            for (int i = 0; i < scan && (key = DIRTY_SECTIONS.poll()) != null; i++) {
                if (!PENDING.remove(key)) continue;
                if (heap.size() < count) heap.add(key);
                else if (dist2(key, x, y, z) < dist2(heap.peek(), x, y, z)) {
                    long evicted = heap.poll();
                    DIRTY_SECTIONS.add(evicted); PENDING.add(evicted); heap.add(key);
                } else { DIRTY_SECTIONS.add(key); PENDING.add(key); }
            }
        }
        int count = heap.size();
        while (!heap.isEmpty()) schedule.accept(heap.poll());
        return count;
    }

    private static long dist2(long key, int x, int y, int z) {
        long dx = (long) SectionPos.x(key) - x, dy = (long) SectionPos.y(key) - y, dz = (long) SectionPos.z(key) - z;
        return dx * dx + dy * dy + dz * dz;
    }

    static boolean isPending(long key) { return PENDING.contains(key); }

    /** A successful submission consumes only the revision that its snapshot captured.
     * If a block/light notification arrived later, it stays pending and makes the job stale. */
    static boolean consumeCurrent(long key, long capturedRevision) {
        synchronized (PENDING) {
            if (capturedRevision < 0 || MeshBuildPool.revision(key) != capturedRevision) return false;
            boolean consumed = PENDING.remove(key);
            if (consumed) DIRTY_SECTIONS.remove(key);
            return consumed;
        }
    }

    public static int pendingCount() { return PENDING.size(); }

    public static void reset() {
        synchronized (PENDING) { DIRTY_SECTIONS.clear(); PENDING.clear(); }
        REPLACED_CHUNKS.clear();
    }
}
