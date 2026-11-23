package dev.antimony.core.mixin;

import dev.antimony.core.render.*;
import java.util.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Iris 1.8.14 option overlay, not a shader-file rewrite or skipped CPU pass. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.Iris",remap=false)
public abstract class IrisCpuPresetMixin {
    @Inject(method="loadExternalShaderpack",at=@At("HEAD"))
    private static void antimony$beginOptions(String name,CallbackInfoReturnable<Boolean> callback){
        RenderQualitySettings.ensureLoaded(net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath());CpuPresetOptions.begin(name);
    }
    @ModifyArg(method="loadExternalShaderpack",at=@At(value="INVOKE",target="Lnet/irisshaders/iris/shaderpack/ShaderPack;<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V"),index=1)
    private static Map<String,String> antimony$cpuOptions(Map<String,String> options){return CpuPresetOptions.overlay(options);}
    @ModifyArg(method="loadExternalShaderpack",at=@At(value="INVOKE",target="Lnet/irisshaders/iris/Iris;tryUpdateConfigPropertiesFile(Ljava/nio/file/Path;Ljava/util/Properties;)V"),index=1)
    private static Properties antimony$keepBaseOptions(Properties properties){return CpuPresetOptions.preserveBase(properties);}
    @Inject(method="tryUpdateConfigPropertiesFile",at=@At("HEAD"),cancellable=true)
    private static void antimony$noPresetReloadSave(java.nio.file.Path path,Properties properties,CallbackInfo callback){if(RenderQualitySettings.controllerReload())callback.cancel();}
    @Inject(method="loadExternalShaderpack",at=@At("RETURN"))
    private static void antimony$endOptions(String name,CallbackInfoReturnable<Boolean> callback){CpuPresetOptions.end();}
}
