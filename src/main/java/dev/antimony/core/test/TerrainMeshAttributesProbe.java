package dev.antimony.core.test;

import dev.antimony.core.irisgl.IrisGlContext;
import dev.antimony.core.render.IrisTerrainMaterials;
import dev.antimony.core.render.MeshBuffer;
import java.lang.reflect.Field;
import java.util.HashMap;

/** No game or GL startup. Synthetic IDs here test transport, not pack-ID acquisition. */
public final class TerrainMeshAttributesProbe {
    public static void main(String[] args) throws Exception {
        MeshBuffer mesh = new MeshBuffer();
        mesh.terrainMaterial(10101);
        quad(mesh, new float[][]{{0, 0}, {2, 0}, {4, 4}, {0, 8}});
        mesh.terrainMaterial(20202); // flushes last vertex with the old material
        quad(mesh, new float[][]{{10, 20}, {14, 22}, {12, 28}, {16, 26}});
        mesh.finish();
        check(MeshBuffer.STRIDE == 15 && mesh.vertexCount() == 8, "unchanged vertex layout");
        for (int i = 0; i < 8; i++) {
            check(mesh.terrainMaterialId(i) == (i < 4 ? 10101 : 20202), "material boundary " + i);
            close(mesh.terrainMidU(i), i < 4 ? 1.5F : 13F, "quad U " + i);
            close(mesh.terrainMidV(i), i < 4 ? 3F : 24F, "quad V " + i);
        }
        MeshBuffer ordinary = new MeshBuffer();
        quad(ordinary, new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}});
        ordinary.finish();
        check(!ordinary.hasTerrainAttributes(), "ordinary mesh disabled");
        check(field(ordinary, "terrainMaterials") == null && field(ordinary, "terrainMidUvs") == null,
                "ordinary mesh has no sidecar allocation");
        mesh.clear();
        quad(mesh, new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}});
        mesh.finish();
        check(!mesh.hasTerrainAttributes(), "reused mesh cleared");
        mesh.clear();
        mesh.trimToSize();
        check(field(mesh, "terrainMaterials") == null, "recycle releases sidecar");

        IrisGlContext context = new IrisGlContext();
        context.bgnpolygon();
        context.terrainVertexAttributes(10101, 1.5F, 3F);
        context.vertex(0, 0, 0);
        context.terrainVertexAttributes(20202, 13F, 24F);
        context.vertex(1, 1, 1);
        check(context.terrainMaterialId(0) == 10101 && context.terrainMaterialId(1) == 20202,
                "context stores per vertex, not last setter");
        close(context.terrainMidU(0), 1.5F, "context first U");
        close(context.terrainMidV(1), 24F, "context second V");
        context.clearTerrainVertexAttributes();
        context.vertex(2, 2, 2);
        check(!context.terrainVertexAttributesEnabled(2), "ordinary context vertex isolated");
        context.bgnpolygon();
        context.vertex(3, 3, 3);
        check(!context.terrainVertexAttributesEnabled(0) && context.terrainMaterialId(0) == -1,
                "ordinary next primitive overwrites stale slot");
        check(IrisGlContext.NO_BLOCK_ENTITY_ID == 0, "actual Iris non-BE sentinel");
        IrisGlContext plain = new IrisGlContext();
        plain.bgnpolygon();
        plain.vertex(0, 0, 0);
        check(field(plain, "terrainPresent") == null, "ordinary context no allocation");

        HashMap<net.minecraft.world.level.block.state.BlockState, Integer> source = new HashMap<>();
        IrisTerrainMaterials.Snapshot old = new IrisTerrainMaterials.Snapshot(1, true, source);
        check(old.ids().isEmpty() && old.generation() == 1 && old.enabled(), "snapshot identity");
        boolean immutable = false;
        try { old.ids().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "snapshot detached immutable map");
        System.out.println("TerrainMeshAttributesProbe OK: quad-four-UV mean; two materials; sidecar isolation/recycle; context staging; non-BE=0; immutable snapshot");
    }

    private static void quad(MeshBuffer mesh, float[][] uvs) {
        for (int i = 0; i < 4; i++) mesh.addVertex(i, i, 0).setUv(uvs[i][0], uvs[i][1]);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void close(float actual, float expected, String label) {
        check(Math.abs(actual - expected) < 0.00001F, label + ": " + actual);
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
