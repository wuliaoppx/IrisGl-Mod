package dev.antimony.core.guard;

import dev.antimony.AntimonyMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Filesystem self-test for {@link ModDisabler}, enabled with
 * {@code -Dantimony.guard.selftest}. It exercises the whole disable/restore
 * round trip on a throw-away text file (never a jar, so a failed run can never
 * leave something loadable behind) and logs a single PASS/FAIL line.
 */
public final class GuardSelfTest {
    private GuardSelfTest() {
    }

    public static void runIfRequested() {
        if (!AntimonyMod.flag("antimony.guard.selftest")) {
            return;
        }
        Thread t = new Thread(GuardSelfTest::run, "antimony-guard-selftest");
        t.setDaemon(true);
        t.start();
    }

    /** Drops probe files left behind by an interrupted earlier run. */
    private static void cleanupStale() {
        try (var stream = Files.list(ModDisabler.stashDir())) {
            stream.filter(p -> p.getFileName().toString().startsWith("antimony-selftest-"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // still locked; next run will try again
                        }
                    });
        } catch (IOException ignored) {
            // no stash yet
        }
    }

    private static void run() {
        Path mods = ModDisabler.modsDir();
        String probe = "antimony-selftest-" + System.currentTimeMillis() + ".txt";
        Path probePath = mods.resolve(probe);
        String stored = probe + ".disabled";
        Path stashed = ModDisabler.stashDir().resolve(stored);
        cleanupStale();
        try {
            Files.createDirectories(mods);
            Files.writeString(probePath, "probe", StandardCharsets.UTF_8);

            ModDisabler.Result disable = ModDisabler.disable(probe);
            boolean listed = ModDisabler.listDisabled().stream().anyMatch(m -> m.stored().equals(stored));
            boolean moved = !Files.exists(probePath) && Files.isRegularFile(stashed);

            ModDisabler.Result restore = ModDisabler.restore(stored);
            boolean back = Files.isRegularFile(probePath) && !Files.exists(stashed);

            boolean pass = disable == ModDisabler.Result.OK && listed && moved
                    && restore == ModDisabler.Result.OK && back;
            AntimonyMod.LOG.info("[Antimony] guard selftest: disable={} listed={} moved={} restore={} back={} => {}",
                    disable, listed, moved, restore, back, pass ? "PASS" : "FAIL");
            Files.deleteIfExists(probePath);

            // Optional second stage: round-trip a REAL loaded mod jar, which is
            // the case that matters - an open archive may refuse to be moved.
            String jar = System.getProperty("antimony.guard.selftest.jar", "");
            if (!jar.isBlank()) {
                ModDisabler.Result jd = ModDisabler.disable(jar);
                boolean jMoved = !Files.exists(mods.resolve(jar))
                        && Files.isRegularFile(ModDisabler.stashDir().resolve(jar + ".disabled"));
                ModDisabler.Result jr = ModDisabler.restore(jar + ".disabled");
                boolean jBack = Files.isRegularFile(mods.resolve(jar));
                AntimonyMod.LOG.info("[Antimony] guard selftest (real jar {}): disable={} moved={} restore={} back={} => {}",
                        jar, jd, jMoved, jr, jBack, (jd == ModDisabler.Result.OK && jMoved && jr == ModDisabler.Result.OK && jBack) ? "PASS" : "FAIL");
            }
        } catch (IOException | RuntimeException e) {
            AntimonyMod.LOG.error("[Antimony] guard selftest threw", e);
            try {
                Files.deleteIfExists(probePath);
                Files.deleteIfExists(stashed);
            } catch (IOException ignored) {
                // best effort cleanup
            }
        }
    }
}
