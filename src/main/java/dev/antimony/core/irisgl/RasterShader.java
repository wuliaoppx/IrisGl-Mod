package dev.antimony.core.irisgl;

import dev.antimony.gl.glsl.ShaderVaryings;

/** Immutable draw owner plus per-thread fragment state. 0=ordinary, 1=discard, 2=HDR committed. */
public interface RasterShader {
    ShaderVaryings layout();
    Worker worker();
    default boolean fragmentDraw() { return false; }
    /** Only a draw with captured per-attachment blending may consume a blended raster batch. */
    default boolean handlesBlend() { return false; }
    default void beginTransaction() { }
    default void endTransaction(boolean success) { }
    interface Worker {
        default void prepareTriangle(float[] triangle,int a,int b,int c,int viewportX,int viewportY,int viewportW,int viewportH){ }
        int shade(float[] triangle, int a, int b, int c, float e0, float e1, float e2,
                  float iw0, float iw1, float iw2, int x, int y, float depth, float iw, boolean front, boolean colorWrite);
    }
}
