package dev.antimony.gl.glsl;

import java.util.Objects;

/** Detached values from Iris' real frame hook and notifier-owned camera tracker. */
public final class IrisCpuFrameInputs {
    public record Snapshot(int frameCounter, float frameTimeCounter, float[] cameraPosition,
                           float[] previousCameraPosition) {
        public Snapshot {
            cameraPosition = cameraPosition.clone();
            previousCameraPosition = previousCameraPosition.clone();
        }
        @Override public float[] cameraPosition() { return cameraPosition.clone(); }
        @Override public float[] previousCameraPosition() { return previousCameraPosition.clone(); }
    }

    private Object pipeline;
    private int lastCounter = -1;

    /** Call after IrisUniformFrame.capture has notified and evaluated the actual Iris suppliers. */
    public Snapshot capture(Object currentPipeline, IrisUniformFrame uniforms) throws ReflectiveOperationException {
        Objects.requireNonNull(currentPipeline);
        Objects.requireNonNull(uniforms);
        if (pipeline != currentPipeline) {
            pipeline = currentPipeline;
            lastCounter = -1;
        }
        Class<?> time = Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms");
        Object counter = time.getField("COUNTER").get(null);
        int value = ((Number) counter.getClass().getMethod("getAsInt").invoke(counter)).intValue();
        if (value < 0 || value >= 720720 || value == lastCounter)
            throw new IllegalStateException("Iris frameCounter did not advance before the CPU frame");
        Object timer = time.getField("TIMER").get(null);
        float seconds = ((Number) timer.getClass().getMethod("getFrameTimeCounter").invoke(timer)).floatValue();
        if (!Float.isFinite(seconds)) throw new IllegalStateException("Iris frameTimeCounter is nonfinite");
        // Timer.beginFrame uses millisecond precision. An unchanged timer is legitimate;
        // this reader must never advance the counter, timer, or camera tracker itself.
        float[] current = vector(uniforms, "cameraPosition");
        float[] previous = vector(uniforms, "previousCameraPosition");
        lastCounter = value;
        return new Snapshot(value, seconds, current, previous);
    }

    private static float[] vector(IrisUniformFrame uniforms, String name) {
        IrisUniformFrame.Value value = uniforms.get(name);
        float[] vector = value == null ? null : value.floats();
        if (vector == null || vector.length != 3)
            throw new IllegalStateException("Missing Iris tracker value " + name);
        for (float component : vector) if (!Float.isFinite(component))
            throw new IllegalStateException("Nonfinite Iris tracker value " + name);
        return vector;
    }
}
