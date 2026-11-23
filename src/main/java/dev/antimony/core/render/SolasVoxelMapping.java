package dev.antimony.core.render;

/** Scalar diagnostic for the identity-matrix case. Production executes the complete shadow source. */
public final class SolasVoxelMapping {
    private SolasVoxelMapping() { }

    public record Voxel(int x, int y, int z, int material) { }

    /**
     * Mirrors lib/vx/voxelization.glsl after the shadow model-view/inverse pair
     * cancels. The input position is the raw section-local source vertex, while
     * the sidecar retains the original quad's block center and draw-local ID.
     */
    public static Voxel map(TerrainVoxelAttributes.Vertex vertex, int sectionX, int sectionY, int sectionZ,
                            float localX, float localY, float localZ, int materialId,
                            float[] cameraPosition, int width, int height, int depth) {
        if (vertex == null || cameraPosition == null || cameraPosition.length != 3)
            throw new IllegalArgumentException("Missing Solas voxel vertex or camera position");
        if (width < 1 || height < 1 || depth < 1)
            throw new IllegalArgumentException("Invalid Solas voxel dimensions");
        for (float coordinate : cameraPosition)
            if (!Float.isFinite(coordinate)) throw new IllegalArgumentException("Nonfinite Solas camera position");
        if (!Float.isFinite(localX) || !Float.isFinite(localY) || !Float.isFinite(localZ))
            throw new IllegalArgumentException("Nonfinite Solas source vertex");
        if (!vertex.firstQuadVertex() || !voxelStage(vertex.stage())) return null;

        // This is Solas' int(mc_Entity.x - 10000), followed by id != 1 and max(id - 1, 1).
        int id = (int) ((float) materialId - 10000.0f);
        if (id == 1) return null;
        int candidate=id-1;
        int voxelMaterial=(Integer.compareUnsigned(candidate,1)>0?candidate:1)&255;

        float[] midBlock = TerrainVoxelAttributes.midBlock(vertex, localX, localY, localZ);
        float modelX = localX + midBlock[0] / 64.0f;
        float modelY = localY + midBlock[1] / 64.0f;
        float modelZ = localZ + midBlock[2] / 64.0f;

        float cameraX = cameraPosition[0], cameraY = cameraPosition[1], cameraZ = cameraPosition[2];
        // Iris' chunk offset is section origin minus the unshifted camera. The
        // shadow view and its inverse cancel around this camera-relative point.
        float shadowX = sectionX * 16.0f + localX - cameraX + (modelX - localX);
        float shadowY = sectionY * 16.0f + localY - cameraY + (modelY - localY);
        float shadowZ = sectionZ * 16.0f + localZ - cameraZ + (modelZ - localZ);
        float voxelX = shadowX + fract(cameraX) + width * 0.5f;
        float voxelY = shadowY + fract(cameraY) + height * 0.5f;
        float voxelZ = shadowZ + fract(cameraZ) + depth * 0.5f;
        if (!(voxelX >= 0.0f && voxelX <= width && voxelY >= 0.0f && voxelY <= height
                && voxelZ >= 0.0f && voxelZ <= depth)) return null;

        int x = (int) voxelX, y = (int) voxelY, z = (int) voxelZ;
        // The GLSL bounds test accepts the upper edge, but imageStore at exactly
        // width/height/depth is outside the declared storage and writes nothing.
        if (x >= width || y >= height || z >= depth) return null;
        return new Voxel(x, y, z, voxelMaterial);
    }


    private static float fract(float value) {
        return value - (float) Math.floor(value);
    }

    private static boolean voxelStage(TerrainVoxelAttributes.Stage stage) {
        return switch (stage) {
            case TERRAIN_SOLID, TERRAIN_CUTOUT_MIPPED, TERRAIN_CUTOUT, TERRAIN_TRANSLUCENT -> true;
            case OTHER -> false;
        };
    }
}
