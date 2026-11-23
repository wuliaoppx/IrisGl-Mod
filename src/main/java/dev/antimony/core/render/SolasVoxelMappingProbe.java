package dev.antimony.core.render;

public final class SolasVoxelMappingProbe {
    public static void main(String[] args) {
        var vertex = new TerrainVoxelAttributes.Vertex(0, 1, 2, 3,
                TerrainVoxelAttributes.Stage.TERRAIN_SOLID);
        var mapped = SolasVoxelMapping.map(vertex, 2, -1, 3, 1.25f, 2.25f, 3.75f,
                10005, new float[]{33.25f, -15.5f, 48.75f}, 192, 96, 192);
        check(mapped != null, "in-volume terrain material maps");
        check(mapped.x() == 96 && mapped.y() == 50 && mapped.z() == 99, "camera-relative voxel coordinates");
        check(mapped.material() == 4, "Solas material id subtracts reserved 10000 and one");

        check(SolasVoxelMapping.map(new TerrainVoxelAttributes.Vertex(1, 1, 2, 3,
                TerrainVoxelAttributes.Stage.TERRAIN_SOLID), 2, -1, 3, 1.25f, 2.25f, 3.75f,
                10005, new float[]{33.25f, -15.5f, 48.75f}, 192, 96, 192) == null,
                "only original quad vertex zero writes");
        check(SolasVoxelMapping.map(new TerrainVoxelAttributes.Vertex(0, 1, 2, 3,
                TerrainVoxelAttributes.Stage.OTHER), 2, -1, 3, 1.25f, 2.25f, 3.75f,
                10005, new float[]{33.25f, -15.5f, 48.75f}, 192, 96, 192) == null,
                "unsupported render stage cannot write");
        check(SolasVoxelMapping.map(vertex, 2, -1, 3, 1.25f, 2.25f, 3.75f,
                10001, new float[]{33.25f, -15.5f, 48.75f}, 192, 96, 192) == null,
                "reserved no-voxel material id is skipped");
        check(SolasVoxelMapping.map(vertex, 2, -1, 3, 1.25f, 2.25f, 3.75f,
                10005, new float[]{160.0f, -15.5f, 48.75f}, 192, 96, 192) == null,
                "out-of-volume world position is skipped");

        var edge = SolasVoxelMapping.map(new TerrainVoxelAttributes.Vertex(0, 0, 0, 0,
                TerrainVoxelAttributes.Stage.TERRAIN_TRANSLUCENT), 0, 0, 0, 0.5f, 0.5f, 0.5f,
                -1, new float[]{0.25f, 0.5f, 0.75f}, 192, 96, 192);
        check(edge != null && edge.material() == 238, "int-to-uint conversion and R8UI truncation are preserved");
        System.out.println("SolasVoxelMappingProbe OK: Iris section transforms, midBlock center, camera fract, stage/material filters, quad source ID, R8UI coordinates");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
