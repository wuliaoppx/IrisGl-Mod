package dev.antimony.gl.glsl;

/**
 * Vertex execution services: fullscreen sampler compatibility plus explicit typed
 * per-vertex attributes. Implements {@link FragState} so generated user functions
 * and texture reads can share the fragment service interface.
 */
public final class VertexState implements FragState {
    /** CPU images for the sampler slots (index = the generated s$N field). */
    public final CpuImage[] unitImages;
    private final int width;
    private final int height;
    private final float u;
    private final float v;
    private InputProvider inputs;
    private int sourceVertexId = -1;
    private FragState imageServices;

    /** Original draw vertex index, before quad splitting or clipping. Missing context stays an error. */
    public VertexState sourceVertexId(int vertexId) {
        if (vertexId < 0) throw new IllegalArgumentException("Negative source vertex ID");
        this.sourceVertexId = vertexId;
        return this;
    }
    public int vertexId() {
        if (sourceVertexId < 0) throw new IllegalStateException("No original vertex invocation context");
        return sourceVertexId;
    }
    /** Resource owner supplies image bindings separately from texture/sampler slots. */
    public VertexState imageServices(FragState services) {
        this.imageServices = java.util.Objects.requireNonNull(services);
        if (services == this) throw new IllegalArgumentException("Recursive vertex image services");
        return this;
    }
    @Override public void storeImage3D(int unit,int[] coordinate,float[] value) {
        if (imageServices == null) throw new IllegalStateException("Unbound vertex floating image " + unit);
        imageServices.storeImage3D(unit,coordinate,value);
    }
    @Override public void storeUnsignedImage3D(int unit,int[] coordinate,int[] value) {
        if (imageServices == null) throw new IllegalStateException("Unbound vertex unsigned image " + unit);
        imageServices.storeUnsignedImage3D(unit,coordinate,value);
    }

    @Override public float[] sample3D(int unit,float u,float v,float w){if(imageServices==null)throw new IllegalStateException("Unbound vertex 3D sampler "+unit);return imageServices.sample3D(unit,u,v,w);}
    @Override public float[] sample3DLod(int unit,float u,float v,float w,float lod){if(imageServices==null)throw new IllegalStateException("Unbound vertex 3D sampler "+unit);return imageServices.sample3DLod(unit,u,v,w,lod);}
    @Override public float[] fetch3D(int unit,int x,int y,int z,int lod){if(imageServices==null)throw new IllegalStateException("Unbound vertex 3D sampler "+unit);return imageServices.fetch3D(unit,x,y,z,lod);}
    @Override public int[] fetchUnsigned3D(int unit,int x,int y,int z,int lod){if(imageServices==null)throw new IllegalStateException("Unbound vertex unsigned 3D sampler "+unit);return imageServices.fetchUnsigned3D(unit,x,y,z,lod);}

    @FunctionalInterface
    public interface InputProvider {
        /** Return a scalar wrapper or an exact-arity primitive array; null means unavailable. */
        Object value(GlslProgram.VertexInput input);
    }

    /**
     * Unpacked shader-space values. material is the pack ID; midUV is the original quad mean.
     * Constructor and shader fetches copy arrays; record accessors expose the provider's arrays.
     */
    public record Attributes(float[] position, float[] color, float[] uv, int[] lightUV,
                             float[] normal, float material, float[] midUV) implements InputProvider {
        public Attributes {
            position = copy(position, 3, "position");
            color = copy(color, 4, "color");
            uv = copy(uv, 2, "uv");
            if (lightUV == null || lightUV.length != 2) {
                throw new IllegalArgumentException("lightUV must be int[2]");
            }
            lightUV = lightUV.clone();
            normal = copy(normal, 3, "normal");
            midUV = copy(midUV, 2, "midUV");
        }

        private static float[] copy(float[] value, int size, String name) {
            if (value == null || value.length != size) {
                throw new IllegalArgumentException(name + " must be float[" + size + "]");
            }
            return value.clone();
        }

        @Override
        public Object value(GlslProgram.VertexInput input) {
            return switch (input.name()) {
                case "Position", "iris_Position" -> position;
                case "Color", "iris_Color", "gl_Color" -> color;
                case "UV0", "iris_UV0" -> uv;
                case "UV2", "iris_UV2" -> lightUV;
                case "Normal", "iris_Normal", "gl_Normal" -> normal;
                case "mc_Entity" -> switch (input.type()) {
                    case FLOAT -> material;
                    case VEC2 -> new float[]{material, 0};
                    case VEC3 -> new float[]{material, 0, 0};
                    case VEC4 -> new float[]{material, 0, 0, 0};
                    default -> null;
                };
                case "mc_midTexCoord" -> switch (input.type()) {
                    case VEC2 -> midUV;
                    // Iris stores two UV floats. GL supplies absent z=0,w=1 for a vec4 input.
                    case VEC3 -> new float[]{midUV[0], midUV[1], 0};
                    case VEC4 -> new float[]{midUV[0], midUV[1], 0, 1};
                    default -> null;
                };
                default -> null;
            };
        }
    }

    /** Explicit per-vertex path. Unknown names and mismatched types fail at fetch. */
    public VertexState(CpuImage[] unitImages, int width, int height, float u, float v,
                       InputProvider inputs) {
        this(unitImages, width, height, u, v);
        this.inputs = java.util.Objects.requireNonNull(inputs, "inputs");
    }

    public boolean hasVertexInputs() {
        return inputs != null;
    }

    /** Snapshot inputs so shader writes cannot modify the mesh or another invocation. */
    public Object vertexInput(GlslProgram.VertexInput input) {
        Object value = inputs == null ? null : inputs.value(input);
        GlslProgram.VertexInputType type = input.type();
        if (value == null) {
            throw new IllegalArgumentException("missing vertex input '" + input.name() + "' (" + type.glslType + ")");
        }
        boolean valid = switch (type) {
            case FLOAT -> value instanceof Float;
            case INT -> value instanceof Integer;
            case BOOL -> value instanceof Boolean;
            default -> type.javaType.isInstance(value)
                && java.lang.reflect.Array.getLength(value) == type.components;
        };
        if (!valid) {
            throw new IllegalArgumentException("vertex input '" + input.name() + "' requires " + type.glslType
                + " (" + type.javaType.getTypeName() + ", components=" + type.components + ")");
        }
        if (value instanceof float[] a) return a.clone();
        if (value instanceof int[] a) return a.clone();
        if (value instanceof boolean[] a) return a.clone();
        return value;
    }

    public VertexState(CpuImage[] unitImages, int width, int height, float u, float v) {
        this.unitImages = unitImages;
        this.width = width;
        this.height = height;
        this.u = u;
        this.v = v;
    }

    @Override
    public float[] sample(int unit, float su, float sv) {
        CpuImage image = unit >= 0 && unit < unitImages.length ? unitImages[unit] : null;
        if (image == null) {
            return new float[]{0.0f, 0.0f, 0.0f, 1.0f};
        }
        return image.sample(su, sv);
    }

    @Override public float[] fetch2D(int unit,int x,int y,int lod){return image(unit).fetch(x,y,lod);}
    @Override public float sampleChannel(int unit,float[] coordinate,int channel){return image(unit).sampleChannel(coordinate[0],coordinate[1],channel);}
    @Override public float sampleLodChannel(int unit,float[] coordinate,float lod,int channel){return image(unit).sampleLodChannel(coordinate[0],coordinate[1],lod,channel);}
    @Override public float sampleBiasChannel(int unit,float[] coordinate,float bias,int channel){return image(unit).sampleBiasChannel(coordinate[0],coordinate[1],bias,channel);}
    @Override public float[] sampleLod(int unit,float[] coordinate,float lod){return image(unit).sampleLod(coordinate[0],coordinate[1],lod);}
    @Override public float[] sampleBias(int unit,float[] coordinate,float bias){return image(unit).sampleBias(coordinate[0],coordinate[1],bias);}
    @Override public float sampleShadow(int unit,float su,float sv,float reference){return image(unit).sampleCompare(su,sv,reference);}
    private CpuImage image(int unit){CpuImage image=unit>=0&&unit<unitImages.length?unitImages[unit]:null;if(image==null)throw new IllegalStateException("Unbound vertex image "+unit);return image;}

    @Override
    public float width() {
        return width;
    }

    @Override
    public float height() {
        return height;
    }

    @Override
    public float fragU() {
        return u;
    }

    @Override
    public float fragV() {
        return v;
    }
}
