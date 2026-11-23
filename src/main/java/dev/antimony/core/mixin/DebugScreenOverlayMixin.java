package dev.antimony.core.mixin;

import java.util.List;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Brands the F3 screen: prefixes the FPS line with a bright-aqua "IrisGL"
 * tag, like a driver overlay. {@code mc.fpsString} looks like
 * "60 fps T: 60 vsync ..." - only the leading digits are the FPS number, so
 * the marker goes in front of it.
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {
    /** §b = aqua, matching the aqua FPS digits vanilla already uses. */
    private static final String PREFIX = "\u00a7b[IrisGL]\u00a7r ";

    @Inject(method = "getGameInformation", at = @At("RETURN"), cancellable = true)
    private void irisgl$brandFpsLine(CallbackInfoReturnable<List<String>> cir) {
        List<String> lines = cir.getReturnValue();
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s.matches("\\d+ fps.*")) {
                lines.set(i, PREFIX + s);
                break;
            }
        }
    }
}
