package dev.antimony.core.guard;

import dev.antimony.AntimonyMod;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;

/**
 * Quits the game and (when possible) starts it again with the exact command
 * line it was launched with, so a disable/restore decision takes effect without
 * the player having to find the launcher.
 *
 * <p>The relaunch is done by a small detached {@code .bat} that waits a few
 * seconds: our own process has to be gone before the jar files can be moved or
 * reloaded. If anything about the relaunch looks unsafe - no command line, no
 * writable temp dir - the game simply quits and the player restarts by hand.</p>
 */
public final class GuardRestart {
    private GuardRestart() {
    }

    public static void restart(Minecraft mc) {
        if (AntimonyMod.flag("antimony.guard.noRestart")) {
            mc.stop();
            return;
        }
        String commandLine = null;
        try {
            commandLine = ProcessHandle.current().info().commandLine().orElse(null);
        } catch (Throwable ignored) {
            // not available on this JVM
        }
        if (commandLine != null && !commandLine.isBlank()) {
            try {
                String workDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().toString();
                String script = "@echo off\r\n"
                        + "timeout /t 3 /nobreak >nul\r\n"
                        + "cd /d \"" + workDir + "\"\r\n"
                        + commandLine + "\r\n";
                Path bat = Files.createTempFile("antimony-restart-", ".bat");
                Files.writeString(bat, script, StandardCharsets.UTF_8);
                bat.toFile().deleteOnExit();
                new ProcessBuilder("cmd", "/c", "start", "", bat.toAbsolutePath().toString())
                        .redirectErrorStream(true)
                        .start();
                AntimonyMod.LOG.info("[Antimony] guard: relaunch scheduled via {}", bat);
            } catch (Throwable t) {
                AntimonyMod.LOG.warn("[Antimony] guard: could not schedule relaunch, quitting only", t);
            }
        } else {
            AntimonyMod.LOG.info("[Antimony] guard: command line unavailable, quitting only");
        }
        mc.stop();
    }
}
