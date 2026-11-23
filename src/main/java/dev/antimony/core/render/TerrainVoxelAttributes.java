package dev.antimony.core.render;

/** Original section-local block context retained while vanilla tessellates. Not a voxel producer receipt. */
public final class TerrainVoxelAttributes {
    private TerrainVoxelAttributes() { }
    /** Names match Iris WorldRenderingPhase; resolve actual ordinals from Iris at frame setup. */
    public enum Stage { TERRAIN_SOLID, TERRAIN_CUTOUT_MIPPED, TERRAIN_CUTOUT, TERRAIN_TRANSLUCENT, OTHER }
    public record Vertex(int sourceVertex,int blockX,int blockY,int blockZ,Stage stage) {
        public float centerX(){return blockX+.5f;}public float centerY(){return blockY+.5f;}public float centerZ(){return blockZ+.5f;}
        public boolean firstQuadVertex(){return (sourceVertex&3)==0;}
    }
    public static int packBlock(int x,int y,int z){
        if(x<0||x>15||y<0||y>15||z<0||z>15)throw new IllegalArgumentException("Block context must be section local");
        return x|(y<<4)|(z<<8);
    }
    public static Vertex vertex(int sourceVertex,int packed,Stage stage){
        if(sourceVertex<0||packed<0||packed>0xfff)throw new IllegalArgumentException("Invalid original terrain vertex context");
        return new Vertex(sourceVertex,packed&15,(packed>>>4)&15,(packed>>>8)&15,java.util.Objects.requireNonNull(stage));
    }
    /** Iris packMidBlock: truncating cast, byte wrap, then signed-byte vertex attribute decoding. */
    public static float[] midBlock(Vertex vertex,float x,float y,float z){
        return new float[]{signedMidBlock(vertex.centerX()-x),signedMidBlock(vertex.centerY()-y),signedMidBlock(vertex.centerZ()-z)};
    }
    private static float signedMidBlock(float delta){
        if(!Float.isFinite(delta))throw new IllegalArgumentException("Nonfinite terrain position");
        return (byte)((int)(delta*64.0f)&255);
    }
}
