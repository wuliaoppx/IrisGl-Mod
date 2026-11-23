package dev.antimony.gl.glsl;

import dev.antimony.core.irisgl.RasterShader;
import java.lang.reflect.*;
import java.util.*;

/** Generic strict terrain FS draw. Activation by Iris still requires its resource/attachment lifecycle.
 * No framebuffer presentation, fake missing inputs, blending, or implicit ARGB conversion. */
public final class TerrainFragmentDraw implements RasterShader {
    public enum Encoding {
        RGBA32F, RGB32F, RGB16F, RGBA16F, RGBA16_UNORM, RGB8_UNORM, RGBA8_UNORM, R11F_G11F_B10F,
        R8_UNORM, RG8_UNORM, R16_UNORM, RG16_UNORM, R16F, RG16F, R32F, RG32F;

        public float encode(float value, int channel) {
            return switch (this) {
                case R8_UNORM -> channel == 0 ? unorm(value, 255) : channel == 3 ? 1 : 0;
                case RG8_UNORM -> channel < 2 ? unorm(value, 255) : channel == 3 ? 1 : 0;
                case R16_UNORM -> channel == 0 ? unorm(value, 65535) : channel == 3 ? 1 : 0;
                case RG16_UNORM -> channel < 2 ? unorm(value, 65535) : channel == 3 ? 1 : 0;
                case R16F -> channel == 0 ? Float.float16ToFloat(Float.floatToFloat16(value)) : channel == 3 ? 1 : 0;
                case RG16F -> channel < 2 ? Float.float16ToFloat(Float.floatToFloat16(value)) : channel == 3 ? 1 : 0;
                case R32F -> channel == 0 ? value : channel == 3 ? 1 : 0;
                case RG32F -> channel < 2 ? value : channel == 3 ? 1 : 0;
                case RGBA32F -> value;
                case RGB32F -> channel == 3 ? 1 : value;
                case RGB16F -> channel == 3 ? 1 : Float.float16ToFloat(Float.floatToFloat16(value));
                case RGBA16F -> Float.float16ToFloat(Float.floatToFloat16(value));
                case RGBA16_UNORM -> Math.round(Math.max(0, Math.min(1, value)) * 65535f) / 65535f;
                case RGB8_UNORM -> channel == 3 ? 1 : Math.round(Math.max(0, Math.min(1, value)) * 255f) / 255f;
                case RGBA8_UNORM -> Math.round(Math.max(0, Math.min(1, value)) * 255f) / 255f;
                case R11F_G11F_B10F -> channel == 3 ? 1 : unsignedFloat(value, channel == 2 ? 5 : 6);
            };
        }

        private static float unorm(float value, int maximum) {
            return Math.round(Math.max(0, Math.min(1, value)) * maximum) / (float) maximum;
        }

        // EXT_packed_float: independent 5-bit biased exponents, 6/6/5 mantissa
        // bits, no sign, subnormals, saturation to the largest finite value.
        // Choose nearest-even directly from float32 (no half double rounding).
        private static float unsignedFloat(float value, int mantissaBits) {
            if (Float.isNaN(value)) return Float.NaN;
            if (value <= 0) return 0;
            float max = Math.scalb(2 - Math.scalb(1f, -mantissaBits), 15);
            if (value >= max) return max;
            int exponent = Math.getExponent(value);
            double step = Math.scalb(1.0, exponent < -14 ? -14 - mantissaBits : exponent - mantissaBits);
            double rounded = Math.rint(value / step) * step;
            return (float) Math.min(max, rounded);
        }
    }
    public record Blend(boolean enabled,int srcRgb,int dstRgb,int srcAlpha,int dstAlpha) {
        public static final Blend OFF=new Blend(false,1,0,1,0);
        public Blend {
            for(int factor:new int[]{srcRgb,dstRgb,srcAlpha,dstAlpha})
                if(factor!=0&&factor!=1&&(factor<0x0300||factor>0x0308))
                    throw new IllegalArgumentException("Unsupported CPU blend factor "+factor);
        }
        private static float factor(int factor,float[] src,float[] dst,int pixel,int channel){
            return switch(factor){
                case 0->0;case 1->1;
                case 0x0300->src[channel];case 0x0301->1-src[channel];
                case 0x0302->src[3];case 0x0303->1-src[3];
                case 0x0304->dst[pixel+3];case 0x0305->1-dst[pixel+3];
                case 0x0306->dst[pixel+channel];case 0x0307->1-dst[pixel+channel];
                case 0x0308->channel==3?1:Math.min(src[3],1-dst[pixel+3]);
                default->throw new IllegalStateException("Invalid captured blend factor");
            };
        }
        float apply(float[] src,float[] dst,int pixel,int channel){
            if(!enabled)return src[channel];
            return src[channel]*factor(channel==3?srcAlpha:srcRgb,src,dst,pixel,channel)
                +dst[pixel+channel]*factor(channel==3?dstAlpha:dstRgb,src,dst,pixel,channel);
        }
    }
    public record Attachment(CpuImage image, Encoding encoding,Blend blend) {
        public Attachment(CpuImage image,Encoding encoding){this(image,encoding,null);}
    }
    private final ShaderVaryings layout;
    private final Constructor<?> constructor;
    private final Map<String,Object> uniforms;
    private final Map<Integer,CpuImage> samplers;
    private final Map<Integer,CpuVolume> volumes;
    private final boolean needsDerivatives;
    private final Map<String,Integer> samplerFields;
    private final Attachment[] targets;
    public boolean handlesBlend(){for(Attachment target:targets)if(target.blend==null)return false;return true;}
    private final int width,height;
    private final Float alphaGreater;
    // Owned by this draw, not by a long-lived pool thread's ThreadLocalMap.
    // Fragment holds its outer draw (including large sampler snapshots): a per-draw
    // ThreadLocal therefore formed a strong thread -> fragment -> draw -> key cycle.
    private final Map<Thread,Worker> workers=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.function.Function<Thread,Worker> workerFactory=ignored->createWorker();
    private float[][] before;
    public boolean fragmentDraw(){return true;}
    public void beginTransaction(){
        if(before!=null)throw new IllegalStateException("Overlapping fragment transaction");
        before=new float[targets.length][];
        for(int i=0;i<targets.length;i++)before[i]=targets[i].image.rgba.clone();
    }
    public void endTransaction(boolean success){
        if(before==null)return;
        if(!success)for(int i=0;i<targets.length;i++)System.arraycopy(before[i],0,targets[i].image.rgba,0,before[i].length);
        before=null;
    }

    public TerrainFragmentDraw(GlslProgram prototype,GlslCompiler.Result meta,ShaderVaryings layout,
            Map<String,Object> liveUniforms,Map<String,CpuImage> textures,int[] drawBuffers,
            Map<Integer,Attachment> attachments,int width,int height,Float alphaGreater) {
        this(prototype,meta,layout,liveUniforms,textures,Map.of(),drawBuffers,attachments,width,height,alphaGreater);
    }
    public TerrainFragmentDraw(GlslProgram prototype,GlslCompiler.Result meta,ShaderVaryings layout,
            Map<String,Object> liveUniforms,Map<String,CpuImage> textures,Map<String,CpuVolume> volumes,int[] drawBuffers,
            Map<Integer,Attachment> attachments,int width,int height,Float alphaGreater) {
        this.layout=layout; this.width=width; this.height=height; this.alphaGreater=alphaGreater;
        if(width<=0||height<=0||alphaGreater!=null&&!Float.isFinite(alphaGreater)) throw new IllegalArgumentException("Invalid draw viewport/alpha test");
        try { constructor=prototype.getClass().getConstructor(); }
        catch(ReflectiveOperationException e){throw new IllegalArgumentException("Shader instance constructor",e);}
        Map<String,Object> u=new LinkedHashMap<>();
        for(String name:meta.usedUniforms) {
            Object value=liveUniforms.get(name);
            if(value==null) throw new IllegalArgumentException("Missing live fragment uniform "+name);
            String signature=meta.uniformTypes.get(name); String[] parts=signature.split("#"); int n=Integer.parseInt(parts[1]);
            Object copy=copy(value);
            boolean valid=switch(parts[0]) {
                case "float" -> copy instanceof Float f && Float.isFinite(f); case "int" -> copy instanceof Integer;
                case "boolean" -> copy instanceof Boolean;
                case "float[]" -> copy instanceof float[] a && a.length==n && finite(a);
                case "int[]" -> copy instanceof int[] a && a.length==n; case "boolean[]" -> copy instanceof boolean[] a && a.length==n;
                default -> false;
            };
            if(!valid) throw new IllegalArgumentException("Invalid live fragment uniform "+name+" "+signature);
            u.put(meta.uniformFields.get(name),copy);
        }
        uniforms=Collections.unmodifiableMap(u);
        Map<Integer,CpuImage> images=new LinkedHashMap<>(); Map<Integer,CpuVolume> volumeImages=new LinkedHashMap<>(); Map<String,Integer> fields=new LinkedHashMap<>();
        for(String name:meta.usedSamplers) {
            Integer unit=meta.samplerUnits.get(name);String type=meta.samplerTypes.getOrDefault(name,"sampler2D");
            if(unit==null)throw new IllegalArgumentException("Missing sampler metadata "+name);
            if(type.equals("sampler2D")||type.equals("sampler2DShadow")){
                CpuImage image=textures.get(name);if(image==null)throw new IllegalArgumentException("Missing live fragment sampler "+name);
                images.put(unit,image.detached());
            }else {
                CpuVolume volume=volumes.get(name);
                if(volume==null||volume.integer()!=type.equals("usampler3D"))throw new IllegalArgumentException("Missing/wrong typed live 3D sampler "+name+" "+type);
                volumeImages.put(unit,volume.detached());
            }
            fields.put("s$"+unit,unit);
        }
        samplers=Collections.unmodifiableMap(images);this.volumes=Collections.unmodifiableMap(volumeImages);
        needsDerivatives=!meta.derivativeInputs.isEmpty();samplerFields=Map.copyOf(fields);
        targets=new Attachment[meta.outSlots]; Set<CpuImage> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int location=0;location<targets.length;location++) {
            if(location>=drawBuffers.length) throw new IllegalArgumentException("Missing DRAWBUFFERS location "+location);
            Attachment target=attachments.get(drawBuffers[location]);
            if(target==null||target.encoding==null||!target.image.matches(width,height)||!seen.add(target.image))
                throw new IllegalArgumentException("Invalid/aliased DRAWBUFFERS target "+drawBuffers[location]);
            targets[location]=target;
        }
    }
    private static Object copy(Object value) {
        if(value instanceof float[] a)return a.clone(); if(value instanceof int[] a)return a.clone(); if(value instanceof boolean[] a)return a.clone(); return value;
    }
    private static boolean finite(float[] a){for(float x:a)if(!Float.isFinite(x))return false;return true;}
    public ShaderVaryings layout(){return layout;}
    public Worker worker(){return workers.computeIfAbsent(Thread.currentThread(),workerFactory);}
    private Worker createWorker() {
        try {
            GlslProgram instance=(GlslProgram)constructor.newInstance();
            for(var e:uniforms.entrySet()) instance.getClass().getField(e.getKey()).set(instance,copy(e.getValue()));
            for(var e:samplerFields.entrySet()) instance.getClass().getField(e.getKey()).setInt(instance,e.getValue());
            return new Fragment(instance,instance.getClass().getField("out"));
        } catch(ReflectiveOperationException e){throw new IllegalStateException("Independent fragment worker creation",e);}
    }
    private final class Fragment implements Worker,FragState {
        private final GlslProgram program;
        private final Field out;
        private final ShaderVaryings.Access inputs;
        private final float[] interpolated=new float[layout.components()];
        private final float[][] encoded=new float[targets.length][4];
        private float u,v,z,reciprocalW;
        private float[] derivativeTriangle;
        private int da,db,dc,pixelX,pixelY;
        private float di0,di1,di2,ce0,ce1,ce2,de0dx,de1dx,de0dy,de1dy;
        private final float[] helper0=new float[layout.components()],helper1=new float[layout.components()];
        private final float[] dx=new float[layout.components()],dy=new float[layout.components()];
        private boolean derivativesValid;
        public void prepareTriangle(float[] triangle,int a,int b,int c,int vx,int vy,int vw,int vh){
            if(!needsDerivatives)return;
            derivativeTriangle=triangle;da=a;db=b;dc=c;di0=1/triangle[a-14];di1=1/triangle[b-14];di2=1/triangle[c-14];
            float x0=vx+(triangle[a-17]*di0*.5f+.5f)*vw,y0=vy+(1-(triangle[a-16]*di0*.5f+.5f))*vh;
            float x1=vx+(triangle[b-17]*di1*.5f+.5f)*vw,y1=vy+(1-(triangle[b-16]*di1*.5f+.5f))*vh;
            float x2=vx+(triangle[c-17]*di2*.5f+.5f)*vw,y2=vy+(1-(triangle[c-16]*di2*.5f+.5f))*vh;
            float area=(x2-x0)*(y1-y0)-(y2-y0)*(x1-x0);
            if(area==0||!Float.isFinite(area))throw new IllegalStateException("Invalid derivative triangle");
            de0dx=(y2-y1)/area;de1dx=(y0-y2)/area;
            de0dy=-(x2-x1)/area;de1dy=-(x0-x2)/area;
        }
        private void helper(int x,int y,float[] target){
            float e0=ce0+(x-pixelX)*de0dx+(y-pixelY)*de0dy;
            float e1=ce1+(x-pixelX)*de1dx+(y-pixelY)*de1dy;
            layout.interpolate(derivativeTriangle,da,db,dc,e0,e1,1-e0-e1,di0,di1,di2,target);
        }
        public float derivative(String name,int component,boolean yAxis){
            if(!needsDerivatives||derivativeTriangle==null)throw new IllegalStateException("No current derivative interpolation owner");
            ShaderVaryings.Lane lane=layout.inputLane(name);
            if(!lane.variable().signature().startsWith("float")||component<0||component>=lane.components())throw new IllegalArgumentException("Derivative input domain "+name);
            if(!derivativesValid){
                int quadX=pixelX&~1;helper(quadX,pixelY,helper0);helper(quadX+1,pixelY,helper1);
                for(int i=0;i<dx.length;i++)dx[i]=helper1[i]-helper0[i];
                int lowerGL=(height-1-pixelY)&~1;int lowerY=height-1-lowerGL;
                helper(pixelX,lowerY,helper0);helper(pixelX,lowerY-1,helper1);
                for(int i=0;i<dy.length;i++)dy[i]=helper1[i]-helper0[i];
                derivativesValid=true;
            }
            float value=(yAxis?dy:dx)[lane.offset()+component];
            if(!Float.isFinite(value))throw new IllegalStateException("Nonfinite floating varying derivative "+name);
            return value;
        }
        Fragment(GlslProgram p,Field out){program=p;this.out=out;inputs=layout.fragmentAccess(p);}
        public float width(){return width;} public float height(){return height;}
        public float fragU(){return u;} public float fragV(){return v;} public float fragZ(){return z;} public float fragW(){return reciprocalW;}
        public float[] sample(int unit,float u,float v) {
            CpuImage image=samplers.get(unit);
            if(image==null)throw new IllegalStateException("Unbound fragment texture unit "+unit);
            return image.sample(u,v);
        }
        public float[] fetch2D(int unit,int x,int y,int lod){return image(unit).fetch(x,y,lod);}
        public float sampleChannel(int unit,float[] coordinate,int channel){return image(unit).sampleChannel(coordinate[0],coordinate[1],channel);}
        public float sampleLodChannel(int unit,float[] coordinate,float lod,int channel){return image(unit).sampleLodChannel(coordinate[0],coordinate[1],lod,channel);}
        public float sampleBiasChannel(int unit,float[] coordinate,float bias,int channel){return image(unit).sampleBiasChannel(coordinate[0],coordinate[1],bias,channel);}
        public float[] sampleLod(int unit,float[] coordinate,float lod){return image(unit).sampleLod(coordinate[0],coordinate[1],lod);}
        public float[] sampleBias(int unit,float[] coordinate,float bias){return image(unit).sampleBias(coordinate[0],coordinate[1],bias);}
        private CpuImage image(int unit){CpuImage image=samplers.get(unit);if(image==null)throw new IllegalStateException("Unbound fragment image "+unit);return image;}
        public float sampleShadow(int unit,float u,float v,float reference){
            CpuImage image=samplers.get(unit);
            if(image==null)throw new IllegalStateException("Unbound fragment comparison unit "+unit);
            return image.sampleCompare(u,v,reference);
        }
        public float[] sample3D(int unit,float u,float v,float w){return volume(unit).sample(u,v,w);}
        public float[] sample3DLod(int unit,float u,float v,float w,float lod){return volume(unit).sampleLod(u,v,w,lod);}
        public float[] fetch3D(int unit,int x,int y,int z,int lod){return volume(unit).fetch(x,y,z,lod);}
        public int[] fetchUnsigned3D(int unit,int x,int y,int z,int lod){return volume(unit).fetchUnsigned(x,y,z,lod);}
        private CpuVolume volume(int unit){CpuVolume volume=volumes.get(unit);if(volume==null)throw new IllegalStateException("Unbound typed 3D unit "+unit);return volume;}
        public int shade(float[] triangle,int a,int b,int c,float e0,float e1,float e2,
                         float iw0,float iw1,float iw2,int x,int y,float depth,float iw,boolean front,boolean colorWrite) {
            ce0=e0;ce1=e1;ce2=e2;pixelX=x;pixelY=y;derivativesValid=false;
            layout.interpolate(triangle,a,b,c,e0,e1,e2,iw0,iw1,iw2,interpolated);
            inputs.install(interpolated,0);
            u=(x+.5f)/width; v=(height-y-.5f)/height; z=depth; reciprocalW=iw;
            try {
                program.run(this);
                float[][] colors=(float[][])out.get(program);
                // Validate every location before committing ANY depth/MRT channel.
                if(colors.length<targets.length)throw new IllegalStateException("Fragment output arity");
                for(int i=0;i<targets.length;i++) if(colors[i]==null||colors[i].length!=4||!finite(colors[i]))
                    throw new IllegalStateException("Invalid fragment output location "+i);
                if(alphaGreater!=null && !(colors[0][3]>alphaGreater))return 1;
                if(!colorWrite)return 2;
                int pixel=(y*width+x)*4;
                for(int i=0;i<targets.length;i++) {
                    Attachment target=targets[i];float[] color=colors[i];
                    for(int channel=0;channel<4;channel++) {
                        float value=target.blend==null?color[channel]:target.blend.apply(color,target.image.rgba,pixel,channel);
                        value=target.encoding.encode(value,channel);
                        if(!Float.isFinite(value))throw new IllegalStateException("Nonfinite/overflowing encoded fragment output at location "+i);
                        encoded[i][channel]=value;
                    }
                }
                // Validate blend and format conversion for every location before any target changes.
                for(int i=0;i<targets.length;i++)System.arraycopy(encoded[i],0,targets[i].image.rgba,pixel,4);
                return 2;
            } catch(DiscardException discarded){return 1;}
            catch(ReflectiveOperationException e){throw new IllegalStateException("Fragment output access",e);}
        }
    }
}
