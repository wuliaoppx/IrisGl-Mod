package dev.antimony.core.present;

import dev.antimony.core.irisgl.IrisGlFramebuffer;
import java.awt.image.BufferedImage;

/** Display/export contract only. Shader attachment alpha is never rewritten in place. */
public final class CpuScreenImage {
    private CpuScreenImage() { }

    /** The window is opaque; PNG/F2 export must show the same RGB even when final alpha is zero. */
    public static BufferedImage rgb(IrisGlFramebuffer frame) {
        validate(frame);
        BufferedImage image = new BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, frame.width, frame.height, frame.color, 0, frame.width);
        return image;
    }

    /** Detached pixels for the layered GDI window; never modify render/history buffers. */
    public static int[] opaqueArgbCopy(IrisGlFramebuffer frame) {
        validate(frame);
        int[] copy = frame.color.clone();
        for (int i = 0; i < copy.length; i++) copy[i] |= 0xff000000;
        return copy;
    }

    /** NativeImage uses ABGR. Final screenshots have opaque alpha, as does the presenter. */
    public static int opaqueAbgr(int argb) {
        return 0xff000000 | (argb & 0x0000ff00) | ((argb & 0xff) << 16) | ((argb >>> 16) & 0xff);
    }

    private static void validate(IrisGlFramebuffer frame) {
        if (frame == null || frame.width < 1 || frame.height < 1
                || frame.color.length != Math.multiplyExact(frame.width, frame.height))
            throw new IllegalArgumentException("Invalid CPU screen image");
    }
}
