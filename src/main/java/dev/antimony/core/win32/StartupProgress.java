package dev.antimony.core.win32;

/** Render-thread startup milestones. Deterministic initialization errors are not loading work. */
public final class StartupProgress {
    public static final int COMPLETE = 6;
    private static final long COMPLETED_HOLD_MS = 1_600L;
    private static final long FADE_MS = 500L;
    private boolean started, failed;
    private int stage = 1;
    private String detail = "等待进入世界";
    private long startedAt, phaseAt, completionPresentedAt = -1L;
    private Object pipeline;

    public void start(long now) {
        started = true;
        failed = false;
        pipeline = null;
        startedAt = phaseAt = now;
        stage = 1;
        detail = "等待进入世界";
        completionPresentedAt = -1L;
    }

    public void beginPipeline(Object next, long now) {
        if (!started || next == pipeline) return;
        pipeline = next;
        stage = 4;
        failed = false;
        phaseAt = now;
        detail = "读取并编译 Iris 光影程序";
        completionPresentedAt = -1L;
    }

    public void update(int next, String message, long now) {
        if (!started || failed || stage == COMPLETE) return;
        int bounded = Math.max(1, Math.min(5, next));
        if (bounded < stage) return;
        if (bounded != stage) phaseAt = now;
        stage = bounded;
        detail = message == null ? "" : message;
    }

    public void fail(String message, long now) {
        if (!started) return;
        if (!failed) { stage = Math.min(stage, 5); phaseAt = now; }
        failed = true;
        detail = message == null ? "初始化失败，请查看日志" : message;
        completionPresentedAt = -1L;
    }

    public void complete(long now) {
        if (!started || failed || stage == COMPLETE) return;
        stage = COMPLETE;
        phaseAt = now;
        detail = "光影首帧已完成";
        // Hold begins when the completed card is actually requested for presentation.
        completionPresentedAt = -1L;
    }

    public Snapshot snapshot(long now) {
        if (stage == COMPLETE && completionPresentedAt < 0L) completionPresentedAt = now;
        float opacity = 1.0F;
        if (stage == COMPLETE) {
            long age = Math.max(0L, now - completionPresentedAt);
            opacity = age <= COMPLETED_HOLD_MS ? 1.0F
                    : Math.max(0.0F, 1.0F - (age - COMPLETED_HOLD_MS) / (float) FADE_MS);
        }
        return new Snapshot(stage, detail, Math.max(0L, now - startedAt) / 1000L,
                Math.max(0L, now - phaseAt) / 1000L, stage == 5 && !failed,
                started && opacity > 0.0F, opacity, failed);
    }

    public record Snapshot(int stage, String detail, long elapsedSeconds, long phaseSeconds,
                           boolean waiting, boolean visible, float opacity, boolean failed) { }
}
