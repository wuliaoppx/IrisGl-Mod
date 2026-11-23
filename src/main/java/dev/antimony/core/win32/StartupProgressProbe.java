package dev.antimony.core.win32;

import dev.antimony.core.irisgl.IrisGlFramebuffer;
import dev.antimony.core.present.CpuScreenImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;

/** Uses the real GUI compositor and PNG codec, with a controlled clock; no Minecraft/GL run. */
public final class StartupProgressProbe {
    public static void main(String[] args) throws Exception {
        StartupProgress state = new StartupProgress();
        state.start(0);
        Object pack = new Object();
        state.beginPipeline(pack, 1);
        state.update(5, "阴影区块 123/456 · 构建中 12 · 待就绪 333", 100);
        state.update(3, "late renderer tick", 1000);
        var waiting = state.snapshot(6100);
        check(waiting.stage() == 5 && waiting.waiting() && waiting.phaseSeconds() == 6, "real waiting milestone, late lower-stage ticks ignored");
        state.update(5, waiting.detail(), 7000);
        check(state.snapshot(7100).phaseSeconds() == 7, "repeated waiting reports do not reset phase timer");
        state.complete(7200);
        var completed = state.snapshot(120_000);
        check(completed.stage() == 6 && !completed.waiting() && completed.visible() && completed.opacity() == 1F,
                "slow CPU frame cannot skip the first visible completed card or clamp it back to 5/6");
        check(state.snapshot(121_600).visible() && !state.snapshot(122_101).visible(), "completion holds then fades only after presentation");
        state.beginPipeline(new Object(), 130_000);
        check(state.snapshot(130_000).stage() == 4 && state.snapshot(130_000).visible(), "new pack restarts progress after completion");
        state.complete(130_001);
        state.beginPipeline(pack, 131_000);
        check(state.snapshot(131_000).stage() == 4, "switching back to earlier pack still restarts");
        Path directory = Path.of("build/probe-output/startup-progress");
        Files.createDirectories(directory);
        IrisGlFramebuffer background = fixture(854, 480);
        int[] before = background.color.clone();
        SplashOverlay.paintFramebuffer(background, waiting, 6400);
        check(!Arrays.equals(before, background.color), "startup card is in the production CPU pixel image, not another HWND");
        for (int i = 0; i < before.length; i++) {
            check((before[i] & 0xff000000) == (background.color[i] & 0xff000000), "GUI composition retains alpha lane " + i);
            int x = i % 854, y = i / 854;
            if (x < 418 || y < 16 || y >= 146) check(before[i] == background.color[i], "pixels outside card unchanged " + i);
        }
        Path waitPng = directory.resolve("waiting.png");
        ImageIO.write(CpuScreenImage.rgb(background), "PNG", waitPng.toFile());
        var decoded = ImageIO.read(waitPng.toFile());
        for (int y = 0; y < 480; y++) for (int x = 0; x < 854; x++)
            check(decoded.getRGB(x, y) == (0xff000000 | background.color[y * 854 + x]), "encoded display/export pixel exact");
        IrisGlFramebuffer moved = fixture(854, 480);
        SplashOverlay.paintFramebuffer(moved, waiting, 6800);
        check(!Arrays.equals(background.color, moved.color), "indeterminate indicator moves using clock, not fabricated completion");
        IrisGlFramebuffer ready = fixture(854, 480);
        SplashOverlay.paintFramebuffer(ready, completed, 120_000);
        ImageIO.write(CpuScreenImage.rgb(ready), "PNG", directory.resolve("completed.png").toFile());
        IrisGlFramebuffer tiny = fixture(37, 13);
        SplashOverlay.paintFramebuffer(tiny, waiting, 6400);
        check(tiny.color.length == 481, "small framebuffer safely scales/clips card");
        state.fail("计算着色器源码未捕获", 132_000);
        state.update(5, "正在等待", 133_000);
        state.complete(134_000);
        var error = state.snapshot(934_000);
        check(error.failed() && !error.waiting() && error.visible() && error.stage() != 6
                && error.detail().equals("计算着色器源码未捕获"), "deterministic error cannot become an 800-second loading spinner or fake success");
        IrisGlFramebuffer errorFrame = fixture(854, 480);
        SplashOverlay.paintFramebuffer(errorFrame, error, 934_000);
        ImageIO.write(CpuScreenImage.rgb(errorFrame), "PNG", directory.resolve("failed.png").toFile());
        state.beginPipeline(new Object(), 935_000);
        check(!state.snapshot(935_000).failed() && state.snapshot(935_000).stage() == 4, "a new pipeline can retry after a setup error");
        System.out.println("PASS startup progress: waiting/clock/reload/visible completed 6/6; real CPU compositor; unchanged alpha/outside pixels; opaque PNG round trip; tiny viewport; deterministic error is not loading/success");
    }
    private static IrisGlFramebuffer fixture(int width, int height) {
        IrisGlFramebuffer frame = new IrisGlFramebuffer(width, height);
        for (int i = 0; i < frame.color.length; i++) frame.color[i] = ((i & 1) == 0 ? 0 : 0x71000000) | 0x416b93;
        return frame;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
