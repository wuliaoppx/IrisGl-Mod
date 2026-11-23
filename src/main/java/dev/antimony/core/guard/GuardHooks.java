package dev.antimony.core.guard;

import dev.antimony.AntimonyMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Hooks the compatibility report into the client: once the title screen comes
 * up, the report is offered as a normal Minecraft screen (drawn by our own
 * rasterizer, so it also proves the GUI path works), and {@code /antimony guard}
 * reopens it at any time - including from inside a world.
 */
@EventBusSubscriber(modid = AntimonyMod.MODID, value = Dist.CLIENT)
public final class GuardHooks {
    private GuardHooks() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen) {
            OpenGlGuard.noteTitleScreen();
        }
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("antimony")
                .then(net.minecraft.commands.Commands.literal("guard")
                        .executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> mc.setScreen(new GuardScreen()));
                            return 1;
                        })));
    }
}
