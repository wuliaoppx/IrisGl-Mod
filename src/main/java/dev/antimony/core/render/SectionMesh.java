package dev.antimony.core.render;

/** CPU geometry for one 16x16x16 chunk section, split by render layer. */
public final class SectionMesh {
    public final int sectionX;
    public final int sectionY;
    public final int sectionZ;
    long sourceRevision, sourceEpoch, materialGeneration;
    public final java.util.List<LightVoxel> lightVoxels = new java.util.ArrayList<>();
    public record LightVoxel(int x,int y,int z,int material) { }
    public final MeshBuffer solid = new MeshBuffer();
    public final MeshBuffer cutoutMipped = new MeshBuffer();
    public final MeshBuffer cutout = new MeshBuffer();
    public final MeshBuffer translucent = new MeshBuffer();

    public SectionMesh(int sectionX, int sectionY, int sectionZ) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
    }

    /**
     * Releases oversized backing arrays when this mesh is replaced or found
     * empty. Keeps idle sections at ~1 KB instead of up to 720 KB (three
     * 240 KB float arrays) - the ~333 MB retained-buffer problem.
     */
    public void recycle() {
        this.lightVoxels.clear();
        this.solid.clear();
        this.solid.trimToSize();
        this.cutoutMipped.clear();
        this.cutoutMipped.trimToSize();
        this.cutout.clear();
        this.cutout.trimToSize();
        this.translucent.clear();
        this.translucent.trimToSize();
    }

    public boolean isEmpty() {
        return this.lightVoxels.isEmpty() && this.solid.isEmpty() && this.cutoutMipped.isEmpty() && this.cutout.isEmpty() && this.translucent.isEmpty();
    }

    public int quadCount() {
        return this.solid.quadCount() + this.cutoutMipped.quadCount() + this.cutout.quadCount() + this.translucent.quadCount();
    }
}
