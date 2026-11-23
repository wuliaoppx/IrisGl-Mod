package dev.antimony.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import dev.antimony.AntimonyMod;
import dev.antimony.core.irisgl.IrisGlFramebuffer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.IntBuffer;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeWin32;

/** Startup card: native before world entry, composited into the CPU GUI image in-world.
 * All native calls stay on the render/window owner thread. No desktop-topmost window,
 * background GUI thread, GL draw or framebuffer readback is required. */
public final class SplashOverlay {
    private static final int CARD_W = 420, CARD_H = 130, MARGIN = 16;
    private static final StartupProgress PROGRESS = new StartupProgress();
    private static boolean requested, nativeBroken;
    private static Pointer gameWindow, card, screenDc, memDc, bitmap, stockBitmap;
    private static IntBuffer nativePixels;
    private static BufferedImage canvas, logo;
    private static int[] cardPixels;
    private static int lastLoggedStage;
    private static long lastLogAt;
    private static String lastLoggedDetail = "";
    private static boolean loggedCpuComposition;

    private SplashOverlay() { }

    public static void start() {
        if (AntimonyMod.flag("antimony.splash.disable")) return;
        requested = true;
        PROGRESS.start(System.currentTimeMillis());
    }

    public static void updateStage(int stage, String detail) {
        if (requested) PROGRESS.update(stage, detail, System.currentTimeMillis());
    }

    public static void beginPipeline(Object pipeline) {
        if (requested) PROGRESS.beginPipeline(pipeline, System.currentTimeMillis());
    }

    public static void complete() {
        if (requested) PROGRESS.complete(System.currentTimeMillis());
    }

    public static void fail(String reason) {
        if (requested) PROGRESS.fail(reason, System.currentTimeMillis());
    }

    /** Paint after shader execution and the GUI, before GDI presentation and F2 export. */
    public static void drawInFrame(IrisGlFramebuffer frame) {
        if (!requested) return;
        long now = System.currentTimeMillis();
        StartupProgress.Snapshot snapshot = PROGRESS.snapshot(now);
        hideNative();
        if (!snapshot.visible()) return;
        paintFramebuffer(frame, snapshot, now);
        logProgress(snapshot, now);
        if (!loggedCpuComposition) {
            loggedCpuComposition = true;
            AntimonyMod.LOG.info("[Antimony] startup progress composited into CPU framebuffer; no separate in-world window/Z-order dependency");
        }
    }

    /** Production pixel compositor, also exercised by the headless PNG regression. The
     * framebuffer's alpha is retained; this GUI step never touches shader RT/history storage. */
    static void paintFramebuffer(IrisGlFramebuffer frame, StartupProgress.Snapshot snapshot, long now) {
        if (frame == null || !snapshot.visible()) return;
        prepareCanvas(snapshot, now);
        int margin = Math.min(MARGIN, Math.max(0, (Math.min(frame.width, frame.height) - 1) / 4));
        float scale = Math.min(1.0F, Math.min((frame.width - 2 * margin) / (float) CARD_W,
                (frame.height - 2 * margin) / (float) CARD_H));
        int width = Math.max(1, Math.round(CARD_W * scale));
        int height = Math.max(1, Math.round(CARD_H * scale));
        int left = Math.max(0, frame.width - margin - width), top = margin;
        for (int y = 0; y < height && top + y < frame.height; y++) {
            int sourceRow = Math.min(CARD_H - 1, (int) ((long) y * CARD_H / height)) * CARD_W;
            int targetRow = (top + y) * frame.width + left;
            for (int x = 0; x < width && left + x < frame.width; x++) {
                int src = cardPixels[sourceRow + Math.min(CARD_W - 1, (int) ((long) x * CARD_W / width))];
                int alpha = Math.round((src >>> 24) * snapshot.opacity());
                if (alpha == 0) continue;
                int old = frame.color[targetRow + x], inverse = 255 - alpha;
                int r = (((src >>> 16) & 255) * alpha + ((old >>> 16) & 255) * inverse + 127) / 255;
                int g = (((src >>> 8) & 255) * alpha + ((old >>> 8) & 255) * inverse + 127) / 255;
                int b = ((src & 255) * alpha + (old & 255) * inverse + 127) / 255;
                frame.color[targetRow + x] = (old & 0xff000000) | (r << 16) | (g << 8) | b;
            }
        }
    }

    /** Menu/loading-screen fallback only. A second popup can never cover the in-world card. */
    public static void renderFrame() {
        if (!requested || nativeBroken) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.level != null && mc.player != null) { hideNative(); return; }
        try {
            long now = System.currentTimeMillis();
            StartupProgress.Snapshot snapshot = PROGRESS.snapshot(now);
            if (!snapshot.visible()) { hideNative(); return; }
            if (card == null && !create(mc)) return;
            if (!Win32.User32.INSTANCE.IsWindow(gameWindow) || !Win32.User32.INSTANCE.IsWindow(card)) { close(); return; }
            if (Win32.User32.INSTANCE.IsIconic(gameWindow)
                    || !gameWindow.equals(Win32.User32.INSTANCE.GetForegroundWindow())) { hideNative(); return; }
            Win32.Rect client = new Win32.Rect();
            if (!Win32.User32.INSTANCE.GetClientRect(gameWindow, client)) return;
            if (client.right - client.left < CARD_W + 2 * MARGIN || client.bottom - client.top < CARD_H + 2 * MARGIN) {
                hideNative(); return;
            }
            prepareCanvas(snapshot, now);
            nativePixels.clear();
            for (int pixel : cardPixels) {
                int a = Math.round((pixel >>> 24) * snapshot.opacity());
                int r = ((pixel >>> 16) & 255) * a / 255;
                int g = ((pixel >>> 8) & 255) * a / 255;
                int b = (pixel & 255) * a / 255;
                nativePixels.put((a << 24) | (r << 16) | (g << 8) | b);
            }
            Win32.Point position = new Win32.Point(client.right - CARD_W - MARGIN, MARGIN);
            if (!Win32.User32.INSTANCE.ClientToScreen(gameWindow, position)) return;
            if (!Win32.User32.INSTANCE.UpdateLayeredWindow(card, screenDc, position, new Win32.Size(CARD_W, CARD_H),
                    memDc, new Win32.Point(), 0, new Win32.BlendFunction(), Win32.ULW_ALPHA))
                throw new IllegalStateException("Startup card UpdateLayeredWindow failed");
            Win32.User32.INSTANCE.SetWindowPos(card, null, 0, 0, 0, 0,
                    Win32.SWP_NOMOVE | Win32.SWP_NOSIZE | Win32.SWP_NOACTIVATE | Win32.SWP_SHOWWINDOW);
            logProgress(snapshot, now);
        } catch (Throwable failure) {
            nativeBroken = true;
            AntimonyMod.LOG.warn("[Antimony] native startup card unavailable; CPU in-world card remains enabled", failure);
            close();
        }
    }

    private static void logProgress(StartupProgress.Snapshot snapshot, long now) {
        if (snapshot.stage() != lastLoggedStage
                || !snapshot.detail().equals(lastLoggedDetail) && now - lastLogAt >= 5_000L) {
            lastLoggedStage = snapshot.stage(); lastLoggedDetail = snapshot.detail(); lastLogAt = now;
            AntimonyMod.LOG.info("[Antimony] startup progress {}/6: {} (elapsed={}s failed={})",
                    snapshot.stage(), snapshot.detail(), snapshot.elapsedSeconds(), snapshot.failed());
        }
    }

    private static boolean create(Minecraft mc) {
        if (mc.getWindow() == null || mc.getWindow().getWindow() == 0L) return false;
        long handle = GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().getWindow());
        if (handle == 0L) return false;
        gameWindow = Pointer.createConstant(handle);
        card = Win32.User32.INSTANCE.CreateWindowExA(
                Win32.WS_EX_LAYERED | Win32.WS_EX_TRANSPARENT | Win32.WS_EX_NOACTIVATE | Win32.WS_EX_TOOLWINDOW,
                "STATIC", "Antimony startup", Win32.WS_POPUP, 0, 0, CARD_W, CARD_H, gameWindow, null, null, null);
        if (card == null) throw new IllegalStateException("CreateWindowExA failed for startup card");
        screenDc = Win32.User32.INSTANCE.GetDC(null);
        memDc = Win32.Gdi32.INSTANCE.CreateCompatibleDC(screenDc);
        Win32.BitmapInfo info = new Win32.BitmapInfo();
        info.bmiHeader.biSize = info.bmiHeader.size(); info.bmiHeader.biWidth = CARD_W; info.bmiHeader.biHeight = -CARD_H;
        info.bmiHeader.biPlanes = 1; info.bmiHeader.biBitCount = 32; info.bmiHeader.biCompression = Win32.BI_RGB;
        PointerByReference bits = new PointerByReference();
        bitmap = Win32.Gdi32.INSTANCE.CreateDIBSection(memDc, info, Win32.DIB_RGB_COLORS, bits, null, 0);
        if (bitmap == null || bits.getValue() == null) throw new IllegalStateException("CreateDIBSection failed for startup card");
        stockBitmap = Win32.Gdi32.INSTANCE.SelectObject(memDc, bitmap);
        nativePixels = bits.getValue().getByteBuffer(0, (long) CARD_W * CARD_H * 4).asIntBuffer();
        return true;
    }

    private static void hideNative() {
        if (card != null && Win32.User32.INSTANCE.IsWindow(card)) Win32.User32.INSTANCE.ShowWindow(card, Win32.SW_HIDE);
    }

    public static void close() {
        if (card != null && Win32.User32.INSTANCE.IsWindow(card)) Win32.User32.INSTANCE.DestroyWindow(card);
        if (bitmap != null) {
            if (memDc != null && stockBitmap != null) Win32.Gdi32.INSTANCE.SelectObject(memDc, stockBitmap);
            Win32.Gdi32.INSTANCE.DeleteObject(bitmap);
        }
        if (memDc != null) Win32.Gdi32.INSTANCE.DeleteDC(memDc);
        if (screenDc != null) Win32.User32.INSTANCE.ReleaseDC(null, screenDc);
        card = gameWindow = bitmap = stockBitmap = memDc = screenDc = null;
        nativePixels = null;
    }

    private static void prepareCanvas(StartupProgress.Snapshot snapshot, long now) {
        if (canvas == null) {
            canvas = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
            cardPixels = new int[CARD_W * CARD_H];
            try (InputStream in = SplashOverlay.class.getResourceAsStream("/antimony_overlay_logo.png")) {
                if (in != null) logo = ImageIO.read(in);
            } catch (Exception ignored) { }
        }
        drawCard(snapshot, now);
        canvas.getRGB(0, 0, CARD_W, CARD_H, cardPixels, 0, CARD_W);
    }

    private static void drawCard(StartupProgress.Snapshot snapshot, long now) {
        Color accent = snapshot.failed() ? new Color(0xdf, 0x70, 0x5b) : new Color(0x46, 0x9d, 0xc9);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.Clear); g.fillRect(0, 0, CARD_W, CARD_H);
            g.setComposite(AlphaComposite.SrcOver);
            RoundRectangle2D body = new RoundRectangle2D.Float(.5F, 1.5F, CARD_W - 1F, CARD_H - 3F, 12F, 12F);
            g.setPaint(new GradientPaint(0, 1, new Color(30, 32, 38), 0, CARD_H - 2, new Color(14, 15, 19)));
            g.fill(body); g.setColor(new Color(70, 157, 201, 100)); g.setStroke(new BasicStroke(1F)); g.draw(body);
            g.setColor(accent); g.fill(new RoundRectangle2D.Float(2F, 11F, 4F, CARD_H - 22F, 4F, 4F));
            if (logo != null) g.drawImage(logo, 18, 24, 52, 52, null);
            int x = 84, available = CARD_W - x - 18;
            Font font = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
            g.setFont(font.deriveFont(Font.BOLD, 18F)); g.setColor(accent); g.drawString("Antimony", x, 31);
            g.setFont(font.deriveFont(13F)); g.setColor(new Color(206, 209, 218));
            g.drawString(snapshot.failed() ? "IrisGL 光影初始化失败" : snapshot.stage() == StartupProgress.COMPLETE
                    ? "IrisGL 光影首帧已完成" : "IrisGL 光影启动中", x, 53);
            g.setColor(new Color(52, 57, 66)); g.fill(new RoundRectangle2D.Float(x, 65, available, 6F, 6F, 6F));
            g.setColor(accent);
            if (snapshot.waiting()) {
                float cycle = (now % 1600L) / 1600F;
                float offset = (1F - Math.abs(cycle * 2F - 1F)) * (available - 76);
                g.fill(new RoundRectangle2D.Float(x + offset, 65, 76F, 6F, 6F, 6F));
            } else g.fill(new RoundRectangle2D.Float(x, 65, available * snapshot.stage() / 6F, 6F, 6F, 6F));
            g.setFont(font.deriveFont(11F)); g.setColor(new Color(184, 188, 198));
            String detail = snapshot.detail();
            while (!detail.isEmpty() && g.getFontMetrics().stringWidth(detail) > available)
                detail = detail.substring(0, detail.length() - 1);
            g.drawString(detail, x, 91);
            g.setFont(font.deriveFont(10.5F)); g.setColor(new Color(140, 145, 156));
            String footer = snapshot.failed() ? "初始化失败 · 请查看日志，不会等待完成"
                    : "阶段 " + snapshot.stage() + "/6 · " + (snapshot.waiting()
                    ? "等待 " + snapshot.phaseSeconds() + " 秒（非百分比）" : "已用时 " + snapshot.elapsedSeconds() + " 秒");
            g.drawString(footer, x, 115);
        } finally { g.dispose(); }
    }
}
