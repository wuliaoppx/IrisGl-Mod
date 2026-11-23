package dev.antimony.gl.glsl;

/** Typed CPU 3D texture. Row/plane coordinates are GL texel coordinates, with no 2D framebuffer Y flip.
 * Allocating storage is NOT evidence that a pack producer ran; binding/generation readiness belongs to its owner. */
public final class CpuVolume {
    public enum Format { RGBA32F, RGBA16F, R8UI }
    public enum Wrap { CLAMP_TO_EDGE, REPEAT }
    public final int width,height,depth;
    public final Format format;
    private final boolean linear;
    private volatile boolean frozen;
    private final Wrap wrapS,wrapT,wrapR;
    private final float[] rgba;
    private final byte[] red;
    public CpuVolume(int width,int height,int depth,Format format,boolean linear,Wrap s,Wrap t,Wrap r) {
        this(width,height,depth,format,linear,s,t,r,false,null,null);
    }
    private CpuVolume(int width,int height,int depth,Format format,boolean linear,Wrap s,Wrap t,Wrap r,
                      boolean frozen,float[] data,byte[] integerData) {
        if(width<=0||height<=0||depth<=0)throw new IllegalArgumentException("3D dimensions must be positive");
        int count=Math.multiplyExact(Math.multiplyExact(width,height),depth);
        this.width=width;this.height=height;this.depth=depth;
        this.format=java.util.Objects.requireNonNull(format);this.linear=linear;this.frozen=frozen;
        wrapS=java.util.Objects.requireNonNull(s);wrapT=java.util.Objects.requireNonNull(t);wrapR=java.util.Objects.requireNonNull(r);
        if(format==Format.R8UI&&linear)throw new IllegalArgumentException("Integer 3D texture cannot use linear filtering");
        rgba=format==Format.R8UI?null:data==null?new float[Math.multiplyExact(count,4)]:data;
        red=format==Format.R8UI?(integerData==null?new byte[count]:integerData):null;
    }
    public CpuVolume detached() {
        if (frozen) return this;
        return new CpuVolume(width,height,depth,format,linear,wrapS,wrapT,wrapR,true,
            rgba==null?null:rgba.clone(),red==null?null:red.clone());
    }
    /** Permanent ownership handoff after all writers have quiesced. No mutable alias survives. */
    CpuVolume seal(){frozen=true;return this;}
    public CpuVolume emptyMutable(){return new CpuVolume(width,height,depth,format,linear,wrapS,wrapT,wrapR,false,null,null);}
    public CpuVolume mutableCopy(){return new CpuVolume(width,height,depth,format,linear,wrapS,wrapT,wrapR,false,rgba==null?null:rgba.clone(),red==null?null:red.clone());}
    public void copyFrom(CpuVolume source){mutable();java.util.Objects.requireNonNull(source);if(width!=source.width||height!=source.height||depth!=source.depth||format!=source.format)throw new IllegalArgumentException("CPU volume copy schema mismatch");if(integer())System.arraycopy(source.red,0,red,0,red.length);else System.arraycopy(source.rgba,0,rgba,0,rgba.length);}
    public boolean integer(){return format==Format.R8UI;}
    public boolean frozen(){return frozen;}
    public long storageBytes(){return integer()?red.length:(long)rgba.length*Float.BYTES;}
    public void clear(){mutable();if(integer())java.util.Arrays.fill(red,(byte)0);else java.util.Arrays.fill(rgba,0.0f);}
    private int index(int x,int y,int z){return (z*height+y)*width+x;}
    private boolean inside(int x,int y,int z){return x>=0&&x<width&&y>=0&&y<height&&z>=0&&z<depth;}
    private void mutable(){if(frozen)throw new IllegalStateException("Cannot write a frozen volume snapshot");}
    private void floating(){if(integer())throw new IllegalStateException("Floating sampler/image used with R8UI");}
    private void unsigned(){if(!integer())throw new IllegalStateException("Unsigned sampler/image used with floating volume");}
    private static void level(int lod){if(lod!=0)throw new IllegalStateException("3D mip level unavailable: "+lod);}
    public void store(int x,int y,int z,float[] value) {
        mutable();floating();if(value==null||value.length!=4)throw new IllegalArgumentException("imageStore needs vec4");
        if(!inside(x,y,z))return;
        int o=index(x,y,z)*4;
        for(int c=0;c<4;c++)rgba[o+c]=format==Format.RGBA16F?Float.float16ToFloat(Float.floatToFloat16(value[c])):value[c];
    }
    public void storeUnsigned(int x,int y,int z,int value) {
        mutable();unsigned();if(inside(x,y,z))red[index(x,y,z)]=(byte)value;
    }
    /** Out-of-range image/fetch access returns a defined robust zero; missing bindings never use this path. */
    public float[] fetch(int x,int y,int z,int lod) {
        floating();level(lod);if(!inside(x,y,z))return new float[4];int o=index(x,y,z)*4;
        return new float[]{rgba[o],rgba[o+1],rgba[o+2],rgba[o+3]};
    }
    public int[] fetchUnsigned(int x,int y,int z,int lod) {
        unsigned();level(lod);return inside(x,y,z)?new int[]{Byte.toUnsignedInt(red[index(x,y,z)]),0,0,1}:new int[4];
    }
    public float[] sample(float u,float v,float w) {
        floating();coordinates(u,v,w);
        if(wrapS==Wrap.REPEAT)u-=Math.floor(u);else u=Math.max(0,Math.min(1,u));
        if(wrapT==Wrap.REPEAT)v-=Math.floor(v);else v=Math.max(0,Math.min(1,v));
        if(wrapR==Wrap.REPEAT)w-=Math.floor(w);else w=Math.max(0,Math.min(1,w));
        if(!linear)return fetch(address((int)Math.floor(u*width),width,wrapS),address((int)Math.floor(v*height),height,wrapT),address((int)Math.floor(w*depth),depth,wrapR),0);
        float fx=u*width-.5f,fy=v*height-.5f,fz=w*depth-.5f;
        int x=(int)Math.floor(fx),y=(int)Math.floor(fy),z=(int)Math.floor(fz);
        float tx=fx-x,ty=fy-y,tz=fz-z;float[] out=new float[4];
        for(int dz=0;dz<2;dz++)for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++) {
            int o=index(address(x+dx,width,wrapS),address(y+dy,height,wrapT),address(z+dz,depth,wrapR))*4;
            float weight=(dx==0?1-tx:tx)*(dy==0?1-ty:ty)*(dz==0?1-tz:tz);
            for(int c=0;c<4;c++)out[c]+=rgba[o+c]*weight;
        }
        return out;
    }
    public int[] sampleUnsigned(float u,float v,float w) {
        unsigned();coordinates(u,v,w);
        if(wrapS==Wrap.REPEAT)u-=Math.floor(u);else u=Math.max(0,Math.min(1,u));
        if(wrapT==Wrap.REPEAT)v-=Math.floor(v);else v=Math.max(0,Math.min(1,v));
        if(wrapR==Wrap.REPEAT)w-=Math.floor(w);else w=Math.max(0,Math.min(1,w));
        return fetchUnsigned(address((int)Math.floor(u*width),width,wrapS),address((int)Math.floor(v*height),height,wrapT),address((int)Math.floor(w*depth),depth,wrapR),0);
    }
    public float[] sampleLod(float u,float v,float w,float lod) {
        if(lod!=0||!Float.isFinite(lod))throw new IllegalStateException("3D mip level unavailable: "+lod);
        return sample(u,v,w);
    }
    private static int address(int x,int size,Wrap wrap){return wrap==Wrap.REPEAT?Math.floorMod(x,size):Math.max(0,Math.min(size-1,x));}
    private static void coordinates(float u,float v,float w){if(!Float.isFinite(u)||!Float.isFinite(v)||!Float.isFinite(w))throw new IllegalArgumentException("Nonfinite 3D sampling coordinate");}
}
