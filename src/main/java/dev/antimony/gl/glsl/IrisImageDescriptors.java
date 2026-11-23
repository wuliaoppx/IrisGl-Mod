package dev.antimony.gl.glsl;

import java.lang.reflect.*;
import java.util.*;

/** Declaration/GL-object pairing only. A descriptor never proves allocation, clear, or world production on CPU. */
public final class IrisImageDescriptors {
    private IrisImageDescriptors() { }
    public record Image(Object pipeline,String name,String samplerName,int textureId,int target,int internalFormat,
                        int pixelFormat,int pixelType,int width,int height,int depth,boolean clear,
                        boolean relative,float relativeWidth,float relativeHeight){
        public CpuVolume.Format cpuFormat(){return switch(internalFormat){case 0x8232->CpuVolume.Format.R8UI;case 0x881A->CpuVolume.Format.RGBA16F;case 0x8814->CpuVolume.Format.RGBA32F;default->null;};}
    }
    public static List<Image> capture(Object pipeline)throws ReflectiveOperationException{
        Class<?> iris=Class.forName("net.irisshaders.iris.Iris");
        Object pack=((Optional<?>)iris.getMethod("getCurrentPack").invoke(null)).orElseThrow(()->new IllegalStateException("No pack for CPU image descriptors"));
        Object declarations=call(pack,"getIrisCustomImages"),images=field(pipeline,"customImages");
        if(!(declarations instanceof Collection<?> info)||!(images instanceof Collection<?> actual))throw new IllegalStateException("Missing Iris custom image declarations/objects");
        return pair(pipeline,info,actual);
    }
    static List<Image> pair(Object pipeline,Collection<?> declarations,Collection<?> actual)throws ReflectiveOperationException{
        Map<String,Object> byName=new HashMap<>();Set<Integer> ids=new HashSet<>();
        for(Object object:actual){String name=(String)call(object,"getName");if(byName.put(name,object)!=null)throw new IllegalStateException("Duplicate current image name: "+name);}
        List<Image> result=new ArrayList<>();Set<String> names=new HashSet<>();
        for(Object declaration:declarations){
            String name=(String)call(declaration,"name"),sampler=(String)call(declaration,"samplerName");Object image=byName.remove(name);
            if(image==null||!names.add(name)||!Objects.equals(sampler,call(image,"getSamplerName")))throw new IllegalStateException("Unmatched image declaration: "+name);
            int id=((Number)call(image,"getId")).intValue();if(id<=0||!ids.add(id))throw new IllegalStateException("Invalid or aliased image object ID: "+name);
            Object target=call(declaration,"target"),format=call(declaration,"format"),internal=call(declaration,"internalTextureFormat"),type=call(declaration,"type");
            int targetId=number(target,"getGlType"),formatId=number(format,"getGlFormat"),internalId=number(internal,"getGlFormat"),typeId=number(type,"getGlFormat");
            if(!Objects.equals(target,call(image,"getTarget"))||!Objects.equals(format,call(image,"getFormat"))||!Objects.equals(internal,call(image,"getInternalFormat"))||!Objects.equals(type,call(image,"getPixelType")))throw new IllegalStateException("Image object format differs from pack declaration: "+name);
            boolean clear=(Boolean)call(declaration,"clear"),relative=(Boolean)call(declaration,"isRelative");
            if(clear!=(Boolean)call(image,"shouldClear"))throw new IllegalStateException("Image clear policy mismatch: "+name);
            int width=number(declaration,"width"),height=number(declaration,"height"),depth=number(declaration,"depth");
            float rw=((Number)call(declaration,"relativeWidth")).floatValue(),rh=((Number)call(declaration,"relativeHeight")).floatValue();
            if(relative){if(targetId!=0x0DE1||!Float.isFinite(rw)||!Float.isFinite(rh)||rw<=0||rh<=0)throw new IllegalStateException("Invalid relative 2D image size: "+name);}
            else if(width<1||(targetId!=0x0DE0&&height<1)||(targetId==0x806F&&depth<1))throw new IllegalStateException("Invalid fixed image size: "+name);
            result.add(new Image(pipeline,name,sampler,id,targetId,internalId,formatId,typeId,width,height,depth,clear,relative,rw,rh));
        }
        if(!byName.isEmpty())throw new IllegalStateException("Current images absent from pack declarations: "+byName.keySet());
        return List.copyOf(result);
    }
    private static int number(Object owner,String method)throws ReflectiveOperationException{return ((Number)call(owner,method)).intValue();}
    private static Object call(Object owner,String name)throws ReflectiveOperationException{return owner.getClass().getMethod(name).invoke(owner);}
    private static Object field(Object owner,String name)throws ReflectiveOperationException{
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(NoSuchFieldException absent){}
        throw new NoSuchFieldException(owner.getClass().getName()+"."+name);
    }
}
