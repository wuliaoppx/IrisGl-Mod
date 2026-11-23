package dev.antimony.core.test;

import dev.antimony.core.irisgl.IrisGlFramebuffer;
import dev.antimony.gl.glsl.DepthFrame;
import java.util.Arrays;

/** Standalone javac/java probe: no Minecraft, Iris, GL context, or Gradle required. */
public final class DepthFrameProbe {
    public static void main(String[] args) {
        DepthFrame frame = new DepthFrame();
        Object world = new Object(), pipeline = new Object();
        IrisGlFramebuffer fb = new IrisGlFramebuffer(2, 2);
        fb.depth[0] = .8f; fb.depth[1] = .7f; fb.depth[2] = .6f; fb.depth[3] = 1;
        long first = frame.begin(world, pipeline, fb, true);
        frame.capture(first, DepthFrame.Stage.PRE_HAND, world, fb);
        // Simulated opaque hand genuinely writes one depth sample.
        fb.depth[1] = .1f;
        frame.capture(first, DepthFrame.Stage.PRE_TRANSLUCENT, world, fb);
        // A translucent pass with depthWrite=false leaves the depth buffer unchanged.
        frame.capture(first, DepthFrame.Stage.FINAL, world, fb);
        var snapshot = frame.consume(world, pipeline, fb);
        check(snapshot.preHand()[1] == .7f && snapshot.preTranslucent()[1] == .1f,
            "hand depth did not separate the correct boundaries");
        check(Arrays.equals(snapshot.preTranslucent(), snapshot.finalDepth()),
            "non-depth-writing translucency should produce equal depths");
        check(snapshot.preHand() != snapshot.preTranslucent() && snapshot.preTranslucent() != snapshot.finalDepth(),
            "copies must not alias, even when their contents are equal");
        fb.depth[0] = .05f;
        check(snapshot.finalDepth()[0] == .8f, "capture aliases the live framebuffer");
        reject(() -> frame.consume(world, pipeline, fb), "double consume");

        long next = frame.begin(world, pipeline, fb, true);
        reject(() -> frame.capture(first, DepthFrame.Stage.PRE_HAND, world, fb), "old generation");
        reject(() -> frame.consume(world, pipeline, fb), "missing stages reused previous frame");
        reject(() -> frame.capture(next, DepthFrame.Stage.FINAL, world, fb), "out of order");
        frame.capture(next, DepthFrame.Stage.PRE_HAND, world, fb);
        reject(() -> frame.consume(world, pipeline, fb), "partial frame");
        frame.capture(next, DepthFrame.Stage.PRE_TRANSLUCENT, world, fb);
        frame.capture(next, DepthFrame.Stage.FINAL, world, fb);
        reject(() -> frame.consume(new Object(), pipeline, fb), "world change");
        reject(() -> frame.consume(world, new Object(), fb), "pipeline change");
        reject(() -> frame.consume(world, pipeline, new IrisGlFramebuffer(3, 1)), "resize");
        reject(() -> frame.consume(world, pipeline, new IrisGlFramebuffer(2, 2)), "same-size replacement");
        frame.consume(world, pipeline, fb);

        IrisGlFramebuffer resized = new IrisGlFramebuffer(3, 1);
        Arrays.fill(resized.depth, .4f);
        long resizedGeneration = frame.begin(world, pipeline, resized, true);
        for (DepthFrame.Stage stage : DepthFrame.Stage.values()) frame.capture(resizedGeneration, stage, world, resized);
        var resizedSnapshot = frame.consume(world, pipeline, resized);
        check(resizedSnapshot.width() == 3 && resizedSnapshot.height() == 1
            && resizedSnapshot.preHand().length == 3 && resizedSnapshot.finalDepth()[2] == .4f,
            "resized depth storage contains stale dimensions/content");
        check(frame.begin(world, pipeline, fb, false) == 0, "disabled frame should not capture");
        reject(() -> frame.consume(world, pipeline, fb), "disabled reused previous frame");
        long enabledAgain = frame.begin(world, pipeline, fb, true);
        frame.invalidate();
        reject(() -> frame.capture(enabledAgain, DepthFrame.Stage.PRE_HAND, world, fb), "explicit invalidation");
        check(frame.begin(null, pipeline, fb, true) == 0, "world teardown");
        check(frame.begin(world, null, fb, true) == 0, "pack absent");
        System.out.println("DepthFrameProbe OK: real stage equality/difference, isolation, missing/partial/stale generations, world/pipeline/resize, disabled/teardown");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void reject(Runnable action, String message) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected rejection: " + message);
    }
}
