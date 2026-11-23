package dev.antimony.core.test;

import dev.antimony.core.render.MeshBuffer;
import dev.antimony.core.render.TerrainVoxelAttributes;
import java.lang.reflect.Field;

public final class TerrainVoxelAttributesProbe {
    public static void main(String[] args) throws Exception {
        var solid=TerrainVoxelAttributes.Stage.TERRAIN_SOLID;
        var cutout=TerrainVoxelAttributes.Stage.TERRAIN_CUTOUT_MIPPED;
        MeshBuffer mesh=new MeshBuffer();
        mesh.terrainBlock(11,1,2,3,solid);
        quad(mesh);
        mesh.terrainBlock(22,15,0,7,cutout);
        quad(mesh);
        mesh.finish();
        for(int i=0;i<8;i++){
            var vertex=mesh.terrainVoxelVertex(i);
            check(vertex.sourceVertex()==i&&vertex.firstQuadVertex()==(i%4==0),"original quad ID "+i);
            check(vertex.blockX()==(i<4?1:15)&&vertex.blockY()==(i<4?2:0)&&vertex.blockZ()==(i<4?3:7),"original block "+i);
            check(vertex.stage()==(i<4?solid:cutout),"original render layer "+i);
        }
        check(mesh.hasSolasVoxelInputs(),"complete source quads validate");
        float[] mid=TerrainVoxelAttributes.midBlock(mesh.terrainVoxelVertex(0),1.51f,2.53f,1.5f);
        check(mid[0]==0&&mid[1]==-1&&mid[2]==-128,"Iris truncation and signed-byte wrap");
        mesh.clear();mesh.terrainMaterial(33);quad(mesh);mesh.finish();
        boolean missing=false;try{mesh.terrainVoxelVertex(0);}catch(IllegalStateException expected){missing=true;}
        check(missing,"material-only reuse cannot inherit voxel context");
        check(!mesh.hasSolasVoxelInputs(),"cached valid result cannot survive clear/material-only rebuild");
        mesh.clear();mesh.terrainBlock(11,1,2,3,solid);quad(mesh);mesh.finish();
        check(mesh.hasSolasVoxelInputs(),"rebuilt valid context restores validation");
        mesh.addVertex(0,0,0);mesh.finish();check(!mesh.hasSolasVoxelInputs(),"cached result invalidates when a vertex is appended");
        mesh.clear();mesh.trimToSize();
        Field blocks=MeshBuffer.class.getDeclaredField("terrainBlocks");blocks.setAccessible(true);
        check(blocks.get(mesh)==null,"voxel context released");
        System.out.println("TerrainVoxelAttributesProbe OK: source IDs, quad grouping, block centers, layer classification, midBlock byte packing, reuse isolation");
    }
    private static void quad(MeshBuffer mesh){for(int i=0;i<4;i++)mesh.addVertex(i,i,0).setUv(i,0);}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}
