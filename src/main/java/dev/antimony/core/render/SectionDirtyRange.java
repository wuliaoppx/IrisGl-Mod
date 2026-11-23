package dev.antimony.core.render;

/** The same one-block halo as LevelRenderer.setBlocksDirty, visited once per section. */
public final class SectionDirtyRange {
    private SectionDirtyRange() { }
    @FunctionalInterface public interface SectionConsumer { void accept(int x, int y, int z); }

    public static void blocks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                              SectionConsumer output) {
        if (minX > maxX || minY > maxY || minZ > maxZ) return;
        // Widen before adding the halo; floor division is required for negative coordinates.
        int x0 = (int)Math.floorDiv((long)minX - 1, 16), x1 = (int)Math.floorDiv((long)maxX + 1, 16);
        int y0 = (int)Math.floorDiv((long)minY - 1, 16), y1 = (int)Math.floorDiv((long)maxY + 1, 16);
        int z0 = (int)Math.floorDiv((long)minZ - 1, 16), z1 = (int)Math.floorDiv((long)maxZ + 1, 16);
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++)
            output.accept(x, y, z);
    }

    public static void neighbors(int x, int y, int z, SectionConsumer output) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
            output.accept(x + dx, y + dy, z + dz);
    }
}
