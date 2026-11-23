package dev.antimony.core.test;

import dev.antimony.core.irisgl.IrisGl;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Offline proof of the back-face culling direction against vanilla's baked
 * quad winding (FaceInfo) and our flipped-Y rasterizer, plus a sanity check of
 * the GUI 3D item light matrix (vanilla-exact JOML composition from
 * GlStateManager.setupGui3DDiffuseLighting).
 */
public final class CullDirectionTest {
    public static void main(String[] args) throws Exception {
        int w = 160;
        int h = 120;

        // Vanilla FaceInfo.UP: (min,min z) (min,max z) (max,max z) (max,min z)
        float[][] upFace = {
                {0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}
        };

        // Camera above, looking straight down at the face (north-up screen).
        // up = (0,0,-1): look direction is -Y, so screen-up maps to -Z.
        int above = drawScene(w, h, upFace, 3.0F, 0.0F, 0.0F, -1.0F, true);
        System.out.println("camera above, cull ON, drawn pixels = " + above);

        // Camera below, looking up at the same face: must be culled. Look
        // direction is +Y, screen-up = -Z again (no degenerate parallel up).
        int below = drawScene(w, h, upFace, -3.0F, 0.0F, 0.0F, -1.0F, true);
        System.out.println("camera below, cull ON, drawn pixels = " + below);

        // Culling off: the back view must draw.
        int belowNoCull = drawScene(w, h, upFace, -3.0F, 0.0F, 0.0F, -1.0F, false);
        System.out.println("camera below, cull OFF, drawn pixels = " + belowNoCull);

        boolean pass = above > 100 && below == 0 && belowNoCull > 100;
        System.out.println(pass ? "CULL-OK" : "CULL-FAIL");

        // GUI 3D light matrix: GuiGraphics scales by (16, -16, 16) before the
        // model, so the visually-top face carries model normal (0, -1, 0).
        // Expected values are the exact vanilla numbers produced by the
        // GlStateManager.setupGui3DDiffuseLighting matrix (verified by
        // LightMatrixProbe against the decompiled source): visually top face
        // fully lit, visually bottom at the 0.4 ambient floor, sides in
        // between with the vanilla isometric asymmetry.
        float[] gl = dev.antimony.core.render.EntityLighting.gui3DLights();
        float top = shade(gl, 0, -1, 0);
        float bottom = shade(gl, 0, 1, 0);
        float north = shade(gl, 0, 0, -1);
        float south = shade(gl, 0, 0, 1);
        float west = shade(gl, -1, 0, 0);
        float east = shade(gl, 1, 0, 0);
        System.out.printf("gui3d top=%.3f bottom=%.3f north=%.3f south=%.3f west=%.3f east=%.3f%n",
                top, bottom, north, south, west, east);
        boolean lightPass = approx(top, 1.0F) && approx(bottom, 0.4F)
                && approx(north, 0.547F) && approx(south, 0.513F)
                && approx(west, 1.0F) && approx(east, 0.4F);
        System.out.println(lightPass ? "LIGHT-OK" : "LIGHT-FAIL");

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, IrisGl.framebuffer().color, 0, w);
        File out = new File(args.length > 0 ? args[0] : "build/cull-test.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "PNG", out);
        if (!pass || !lightPass) {
            System.exit(1);
        }
    }

    private static int drawScene(int w, int h, float[][] face, float camY,
                                 float upX, float upY, float upZ, boolean cull) {
        IrisGl.beginFrame(w, h);
        // Every scene starts from a black canvas: without this the counts
        // accumulate across scenes (all three cameras project the square to
        // the same screen region).
        IrisGl.setClearColor(0xFF000000);
        IrisGl.clear();
        IrisGl.perspective(70.0F, w / (float) h, 0.05F, 100.0F);
        IrisGl.lookat(0.5F, camY, 0.5F, 0.5F, 0.0F, 0.5F, upX, upY, upZ);
        IrisGl.zbuffer(true);
        IrisGl.alphatest(false, 0.0F);
        IrisGl.texture(false);
        IrisGl.cullface(cull);
        IrisGl.bgnpolygon();
        IrisGl.c3f(1, 1, 1);
        for (float[] v : face) {
            IrisGl.v3f(v[0], v[1], v[2]);
        }
        IrisGl.endpolygon();
        IrisGl.flushRaster();
        return countNonBlack(w, h);
    }

    private static boolean approx(float value, float expected) {
        return Math.abs(value - expected) < 0.01F;
    }

    private static float shade(float[] lights, float nx, float ny, float nz) {
        float dot0 = lights[0] * nx + lights[1] * ny + lights[2] * nz;
        float dot1 = lights[3] * nx + lights[4] * ny + lights[5] * nz;
        float accum = Math.max(0.0F, dot0) + Math.max(0.0F, dot1);
        accum = Math.min(1.0F, accum);
        return accum * 0.6F + 0.4F;
    }

    private static int countNonBlack(int w, int h) {
        int n = 0;
        int[] px = IrisGl.framebuffer().color;
        for (int i = 0; i < w * h; i++) {
            if ((px[i] & 0xFFFFFF) != 0) {
                n++;
            }
        }
        return n;
    }
}
