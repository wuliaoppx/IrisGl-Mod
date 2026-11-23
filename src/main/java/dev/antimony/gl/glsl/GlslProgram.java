package dev.antimony.gl.glsl;

/**
 * Contract implemented by every generated program class
 * ({@code dev.antimony.gl.glsl.gen.GenNNN}). The executor sets uniform and
 * sampler fields, fills per-fragment inputs, then calls {@link #run}.
 * Outputs live in {@code out[loc]} (float[4] rgba), indexed by the layout
 * location captured from the GLSL.
 */
public interface GlslProgram {
    /** Scalar domain and arity of a fetched vertex attribute; no implicit coercion. */
    enum VertexInputType {
        FLOAT("float", float.class, 1), INT("int", int.class, 1), BOOL("bool", boolean.class, 1),
        VEC2("vec2", float[].class, 2), VEC3("vec3", float[].class, 3), VEC4("vec4", float[].class, 4),
        IVEC2("ivec2", int[].class, 2), IVEC3("ivec3", int[].class, 3), IVEC4("ivec4", int[].class, 4),
        BVEC2("bvec2", boolean[].class, 2), BVEC3("bvec3", boolean[].class, 3), BVEC4("bvec4", boolean[].class, 4);

        public final String glslType;
        public final Class<?> javaType;
        public final int components;

        VertexInputType(String glslType, Class<?> javaType, int components) {
            this.glslType = glslType;
            this.javaType = javaType;
            this.components = components;
        }
    }

    record VertexInput(String name, String javaField, VertexInputType type) { }

    /** True only when parsed vertex code references the built-in original draw index. */
    default boolean usesVertexId(){return false;}

    /** Declared fetched inputs, in declaration order. Empty for fragment programs. */
    default java.util.List<VertexInput> vertexInputs() {
        return java.util.List.of();
    }

    /** Final homogeneous clip position, copied for the caller. Consume without another MVP. */
    default float[] vertexPosition() {
        throw new IllegalStateException("program has no vertex stage");
    }

    void run(FragState st);
    default void runCompute(ComputeState st){throw new IllegalStateException("Program has no compute stage");}

    /**
     * Fill the per-fragment varyings this program reads (phase A3 executor).
     * Generated programs that consume {@code texcoord} override this with a
     * direct field write; programs without varyings keep the no-op.
     */
    default void setFragment(float u, float v) {
    }

    /**
     * Generated vertex entry. The fullscreen executor may retain its once-per-frame
     * invocation; mesh callers supply a VertexState input provider and invoke once per
     * vertex, then consume vertexPosition() directly. Program instances are mutable
     * invocation state and must not be shared concurrently. Fragment programs keep the no-op.
     */
    default void runVertex(VertexState vs) {
    }
}
