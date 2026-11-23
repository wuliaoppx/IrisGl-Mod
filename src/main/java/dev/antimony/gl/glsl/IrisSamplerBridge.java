package dev.antimony.gl.glsl;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.IntSupplier;

/** Reads Iris' already-intercepted bindings; CPU compiler sampler indices are unrelated GL units. */
public final class IrisSamplerBridge {
    private IrisSamplerBridge() { }

    public record Binding(int unit, int texture, int sampler, String type) { }

    public static Object field(Object owner, String name) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException ignored) { }
            catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
        }
        return null;
    }

    public static Object call(Object owner, String name) {
        if (owner == null) return null;
        try {
            var method = owner.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(owner);
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }

    public static int textureId(Object access) {
        Object supplier = call(access, "getTextureId");
        try { return supplier instanceof IntSupplier s ? s.getAsInt() : 0; }
        catch (RuntimeException unavailable) { return 0; }
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    public static Binding binding(Object program, int unit) {
        Object bindings = field(field(program, "samplers"), "samplerBindings");
        if (!(bindings instanceof Iterable<?> list)) return null;
        for (Object binding : list) {
            if (!(field(binding, "textureUnit") instanceof Integer value) || value != unit) continue;
            Object supplier = field(binding, "texture");
            if (!(supplier instanceof IntSupplier s)) return null;
            try {
                Object sampler = field(binding, "sampler");
                return new Binding(unit, s.getAsInt(), sampler instanceof Integer i ? i : 0,
                    String.valueOf(field(binding, "textureType")));
            } catch (RuntimeException unavailable) { return null; }
        }
        return null;
    }

    /** Fallback only when the pass retains Iris' deactivation set and an actual binding confirms the ID. */
    public static Binding stageBinding(Object program, Object overrides, Object flipped, String name) {
        if (!(overrides instanceof Map<?, ?> map) || !(flipped instanceof java.util.Set<?> disabled)) return null;
        String[] legacy = {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};
        int index = -1;
        if (name.startsWith("colortex")) {
            try { index = Integer.parseInt(name.substring(8)); } catch (NumberFormatException ignored) { }
        } else for (int i = 0; i < legacy.length; i++) if (name.equals(legacy[i])) index = i;
        if (index >= 0 && disabled.contains(index)) return null;
        Object access = index >= 0 ? map.get("colortex" + index) : map.get(name);
        if (access == null && index >= 0 && index < legacy.length) access = map.get(legacy[index]);
        int texture = textureId(access);
        if (texture <= 0) return null;
        Object bindings = field(field(program, "samplers"), "samplerBindings");
        if (bindings instanceof Iterable<?> list) for (Object binding : list) {
            Object unit = field(binding, "textureUnit");
            if (unit instanceof Integer i) {
                Binding effective = binding(program, i);
                if (effective != null && effective.texture() == texture) return effective;
            }
        }
        return null;
    }

    /** Maps are only an object lookup by the effective supplier ID, never an override decision. */
    public static Map<Integer,Object> collectResources(Object value){
        Map<Integer,Object> result=new java.util.LinkedHashMap<>();collect(value,result);return Map.copyOf(result);
    }
    public static void collect(Object value, Map<Integer, Object> resources) {
        if (!(value instanceof Map<?, ?> map)) return;
        for (Object access : map.values()) {
            if (access instanceof Map<?, ?>) collect(access, resources);
            else {
                int id = textureId(access);
                if(id<=0)id=intOf(call(access,"getId"));
                if(id>0)resources.put(id,access);
            }
        }
    }

    /** Iris PNG uploads have first decoded row at texture v=0 and use GL_RGBA8, not SRGB8. */
    public static CpuImage nativeImage(Object access, boolean linear, boolean repeatS, boolean repeatT) {
        Object pixels = call(access, "getPixels");
        if (pixels == null) return null;
        try {
            int width = (Integer) call(pixels, "getWidth"), height = (Integer) call(pixels, "getHeight");
            var getter = pixels.getClass().getMethod("getPixelRGBA", int.class, int.class);
            getter.setAccessible(true);
            int[] abgr = new int[Math.multiplyExact(width, height)];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                abgr[y * width + x] = (Integer) getter.invoke(pixels, x, y);
            CpuImage image = new CpuImage(width, height, linear, repeatS, repeatT, true);
            image.copyRgba8(abgr, true);
            return image;
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }

    public record Filtering(boolean linear, boolean repeat, String source) { }

    /** Read an Iris-owned CPU texture backing, including the fixed-seed NativeImage noise texture. */
    public static CpuImage nativePixels(Object access,boolean linear,boolean repeatS,boolean repeatT){
        if(access==null)return null;
        Object type=call(access,"getType");
        // TextureType is an enum in Iris 1.8.14; use its declared constant name,
        // not an implementation-dependent toString() representation.
        if(!(type instanceof Enum<?> textureType)||!"TEXTURE_2D".equals(textureType.name()))return null;
        CpuImage nativeImage=nativeImage(access,linear,repeatS,repeatT);
        if(nativeImage!=null)return nativeImage;
        Object managed=null;
        for(String name:new String[]{"pixels","getPixels","image","getImage"}){
            managed=field(access,name);if(managed==null)managed=call(access,name);
            if(managed!=null)break;
        }
        if(managed instanceof com.mojang.blaze3d.platform.NativeImage image){
            int width=image.getWidth(),height=image.getHeight();
            CpuImage result=new CpuImage(width,height,linear,repeatS,repeatT,true);
            int[] rgba=new int[Math.multiplyExact(width,height)];
            for(int y=0;y<height;y++)for(int x=0;x<width;x++)rgba[y*width+x]=image.getPixelRGBA(x,y);
            result.copyRgba8(rgba,true);return result;
        }
        return null;
    }

    /** Pair original pack metadata with manager-created TextureAccess by stage and sampler name. */
    public static void collectFiltering(Object data, Object accesses, Map<Integer, Filtering> filters) {
        if (!(data instanceof Map<?, ?> dataMap) || !(accesses instanceof Map<?, ?> accessMap)) return;
        for (var entry : dataMap.entrySet()) {
            Object access = accessMap.get(entry.getKey());
            if (entry.getValue() instanceof Map<?, ?>) collectFiltering(entry.getValue(), access, filters);
            else {
                int id = textureId(access);
                Object config = call(entry.getValue(), "getFilteringData");
                Object blur = call(config, "shouldBlur"), clamp = call(config, "shouldClamp");
                if (id > 0 && blur instanceof Boolean b && clamp instanceof Boolean c)
                    filters.put(id, new Filtering(b, !c, "pack-metadata"));
            }
        }
    }

    public static Object currentPack() {
        try {
            Object current = Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPack").invoke(null);
            return current instanceof java.util.Optional<?> optional ? optional.orElse(null) : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }

    /** Recorded setup metadata; CPU execution never queries the GL driver. */
    public static int driverUnit(int program,String name){
        Integer location=dev.antimony.gl.shim.GlShimState.observedUniformLocation(program,name);
        if(location==null||location<0)return -1;
        var value=dev.antimony.gl.shim.GlShimState.uniformValue(program,location);
        return value!=null&&value.i.length==1?value.i[0]:-1;
    }
    private static final Map<Object,Map<String,Integer>> SAMPLER_UNITS=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    public static int samplerUnit(Object program,int id,String name){
        Map<String,Integer> cache=SAMPLER_UNITS.computeIfAbsent(program,key->new java.util.HashMap<>());
        Integer known=cache.get(name);if(known!=null)return known;
        Integer location=dev.antimony.gl.shim.GlShimState.observedUniformLocation(id,name);
        if(location!=null&&location>=0){
            Object initializer=field(field(program,"samplers"),"initializer");
            if(initializer instanceof Iterable<?> list)for(Object call:list){
                if(java.util.Objects.equals(field(call,"location"),location)&&field(call,"value") instanceof Integer unit){cache.put(name,unit);return unit;}
            }
        }
        int unit=driverUnit(id,name);if(unit>=0)cache.put(name,unit);return unit;
    }
    public static Binding namedBinding(Object program,int id,String name){int unit=samplerUnit(program,id,name);return unit<0?null:binding(program,unit);}
    public static int parameter(int texture,int parameter){return dev.antimony.gl.shim.GlShimState.textureParameter(texture,parameter);}
}
