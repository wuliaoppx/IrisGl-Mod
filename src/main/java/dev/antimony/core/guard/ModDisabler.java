package dev.antimony.core.guard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.antimony.AntimonyMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reversible "disable a mod" support.
 *
 * <p>A disabled jar is <b>moved</b>, never deleted: {@code mods/X.jar} becomes
 * {@code mods/.antimony-disabled/X.jar.disabled}. FML only loads {@code *.jar}
 * files from the top level of {@code mods/}, so the jar is not loaded on the
 * next launch, and the folder can be restored byte for byte at any time.</p>
 *
 * <p>The move is attempted immediately. Windows usually allows it because Java
 * opens archives with {@code FILE_SHARE_DELETE}; if the file really is locked
 * the caller gets {@link Result#LOCKED} and nothing is changed.</p>
 */
public final class ModDisabler {
    public enum Result {
        OK, MISSING, LOCKED, FAILED
    }

    /** A jar that is currently sitting in the stash folder. */
    public record DisabledMod(String original, String stored, String disabledAt, List<String> symbols) {
    }

    private static final String STASH_NAME = ".antimony-disabled";
    private static final String MANIFEST_NAME = "manifest.json";
    private static final String SUFFIX = ".disabled";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ModDisabler() {
    }

    // ---- locations -----------------------------------------------------

    /** The {@code mods} directory, resolved before Minecraft exists if need be. */
    public static Path modsDir() {
        Path cwd = Path.of(System.getProperty("user.dir", ".")).resolve("mods");
        if (Files.isDirectory(cwd)) {
            return cwd;
        }
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                Path mods = mc.gameDirectory.toPath().resolve("mods");
                if (Files.isDirectory(mods)) {
                    return mods;
                }
            }
        } catch (Throwable ignored) {
            // client not initialised yet
        }
        return cwd;
    }

    public static Path stashDir() {
        return modsDir().resolve(STASH_NAME);
    }

    // ---- disable / restore ---------------------------------------------

    public static Result disable(String jarFileName) {
        Path src = modsDir().resolve(jarFileName);
        if (!Files.isRegularFile(src)) {
            return Result.MISSING;
        }
        try {
            Path stash = stashDir();
            Files.createDirectories(stash);
            Path dst = uniqueTarget(stash, jarFileName + SUFFIX);
            String sha1 = sha1(src);
            Files.move(src, dst);
            appendManifest(new DisabledMod(jarFileName, dst.getFileName().toString(),
                    Instant.now().toString(), List.of()), sha1);
            AntimonyMod.LOG.info("[Antimony] guard: disabled {} -> {}", jarFileName, dst);
            return Result.OK;
        } catch (AccessDeniedException e) {
            AntimonyMod.LOG.warn("[Antimony] guard: {} is locked by another process", jarFileName, e);
            return Result.LOCKED;
        } catch (IOException | RuntimeException e) {
            AntimonyMod.LOG.warn("[Antimony] guard: could not disable {}", jarFileName, e);
            return Result.FAILED;
        }
    }

    public static Result restore(String storedFileName) {
        Path src = stashDir().resolve(storedFileName);
        if (!Files.isRegularFile(src)) {
            return Result.MISSING;
        }
        String original = storedFileName.endsWith(SUFFIX)
                ? storedFileName.substring(0, storedFileName.length() - SUFFIX.length())
                : storedFileName;
        Path dst = modsDir().resolve(original);
        try {
            if (Files.exists(dst)) {
                // Something already occupies the name (re-downloaded copy).
                // Never overwrite: park the restored file beside it.
                dst = uniqueTarget(modsDir(), original);
            }
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            removeManifest(storedFileName);
            AntimonyMod.LOG.info("[Antimony] guard: restored {} -> {}", storedFileName, dst);
            return Result.OK;
        } catch (AccessDeniedException e) {
            AntimonyMod.LOG.warn("[Antimony] guard: {} is locked by another process", storedFileName, e);
            return Result.LOCKED;
        } catch (IOException | RuntimeException e) {
            AntimonyMod.LOG.warn("[Antimony] guard: could not restore {}", storedFileName, e);
            return Result.FAILED;
        }
    }

    /** Everything currently stashed, newest first. Disk is the source of truth. */
    public static List<DisabledMod> listDisabled() {
        Path stash = stashDir();
        List<DisabledMod> out = new ArrayList<>();
        if (!Files.isDirectory(stash)) {
            return out;
        }
        Map<String, DisabledMod> known = readManifest();
        try (var stream = Files.list(stash)) {
            List<Path> jars = stream
                    .filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
            for (Path jar : jars) {
                String stored = jar.getFileName().toString();
                DisabledMod rec = known.get(stored);
                if (rec == null) {
                    String original = stored.substring(0, stored.length() - SUFFIX.length());
                    rec = new DisabledMod(original, stored, "", List.of());
                }
                out.add(rec);
            }
        } catch (IOException e) {
            AntimonyMod.LOG.debug("[Antimony] guard: could not list stash", e);
        }
        return out;
    }

    // ---- internals -----------------------------------------------------

    /** Returns a free path in {@code dir} for a file derived from {@code name}. */
    private static Path uniqueTarget(Path dir, String name) {
        Path target = dir.resolve(name);
        if (!Files.exists(target)) {
            return target;
        }
        for (int i = 1; ; i++) {
            Path candidate = dir.resolve(name + "." + i);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private static Path manifestPath() {
        return stashDir().resolve(MANIFEST_NAME);
    }

    private static Map<String, DisabledMod> readManifest() {
        Map<String, DisabledMod> out = new LinkedHashMap<>();
        Path file = manifestPath();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return out;
            }
            JsonArray entries = root.getAsJsonObject().getAsJsonArray("entries");
            if (entries == null) {
                return out;
            }
            for (JsonElement element : entries) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject o = element.getAsJsonObject();
                List<String> symbols = new ArrayList<>();
                if (o.has("symbols") && o.get("symbols").isJsonArray()) {
                    for (JsonElement s : o.getAsJsonArray("symbols")) {
                        symbols.add(s.getAsString());
                    }
                }
                DisabledMod rec = new DisabledMod(
                        str(o, "original"), str(o, "stored"), str(o, "disabledAt"), symbols);
                out.put(rec.stored(), rec);
            }
        } catch (Exception e) {
            AntimonyMod.LOG.debug("[Antimony] guard: unreadable manifest", e);
        }
        return out;
    }

    private static void appendManifest(DisabledMod rec, String sha1) {
        Map<String, DisabledMod> known = readManifest();
        known.put(rec.stored(), rec);
        writeManifest(known, sha1, rec.stored());
    }

    private static void removeManifest(String stored) {
        Map<String, DisabledMod> known = readManifest();
        if (known.remove(stored) != null) {
            writeManifest(known, "", "");
        }
    }

    private static void writeManifest(Map<String, DisabledMod> entries, String sha1, String sha1For) {
        try {
            Path stash = stashDir();
            Files.createDirectories(stash);
            JsonObject root = new JsonObject();
            root.addProperty("comment", "Antimony: jars parked here are NOT deleted. "
                    + "Move a file back to mods/ (drop the .disabled suffix) to re-enable it.");
            JsonArray array = new JsonArray();
            for (DisabledMod rec : entries.values()) {
                // Drop records whose file is gone (manual cleanup, failed run).
                if (!Files.exists(stashDir().resolve(rec.stored()))) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("original", rec.original());
                o.addProperty("stored", rec.stored());
                o.addProperty("disabledAt", rec.disabledAt());
                if (rec.stored().equals(sha1For) && !sha1.isEmpty()) {
                    o.addProperty("sha1", sha1);
                }
                JsonArray symbols = new JsonArray();
                for (String s : rec.symbols()) {
                    symbols.add(s);
                }
                o.add("symbols", symbols);
                array.add(o);
            }
            root.add("entries", array);
            Files.writeString(manifestPath(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AntimonyMod.LOG.debug("[Antimony] guard: could not write manifest", e);
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String sha1(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }
}
