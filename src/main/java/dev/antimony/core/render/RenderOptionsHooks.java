package dev.antimony.core.render;

import dev.antimony.AntimonyMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Phase A4 wiring: loads the persisted render options and registers the
 * Sodium options page - both strictly behind a Sodium-present check, so the
 * Sodium-referencing {@link AntimonyRenderOptions} class never loads (and
 * never triggers a ClassNotFoundException) on a Sodium-less install.
 *
 * <p>Iris presence is deliberately NOT required for registration: the user
 * asked for the toggles to "load only when Iris is detected", which the
 * runtime honours by keeping the post chain dormant without Iris
 * ({@code IrisPostChain.pipeline()} returns null); registering the page
 * whenever Sodium exists keeps the settings visible before a shader pack is
 * picked and lets the player pre-configure quality.</p>
 */
@EventBusSubscriber(modid = AntimonyMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class RenderOptionsHooks {
    private RenderOptionsHooks() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(RenderOptionsHooks::setup);
    }

    private static void setup() {
        RenderQualitySettings.ensureLoaded(net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath());
        boolean sodium = net.neoforged.fml.ModList.get().isLoaded("sodium");
        if (!sodium) {
            AntimonyMod.LOG.info("[Antimony] render options: Sodium absent, settings page not registered");
            return;
        }
        // Load persisted values FIRST so the chain's first frame already uses
        // them, then hand the page to Sodium.
        AntimonyRenderOptions.loadFromDisk();
        AntimonyRenderOptions.register();
    }
}
