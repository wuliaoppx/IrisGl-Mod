package dev.antimony.core.render;

/**
 * Adapts Xaero 26.6's render-thread work allowance to slow CPU frames.
 * The original writer still owns traversal, its 100-tile limit, server rules,
 * and complete-grid publication. Only its 50 ms scheduling-credit cap changes.
 */
public final class CpuMapUpdateBudget {
    // The verified writer turns each credit millisecond into 86,960 ns of work.
    private static final long NANOS_PER_CREDIT = 86_960L;
    private static final long MAX_WORK_NANOS = 50_000_000L;
    private long previousNanos;
    private boolean observed;
    private long cap = 50L;

    /** Allow at most 2% of the preceding frame, capped at 50 ms, never below native. */
    public void observe(long nowNanos) {
        if (observed) {
            long elapsed = nowNanos - previousNanos;
            long work = elapsed > 0 ? Math.min(MAX_WORK_NANOS, elapsed / 50L) : 0L;
            cap = Math.max(50L, work / NANOS_PER_CREDIT);
        }
        previousNanos = nowNanos;
        observed = true;
    }

    public long creditCap(long nativeCap) { return Math.max(nativeCap, cap); }
}
