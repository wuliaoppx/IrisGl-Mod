package dev.antimony.core.render;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** Compare the production section-range conversion with vanilla's individual-block halo. */
public final class SectionDirtyRangeProbe {
    public static void main(String[] args) {
        Random random = new Random(0x271127);
        for (int sample = 0; sample < 2000; sample++) {
            int x = random.nextInt(200) - 100, y = random.nextInt(200) - 100, z = random.nextInt(200) - 100;
            int x1 = x + random.nextInt(20), y1 = y + random.nextInt(20), z1 = z + random.nextInt(20);
            Set<String> expected = new HashSet<>(), actual = new HashSet<>();
            for (int bz = z - 1; bz <= z1 + 1; bz++) for (int bx = x - 1; bx <= x1 + 1; bx++) for (int by = y - 1; by <= y1 + 1; by++)
                expected.add(key(bx >> 4, by >> 4, bz >> 4));
            SectionDirtyRange.blocks(x, y, z, x1, y1, z1, (sx, sy, sz) -> {
                if (!actual.add(key(sx, sy, sz))) throw new AssertionError("Duplicate section visit");
            });
            if (!actual.equals(expected)) throw new AssertionError("Vanilla block halo mismatch " + sample);
        }
        Set<String> neighbors = new HashSet<>();
        SectionDirtyRange.neighbors(-2, 7, 1, (x, y, z) -> neighbors.add(key(x, y, z)));
        if (neighbors.size() != 27 || !neighbors.contains(key(-3, 6, 0)) || !neighbors.contains(key(-1, 8, 2)))
            throw new AssertionError("Missing diagonal neighbor");
        SectionDirtyRange.blocks(1, 1, 1, 0, 0, 0, (x, y, z) -> { throw new AssertionError("Reversed range"); });
        System.out.println("PASS dirty sections: 2000 vanilla one-block halos, negative coordinates and boundaries, no duplicate sections, all 27 neighbors");
    }
    private static String key(int x, int y, int z) { return x + "," + y + "," + z; }
}
