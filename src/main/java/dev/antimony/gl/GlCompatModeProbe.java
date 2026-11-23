package dev.antimony.gl;

/** Each Gradle task launches a fresh JVM: tests the real static mode selected at bootstrap. */
public final class GlCompatModeProbe {
    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("expected MODE default|explicit");
        GlCompat.Mode expected = GlCompat.Mode.valueOf(args[0]);
        boolean defaultCase = args[1].equals("default");
        if (defaultCase && System.getProperty("antimony.glcompat") != null)
            throw new AssertionError("Default test must not silently inject -Dantimony.glcompat");
        if (!defaultCase && System.getProperty("antimony.glcompat") == null)
            throw new AssertionError("Explicit override missing");
        if (GlCompat.mode() != expected) throw new AssertionError("actual mode " + GlCompat.mode() + " != " + expected);
        if (GlCompat.intercepting() != (expected == GlCompat.Mode.ON)) throw new AssertionError("interception gate disagrees with mode");
        if (GlCompat.active() != (expected != GlCompat.Mode.OFF)) throw new AssertionError("active gate disagrees with mode");
        System.out.println("PASS fresh JVM GlCompat mode=" + GlCompat.mode() + "; selection=" + args[1]
                + "; property=" + System.getProperty("antimony.glcompat", "<absent>"));
    }
}
