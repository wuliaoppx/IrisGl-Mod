package dev.antimony.core.render;

import dev.antimony.core.irisgl.*;
import java.nio.file.*;
import java.util.*;

/** Configuration migration, temporary pack overlays and full-resolution GUI scaling, without a game. */
public final class RenderQualityProbe {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("antimony-quality-probe");Path config=root.resolve("config/antimony-render.json");Files.createDirectories(config.getParent());
        Files.writeString(config,"{\"postEnabled\":true,\"scalePercent\":50,\"custom\":17}");
        RenderQualitySettings.ensureLoaded(root);check(RenderQualitySettings.active()==RenderQualitySettings.Preset.QUALITY,"old config migrates without lowering quality");
        RenderQualitySettings.stage(RenderQualitySettings.Preset.PERFORMANCE);RenderQualitySettings.saveSelection();String data=Files.readString(config);
        check(data.contains("performance")&&data.contains("custom")&&data.contains("scalePercent"),"selection persists without deleting existing settings");
        RenderQualitySettings.applyPending();
        Map<String,String> base=Map.of("BLOOM","true","AO","1","unrelated","original");CpuPresetOptions.begin("MakeUp-UltraFast-9.6.zip");Map<String,String> over=CpuPresetOptions.overlay(base);
        check(over.get("BLOOM").equals("false")&&base.get("BLOOM").equals("true"),"temporary options never mutate base map");
        Properties computed=new Properties();computed.putAll(over);Properties restored=CpuPresetOptions.preserveBase(computed);
        check(restored.getProperty("BLOOM").equals("true")&&restored.getProperty("AO").equals("1")&&!restored.containsKey("VOL_LIGHT"),"forced options restore base values or absence before normal persistence");CpuPresetOptions.end();
        check(RenderQualitySettings.overridesFor("Solas Shader V3.7b.zip",RenderQualitySettings.Preset.PERFORMANCE).isEmpty(),"Solas LPV/compute not removed by a generic feature preset");
        IrisGlFramebuffer small=new IrisGlFramebuffer(2,2),large=new IrisGlFramebuffer(4,4);small.color[0]=0xff010203;small.color[1]=0xff123456;small.color[2]=0xffabcdef;small.color[3]=0xff987654;small.depth[0]=.1f;small.depth[1]=.2f;small.depth[2]=.3f;small.depth[3]=.4f;
        SceneFramebufferScaler.copy(small,large);for(int y=0;y<4;y++)for(int x=0;x<4;x++)check(large.color[y*4+x]==small.color[(y/2)*2+x/2],"full GUI canvas retains scene pixels");
        IrisGlContext context=new IrisGlContext();SceneFramebufferScaler.beginScene(context,80,40,RenderQualitySettings.Preset.PERFORMANCE);IrisGlFramebuffer first=context.framebuffer;check(first.width==40&&first.height==20,"real scene dimensions lowered");SceneFramebufferScaler.prepareGui(context);check(context.framebuffer.width==80&&context.framebuffer.height==40,"GUI canvas remains full resolution");SceneFramebufferScaler.beginScene(context,80,40,RenderQualitySettings.Preset.PERFORMANCE);check(context.framebuffer==first,"scene framebuffer reused, not allocated each frame");
        PresetComparisonGuard guard=new PresetComparisonGuard();
        guard.accept(new PresetComparisonGuard.Observation(1920,1055,1920,1055,1,2,3,10,20,100),RenderQualitySettings.Preset.QUALITY);
        guard.accept(new PresetComparisonGuard.Observation(1920,1055,1440,791,1,2,3,10,20,50),RenderQualitySettings.Preset.BALANCED);
        rejected(()->guard.accept(new PresetComparisonGuard.Observation(988,468,494,234,1,2,3,10,20,50),RenderQualitySettings.Preset.PERFORMANCE),"window resize must invalidate speed comparison");
        rejected(()->guard.accept(new PresetComparisonGuard.Observation(1920,1055,960,528,1,2,3,10,21,50),RenderQualitySettings.Preset.PERFORMANCE),"camera turn must invalidate speed comparison");
        rejected(()->guard.accept(new PresetComparisonGuard.Observation(1920,1055,960,528,1,2,3,10,20,0),RenderQualitySettings.Preset.PERFORMANCE),"empty scene must not pass as faster geometry");
        rejected(()->guard.accept(new PresetComparisonGuard.Observation(1920,1055,960,528,Double.NaN,2,3,10,20,50),RenderQualitySettings.Preset.PERFORMANCE),"invalid camera must be rejected");
        PresetMeasurementState measurement=new PresetMeasurementState();
        check(measurement.observe(new PresetComparisonGuard.Observation(1920,1055,1920,1055,1,2,3,10,20,100),RenderQualitySettings.Preset.QUALITY),"valid measurement accepted");
        check(!measurement.observe(new PresetComparisonGuard.Observation(1920,1055,1920,1055,1,2,3,11,20,100),RenderQualitySettings.Preset.QUALITY),"camera change rejects measurement without throwing into renderer");
        check(measurement.invalid()&&measurement.failure().contains("Camera changed"),"invalid reason retained");
        check(!measurement.observe(new PresetComparisonGuard.Observation(1920,1055,1920,1055,1,2,3,10,20,100),RenderQualitySettings.Preset.QUALITY),"invalid timing run never silently passes later");
        System.out.println("RENDER QUALITY PROBE OK: quality default, persisted selection, option restoration, Solas preservation, reusable full-GUI scaling, honest comparison and nonfatal diagnostic invalidation");
    }
    private static void rejected(Runnable action,String message){try{action.run();}catch(IllegalStateException expected){return;}throw new AssertionError(message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
