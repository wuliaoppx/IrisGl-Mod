package dev.antimony.core.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.antimony.AntimonyMod;
import dev.antimony.core.irisgl.IrisGlFramebuffer;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

/**
 * Presentation without a second window: the CPU framebuffer is written
 * straight into the colour texture of Minecraft's own main render target.
 * Vanilla then samples it with its post-chain and blits it to the window
 * (Minecraft.runTick -> mainRenderTarget.blitToScreen), so the game window
 * itself shows the Antimony frame - no overlay, no focus heuristics.
 */
public final class MainTextureBlit {
    private static Field colorTextureIdField;
    private static int lastTextureId = -1;
    private static long uploadedFrames;

    private MainTextureBlit() {
    }

    /**
     * Called when the software framebuffer size changes (window resize).
     * Nothing to invalidate here: every present() uploads at the current
     * window size, and vanilla reallocates the main-target texture on resize,
     * whose new id is picked up automatically on the next frame.
     */
    public static void onTargetResized() {
    }

    private static ByteBuffer uploadBuffer;
    private static int[] rowScratch;

    public static void present(IrisGlFramebuffer fb) {
        try {
            RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
            if (target == null) {
                return;
            }
            if (colorTextureIdField == null) {
                colorTextureIdField = RenderTarget.class.getDeclaredField("colorTextureId");
                colorTextureIdField.setAccessible(true);
            }
            int texId = colorTextureIdField.getInt(target);
            if (texId <= 0) {
                return;
            }
            if (texId != lastTextureId) {
                AntimonyMod.LOG.info("[Antimony] blitting software frames into main render target texture {}", texId);
                lastTextureId = texId;
            }
            int w = Minecraft.getInstance().getWindow().getWidth();
            int h = Minecraft.getInstance().getWindow().getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            // Reuse one direct buffer across frames: allocating (and freeing)
            // a window-sized direct ByteBuffer every frame churned native
            // memory and contributed to the garbage-collection stutter.
            int bytes = w * h * 4;
            if (uploadBuffer == null || uploadBuffer.capacity() < bytes) {
                if (uploadBuffer != null) {
                    MemoryUtil.memFree(uploadBuffer);
                }
                uploadBuffer = MemoryUtil.memAlloc(bytes);
            }
            ByteBuffer buf = uploadBuffer;
            buf.clear();
            {
                int fw = fb.width;
                int fh = fb.height;
                int[] src = fb.color;
                if (fw == w && fh == h) {
                    // 1:1 fast path: rows are plain BGRA swaps of our ARGB
                    // ints, so fill one scratch row and bulk-copy it. The
                    // scalar path below ran a 64-bit divide plus four indexed
                    // puts per pixel (~410k puts/frame) whenever the window
                    // lost focus and the GL presenter fell back to this path.
                    if (rowScratch == null || rowScratch.length < w) {
                        rowScratch = new int[w];
                    }
                    for (int y = 0; y < h; y++) {
                        int sy = fh - 1 - y;
                        int row = sy * fw;
                        int[] scratch = rowScratch;
                        for (int x = 0; x < w; x++) {
                            int argb = src[row + x];
                            scratch[x] = 0xFF000000
                                    | (argb & 0xFF) << 16          // R -> byte 2
                                    | (argb & 0x0000FF00)          // G -> byte 1
                                    | (argb >>> 16) & 0xFF;        // B -> byte 0
                        }
                        buf.position(y * w * 4);
                        buf.asIntBuffer().put(scratch, 0, w);
                    }
                    buf.clear();
                } else {
                    // OpenGL FBO textures store row 0 at the logical bottom; our
                    // framebuffer row 0 is the top of the image, so flip while scaling.
                    for (int y = 0; y < h; y++) {
                        int sy = fh - 1 - (int) ((long) y * fh / h);
                        int row = sy * fw;
                        int off = y * w * 4;
                        for (int x = 0; x < w; x++) {
                            int sx = (int) ((long) x * fw / w);
                            int argb = src[row + sx];
                            buf.put(off + x * 4, (byte) (argb & 0xFF));         // B
                            buf.put(off + x * 4 + 1, (byte) ((argb >>> 8) & 0xFF));   // G
                            buf.put(off + x * 4 + 2, (byte) ((argb >>> 16) & 0xFF));  // R
                            buf.put(off + x * 4 + 3, (byte) 0xFF);              // A
                        }
                    }
                }
                // Vanilla's NativeImage._upload leaves GL_UNPACK_ROW_LENGTH /
                // _SKIP_ROWS / _SKIP_PIXELS set (it never resets them) and its
                // own uploads always pass either width 0 or the full image
                // width, so the stale row length is harmless *for vanilla*.
                // Our upload is also full-width, but a stale non-zero ROW_LENGTH
                // combined with our tightly-packed buffer makes the driver read
                // out of bounds (observed as a hard crash inside atio6axx.dll
                // after ~800 frames on AMD). Reset the full unpack state before
                // uploading - this is exactly what vanilla does at the start of
                // its own uploads (GlStateManager._setupUnpackPixelStoreState).
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);   // 3314
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);    // 3315
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);  // 3316
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);    // 3317
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
                try {
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h,
                            GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
                } finally {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                }
                uploadedFrames++;
            }
        } catch (Throwable t) {
            AntimonyMod.LOG.error("[Antimony] main-texture blit failed", t);
        }
    }

    public static long uploadedFrames() {
        return uploadedFrames;
    }
}
