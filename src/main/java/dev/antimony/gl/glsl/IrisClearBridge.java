package dev.antimony.gl.glsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Vector4f;

/** Mirrors Iris' declared colour clears without invoking its GL clear operations. */
public final class IrisClearBridge {
    private static final int COLOR_BUFFER_BIT = 0x4000;

    private IrisClearBridge() { }

    public record Clear(int texture, float red, float green, float blue, float alpha) { }

    /** Read the live clear objects: Iris can rebuild them after a resize. */
    public static List<Clear> plan(Object pipeline, boolean full, float red, float green, float blue) {
        return planField(pipeline,full?"clearPassesFull":"clearPasses",red,green,blue);
    }
    public static List<Clear> planField(Object pipeline,String field,float red,float green,float blue){
        Object value = IrisSamplerBridge.field(pipeline, field);
        if (!(value instanceof Iterable<?> passes))
            throw new IllegalStateException("Iris colour clear passes unavailable");
        Map<Integer, Clear> result = new LinkedHashMap<>();
        for (Object pass : passes) {
            Object flags = IrisSamplerBridge.field(pass, "clearFlags");
            if (!(flags instanceof Integer bits))
                throw new IllegalStateException("Iris clear flags unavailable");
            if ((bits & COLOR_BUFFER_BIT) == 0) continue;
            Object configured = IrisSamplerBridge.field(pass, "color");
            Vector4f color = configured instanceof Vector4f c ? c : new Vector4f(red, green, blue, 1.0f);
            Object framebuffer = IrisSamplerBridge.call(pass, "getFramebuffer");
            if (framebuffer == null) throw new IllegalStateException("Iris clear framebuffer unavailable");
            // ClearPassCreator attaches colour buffers densely by slot, including both sides of each RT.
            for (int slot = 0; slot < 32; slot++) {
                int texture;
                try {
                    var getter = framebuffer.getClass().getMethod("getColorAttachment", int.class);
                    getter.setAccessible(true);
                    Object id = getter.invoke(framebuffer, slot);
                    texture = id instanceof Integer i ? i : 0;
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Iris clear attachment unavailable", failure);
                }
                if (texture == 0) break;
                result.put(texture, new Clear(texture, color.x, color.y, color.z, color.w));
            }
        }
        return new ArrayList<>(result.values());
    }

    public static int apply(List<Clear> clears, Map<Integer, CpuImage> images) {
        // Validate the complete set first, so an unavailable attachment cannot cause a partial clear.
        for (Clear clear : clears) if (!images.containsKey(clear.texture()))
            throw new IllegalStateException("CPU image missing for Iris clear texture " + clear.texture());
        for (Clear clear : clears)
            images.get(clear.texture()).clear(clear.red(), clear.green(), clear.blue(), clear.alpha());
        return clears.size();
    }
}
