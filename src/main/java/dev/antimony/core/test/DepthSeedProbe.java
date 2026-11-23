package dev.antimony.core.test;

import dev.antimony.core.irisgl.IrisGlFramebuffer;
import dev.antimony.gl.glsl.CpuImage;
import dev.antimony.gl.glsl.DepthFrame;
import dev.antimony.gl.glsl.IrisPostChain;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Verifies production depth seed sampling and independent real-ID destinations without a game. */
public final class DepthSeedProbe {
    public static void main(String[] args) throws Exception {
        Object world = new Object(), pipeline = new Object();
        IrisGlFramebuffer fb = new IrisGlFramebuffer(4, 2);
        DepthFrame frame = new DepthFrame();
        long generation = frame.begin(world, pipeline, fb, true);
        for (int i = 0; i < fb.depth.length; i++) fb.depth[i] = (i + 1) / 10f;
        frame.capture(generation, DepthFrame.Stage.PRE_HAND, world, fb);
        fb.depth[5] = .01f;
        frame.capture(generation, DepthFrame.Stage.PRE_TRANSLUCENT, world, fb);
        fb.depth[7] = .02f;
        frame.capture(generation, DepthFrame.Stage.FINAL, world, fb);
        var snapshot = frame.consume(world, pipeline, fb);
        Method seed = IrisPostChain.class.getDeclaredMethod("seedDepthTexture", Map.class, Set.class,
            int.class, float[].class, DepthFrame.Snapshot.class, int.class, int.class);
        seed.setAccessible(true);
        Map<Integer, CpuImage> images = new HashMap<>();
        Set<Integer> content = new HashSet<>();
        seed.invoke(null, images, content, 7, snapshot.finalDepth(), snapshot, 2, 1);
        seed.invoke(null, images, content, 1, snapshot.preTranslucent(), snapshot, 2, 1);
        seed.invoke(null, images, content, 2, snapshot.preHand(), snapshot, 2, 1);
        check(content.equals(Set.of(7, 1, 2)), "real Iris texture IDs were not published");
        check(images.get(7) != images.get(1) && images.get(1) != images.get(2), "seed destinations alias");
        check(images.get(7).rgba[0] == .01f && images.get(7).rgba[4] == .02f, "final sample mapping wrong");
        check(images.get(1).rgba[0] == .01f && images.get(1).rgba[4] == .8f, "pre-translucent seed wrong");
        check(images.get(2).rgba[0] == .6f && images.get(2).rgba[4] == .8f, "pre-hand seed wrong");
        for (CpuImage image : images.values()) for (int o = 0; o < image.rgba.length; o += 4)
            check(image.rgba[o] == image.rgba[o + 1] && image.rgba[o] == image.rgba[o + 2]
                && image.rgba[o + 3] == 1, "depth RGBA convention changed");
        seed.invoke(null, images, content, 0, snapshot.preHand(), snapshot, 2, 1);
        check(images.size() == 3 && content.size() == 3, "missing optional ID created a texture");
        System.out.println("DepthSeedProbe OK: production depth0/1/2 real IDs, independent contents, nearest resize, RGB depth/alpha 1");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
