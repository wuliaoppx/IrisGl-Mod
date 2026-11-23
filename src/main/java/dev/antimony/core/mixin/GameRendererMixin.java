package dev.antimony.core.mixin;

import dev.antimony.AntimonyMod;
import dev.antimony.core.render.IrisGlRenderer;
import dev.antimony.core.present.ScreenPresenter;
import dev.antimony.core.win32.SplashOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    private static boolean irisgl$logged;

    @Inject(method = "close()V", at = @At("HEAD"))
    private void irisgl$closePresenter(CallbackInfo ci) {
        ScreenPresenter.close();
        SplashOverlay.close();
    }

    // The first vanilla invocation follows Iris' HEAD frame-counter/timer hook.
    // A second HEAD injector can run before Iris regardless of its mixin priority.
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;isWindowActive()Z"), cancellable = true)
    private void irisgl$replaceFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        if (!irisgl$logged) {
            irisgl$logged = true;
            AntimonyMod.LOG.info("[Antimony] GameRenderer.render hook reached");
            SplashOverlay.updateStage(1, "渲染循环接管");
        }
        // Driver the splash card from the render thread: a window belongs to
        // its creating thread and the render thread pumps messages every
        // frame, so the card can never deadlock the game the way the old
        // background-thread version did (stuck in glfwPollEvents).
        SplashOverlay.renderFrame();
        // Lifecycle for the GDI present overlay: while the software renderer
        // owns the frame it paints every tick, but the moment it stops
        // (saving-and-quitting to the title screen, crash fallback, ...) the
        // layered window would otherwise keep its LAST frame on screen
        // forever - the stale "Saving world" card. Hide it explicitly on the
        // first frame that no longer renders.
        boolean replace = IrisGlRenderer.shouldReplace();
        if (replace && net.minecraft.client.Minecraft.getInstance().level != null)
            SplashOverlay.updateStage(2, "世界场景载入");
        ScreenPresenter.frameTick(replace);
        if (replace) {
            IrisGlRenderer.render((GameRenderer) (Object) this, deltaTracker);
            ci.cancel();
        }
    }
}
