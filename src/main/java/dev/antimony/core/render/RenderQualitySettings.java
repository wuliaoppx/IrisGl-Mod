package dev.antimony.core.render;

import com.google.gson.*;
import dev.antimony.AntimonyMod;
import java.nio.file.*;
import java.util.*;

/** Persisted CPU presets. Shader options are transient and never written into a shader zip. */
public final class RenderQualitySettings {
    public enum Preset {
        QUALITY("画质",1f), BALANCED("均衡",.75f), PERFORMANCE("性能",.5f);
        public final String label;public final float sceneScale;
        Preset(String label,float sceneScale){this.label=label;this.sceneScale=sceneScale;}
        public static Preset parse(String value){return value==null?QUALITY:valueOf(value.toUpperCase(Locale.ROOT));}
    }
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static Preset active=Preset.QUALITY,selected=Preset.QUALITY;
    private static Path config;
    private static boolean pending,controllerReload;
    private static int windowWidth=1,windowHeight=1,sceneWidth=1,sceneHeight=1;
    private RenderQualitySettings(){}
    public static synchronized void ensureLoaded(Path gameDirectory){
        if(config!=null)return;
        config=gameDirectory.resolve("config/antimony-render.json");
        try{
            JsonObject data=read();if(data.has("preset"))active=Preset.parse(data.get("preset").getAsString());
            String override=System.getProperty("antimony.render.preset");if(override!=null)active=Preset.parse(override);
        }catch(Exception invalid){active=Preset.QUALITY;AntimonyMod.LOG.warn("[Antimony] invalid CPU preset; preserving quality default",invalid);}
        selected=active;
        AntimonyMod.LOG.info("[Antimony] CPU preset loaded: {} sceneScale={} defaultQuality={}",active,active.sceneScale,active==Preset.QUALITY);
    }
    public static Preset active(){return active;}
    public static Preset selected(){return selected;}
    public static void stage(Preset preset){selected=Objects.requireNonNull(preset);}
    public static synchronized void saveSelection(){
        if(config==null)throw new IllegalStateException("CPU preset configuration not initialized");
        try{JsonObject data=read();data.addProperty("preset",selected.name().toLowerCase(Locale.ROOT));Files.createDirectories(config.getParent());Files.writeString(config,GSON.toJson(data));pending=selected!=active;}
        catch(Exception failure){AntimonyMod.LOG.error("[Antimony] CPU preset could not be saved",failure);}
    }
    private static JsonObject read()throws java.io.IOException{
        if(config==null||!Files.exists(config))return new JsonObject();
        JsonObject data=GSON.fromJson(Files.readString(config),JsonObject.class);return data==null?new JsonObject():data;
    }
    /** Render-thread boundary only; do not destroy an Iris pipeline during a worker draw. */
    public static void applyPending(){
        if(!pending)return;pending=false;Preset previous=active;active=selected;
        try{
            Class<?> iris=Class.forName("net.irisshaders.iris.Iris");
            Object current=iris.getMethod("getCurrentPack").invoke(null);
            if(current instanceof Optional<?> pack&&pack.isPresent()){
                controllerReload=true;
                try{iris.getMethod("reload").invoke(null);}finally{controllerReload=false;}
            }
            AntimonyMod.LOG.info("[Antimony] CPU preset applied: {} -> {}; lowered features={}",previous,active,overridesFor(currentPackName(),active));
        }catch(ClassNotFoundException absent){/* Plain CPU renderer still supports the scene scale. */}
        catch(Exception failure){controllerReload=false;AntimonyMod.LOG.error("[Antimony] CPU preset reload failed; not a completed shader frame",failure);dev.antimony.core.win32.SplashOverlay.fail("渲染档位重载失败，请查看日志");}
    }
    public static boolean controllerReload(){return controllerReload;}
    private static String currentPackName(){try{return String.valueOf(Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPackName").invoke(null));}catch(Exception unavailable){return "";}}
    /** Only option names and values verified in the original MakeUp 9.6 source. */
    public static Map<String,String> overridesFor(String pack,Preset preset){
        if(!"MakeUp-UltraFast-9.6.zip".equals(pack)||preset==Preset.QUALITY)return Map.of();
        if(preset==Preset.BALANCED)return Map.of("SHADOW_QTY_SLIDER","1");
        return Map.of("SHADOW_QTY_SLIDER","1","BLOOM","false","VOL_LIGHT","0","AO","0","V_CLOUDS","0","REFLECTION_SLIDER","0");
    }
    public static String description(Preset preset){return switch(preset){
        case QUALITY->"窗口原始分辨率；保留当前光影选项。";
        case BALANCED->"世界与后处理 75% 分辨率；MakeUp 使用较低阴影精度，保留特效。";
        case PERFORMANCE->"世界与后处理 50% 分辨率；MakeUp 关闭 Bloom、光束、AO、体积云及反射。Solas 不关闭 LPV/计算。GUI 保持窗口分辨率。";
    };}
    public static void dimensions(int w,int h,int sw,int sh){windowWidth=w;windowHeight=h;sceneWidth=sw;sceneHeight=sh;}
    public static int mapWidth(int nativeWidth){return Math.max(1,Math.round(nativeWidth*sceneWidth/(float)windowWidth));}
    public static int mapHeight(int nativeHeight){return Math.max(1,Math.round(nativeHeight*sceneHeight/(float)windowHeight));}
}
