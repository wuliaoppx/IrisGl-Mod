package dev.antimony.core.render;

/** Rejects resolution/camera changes and empty geometry in preset timing comparisons. */
public final class PresetComparisonGuard {
    public record Observation(int windowWidth, int windowHeight, int sceneWidth, int sceneHeight,
                              double eyeX, double eyeY, double eyeZ, float yaw, float pitch, int geometryQuads) { }
    private Observation baseline;

    public void accept(Observation frame, RenderQualitySettings.Preset preset) {
        if (frame == null || !Double.isFinite(frame.eyeX) || !Double.isFinite(frame.eyeY)
                || !Double.isFinite(frame.eyeZ) || !Float.isFinite(frame.yaw) || !Float.isFinite(frame.pitch))
            throw new IllegalStateException("Preset comparison requires a finite camera observation");
        if (frame.windowWidth <= 0 || frame.windowHeight <= 0
                || frame.sceneWidth != Math.max(1, Math.round(frame.windowWidth * preset.sceneScale))
                || frame.sceneHeight != Math.max(1, Math.round(frame.windowHeight * preset.sceneScale)))
            throw new IllegalStateException("Preset scene extent does not match the same window");
        if (frame.geometryQuads <= 0)
            throw new IllegalStateException("Empty scene geometry is not a valid preset speed sample");
        if (baseline == null) { baseline = frame; return; }
        if (frame.windowWidth != baseline.windowWidth || frame.windowHeight != baseline.windowHeight)
            throw new IllegalStateException("Window changed during preset comparison: " + baseline.windowWidth + "x"
                    + baseline.windowHeight + " -> " + frame.windowWidth + "x" + frame.windowHeight);
        if (Math.abs(frame.eyeX - baseline.eyeX) > .02 || Math.abs(frame.eyeY - baseline.eyeY) > .02
                || Math.abs(frame.eyeZ - baseline.eyeZ) > .02
                || Math.abs(frame.yaw - baseline.yaw) > .01f || Math.abs(frame.pitch - baseline.pitch) > .01f)
            throw new IllegalStateException("Camera changed during preset comparison; samples are not comparable");
    }
}
