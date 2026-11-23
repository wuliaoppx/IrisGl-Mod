package dev.antimony.gl.glsl;

import java.lang.reflect.*;
import java.util.*;

/** Pure Java exercise of physical shadow slot/RT identity resolution; no GL or game startup. */
public final class CpuShadowBindingsProbe {
    public static final class Framebuffer {
        private final Map<Integer,Integer> slots;
        Framebuffer(Map<Integer,Integer> slots){this.slots=Map.copyOf(slots);}
        public int getColorAttachment(int slot){return slots.getOrDefault(slot,0);}
    }
    public static final class Target {
        private final int main,alt;
        private final String format;
        Target(int main,int alt){this(main,alt,"RGBA32F");}
        Target(int main,int alt,String format){this.main=main;this.alt=alt;this.format=format;}
        public int getMainTexture(){return main;}
        public int getAltTexture(){return alt;}
        public String getInternalFormat(){return format;}
    }
    public static final class Targets {
        private final Target[] targets;
        Targets(Target...targets){this.targets=targets;}
        public int getRenderTargetCount(){return targets.length;}
        public Target get(int index){return targets[index];}
    }
    public static final class Pipeline {Object shadowRenderTargets;}
    public static final class Shader {Object writingToBeforeTranslucent;}
    public static void main(String[] args)throws Exception{
        Pipeline owner=new Pipeline();owner.shadowRenderTargets=new Targets(new Target(10,11),new Target(20,21));
        set("pipeline",owner);set("resolution",2);
        Shader shader=new Shader();shader.writingToBeforeTranslucent=new Framebuffer(Map.of(0,21));
        var defaults=new IrisTerrainSources.DrawDirectives(List.of(7),true,"GREATER",.1f,"NONE",List.of());
        var binding=IrisCpuShadowTargets.bindings(shader,1,defaults);
        check(Arrays.equals(binding.drawBuffers(),new int[]{1}),"implicit layout follows actual FBO slot, not guessed buffer 0 or source hint");
        check(binding.attachments().get(1).image().matches(2,2),"owned physical target format/extent");
        int[] editable=binding.drawBuffers();editable[0]=0;
        check(binding.drawBuffers()[0]==1,"binding output map is immutable");
        var explicit=new IrisTerrainSources.DrawDirectives(List.of(1),false,"GREATER",.1f,"NONE",List.of());
        IrisCpuShadowTargets.bindings(shader,1,explicit);
        reject(()->IrisCpuShadowTargets.bindings(shader,1,new IrisTerrainSources.DrawDirectives(List.of(0),false,"GREATER",.1f,"NONE",List.of())),"explicit source/FBO disagreement");
        reject(()->IrisCpuShadowTargets.bindings(shader,1,null),"missing source directives");
        shader.writingToBeforeTranslucent=new Framebuffer(Map.of(0,999));
        reject(()->IrisCpuShadowTargets.bindings(shader,1,defaults),"unowned physical texture");
        shader.writingToBeforeTranslucent=new Framebuffer(Map.of(0,20,1,20));
        reject(()->IrisCpuShadowTargets.bindings(shader,2,defaults),"aliased output slots");
        shader.writingToBeforeTranslucent=new Framebuffer(Map.of(0,21));
        Field pool=IrisCpuShadowTargets.class.getDeclaredField("images");pool.setAccessible(true);
        ((Map<Integer,CpuImage>)pool.get(null)).put(20,new CpuImage(2,2));
        set("readyOrdinal",3L);set("resourceGeneration",4L);set("readyGeneration",4L);set("currentTextures",Set.of(21));
        check(IrisCpuShadowTargets.resolve(owner,3,21)!=null,"matching pipeline/layout/ordinal permits current target");
        check(IrisCpuShadowTargets.resolve(owner,3,20)==null,"retained but not-current target cannot be sampled");
        set("resourceGeneration",5L);
        check(!IrisCpuShadowTargets.ready(owner,3),"resource generation changes invalidate readiness");
        Map<String,TerrainFragmentDraw.Encoding> formats=Map.of(
            "RGB8",TerrainFragmentDraw.Encoding.RGB8_UNORM,
            "RGB16F",TerrainFragmentDraw.Encoding.RGB16F,
            "R11F_G11F_B10F",TerrainFragmentDraw.Encoding.R11F_G11F_B10F,
            "RGBA16",TerrainFragmentDraw.Encoding.RGBA16_UNORM,
            "RGBA",TerrainFragmentDraw.Encoding.RGBA8_UNORM);
        int texture=30;
        for(var format:formats.entrySet()){
            owner.shadowRenderTargets=new Targets(new Target(texture,texture+1,format.getKey()));
            shader.writingToBeforeTranslucent=new Framebuffer(Map.of(0,texture+1));
            var target=IrisCpuShadowTargets.bindings(shader,1,defaults).attachments().get(0);
            check(target.encoding()==format.getValue(),"actual target format mapping "+format.getKey());
            target.image().clear(.2003f,3.14159f,7.9001f,.375f);
            float[] values={.2003f,3.14159f,7.9001f,.375f};
            for(int channel=0;channel<4;channel++)check(target.image().rgba[channel]==format.getValue().encode(values[channel],channel),
                "owned CPU storage honors target format and implicit alpha "+format.getKey()+" channel "+channel);
            texture+=2;
        }
        reject(()->IrisCpuShadowTargets.encoding("UNSUPPORTED_FORMAT"),"unknown attachment format");
        System.out.println("CPU SHADOW BINDINGS PROBE OK: legal implicit physical layout, explicit mismatch/missing/alias rejection, immutable contract, layout-generation readiness and actual Solas RGB/HDR format storage");
    }
    private static void set(String name,Object value)throws Exception{Field field=IrisCpuShadowTargets.class.getDeclaredField(name);field.setAccessible(true);field.set(null,value);}
    private static void reject(Runnable action,String name){try{action.run();throw new AssertionError("Expected rejection: "+name);}catch(IllegalStateException|IllegalArgumentException expected){}}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
}
