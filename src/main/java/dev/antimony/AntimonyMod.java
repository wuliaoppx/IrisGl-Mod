package dev.antimony;

import com.mojang.logging.LogUtils;
import dev.antimony.core.win32.SplashOverlay;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Antimony (锑, Sb) - the element that replaced silicon in the rendering
 * pipeline. Formerly known as IrisGL; the SGI IRIS GL software rasterizer
 * underneath keeps its historical name.
 */
@Mod(AntimonyMod.MODID)
public final class AntimonyMod {
    public static final String MODID = "antimony";
    public static final Logger LOG = LogUtils.getLogger();

    /**
     * True when a debug switch is set and not explicitly "false". Unlike
     * {@link Boolean#getBoolean} this also honours bare flags such as
     * {@code -Dantimony.debug.threads}, which used to read as false and
     * silently disabled the diagnostics.
     */
    public static boolean flag(String name) {
        String v = System.getProperty(name);
        return v != null && !"false".equalsIgnoreCase(v);
    }

    public AntimonyMod(IEventBus modEventBus, ModContainer modContainer) {
        LOG.info("[Antimony] loading (SGI IRIS GL software renderer, mod antimony)");
        if (FMLEnvironment.dist.isClient()) {
            // Driver-style corner notification: visible from the NeoForge
            // loading screen until the software renderer has drawn a few
            // in-world frames.
            SplashOverlay.start();
            // Scan the mods folder for jars that still talk to OpenGL directly:
            // once the software renderer owns the frame those draws can never
            // work, so they are reported (and, with the default settings,
            // shown as an error dialog) before the player wastes a session.
            dev.antimony.core.guard.OpenGlGuard.scanAsync();
            // Opt-in filesystem round-trip test for the disable/restore path.
            dev.antimony.core.guard.GuardSelfTest.runIfRequested();
            // CPU shader lifecycle/source interception is enabled by default. Explicit
            // report/off overrides are diagnostics, not a way to run the CPU shader chain.
            dev.antimony.gl.GlCompat.init();
            if (dev.antimony.gl.GlCompat.active()) {
                dev.antimony.gl.footprint.GlFootprint.scanAsync();
            }
        }
    }
}
