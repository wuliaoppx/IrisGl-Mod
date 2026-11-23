package dev.antimony.core.test;

import dev.antimony.gl.glsl.CpuImage;
import dev.antimony.gl.glsl.IrisClearBridge;
import java.util.List;
import java.util.Map;
import org.joml.Vector4f;

/** Nonzero fixtures exercise the production clear planner without GL. */
public final class IrisClearBridgeProbe {
    public static final class Framebuffer {
        private final int[] ids;
        Framebuffer(int... ids) { this.ids = ids; }
        public int getColorAttachment(int slot) { return slot < ids.length ? ids[slot] : 0; }
    }
    public static final class Pass {
        private final Vector4f color;
        private final int clearFlags;
        private final Framebuffer framebuffer;
        Pass(Vector4f color, int flags, int... ids) {
            this.color = color; clearFlags = flags; framebuffer = new Framebuffer(ids);
        }
        public Framebuffer getFramebuffer() { return framebuffer; }
    }
    public static final class Pipeline {
        private final List<Pass> clearPasses = List.of(new Pass(new Vector4f(0), 0x4000, 21, 22));
        private final List<Pass> clearPassesFull = List.of(
            new Pass(null, 0x4000, 11, 12), new Pass(new Vector4f(0), 0x4000, 21, 22),
            new Pass(new Vector4f(0), 0x4000, 31, 32), new Pass(null, 0x100, 99));
    }
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        Pipeline pipeline = new Pipeline();
        Map<Integer, CpuImage> images = new java.util.HashMap<>();
        for (int id : new int[]{11,12,21,22,31,32}) {
            CpuImage image = new CpuImage(2,2); image.clear(.7f,.6f,.5f,.4f); images.put(id,image);
        }
        var regular = IrisClearBridge.plan(pipeline, false, .1f,.2f,.3f);
        require(regular.size() == 2);
        IrisClearBridge.apply(regular, images);
        require(images.get(21).rgba[0] == 0 && images.get(22).rgba[0] == 0);
        require(images.get(31).rgba[0] == .7f && images.get(11).rgba[0] == .7f);
        var full = IrisClearBridge.plan(pipeline, true, .1f,.2f,.3f);
        require(full.size() == 6);
        IrisClearBridge.apply(full, images);
        require(images.get(11).rgba[0] == .1f && images.get(12).rgba[3] == 1);
        require(images.get(31).rgba[0] == 0);
        images.get(21).clear(.8f,0,0,1);
        try { IrisClearBridge.apply(List.of(regular.get(0), new IrisClearBridge.Clear(999,0,0,0,0)), images); throw new AssertionError(); }
        catch (IllegalStateException expected) { require(images.get(21).rgba[0] == .8f); }
        System.out.println("PASS production clears: regular both mask sides; history preserved; full fog/default colour; depth-only ignored; validation before mutation");
    }
}
