package dev.antimony.core.test;

import dev.antimony.gl.glsl.CpuImage;
import dev.antimony.gl.glsl.IrisSamplerBridge;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import javax.imageio.ImageIO;

/** Standalone custom-texture probe using the installed Iris interceptor, without a game or GL context. */
public final class IrisSamplerBridgeProbe {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void close(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < 1e-5f, message + ": " + actual + " != " + expected);
    }
    static Object immutable(String type, Object... values) throws Exception {
        Class<?> clazz = Class.forName("com.google.common.collect.Immutable" + type);
        return clazz.getMethod("copyOf", Collection.class).invoke(null, Arrays.asList(values));
    }
    static Object intercepted(boolean flipped, IntSupplier custom, IntSupplier rt) throws Exception {
        Class<?> access = Class.forName("net.irisshaders.iris.gl.texture.TextureAccess");
        Object resource = Proxy.newProxyInstance(access.getClassLoader(), new Class<?>[]{access},
            (p,m,a)-> m.getName().equals("getTextureId") ? custom : null);
        Map<String,Object> overrides = (Map<String,Object>)Class.forName("it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap")
            .getConstructor().newInstance();
        overrides.put("gaux2", resource);
        Class<?> holder = Class.forName("net.irisshaders.iris.gl.sampler.SamplerHolder");
        IntSupplier[] captured = new IntSupplier[1];
        Object delegate = Proxy.newProxyInstance(holder.getClassLoader(), new Class<?>[]{holder}, (p,m,a)-> {
            if (m.getName().equals("addDynamicSampler")) { captured[0]=(IntSupplier)a[0]; return true; }
            return false;
        });
        Class<?> interceptor = Class.forName("net.irisshaders.iris.gl.program.ProgramSamplers$CustomTextureSamplerInterceptor");
        Constructor<?> constructor = interceptor.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Object instance = constructor.newInstance(delegate, overrides, flipped ? immutable("Set",5) : immutable("Set"));
        interceptor.getMethod("addDynamicSampler",IntSupplier.class,String[].class)
            .invoke(instance,rt,new String[]{"colortex5","gaux2"});
        return captured[0];
    }
    static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");
        Field f=unsafeType.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return unsafeType.getMethod("allocateInstance",Class.class).invoke(f.get(null),type);
    }
    static void set(Object owner,String name,Object value) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner,value);
    }
    static void bindingProbe() throws Exception {
        AtomicInteger resourceId=new AtomicInteger(9001), rtId=new AtomicInteger(55);
        IntSupplier custom=resourceId::get,rt=rtId::get;
        IntSupplier deferred=(IntSupplier)intercepted(false,custom,rt);
        IntSupplier composite2=(IntSupplier)intercepted(true,custom,rt);
        check(deferred.getAsInt()==9001,"deferred override lost");
        check(composite2.getAsInt()==55,"flipped RT incorrectly overridden");
        Class<?> binding=Class.forName("net.irisshaders.iris.gl.sampler.SamplerBinding");
        Object actual=binding.getConstructors()[0].newInstance(null,11,deferred,null,null);
        Object samplers=allocate(Class.forName("net.irisshaders.iris.gl.program.ProgramSamplers"));
        set(samplers,"samplerBindings",immutable("List",actual));
        Object program=allocate(Class.forName("net.irisshaders.iris.gl.program.Program"));
        set(program,"samplers",samplers);
        check(IrisSamplerBridge.binding(program,0)==null,"CPU compiler unit must not be used as GL unit");
        check(IrisSamplerBridge.binding(program,11).texture()==9001,"effective binding lookup");
        resourceId.set(9002);
        check(IrisSamplerBridge.binding(program,11).texture()==9002,"supplier was frozen");
        check(composite2.getAsInt()==55,"custom map replaced composite RT");
        Class<?> accessType=Class.forName("net.irisshaders.iris.gl.texture.TextureAccess");
        Object access=Proxy.newProxyInstance(accessType.getClassLoader(),new Class<?>[]{accessType},
            (p,m,a)->m.getName().equals("getTextureId")?custom:null);
        check(IrisSamplerBridge.stageBinding(program,Map.of("gaux2",access),Set.of(),"colortex5").texture()==9002,
            "confirmed stage fallback");
        check(IrisSamplerBridge.stageBinding(program,Map.of("gaux2",access),Set.of(5),"gaux2")==null,
            "stage fallback must respect flipped alias");
        Object filtering=Class.forName("net.irisshaders.iris.shaderpack.texture.TextureFilteringData")
            .getConstructor(boolean.class,boolean.class).newInstance(true,false);
        Object data=Class.forName("net.irisshaders.iris.shaderpack.texture.CustomTextureData$PngData")
            .getConstructors()[0].newInstance(filtering,new byte[0]);
        Map<Integer,IrisSamplerBridge.Filtering> filters=new HashMap<>();
        IrisSamplerBridge.collectFiltering(Map.of("DEFERRED",Map.of("gaux2",data)),
            Map.of("DEFERRED",Map.of("gaux2",access)),filters);
        check(filters.get(9002).linear()&&filters.get(9002).repeat(),"real Iris PNG metadata blur/repeat");
        System.out.println("PASS real Iris PngData metadata by stage+name; confirmed supplier fallback respects flipped aliases");
        System.out.println("PASS actual Iris interceptor: deferred gaux2 resource; flipped composite2 colortex5 RT; actual unit 11; live supplier");
    }
    static void pixelsProbe() {
        int[] argb={0xff804020,0xffff0000,0xff00ff00,0xff0000ff};
        CpuImage resource=new CpuImage(2,2,false,true,true,true); resource.copyRgba8(argb,false);
        close(resource.sample(.25f,.25f)[0],128/255f,"normalized R without gamma");
        close(resource.sample(.25f,.25f)[2],32/255f,"ARGB channel order");
        close(resource.sample(.25f,.75f)[1],1,"NativeImage row orientation");
        close(resource.sample(-.75f,1.25f)[0],128/255f,"negative repeat");
        CpuImage abgr=new CpuImage(1,1,false,false,false,true); abgr.copyRgba8(new int[]{0xff204080},true);
        close(abgr.sample(0,0)[0],128/255f,"NativeImage ABGR channel order");
        CpuImage linear=new CpuImage(2,2,true,true,true,true); linear.copyRgba8(argb,false);
        close(linear.sample(0,.25f)[0],(128/255f+1)/2,"bilinear seam wraps neighbors");
        CpuImage target=new CpuImage(2,2); System.arraycopy(resource.rgba,0,target.rgba,0,16);
        close(target.sample(.25f,.25f)[1],1,"RT orientation preserved");
        close(target.sample(-2,1.25f)[0],128/255f,"RT clamp preserved");
        System.out.println("PASS RGBA8 normalization/channels, nearest/repeat, bilinear wrap seam, upload direction, RT flip/clamp");
    }
    static void cloudProbe(String path) throws Exception {
        var png=ImageIO.read(Path.of(path).toFile());
        int w=png.getWidth(),h=png.getHeight();
        int[] argb=new int[w*h];
        // getRGB applies Java's gray ColorSpace transfer; STB/NativeImage uploads raw PNG samples.
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int r=png.getRaster().getSample(x,y,0);
            argb[y*w+x]=0xff000000|(r<<16)|(r<<8)|r;
        }
        CpuImage image=new CpuImage(w,h,true,true,true,true); image.copyRgba8(argb,false);
        long sourceSum=0; double sum=0; int sourceNonzero=0,nonzero=0;
        for(int i=0;i<argb.length;i++) {
            int r=(argb[i]>>>16)&255; sourceSum+=r; if(r!=0)sourceNonzero++;
            sum+=image.rgba[i*4]; if(image.rgba[i*4]!=0)nonzero++;
        }
        check(w==512&&h==512,"native cloud dimensions");
        check(Math.abs(sum/argb.length-sourceSum/(255.0*argb.length))<1e-7,"PNG data gamma changed");
        check(nonzero==sourceNonzero&&nonzero>0,"PNG nonzero data lost");
        close(image.sample(.173f,.287f)[0],image.sample(1.173f,-1.713f)[0],"cloud UV periodicity");
        System.out.println("PASS cloud "+path+" native="+w+"x"+h+" meanR="+(sum/argb.length)+" nonzero="+nonzero);
    }
    public static void main(String[] args) throws Exception {
        bindingProbe(); pixelsProbe(); for(String path:args) cloudProbe(path);
    }
}
