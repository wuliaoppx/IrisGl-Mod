package dev.antimony.core.present;

import dev.antimony.core.irisgl.IrisGlFramebuffer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;

/** Actual PNG round trip with zero/mixed alpha, RGB channel order and render-buffer isolation. */
public final class CpuScreenImageProbe {
    public static void main(String[] args) throws Exception {
        IrisGlFramebuffer fb = new IrisGlFramebuffer(7, 3);
        for (int i = 0; i < fb.color.length; i++)
            fb.color[i] = ((i % 3 == 0 ? 0 : i % 3 == 1 ? 113 : 255) << 24) | ((i * 31 & 255) << 16) | ((i * 13 & 255) << 8) | (i * 71 & 255);
        int[] original = fb.color.clone();
        var image = CpuScreenImage.rgb(fb);
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "PNG", bytes)) throw new AssertionError("PNG encoder");
        var decoded = ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        if (decoded.getColorModel().hasAlpha()) throw new AssertionError("Display PNG must be RGB");
        int[] copy = CpuScreenImage.opaqueArgbCopy(fb);
        for (int y = 0; y < fb.height; y++) for (int x = 0; x < fb.width; x++) {
            int i = y * fb.width + x, expected = original[i] | 0xff000000;
            if (decoded.getRGB(x, y) != expected || copy[i] != expected) throw new AssertionError("Displayed RGB/alpha differs at " + i);
            int abgr = CpuScreenImage.opaqueAbgr(original[i]);
            int argb = (abgr & 0xff00ff00) | ((abgr & 0xff) << 16) | ((abgr >>> 16) & 0xff);
            if (argb != expected) throw new AssertionError("NativeImage channel order/alpha");
        }
        copy[0] = 0;
        if (!Arrays.equals(original, fb.color)) throw new AssertionError("Shader/frame/history alpha changed in place");
        System.out.println("PASS opaque screenshot: real RGB PNG round trip, zero/mixed alpha, exact RGB/ABGR and detached GDI copy; shader alpha untouched");
    }
}
