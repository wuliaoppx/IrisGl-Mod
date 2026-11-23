package dev.antimony.gl.glsl;

import dev.antimony.core.irisgl.*;
import java.util.*;

/** Physical Iris shadow attachment IDs with independent CPU depth/color storage. */
public final class IrisCpuShadowTargets {
    private static Object pipeline,targetOwner;
    private static String layout="";
    private static Set<Integer> currentTextures=Set.of();
    private static long resourceGeneration,readyGeneration;
    private static int resolution;
    private static final Map<Integer,CpuImage> images=new HashMap<>();
    private static IrisGlContext context;
    private static int mainDepth,opaqueDepth;
    private static long readyOrdinal=-1;
    private IrisCpuShadowTargets() { }
    public static IrisGlContext begin(Object current,IrisShadowFrame shadow){
        Object targets=IrisSamplerBridge.field(current,"shadowRenderTargets");
        if(targets==null)throw new IllegalStateException("No owned Iris shadow targets");
        int size=shadow.resolution();
        int nextMain=id(IrisSamplerBridge.call(IrisSamplerBridge.call(targets,"getDepthTexture"),"getTextureId"));
        int nextOpaque=id(IrisSamplerBridge.call(IrisSamplerBridge.call(targets,"getDepthTextureNoTranslucents"),"getTextureId"));
        if(nextMain<=0||nextOpaque<=0||nextMain==nextOpaque)throw new IllegalStateException("Invalid shadow depth identities");
        Set<Integer> ids=new LinkedHashSet<>(List.of(nextMain,nextOpaque));
        Map<Integer,TerrainFragmentDraw.Encoding> colorEncodings=new LinkedHashMap<>();
        StringBuilder nextLayout=new StringBuilder().append(size).append(':').append(nextMain).append('/').append(nextOpaque);
        Object count=IrisSamplerBridge.call(targets,"getRenderTargetCount");
        if(!(count instanceof Integer n))throw new IllegalStateException("Missing shadow target count");
        for(int i=0;i<n;i++){
            Object target=callIndex(targets,"get",i);if(target==null)continue;
            int main=id(IrisSamplerBridge.call(target,"getMainTexture")),alt=id(IrisSamplerBridge.call(target,"getAltTexture"));
            if(main<=0||alt<=0||!ids.add(main)||!ids.add(alt))throw new IllegalStateException("Invalid/aliased shadow color layout");
            Object format=IrisSamplerBridge.call(target,"getInternalFormat");
            if(format==null)throw new IllegalStateException("Missing owned shadow color format at target "+i);
            TerrainFragmentDraw.Encoding encoding=encoding(String.valueOf(format));
            nextLayout.append(';').append(i).append(':').append(main).append('/').append(alt).append('@').append(encoding);
            colorEncodings.put(main,encoding);colorEncodings.put(alt,encoding);
        }
        boolean reset=pipeline!=current||targetOwner!=targets||!layout.equals(nextLayout.toString());
        if(context==null||resolution!=size)context=new IrisGlContext();
        if(reset){images.clear();resourceGeneration++;}
        pipeline=current;targetOwner=targets;resolution=size;layout=nextLayout.toString();currentTextures=Set.copyOf(ids);
        mainDepth=nextMain;opaqueDepth=nextOpaque;
        readyOrdinal=-1;context.beginFrame(size,size);context.framebuffer.clearDepth();
        for(int texture:currentTextures)image(texture);
        for(var entry:colorEncodings.entrySet())image(entry.getKey(),entry.getValue());
        IrisClearBridge.apply(IrisClearBridge.planField(current,reset?"shadowClearPassesFull":"shadowClearPasses",1,1,1),images);
        context.cull=false;context.depthTest=true;context.depthWrite=true;context.blend=false;
        return context;
    }
    public record Bindings(int[] drawBuffers,Map<Integer,TerrainFragmentDraw.Attachment> attachments) {
        public Bindings{drawBuffers=drawBuffers.clone();attachments=Map.copyOf(attachments);}
        @Override public int[] drawBuffers(){return drawBuffers.clone();}
    }
    /** Resolve actual output locations from Iris' physical FBO, including its legal default layout. */
    public static Bindings bindings(Object shader,int outputSlots,IrisTerrainSources.DrawDirectives directives){
        if(directives==null||outputSlots<1||outputSlots>8)throw new IllegalStateException("Missing/unsupported shadow output contract");
        if(!directives.bufferBlendOverrides().isEmpty())throw new IllegalStateException("Per-buffer shadow blend overrides not yet supported: "+directives);
        Object framebuffer=IrisSamplerBridge.field(shader,"writingToBeforeTranslucent");
        Object targets=IrisSamplerBridge.field(pipeline,"shadowRenderTargets");
        Object countValue=IrisSamplerBridge.call(targets,"getRenderTargetCount");
        if(framebuffer==null||!(countValue instanceof Integer count))throw new IllegalStateException("No current physical shadow FBO/target owner");
        int[] buffers=new int[outputSlots];
        Map<Integer,TerrainFragmentDraw.Attachment> attachments=new LinkedHashMap<>();
        Set<Integer> textures=new HashSet<>();
        for(int location=0;location<outputSlots;location++){
            int texture=id(callIndex(framebuffer,"getColorAttachment",location));
            int logical=-1;Object selected=null;
            for(int index=0;index<count;index++){
                Object target=callIndex(targets,"get",index);if(target==null)continue;
                if(texture==id(IrisSamplerBridge.call(target,"getMainTexture"))||texture==id(IrisSamplerBridge.call(target,"getAltTexture"))){
                    if(logical!=-1)throw new IllegalStateException("Aliased shadow RT identity");logical=index;selected=target;
                }
            }
            if(texture<=0||logical<0||!textures.add(texture))throw new IllegalStateException("Missing/aliased shadow attachment at output location "+location);
            if(!directives.unknownDrawBuffers()&&(location>=directives.drawBuffers().size()||directives.drawBuffers().get(location)!=logical))
                throw new IllegalStateException("Explicit shadow directive/FBO mapping mismatch at location "+location);
            Object format=IrisSamplerBridge.call(selected,"getInternalFormat");
            if(format==null)throw new IllegalStateException("Missing owned shadow format");
            buffers[location]=logical;
            TerrainFragmentDraw.Encoding targetEncoding=encoding(String.valueOf(format));
            if(attachments.put(logical,new TerrainFragmentDraw.Attachment(image(texture,targetEncoding),targetEncoding))!=null)
                throw new IllegalStateException("Duplicate shadow RT location");
        }
        return new Bindings(buffers,attachments);
    }

    public static Map<Integer,TerrainFragmentDraw.Attachment> attachments(Object shader,int[] drawBuffers){
        Object framebuffer=IrisSamplerBridge.field(shader,"writingToBeforeTranslucent");
        if(framebuffer==null)throw new IllegalStateException("No current shadow shader framebuffer");
        Map<Integer,TerrainFragmentDraw.Attachment> result=new LinkedHashMap<>();
        Object targets=IrisSamplerBridge.field(pipeline,"shadowRenderTargets");
        for(int location=0;location<drawBuffers.length;location++){
            int texture=id(callIndex(framebuffer,"getColorAttachment",location));
            Object target=callIndex(targets,"get",drawBuffers[location]);
            Object format=IrisSamplerBridge.call(target,"getInternalFormat");
            if(texture<=0||format==null)throw new IllegalStateException("Missing physical shadow attachment "+location);
            TerrainFragmentDraw.Encoding targetEncoding=encoding(String.valueOf(format));
            result.put(drawBuffers[location],new TerrainFragmentDraw.Attachment(image(texture,targetEncoding),targetEncoding));
        }
        return result;
    }
    public static TerrainFragmentDraw.Encoding encoding(String format){return switch(format){
        case "R8","RED"->TerrainFragmentDraw.Encoding.R8_UNORM;
        case "RG8","RG"->TerrainFragmentDraw.Encoding.RG8_UNORM;
        case "R16"->TerrainFragmentDraw.Encoding.R16_UNORM;
        case "RG16"->TerrainFragmentDraw.Encoding.RG16_UNORM;
        case "R16F"->TerrainFragmentDraw.Encoding.R16F;
        case "RG16F"->TerrainFragmentDraw.Encoding.RG16F;
        case "R32F"->TerrainFragmentDraw.Encoding.R32F;
        case "RG32F"->TerrainFragmentDraw.Encoding.RG32F;
        case "RGBA32F"->TerrainFragmentDraw.Encoding.RGBA32F;
        case "RGB32F"->TerrainFragmentDraw.Encoding.RGB32F;
        case "RGB16F"->TerrainFragmentDraw.Encoding.RGB16F;
        case "RGBA16F"->TerrainFragmentDraw.Encoding.RGBA16F;
        case "RGBA16"->TerrainFragmentDraw.Encoding.RGBA16_UNORM;
        case "RGB8"->TerrainFragmentDraw.Encoding.RGB8_UNORM;
        case "RGBA8","RGBA"->TerrainFragmentDraw.Encoding.RGBA8_UNORM;
        case "R11F_G11F_B10F"->TerrainFragmentDraw.Encoding.R11F_G11F_B10F;
        default->throw new IllegalStateException("Unsupported CPU attachment format "+format);
    };}
    public static void captureOpaque(){context.flushRaster();copyDepth(opaqueDepth);}
    public static void complete(long ordinal){context.flushRaster();copyDepth(mainDepth);readyGeneration=resourceGeneration;readyOrdinal=ordinal;}
    public static boolean ready(Object expected,long ordinal){return pipeline==expected&&readyOrdinal==ordinal&&readyGeneration==resourceGeneration;}
    public static CpuImage resolve(Object expected,long ordinal,int texture){return ready(expected,ordinal)&&currentTextures.contains(texture)?images.get(texture):null;}
    public static Object shader(Object pipeline,String key){
        try{
            Class<?> keys=Class.forName("net.irisshaders.iris.pipeline.programs.ShaderKey");
            Object map=IrisSamplerBridge.call(pipeline,"getShaderMap");
            return map.getClass().getMethod("getShader",keys).invoke(map,keys.getField(key).get(null));
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Current Iris shader key "+key,e);}
    }
    private static void copyDepth(int texture){
        CpuImage image=image(texture);float[] source=context.framebuffer.depth;
        for(int i=0;i<source.length;i++){int p=i*4;image.rgba[p]=image.rgba[p+1]=image.rgba[p+2]=source[i];image.rgba[p+3]=1;}
    }
    private static CpuImage image(int texture){
        if(texture<=0)throw new IllegalStateException("Invalid shadow texture");
        return images.computeIfAbsent(texture,key->new CpuImage(resolution,resolution));
    }
    private static CpuImage image(int texture,TerrainFragmentDraw.Encoding encoding){
        CpuImage image=image(texture);image.storageEncoding(encoding);return image;
    }
    private static int id(Object value){return value instanceof Integer i?i:0;}
    private static Object callIndex(Object owner,String name,int index){
        try{return owner.getClass().getMethod(name,int.class).invoke(owner,index);}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Iris resource method "+name,e);}
    }
}
