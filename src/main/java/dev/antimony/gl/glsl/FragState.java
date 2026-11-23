package dev.antimony.gl.glsl;

/**
 * Per-fragment execution services for translated programs. Implemented by the
 * CPU post-pipeline executor (A3): resolves sampler units to CPU images and
 * carries per-fragment context that GLSL exposes as gl_FragCoord.
 */
public interface FragState {
    /**
     * Sample the CPU image bound to the given texture unit at (u, v).
     * Returns rgba as float[4]; out-of-range clamps to the edge like a
     * CLAMP_TO_EDGE linear sample (composite passes only do plain reads).
     */
    float[] sample(int unit, float u, float v);
    default float[] sample(int unit,float[] coordinate){
        if(coordinate==null||coordinate.length!=2)throw new IllegalArgumentException("sampler2D coordinate arity");
        return sample(unit,coordinate[0],coordinate[1]);
    }
    /** Existing 2D executor mip limitation is retained; the supplied LOD expression still evaluates once. */
    default float[] sampleLod(int unit,float[] coordinate,float lod){return sample(unit,coordinate);}
    /** A bias is transported separately from an explicit LOD and requires a bound image provider. */
    default float[] sampleBias(int unit,float[] coordinate,float bias){throw new IllegalStateException("No biased texture provider for unit "+unit);}
    default float sampleChannel(int unit,float[] coordinate,int channel){return sample(unit,coordinate)[channel];}
    default float sampleLodChannel(int unit,float[] coordinate,float lod,int channel){return sampleLod(unit,coordinate,lod)[channel];}
    default float sampleBiasChannel(int unit,float[] coordinate,float bias,int channel){return sampleBias(unit,coordinate,bias)[channel];}
    /** Comparison samplers are a distinct domain; never compare an unrelated global shadow map. */
    default float sampleShadow(int unit,float[] coordinate){
        if(coordinate==null||coordinate.length!=3)throw new IllegalArgumentException("sampler2DShadow coordinate arity");
        return sampleShadow(unit,coordinate[0],coordinate[1],coordinate[2]);
    }
    default float sampleShadow(int unit,float u,float v,float reference){throw new IllegalStateException("Unbound comparison sampler "+unit);}
    default float sampleShadowLod(int unit,float[] coordinate,float lod){
        if(lod!=0||!Float.isFinite(lod))throw new IllegalStateException("Shadow mip level unavailable: "+lod);
        return sampleShadow(unit,coordinate);
    }
    default float[] shadow2D(int unit,float[] coordinate){
        float value=sampleShadow(unit,coordinate);return new float[]{value,value,value,1};
    }
    default float[] sample3D(int unit,float[] coordinate){
        if(coordinate==null||coordinate.length!=3)throw new IllegalArgumentException("sampler3D coordinate arity");
        return sample3D(unit,coordinate[0],coordinate[1],coordinate[2]);
    }
    default float[] sample3DLod(int unit,float[] coordinate,float lod){
        if(coordinate==null||coordinate.length!=3)throw new IllegalArgumentException("sampler3D coordinate arity");
        return sample3DLod(unit,coordinate[0],coordinate[1],coordinate[2],lod);
    }
    default float[] fetch2D(int unit,int[] coordinate,int lod){
        if(coordinate==null||coordinate.length!=2)throw new IllegalArgumentException("sampler2D fetch arity");
        return fetch2D(unit,coordinate[0],coordinate[1],lod);
    }
    default float[] fetch2D(int unit,int x,int y,int lod){throw new IllegalStateException("Unbound integer 2D fetch "+unit);}
    default float[] fetch3D(int unit,int[] coordinate,int lod){
        if(coordinate==null||coordinate.length!=3)throw new IllegalArgumentException("sampler3D fetch arity");
        return fetch3D(unit,coordinate[0],coordinate[1],coordinate[2],lod);
    }

    default int[] globalInvocationID(){throw new IllegalStateException("No compute invocation context");}
    default int[] fetchUnsigned3D(int unit,int[] coordinate,int lod){
        if(coordinate==null||coordinate.length!=3)throw new IllegalArgumentException("unsigned fetch coordinate arity");
        return fetchUnsigned3D(unit,coordinate[0],coordinate[1],coordinate[2],lod);
    }
    default void storeImage3D(int unit,int[] coordinate,float[] value){throw new IllegalStateException("Unbound writable floating image "+unit);}
    default void storeUnsignedImage3D(int unit,int[] coordinate,int[] value){throw new IllegalStateException("Unbound writable unsigned image "+unit);}

    /** Typed 3D services fail explicitly until a current resource owner supplies them. */
    default float[] sample3D(int unit,float u,float v,float w) { throw new IllegalStateException("Unbound floating 3D sampler "+unit); }
    default float[] sample3DLod(int unit,float u,float v,float w,float lod) {
        if(lod!=0||!Float.isFinite(lod))throw new IllegalStateException("3D mip level unavailable: "+lod);
        return sample3D(unit,u,v,w);
    }
    default float[] fetch3D(int unit,int x,int y,int z,int lod) { throw new IllegalStateException("Unbound floating 3D fetch "+unit); }
    default int[] fetchUnsigned3D(int unit,int x,int y,int z,int lod) { throw new IllegalStateException("Unbound unsigned 3D fetch "+unit); }
    /** Defined only for direct floating varying derivatives with an actual 2x2 raster interpolation owner. */
    default float derivative(String varying,int component,boolean yAxis) { throw new IllegalStateException("No raster derivative provider for "+varying); }

    /** Render target width in pixels. */
    float width();

    /** Render target height in pixels. */
    float height();

    /** This fragment's normalized coordinate u = x/width in [0,1). */
    default float fragU() {
        return 0.0f;
    }

    /** This fragment's normalized coordinate v = y/height in [0,1). */
    default float fragV() {
        return 0.0f;
    }

    /** Window-space z for gl_FragCoord.z (post passes only read it rarely). */
    default float fragZ() {
        return 0.5f;
    }

    /** Reciprocal clip w; fullscreen quads retain their existing w=1 default. */
    default float fragW() { return 1.0f; }

    default float pixelSizeX() {
        return 1.0f / Math.max(width(), 1.0f);
    }

    default float pixelSizeY() {
        return 1.0f / Math.max(height(), 1.0f);
    }
}
