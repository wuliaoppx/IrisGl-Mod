package dev.antimony.core.render;

/** Slow-frame scheduling and session isolation; no game/GL context. */
public final class CpuMapUpdateBudgetProbe {
    public static void main(String[] args) {
        var budget = new CpuMapUpdateBudget();
        long now = 1_000_000_000L;
        budget.observe(now);
        check(budget.creditCap(50) == 50, "first call retains native scheduling");
        budget.observe(now += 16_000_000L);
        check(budget.creditCap(50) == 50, "fast frames retain native cap");
        budget.observe(now += 1_000_000_000L);
        check(budget.creditCap(50) == 229, "one-second frame permits about 20ms of native work");
        budget.observe(now += 4_000_000_000L);
        check(budget.creditCap(50) * 86_960L <= 50_000_000L, "long CPU frame has bounded allowance");
        check(budget.creditCap(50) == 574, "slow-frame cap uses original budget units");
        budget.observe(now += 60_000_000_000L);
        check(budget.creditCap(50) == 574, "pause cannot create unbounded catch-up work");
        budget.observe(now += 16_000_000L);
        check(budget.creditCap(50) == 50, "fast frames do not inherit slow allowance");
        budget.observe(now -= 100_000L);
        check(budget.creditCap(50) == 50, "nonpositive interval is conservative");
        check(budget.creditCap(900) == 900, "never reduce a larger native allowance");
        var independent = new CpuMapUpdateBudget();
        independent.observe(now);
        check(independent.creditCap(50) == 50, "new writer session has independent history");
        var wrap = new CpuMapUpdateBudget();
        wrap.observe(Long.MAX_VALUE - 1_000_000L);
        wrap.observe(Long.MIN_VALUE + 998_999_999L);
        check(wrap.creditCap(50) == 229, "nanoTime wrap preserves positive elapsed difference");
        System.out.println("CPU MAP BUDGET PROBE OK: native fast frames, slow-frame allowance, bounded stalls, recovery and writer isolation");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
