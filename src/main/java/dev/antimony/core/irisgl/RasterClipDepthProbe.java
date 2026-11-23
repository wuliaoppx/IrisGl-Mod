package dev.antimony.core.irisgl;

/** Numerical regression on the production triangle raster path; no Minecraft visual claim. */
public final class RasterClipDepthProbe {
    public static void main(String[] args) {
        IrisGlContext context = new IrisGlContext();
        context.framebuffer = new IrisGlFramebuffer(4, 4);
        context.framebuffer.clearDepth();
        context.viewportW = context.viewportH = 4;
        context.cull = false;
        float[] triangle = new float[3 * IrisGlContext.STRIDE];
        vertex(triangle, 0, -.75f, .75f, 0, 1, 1, 0, 0);
        vertex(triangle, 17, .75f, .75f, .5f, 2, 0, 1, 0);
        vertex(triangle, 34, -.75f, -.75f, 1, 4, 0, 0, 1);
        IrisGlRasterizer.rasterTriangle(context, triangle, 0, new TileRasterEngine.StateSnap(context), 0, 0, 3, 3);
        float depth = context.framebuffer.depth[5];
        check(Math.abs(depth - .75f) < .00001f, "actual screen-depth must be .75 not .642857: " + depth);
        int color = context.framebuffer.color[5];
        check(Math.abs(((color >>> 16) & 255) / 255f - 4f/7) < .005f, "smooth red perspective interpolation");
        check(Math.abs(((color >>> 8) & 255) / 255f - 2f/7) < .005f, "smooth green perspective interpolation");
        check(Math.abs((color & 255) / 255f - 1f/7) < .005f, "smooth blue perspective interpolation");
        System.out.println("PASS production raster: unequal w=1,2,4; affine depth=.75; smooth RGB=4/7,2/7,1/7");

        for (int plane = 0; plane < 6; plane++) {
            float[] source = {-0.2f,-0.2f,0,1,10, .2f,-.2f,0,1,20, 0,.2f,0,1,30};
            int axis = plane / 2;
            source[axis] = plane % 2 == 0 ? -2 : 2;
            float[] out = new float[HomogeneousClipper.MAX_VERTICES * 5], tmp = out.clone();
            int n = HomogeneousClipper.triangle(source, 0, 5, 10, 5, out, tmp);
            check(n == 4, "plane " + plane + " must clip positive-w triangle to quad");
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < 3; j++) check(Math.abs(out[i*5+j]) <= out[i*5+3] + .00001f, "all six homogeneous constraints");
                check(out[i*5+4] >= 10 && out[i*5+4] <= 30, "payload kept through clip");
            }
        }
        float[] rejected = {0,0,-2,1, 0,0,-3,1, 0,0,-4,1};
        check(HomogeneousClipper.triangle(rejected,0,4,8,4,new float[48],new float[48]) == 0, "all-near-outside rejected");
        rejected[0] = Float.NaN;
        check(HomogeneousClipper.triangle(rejected,0,4,8,4,new float[48],new float[48]) == 0, "nonfinite clip rejected");
        System.out.println("RASTER CLIP/DEPTH PROBE OK: six planes and positive-w near clipping, no game/GL claim");
    }

    private static void vertex(float[] t, int offset, float x, float y, float ndcZ, float w, float r, float g, float b) {
        t[offset] = x*w; t[offset+1] = y*w; t[offset+2] = ndcZ*w; t[offset+3] = w;
        t[offset+6] = r; t[offset+7] = g; t[offset+8] = b; t[offset+9] = 1;
    }
    private static void check(boolean passed, String text) { if (!passed) throw new AssertionError(text); }
}
