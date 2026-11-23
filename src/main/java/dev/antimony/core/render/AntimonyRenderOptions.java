package dev.antimony.core.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.antimony.AntimonyMod;
import net.minecraft.network.chat.Component;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase A4: Antimony's software-rendering options, registered into Sodium's
 * video-settings screen through its public config API
 * ({@code ConfigManager.registerConfigEntryPoint}). Only instantiated when
 * Sodium is present (checked by the caller), so the class never loads - and
 * its Sodium references never resolve - in a vanilla install.
 *
 * <p>Two controls live under a dedicated "锑 · 软件渲染" page:</p>
 * <ul>
 *   <li><b>光影后处理</b> - master switch for the A3 CPU post chain
 *       ({@code antimony.post}); off skips the chain entirely.</li>
 *   <li><b>质量档（渲染比例）</b> - the chain's resolution scale
 *       ({@code antimony.post.scale}, 25%..100%); lower = faster, blurrier
 *       composite stages.</li>
 * </ul>
 *
 * <p>Values persist to {@code config/antimony-render.json} and are applied to
 * the same system properties the A3 chain reads, so the JSON file, the GUI and
 * the command line stay one source of truth (command line wins: it is read
 * first, and the GUI writes over it).</p>
 */
public final class AntimonyRenderOptions implements ConfigEntryPoint {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Live settings; mutated by the GUI, persisted, read at startup. */
    private static boolean postEnabled = true;
    private static int scalePercent = 50;

    private static Path configPath() {
        return net.minecraft.client.Minecraft.getInstance().gameDirectory
                .toPath().resolve("config").resolve("antimony-render.json");
    }

    /** Called once from client setup BEFORE the chain classes initialize. */
    public static void loadFromDisk() {
        try {
            Path path = configPath();
            if (!Files.exists(path)) {
                return;
            }
            JsonObject json = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (json == null) {
                return;
            }
            if (json.has("postEnabled")) {
                postEnabled = json.get("postEnabled").getAsBoolean();
            }
            if (json.has("scalePercent")) {
                int v = json.get("scalePercent").getAsInt();
                if (v >= 25 && v <= 100) {
                    scalePercent = v;
                }
            }
        } catch (Throwable t) {
            AntimonyMod.LOG.warn("[Antimony] render options unreadable, using defaults", t);
        }
        AntimonyMod.LOG.info("[Antimony] render options: postEnabled={} scalePercent={}",
                postEnabled, scalePercent);
    }

    private static void saveToDisk() {
        try {
            JsonObject json = Files.exists(configPath()) ? GSON.fromJson(Files.readString(configPath()), JsonObject.class) : new JsonObject();
            if(json==null)json=new JsonObject();
            json.addProperty("postEnabled", postEnabled);
            json.addProperty("scalePercent", scalePercent);
            Files.createDirectories(configPath().getParent());
            Files.writeString(configPath(), GSON.toJson(json));
        } catch (Throwable t) {
            AntimonyMod.LOG.warn("[Antimony] render options save failed", t);
        }
    }

    /** Push the current settings into the system properties the chain reads. */
    private static void applyToProperties() {
        System.setProperty("antimony.post", postEnabled ? "on" : "off");
        System.setProperty("antimony.post.scale", String.valueOf(scalePercent / 100.0));
        dev.antimony.gl.glsl.IrisPostChain.loadRenderOptions(postEnabled,scalePercent);
    }

    /** Sodium presence is checked by the caller; see AntimonyMod client setup. */
    public static void register() {
        try {
            net.caffeinemc.mods.sodium.client.config.ConfigManager.registerConfigEntryPoint(
                    AntimonyRenderOptions::new, "antimony");
            AntimonyMod.LOG.info("[Antimony] render options registered into Sodium settings");
        } catch (Throwable t) {
            // API drift or a Sodium version without the config API: the mod
            // keeps working, options just stay on the command line.
            AntimonyMod.LOG.warn("[Antimony] Sodium options registration failed", t);
        }
    }

    public static boolean postEnabled() {
        return postEnabled;
    }

    public static int scalePercent() {
        return scalePercent;
    }

    @Override
    public void registerConfigEarly(ConfigBuilder builder) {
        // Nothing: the page only needs mod metadata + one page, late is fine.
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var mod = builder.registerOwnModOptions();
        OptionPageBuilder page = builder.createOptionPage();
        page.setName(Component.translatable("锑 · 软件渲染"));

        OptionGroupBuilder group = builder.createOptionGroup();
        group.setName(Component.translatable("光影后处理（CPU）"));

        var preset=builder.createEnumOption(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("antimony","render_preset"),RenderQualitySettings.Preset.class);
        preset.setName(Component.literal("渲染档位"))
            .setDefaultValue(RenderQualitySettings.Preset.QUALITY)
            .setElementNameProvider(v->Component.literal(v.label))
            .setTooltip(v->Component.literal(RenderQualitySettings.description(v)))
            .setImpact(OptionImpact.HIGH)
            .setBinding(RenderQualitySettings::stage,RenderQualitySettings::selected)
            .setStorageHandler(RenderQualitySettings::saveSelection);
        group.addOption(preset);

        BooleanOptionBuilder post = builder.createBooleanOption(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("antimony", "post_enabled"));
        post.setName(Component.translatable("光影后处理"))
            .setTooltip(Component.translatable(
                "用 CPU 执行 Iris 光影包的全屏后处理段（FXAA、色调映射）。关闭后画面为未处理的原软件渲染输出。"))
            .setDefaultValue(true)
            .setImpact(OptionImpact.HIGH)
            .setStorageHandler(AntimonyRenderOptions::applyAndSave)
            .setBinding(v -> {
                postEnabled = v;
            }, () -> postEnabled);
        group.addOption(post);

        IntegerOptionBuilder scale = builder.createIntegerOption(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("antimony", "post_scale"));
        scale.setName(Component.translatable("质量档（渲染比例）"))
            .setTooltip(Component.translatable(
                "后处理链的分辨率比例。100% 最清晰也最慢；50% 适合大多数机器。"))
            .setRange(new Range(25, 100, 5))
            .setDefaultValue(50)
            .setImpact(OptionImpact.MEDIUM)
            .setStorageHandler(AntimonyRenderOptions::applyAndSave)
            .setValueFormatter(p -> Component.literal(p + "%"))
            .setBinding(v -> {
                scalePercent = v;
            }, () -> scalePercent);
        group.addOption(scale);

        page.addOptionGroup(group);
        mod.addPage(page);
    }

    /** Runs after the GUI applies changes: push to the chain, persist. */
    private static void applyAndSave() {
        applyToProperties();
        saveToDisk();
    }
}
