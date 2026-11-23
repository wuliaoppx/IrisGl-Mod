package dev.antimony.gl.glsl;

/**
 * Scalar builtin helpers for the GLSL-to-Java translated programs. The
 * compiler expands vector operations componentwise at emit time; float[]
 * helpers here cover the cross-component reductions (dot/length/normalize,
 * matrices) that cannot be inlined cheaply. All arrays are RGBA/XYZW-ordered;
 * matrices are column-major float[n*n].
 */
public final class GlslRuntime {
    private GlslRuntime() {
    }

    public static float[] negate(float[] value){
        float[] result=new float[value.length];for(int i=0;i<value.length;i++)result[i]=-value[i];return result;
    }
    public static int clamp(int value,int minimum,int maximum){return Math.min(Math.max(value,minimum),maximum);}

    // ---- scalar GLSL semantics -------------------------------------------

    /**
     * B2: {@code shadow2D(sampler2DShadow, vec3)}. The GPU compare mode is
     * LEQUAL, so a fragment is lit when its reference depth is not greater than
     * the stored occluder depth. Returns the vec4 the shaders index as
     * {@code .r}; outside the shadow map the GPU returns "lit".
     */
    public static float[] shadow2D(int unit, float u, float v, float ref) {
        float lit = dev.antimony.core.render.ShadowMap.compare(u, v, ref);
        return new float[]{lit, lit, lit, 1.0f};
    }

    public static float mix(float x, float y, float a) {
        return x * (1.0f - a) + y * a;
    }

    public static float step(float edge, float x) {
        return x < edge ? 0.0f : 1.0f;
    }

    public static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    public static float clamp(float x, float minVal, float maxVal) {
        // GLSL clamp lowers to fmin(fmax(x, minVal), maxVal), and both of those
        // IGNORE a NaN operand instead of propagating it. Java's comparisons are
        // false for NaN, so the branchy version would return NaN and poison the
        // pixel. MakeUp's TAA hits this on every flat 3x3 neighbourhood (the
        // convex-hull determinate is distance(minValid,maxValid) = 0, so
        // ponderation is 0/0); hardware resolves it to the low bound, i.e. "no
        // temporal restriction", which keeps the history colour clamped into
        // the local range instead of turning the pixel black.
        if (Float.isNaN(x)) {
            return minVal;
        }
        return x < minVal ? minVal : (x > maxVal ? maxVal : x);
    }

    public static float fract(float x) {
        return x - (float) Math.floor(x);
    }

    /** GLSL mod: x - y*floor(x/y), result keeps sign of y. */
    public static float mod(float x, float y) {
        return x - y * (float) Math.floor(x / y);
    }

    public static float sign(float x) {
        return x > 0 ? 1.0f : (x < 0 ? -1.0f : 0.0f);
    }

    public static float inversesqrt(float x) {
        return 1.0f / (float) Math.sqrt(x);
    }

    // scalar wrappers keeping GLSL float semantics readable in generated code
    public static float floor(float x) {
        return (float) Math.floor(x);
    }

    public static float ceil(float x) {
        return (float) Math.ceil(x);
    }

    public static float sqrt(float x) {
        return (float) Math.sqrt(x);
    }

    public static float pow(float x, float y) {
        return (float) Math.pow(x, y);
    }

    public static float exp(float x) {
        return (float) Math.exp(x);
    }

    public static float exp2(float x) {
        return (float) Math.pow(2.0, x);
    }

    public static float log(float x) {
        return (float) Math.log(x);
    }

    public static float log2(float x) {
        return (float) (Math.log(x) / Math.log(2.0));
    }

    public static float sin(float x) {
        return (float) Math.sin(x);
    }

    public static float cos(float x) {
        return (float) Math.cos(x);
    }

    public static float tan(float x) {
        return (float) Math.tan(x);
    }

    public static float atan(float x) {
        return (float) Math.atan(x);
    }

    public static float atan(float y, float x) {
        return (float) Math.atan2(y, x);
    }

    public static boolean isnan(float x) {
        return Float.isNaN(x);
    }

    /** Column-major 3x3 construction from 9 scalars (GLSL column order). */
    public static float[] mat3(float c0r0, float c0r1, float c0r2,
                               float c1r0, float c1r1, float c1r2,
                               float c2r0, float c2r1, float c2r2) {
        return new float[] {
            c0r0, c0r1, c0r2,
            c1r0, c1r1, c1r2,
            c2r0, c2r1, c2r2
        };
    }

    // ---- vector reductions (any N) ----------------------------------------

    public static float dot(float[] a, float[] b) {
        float s = 0.0f;
        for (int i = 0; i < a.length && i < b.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    public static float length(float[] v) {
        return (float) Math.sqrt(dot(v, v));
    }

    public static float distance(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        float s = 0.0f;
        for (int i = 0; i < n; i++) {
            float d = a[i] - b[i];
            s += d * d;
        }
        return (float) Math.sqrt(s);
    }

    public static float[] normalize(float[] v) {
        float inv = 1.0f / Math.max(length(v), 1.0e-12f);
        float[] r = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = v[i] * inv;
        }
        return r;
    }

    public static float[] cross(float[] a, float[] b) {
        return new float[] {
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        };
    }

    public static float[] reflect(float[] i, float[] n) {
        float d = 2.0f * dot(n, i);
        float[] r = new float[i.length];
        for (int k = 0; k < i.length; k++) {
            r[k] = i[k] - d * n[k];
        }
        return r;
    }

    public static float[] refract(float[] i, float[] n, float eta) {
        float ni = dot(n, i);
        float k = 1.0f - eta * eta * (1.0f - ni * ni);
        if (k < 0.0f) {
            return new float[i.length];
        }
        float t = eta * ni - (float) Math.sqrt(k);
        float[] r = new float[i.length];
        for (int c = 0; c < i.length; c++) {
            r[c] = eta * i[c] - t * n[c];
        }
        return r;
    }

    // ---- matrices (column-major float[n*n]) --------------------------------

    /** Column-major 4x4 construction from 16 scalars (GLSL column order). */
    public static float[] mat4(float c0r0, float c0r1, float c0r2, float c0r3,
                               float c1r0, float c1r1, float c1r2, float c1r3,
                               float c2r0, float c2r1, float c2r2, float c2r3,
                               float c3r0, float c3r1, float c3r2, float c3r3) {
        return new float[] {
            c0r0, c0r1, c0r2, c0r3,
            c1r0, c1r1, c1r2, c1r3,
            c2r0, c2r1, c2r2, c2r3,
            c3r0, c3r1, c3r2, c3r3
        };
    }

    /** mat4(scalar): GLSL fills the diagonal (corpus: mat4(1.0f)). */
    public static float[] identity4() {
        return new float[] {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
    }

    /** mat4(mat3): upper-left 3x3 copied, rest identity. */
    public static float[] mat4FromMat3(float[] m) {
        return new float[] {
            m[0], m[1], m[2], 0.0f,
            m[3], m[4], m[5], 0.0f,
            m[6], m[7], m[8], 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
    }

    /** m * v, both sized by v.length; m is n*n column-major. */
    public static float[] mulMatVec(float[] m, float[] v) {
        int n = v.length;
        float[] r = new float[n];
        for (int row = 0; row < n; row++) {
            float s = 0.0f;
            for (int col = 0; col < n; col++) {
                s += m[col * n + row] * v[col];
            }
            r[row] = s;
        }
        return r;
    }

    /** v * m (row vector times matrix). */
    public static float[] mulVecMat(float[] v, float[] m) {
        int n = v.length;
        float[] r = new float[n];
        for (int row = 0; row < n; row++) {
            float s = 0.0f;
            for (int col = 0; col < n; col++) {
                s += v[col] * m[row * n + col];
            }
            r[row] = s;
        }
        return r;
    }

    /** m1 * m2, both n*n column-major. */
    public static float[] mulMatMat(float[] m1, float[] m2) {
        int n = (int) Math.round(Math.sqrt(m2.length));
        float[] r = new float[n * n];
        for (int col = 0; col < n; col++) {
            for (int row = 0; row < n; row++) {
                float s = 0.0f;
                for (int k = 0; k < n; k++) {
                    s += m1[k * n + row] * m2[col * n + k];
                }
                r[col * n + row] = s;
            }
        }
        return r;
    }

    /** Upper-left 3x3 of a 4x4. */
    public static float[] mat3FromMat4(float[] m) {
        return new float[] {
            m[0], m[1], m[2],
            m[4], m[5], m[6],
            m[8], m[9], m[10]
        };
    }

    /** Column i of an n*n column-major matrix, as float[n]. */
    public static float[] matCol(float[] m, int i, int n) {
        float[] r = new float[n];
        for (int row = 0; row < n; row++) {
            r[row] = m[i * n + row];
        }
        return r;
    }

    /** Convenience constructor wrapper so emitted code reads like GLSL. */
    public static float[] vec(float... c) {
        return c;
    }

    /**
     * Sink for gl_Position writes in vertex-mode programs: the CPU executor
     * rasterizes fullscreen quads itself, so the position is discarded.
     */
    public static float[] deadPosition() {
        return new float[4];
    }
}
