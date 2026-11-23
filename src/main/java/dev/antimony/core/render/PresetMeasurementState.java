package dev.antimony.core.render;

/** A rejected measurement is not a renderer failure. The invalid result stays invalid for this run. */
public final class PresetMeasurementState {
    private final PresetComparisonGuard comparison = new PresetComparisonGuard();
    private String failure;

    public boolean observe(PresetComparisonGuard.Observation frame, RenderQualitySettings.Preset preset) {
        if (failure != null) return false;
        try {
            comparison.accept(frame, preset);
            return true;
        } catch (IllegalStateException invalid) {
            failure = invalid.getMessage();
            return false;
        }
    }

    public void invalidate(String reason) { if (failure == null) failure = reason; }
    public boolean invalid() { return failure != null; }
    public String failure() { return failure; }
}
