package dev.antimony.core.render;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.SectionPos;

/** Exercises the production notification queue, selector, revision checks and scheduling. */
public final class SectionUpdateQueueProbe {
    public static void main(String[] args) {
        ChunkUpdateHooks.reset();
        WorldRenderer world = new WorldRenderer();
        Map<Long, Long> revisions = new HashMap<>();
        SectionDirtyRange.neighbors(-1, 4, -2, (x, y, z) -> {
            long key = SectionPos.asLong(x, y, z);
            ChunkUpdateHooks.onSectionDirty(x, y, z);
            ChunkUpdateHooks.onSectionDirty(x, y, z);
            revisions.put(key, MeshBuildPool.revision(key));
        });
        check(ChunkUpdateHooks.pendingCount() == 27, "duplicate notifications coalesce to the complete vanilla halo");
        Set<Long> scheduled = new HashSet<>();
        for (int frame = 0; frame < 9; frame++) {
            int count = ChunkUpdateHooks.drainNearest(key -> {
                check(scheduled.add(key), "each dirty section scheduled once");
                world.scheduleRemesh(key);
            }, -1, 4, -2);
            check(count == 3, "existing per-frame scheduling budget retained");
            for (var entry : revisions.entrySet())
                check(MeshBuildPool.revision(entry.getKey()) == entry.getValue(), "draining/scheduling does not invalidate the section or neighbours again");
        }
        check(ChunkUpdateHooks.pendingCount() == 0 && scheduled.equals(revisions.keySet()), "finite dirty batch converges with no lost halo");
        for (long key : scheduled) check(world.scheduledForRebuild(key), "production world schedules the exact section");
        long required = SectionPos.asLong(999, 5, 999);
        for (int i = 0; i < 20_000; i++) ChunkUpdateHooks.onSectionDirty(i - 10_000, 0, 0);
        ChunkUpdateHooks.onSectionDirty(999, 5, 999);
        long captured = MeshBuildPool.revision(required);
        check(ChunkUpdateHooks.consumeCurrent(required, captured), "current shadow input can consume its mark ahead of unrelated 20k backlog");
        world.scheduleRemesh(required);
        check(!ChunkUpdateHooks.isPending(required) && ChunkUpdateHooks.pendingCount() == 20_000,
                "priority acknowledgement leaves unrelated notifications pending");
        int selected = ChunkUpdateHooks.drainNearest(world::scheduleRemesh, 0, 0, 0);
        check(selected == 3 && !ChunkUpdateHooks.isPending(required), "consumed queue entry cannot resurrect during normal draining");
        ChunkUpdateHooks.onSectionDirty(999, 5, 999);
        long newRevision = MeshBuildPool.revision(required);
        check(newRevision > captured && !ChunkUpdateHooks.consumeCurrent(required, captured), "late block/light change rejects an older snapshot acknowledgement");
        check(ChunkUpdateHooks.isPending(required), "late change is not lost");
        check(ChunkUpdateHooks.consumeCurrent(required, newRevision), "fresh rebuild consumes the latest revision");
        check(MeshBuildPool.revision(required) == newRevision, "consumption is not another invalidation");
        ChunkUpdateHooks.reset();
        System.out.println("PASS production dirty queue: 27-section vanilla halo/dedup/budget/convergence; scheduling never re-invalidates neighbours; current shadow input priority through 20k backlog; later changes preserved by revision check");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
