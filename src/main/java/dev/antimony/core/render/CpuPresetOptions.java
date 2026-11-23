package dev.antimony.core.render;

import java.util.*;

/** Scoped option overlay at Iris construction; persisted base choices are restored before any normal save. */
public final class CpuPresetOptions {
    private static final ThreadLocal<Context> LOADING=new ThreadLocal<>();
    private static final class Context {final String name;Map<String,String> base=Map.of(),forced=Map.of();Context(String name){this.name=name;}}
    private CpuPresetOptions(){}
    public static void begin(String name){LOADING.set(new Context(name));}
    public static void end(){LOADING.remove();}
    public static Map<String,String> overlay(Map<String,String> original){
        Context context=LOADING.get();if(context==null)return original;
        context.base=Map.copyOf(original);context.forced=RenderQualitySettings.overridesFor(context.name,RenderQualitySettings.active());
        if(context.forced.isEmpty())return original;
        Map<String,String> result=new LinkedHashMap<>(original);result.putAll(context.forced);return result;
    }
    public static Properties preserveBase(Properties computed){
        Context context=LOADING.get();if(context==null||context.forced.isEmpty())return computed;
        Properties result=new Properties();result.putAll(computed);
        for(String name:context.forced.keySet()){String value=context.base.get(name);if(value==null)result.remove(name);else result.setProperty(name,value);}
        return result;
    }
}
