package dev.antimony.gl.glsl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CPU equivalent of FinalPassRenderer's copy passes; texture identities never change. */
public final class IrisRenderTargetHistory {
    private IrisRenderTargetHistory() { }

    public record Copy(int source, int destination) { }

    /** A missing binding may only use a uniquely known write, never arbitrary main-first ordering. */
    public static int uniqueWritten(int[] candidates, Set<Integer> written) {
        int result = 0;
        for (int candidate : candidates) if (candidate > 0 && written.contains(candidate)) {
            if (result != 0 && result != candidate) return 0;
            result = candidate;
        }
        return result;
    }

    public static final class Transaction {
        private final List<Copy> copies;
        private final Map<Integer, CpuImage> before = new HashMap<>();
        private boolean finished;

        public Transaction(List<Copy> copies, Map<Integer, CpuImage> images) {
            this(copies, images, new HashMap<>());
        }

        public Transaction(List<Copy> copies, Map<Integer, CpuImage> images, Map<Integer, CpuImage> snapshots) {
            this.copies = List.copyOf(copies);
            for (Copy copy : copies) {
                CpuImage destination = images.get(copy.destination());
                if (destination != null && !before.containsKey(copy.destination())) {
                    CpuImage snapshot = snapshots.get(copy.destination());
                    if (snapshot == null || !snapshot.matches(destination.width, destination.height)) {
                        snapshot = new CpuImage(destination.width, destination.height);
                        snapshots.put(copy.destination(), snapshot);
                    }
                    snapshot.copyFrom(destination);
                    before.put(copy.destination(), snapshot);
                }
            }
        }

        /** Validate every source before publishing any copy; allocations do not prove valid contents. */
        public boolean commit(Map<Integer, CpuImage> images, Set<Integer> valid) {
            if (finished) throw new IllegalStateException("History transaction already finished");
            List<CpuImage> sources = new ArrayList<>();
            for (Copy copy : copies) {
                CpuImage source = images.get(copy.source()), destination = images.get(copy.destination());
                if (copy.source() <= 0 || copy.destination() <= 0 || source == null || destination == null
                        || !valid.contains(copy.source()) || !destination.matches(source.width, source.height)) return false;
                sources.add(source);
            }
            // Match Iris' ordered copies, after the final screen has consumed the old history.
            for (int i = 0; i < copies.size(); i++) images.get(copies.get(i).destination()).copyFrom(sources.get(i));
            finished = true;
            return true;
        }

        public void rollback(Map<Integer, CpuImage> images) {
            if (finished) return;
            for (var entry : before.entrySet()) {
                CpuImage destination = images.get(entry.getKey());
                if (destination != null && destination.matches(entry.getValue().width, entry.getValue().height))
                    destination.copyFrom(entry.getValue());
            }
            finished = true;
        }
    }
}
