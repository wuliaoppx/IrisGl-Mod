package dev.antimony.core.test;

import dev.antimony.gl.glsl.CpuImage;
import dev.antimony.gl.glsl.IrisPostChain;
import dev.antimony.gl.shim.GlShimState;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/** Exercises the production resolver, not a duplicate model, without a game or GL context. */
public final class IrisPostChainRtProbe {
    public static final class Binding {
        int textureUnit = 11;
        IntSupplier texture;
        Binding(int id) { texture = () -> id; }
    }
    public static final class Samplers { List<Binding> samplerBindings; }
    public static final class Program { Samplers samplers = new Samplers(); }
    public enum TextureType { TEXTURE_2D, TEXTURE_3D;
        @Override public String toString() { return "display-" + name(); }
    }
    public static final class Pixels {
        public int getWidth() { return 2; }
        public int getHeight() { return 2; }
        public int getPixelRGBA(int x, int y) {
            return new int[]{0xff204080, 0xff0000ff, 0xff00ff00, 0xffff0000}[y * 2 + x];
        }
    }
    public static final class Resource {
        public TextureType getType() { return TextureType.TEXTURE_2D; }
        public Pixels getPixels() { return new Pixels(); }
        public IntSupplier getTextureId() { return () -> 901; }
    }
    public static final class Filter {
        public boolean shouldBlur() { return true; }
        public boolean shouldClamp() { return false; }
    }
    public static final class PngData {
        public Filter getFilteringData() { return new Filter(); }
    }
    static Object instance(Class<?> type) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        return constructor.newInstance();
    }
    static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner,value);
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Class<?> chainType = Class.forName(IrisPostChain.class.getName()+"$Chain");
        Class<?> passType = Class.forName(IrisPostChain.class.getName()+"$PassDef");
        Class<?> rtType = Class.forName(IrisPostChain.class.getName()+"$RtDef");
        Object chain = instance(chainType), pass = instance(passType), rt = instance(rtType);
        set(rt,"index",6); set(rt,"mainTexture",60); set(rt,"altTexture",61);
        ((List<Object>)field(chain,"targets")).add(rt);
        set(pass,"name","sentinel"); set(pass,"programId",987654);
        ((Map<String,Integer>)field(pass,"samplerGlUnits")).put("gaux3",11);
        Program program = new Program(); program.samplers.samplerBindings=List.of(new Binding(60)); set(pass,"program",program);
        Map<Integer,CpuImage> images = new HashMap<>();
        CpuImage main = new CpuImage(2,2), alt = new CpuImage(2,2);
        main.clear(6.125f,7.25f,8.5f,.75f); alt.clear(61,62,63,.125f);
        images.put(60,main); images.put(61,alt);
        GlShimState.bindTextureUnit(0,61); GlShimState.bindTextureUnit(15,61);
        Method resolve = IrisPostChain.class.getDeclaredMethod("resolveSampler",String.class,chainType,Map.class,passType);
        resolve.setAccessible(true);
        check(resolve.invoke(null,"gaux3",chain,images,pass)==main,"stale unrelated units overrode actual main binding");
        program.samplers.samplerBindings=List.of(new Binding(61));
        check(resolve.invoke(null,"gaux3",chain,images,pass)==alt,"draw attachment binding redirected to latest/other side");
        Method snapshot = IrisPostChain.class.getDeclaredMethod("snapshotOf",CpuImage.class); snapshot.setAccessible(true);
        CpuImage frozen = (CpuImage)snapshot.invoke(null,alt); float[] old = frozen.rgba.clone();
        alt.clear(999,998,997,0);
        check(java.util.Arrays.equals(old,frozen.rgba),"read-before-write alias snapshot changed");
        program.samplers.samplerBindings=List.of(new Binding(60));
        images.remove(60);
        check(resolve.invoke(null,"gaux3",chain,images,pass)==null,"missing explicit source redirected to RT");
        images.put(60,main);
        program.samplers.samplerBindings=List.of(); set(pass,"stageReadsFromAlt",Set.of(6));
        check(resolve.invoke(null,"gaux3",chain,images,pass)==alt,"immutable read side fallback ignored");
        set(pass,"stageReadsFromAlt",Set.of());
        check(resolve.invoke(null,"gaux3",chain,images,pass)==main,"main read side fallback ignored");
        set(chain,"depthTexture",7); set(chain,"depthNoTranslucentsTexture",1); set(chain,"depthNoHandTexture",2);
        Method candidates = IrisPostChain.class.getDeclaredMethod("candidateTextures",String.class,chainType); candidates.setAccessible(true);
        for (int stage = 0; stage < 3; stage++) {
            int texture = new int[]{7,1,2}[stage]; String name = "depthtex"+stage;
            CpuImage depth = new CpuImage(2,2); depth.clear(.125f*(stage+1),0,0,1); images.put(texture,depth);
            ((Map<String,Integer>)field(pass,"samplerGlUnits")).put(name,11);
            program.samplers.samplerBindings=List.of(new Binding(texture));
            check(resolve.invoke(null,name,chain,images,pass)==depth,"depth stage binding not recognized: "+name);
            check(((int[])candidates.invoke(null,name,chain))[0]==texture,"depth name maps to wrong ID: "+name);
        }
        Method seed = IrisPostChain.class.getDeclaredMethod("seedExposurePixel",Map.class,int.class,int.class,int.class); seed.setAccessible(true);
        seed.invoke(null,images,600,4,2);
        check(images.get(600).sample(.5f,.5f)[0]==1,"even-resolution exposure centre is not neutral");
        for (int i=0;i<images.get(600).rgba.length;i+=4) check(images.get(600).rgba[i]==1,"exposure plane left unseeded");
        images.get(600).clear(.375f,0,0,1); seed.invoke(null,images,600,4,2);
        check(images.get(600).sample(.5f,.5f)[0]==.375f,"exposure seed overwrote history");
        Method resourceImage = IrisPostChain.class.getDeclaredMethod("resourceImage",chainType,int.class);
        resourceImage.setAccessible(true);
        Resource resource = new Resource();
        ((Map<Integer,Object>)field(chain,"resourceAccess")).put(901,resource);
        check(resourceImage.invoke(null,chain,901)==null,"unknown resource filtering must reject");
        var filtering = (Map<Integer,dev.antimony.gl.glsl.IrisSamplerBridge.Filtering>)field(chain,"resourceFiltering");
        dev.antimony.gl.glsl.IrisSamplerBridge.collectFiltering(Map.of("noisetex",new PngData()),Map.of("noisetex",resource),filtering);
        CpuImage noise = (CpuImage)resourceImage.invoke(null,chain,901);
        check(noise!=null && noise.matches(2,2),"explicit noisetex metadata and CPU pixels resolve");
        check(Math.abs(noise.sample(.25f,.25f)[0]-128/255f)<1e-6f,"noisetex ABGR normalized red without gamma");
        check(Math.abs(noise.sample(.25f,.25f)[2]-32/255f)<1e-6f,"noisetex channel order");
        check(Math.abs(noise.sample(.25f,.25f)[0]-noise.sample(-.75f,1.25f)[0])<1e-6f,"noisetex repeat and upload rows");
        check(Math.abs(noise.sample(0,.25f)[0]-(128/255f+1)/2)<1e-6f,"noisetex bilinear repeat seam");
        check(resourceImage.invoke(null,chain,901)==noise,"resource snapshot cached within chain");
        System.out.println("PASS noisetex CPU backing with enum-name identity, explicit PNG filtering, ABGR/linear values, upload rows, repeat seam and unknown-config rejection");
        System.out.println("PASS distinct depthtex0/1/2 binding IDs and names; neutral full-plane exposure first seed and history preservation");
        System.out.println("PASS production resolver actual binding beats stale units, draw alias identity/snapshot, unavailable source rejection, immutable build read-side fallback");
    }
}
