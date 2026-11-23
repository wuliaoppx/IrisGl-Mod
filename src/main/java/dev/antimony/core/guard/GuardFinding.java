package dev.antimony.core.guard;

import java.util.List;

/**
 * One mod jar that was found to talk to OpenGL directly.
 *
 * @param fileName    the jar name inside {@code mods/}, used as the handle for
 *                    disabling it
 * @param modId       mod id from {@code META-INF/neoforge.mods.toml}, may be empty
 * @param displayName display name from the same file, may be empty
 * @param hard        true when the jar contains real GL draw/shader entry points
 *                    (those draws can never reach the screen), false when it
 *                    merely references the {@code org.lwjgl.opengl} package
 * @param symbols     the matched GL symbols, capped for display
 */
public record GuardFinding(String fileName, String modId, String displayName,
                           boolean hard, List<String> symbols) {

    /** What the player should see: display name first, then mod id, then file name. */
    public String label() {
        if (this.displayName != null && !this.displayName.isBlank()) {
            return this.displayName;
        }
        if (this.modId != null && !this.modId.isBlank()) {
            return this.modId;
        }
        return this.fileName;
    }
}
