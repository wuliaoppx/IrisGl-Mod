package dev.antimony.core.test;

import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlTexture;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * Parallel-vs-serial rasterizer parity: renders the smoke scene with the tile
 * engine forced parallel (-Dantimony.raster.parallelMin=0) and forced serial
 * (-Dantimony.raster.parallelMin=999999) and compares the raw framebuffer
 * word-for-word. Also exercises the new cull + light paths with normals.
 */
public final class ParityTest {
    public static void main(String[] args) throws Exception {
        int w = 320;
        int h = 180;
        IrisGl.beginFrame(w, h);
        IrisGl.ortho(0, w, h, 0, -1, 1);
        IrisGl.zbuffer(false);
        IrisGl.bgnpolygon();
        IrisGl.c3f(0.25F, 0.45F, 0.95F);
        IrisGl.v2f(0, 0);
        IrisGl.c3f(0.25F, 0.45F, 0.95F);
        IrisGl.v2f(w, 0);
        IrisGl.c3f(0.75F, 0.88F, 1.0F);
        IrisGl.v2f(w, h);
        IrisGl.c3f(0.75F, 0.88F, 1.0F);
        IrisGl.v2f(0, h);
        IrisGl.endpolygon();

        int tw = 16;
        int[] px = new int[tw * tw];
        for (int y = 0; y < tw; y++) {
            for (int x = 0; x < tw; x++) {
                boolean on = ((x >> 1) + (y >> 1)) % 2 == 0;
                px[y * tw + x] = on ? 0xFFFFFFFF : 0xFF202020;
            }
        }
        IrisGlTexture tex = new IrisGlTexture(tw, tw, px);

        IrisGl.zbuffer(true);
        IrisGl.perspective(70.0F, w / (float) h, 0.05F, 100.0F);
        IrisGl.lookat(3, 3, 5, 0, 0, 0, 0, 1, 0);
        IrisGl.texture(true);
        IrisGl.texbind(tex);
        IrisGl.ctx().blend = false;
        IrisGl.alphatest(false, 0.0F);
        IrisGl.cullface(true);
        IrisGl.shaderLights(0.16F, 0.81F, -0.57F, -0.16F, 0.81F, 0.57F);
        IrisGl.ctx().lightShade = true;

        // Vanilla FaceInfo-ordered cube: UP + DOWN + NORTH + EAST.
        float s = 1.0F;
        float[][] faces = {
                // UP (FaceInfo.UP order)
                {-s, s, -s, -s, s, s, s, s, s, s, s, -s},
                // DOWN
                {-s, -s, s, -s, -s, -s, s, -s, -s, s, -s, s},
                // NORTH
                {s, s, -s, s, -s, -s, -s, -s, -s, -s, s, -s},
                // EAST
                {s, s, s, s, -s, s, s, -s, -s, s, s, -s}
        };
        for (int f = 0; f < faces.length; f++) {
            float[] face = faces[f];
            float nx = 0, ny = 0, nz = 0;
            if (f == 0) { ny = 1; }       // UP
            else if (f == 1) { ny = -1; } // DOWN
            else if (f == 2) { nz = -1; } // NORTH
            else { nx = 1; }              // EAST
            IrisGl.bgnpolygon();
            for (int i = 0; i < 4; i++) {
                float x = face[i * 3];
                float y = face[i * 3 + 1];
                float z = face[i * 3 + 2];
                IrisGl.c3f(1, 1, 1);
                IrisGl.t2f(i == 0 || i == 3 ? 0 : 1, i < 2 ? 1 : 0);
                IrisGl.n3f(nx, ny, nz);
                IrisGl.v3f(x, y, z);
            }
            IrisGl.endpolygon();
        }
        IrisGl.flushRaster();

        int[] snapshot = Arrays.copyOf(IrisGl.framebuffer().color, w * h);
        long sum = 0;
        int lit = 0;
        for (int p : snapshot) {
            sum += p & 0xFFFFFFFFL;
            if ((p & 0xFFFFFF) != 0) {
                lit++;
            }
        }
        System.out.println("mode=" + System.getProperty("antimony.raster.parallelMin", "48")
                + " lit=" + lit + " sum=" + String.format("%016x", sum));

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, snapshot, 0, w);
        File out = new File(args.length > 0 ? args[0] : "build/parity.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "PNG", out);
        System.out.println("wrote " + out.getAbsolutePath());
    }
}
