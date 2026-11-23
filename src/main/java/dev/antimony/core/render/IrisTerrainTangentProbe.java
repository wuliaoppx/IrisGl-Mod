package dev.antimony.core.render;

/** Tests the real installed Iris builder call and SNORM8 transport; no image equivalence claim. */
public final class IrisTerrainTangentProbe {
    public static void main(String[] args) {
        float[] source = new float[4 * MeshBuffer.STRIDE];
        float[][] vertices = {{0,0,0,0,0},{1,0,0,1,0},{1,0,1,1,1},{0,0,1,0,1}};
        for(int i=0;i<4;i++) {
            for(int j=0;j<5;j++) source[i*15+j] = vertices[i][j];
            source[i*15+12]=1;
        }
        float[] result = new float[16];
        check(IrisTerrainTangents.fill(source,0,result),"actual Iris tangent builder must load");
        for(int i=0;i<4;i++) near(result,i*4,new float[]{1,0,0,1});
        for(int i=0;i<4;i++) source[i*15+3] *= -1;
        check(IrisTerrainTangents.fill(source,0,result),"mirrored UV tangent");
        for(int i=0;i<4;i++) near(result,i*4,new float[]{-1,0,0,-1});
        System.out.println("IRIS TERRAIN TANGENT PROBE OK: actual Iris 18-float computeTangent, original quad tri0/1/2, per-vertex SNORM8, mirrored UV handedness");
    }
    private static void near(float[] actual,int o,float[] expected){for(int i=0;i<4;i++)check(Math.abs(actual[o+i]-expected[i])<.0001,"component "+i+" actual="+actual[o+i]+" expected="+expected[i]);}
    private static void check(boolean passed,String message){if(!passed)throw new AssertionError(message);}
}
