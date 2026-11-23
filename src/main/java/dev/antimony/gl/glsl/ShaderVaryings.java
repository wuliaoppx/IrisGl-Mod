package dev.antimony.gl.glsl;

import java.lang.reflect.Field;
import java.util.*;

/** Immutable typed VS/FS link. Integer lanes keep raw bits, never rounded float IDs. */
public final class ShaderVaryings {
    public enum Interpolation { SMOOTH, FLAT, NOPERSPECTIVE }
    public record Variable(String name, String javaField, String signature, String glslType, Interpolation interpolation,
                           int location, boolean centroid, boolean sample) { }
    public record Lane(Variable variable, int offset, int components) { }
    private final List<Lane> lanes;
    private final int components;
    private final byte[] modes;
    private final List<Variable> destinations;
    private ShaderVaryings(List<Variable> variables, List<Variable> destinations) {
        this.destinations = List.copyOf(destinations);
        List<Lane> packed = new ArrayList<>(); int count = 0;
        for (Variable v : variables) {
            if (v.centroid || v.sample) throw new IllegalArgumentException("Unsupported multisample varying " + v.name);
            String[] sig = v.signature.split("#"); int n = Integer.parseInt(sig[1]);
            if (n < 1 || n > 16 || !Set.of("float", "float[]", "int", "int[]", "boolean", "boolean[]").contains(sig[0]))
                throw new IllegalArgumentException("Unsupported varying type " + v);
            if (!sig[0].startsWith("float") && v.interpolation != Interpolation.FLAT)
                throw new IllegalArgumentException("Integer/bool varying must be flat: " + v.name);
            packed.add(new Lane(v, count, n)); count += n;
        }
        lanes = List.copyOf(packed); components = count; modes = new byte[count];
        for (Lane lane : lanes) Arrays.fill(modes, lane.offset, lane.offset + lane.components,
            (byte) lane.variable.interpolation.ordinal());
    }
    public static ShaderVaryings outputs(GlslCompiler.Result vertex) {
        List<Variable> vars = List.copyOf(vertex.varyingOutputs.values()); return new ShaderVaryings(vars, vars);
    }
    /** Only reachable FS reads require matching producers; dead declarations do not invent dependencies. */
    public static ShaderVaryings link(GlslCompiler.Result vertex, GlslCompiler.Result fragment) {
        List<Variable> vars = new ArrayList<>(), inputs = new ArrayList<>();
        for (String name : fragment.usedVaryings) {
            Variable input = Objects.requireNonNull(fragment.varyingInputs.get(name), "Missing varying metadata " + name);
            Variable output = vertex.varyingOutputs.get(name);
            if (input.location >= 0) {
                List<Variable> matches = vertex.varyingOutputs.values().stream().filter(v -> v.location == input.location).toList();
                if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous varying location " + input.location);
                output = matches.isEmpty() ? null : matches.get(0);
            }
            if (output == null || !output.signature.equals(input.signature) || !output.glslType.equals(input.glslType) || output.interpolation != input.interpolation
                    || output.centroid != input.centroid || output.sample != input.sample)
                throw new IllegalArgumentException("Unlinked/mismatched live fragment input " + input);
            vars.add(output); inputs.add(input);
        }
        return new ShaderVaryings(vars, inputs);
    }
    public Lane inputLane(String name){
        for(int i=0;i<destinations.size();i++)if(destinations.get(i).name().equals(name))return lanes.get(i);
        throw new IllegalArgumentException("No linked fragment varying "+name);
    }
    public List<Lane> lanes() { return lanes; }
    public int components() { return components; }
    public byte[] modes() { return modes.clone(); }
    public Access vertexAccess(GlslProgram program) { return new Access(program, false); }
    public Access fragmentAccess(GlslProgram program) { return new Access(program, true); }
    /** Resolve fields/types once. Each access owns its input arrays, never a caller's payload. */
    private enum Storage {
        FLOAT(float.class), FLOAT_ARRAY(float[].class), INT(int.class), INT_ARRAY(int[].class),
        BOOL(boolean.class), BOOL_ARRAY(boolean[].class);
        final Class<?> type;
        Storage(Class<?> type) { this.type = type; }
        static Storage of(String signature) {
            return switch (signature.substring(0, signature.indexOf('#'))) {
                case "float" -> FLOAT; case "float[]" -> FLOAT_ARRAY;
                case "int" -> INT; case "int[]" -> INT_ARRAY;
                case "boolean" -> BOOL; case "boolean[]" -> BOOL_ARRAY;
                default -> throw new IllegalArgumentException("varying storage " + signature);
            };
        }
    }
    public final class Access {
        private final GlslProgram program;
        private final Field[] fields;
        private final Storage[] storage;
        private final Object[] inputArrays;
        private Access(GlslProgram program, boolean fragment) {
            this.program = program; fields = new Field[lanes.size()];
            storage = new Storage[fields.length]; inputArrays = new Object[fields.length];
            try {
                for (int i=0;i<fields.length;i++) {
                    fields[i]=program.getClass().getField(fragment ? destinations.get(i).javaField : lanes.get(i).variable.javaField);
                    storage[i]=Storage.of(lanes.get(i).variable.signature);
                    if(fields[i].getType()!=storage[i].type)throw new IllegalArgumentException("Varying field type mismatch " + fields[i]);
                }
            } catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Invalid linked shader fields",e); }
        }
        public void capture(float[] destination,int offset) {
            try {
                for(int i=0;i<fields.length;i++) {
                    Lane lane=lanes.get(i); int o=offset+lane.offset;
                    switch(storage[i]) {
                        case FLOAT -> putFloat(destination,o,fields[i].getFloat(program),lane,0);
                        case INT -> destination[o]=Float.intBitsToFloat(fields[i].getInt(program));
                        case BOOL -> destination[o]=Float.intBitsToFloat(fields[i].getBoolean(program)?1:0);
                        case FLOAT_ARRAY -> {
                            float[] value=(float[])fields[i].get(program);
                            for(int j=0;j<lane.components;j++)putFloat(destination,o+j,value[j],lane,j);
                        }
                        case INT_ARRAY -> {
                            int[] value=(int[])fields[i].get(program);
                            for(int j=0;j<lane.components;j++)destination[o+j]=Float.intBitsToFloat(value[j]);
                        }
                        case BOOL_ARRAY -> {
                            boolean[] value=(boolean[])fields[i].get(program);
                            for(int j=0;j<lane.components;j++)destination[o+j]=Float.intBitsToFloat(value[j]?1:0);
                        }
                    }
                }
            } catch(ReflectiveOperationException|NullPointerException|IndexOutOfBoundsException e) {
                throw new IllegalStateException("Invalid vertex output payload",e);
            }
        }
        private void putFloat(float[] destination,int offset,float value,Lane lane,int component) {
            if(!Float.isFinite(value))throw new IllegalStateException("Nonfinite varying " + lane.variable.name + "[" + component + "]");
            destination[offset]=value;
        }
        public void install(float[] source,int offset) {
            try {
                for(int i=0;i<fields.length;i++) {
                    Lane lane=lanes.get(i); int o=offset+lane.offset;
                    switch(storage[i]) {
                        case FLOAT -> fields[i].setFloat(program,source[o]);
                        case INT -> fields[i].setInt(program,Float.floatToRawIntBits(source[o]));
                        case BOOL -> fields[i].setBoolean(program,Float.floatToRawIntBits(source[o])!=0);
                        case FLOAT_ARRAY -> {
                            float[] value=(float[])inputArrays[i];
                            if(value==null)inputArrays[i]=value=new float[lane.components];
                            System.arraycopy(source,o,value,0,lane.components); fields[i].set(program,value);
                        }
                        case INT_ARRAY -> {
                            int[] value=(int[])inputArrays[i];
                            if(value==null)inputArrays[i]=value=new int[lane.components];
                            for(int j=0;j<value.length;j++)value[j]=Float.floatToRawIntBits(source[o+j]);
                            fields[i].set(program,value);
                        }
                        case BOOL_ARRAY -> {
                            boolean[] value=(boolean[])inputArrays[i];
                            if(value==null)inputArrays[i]=value=new boolean[lane.components];
                            for(int j=0;j<value.length;j++)value[j]=Float.floatToRawIntBits(source[o+j])!=0;
                            fields[i].set(program,value);
                        }
                    }
                }
            } catch(ReflectiveOperationException|IndexOutOfBoundsException e) {
                throw new IllegalStateException("Invalid fragment input payload",e);
            }
        }
    }
    /** Freeze original provoking vertex before clipping/winding normalization. */
    public void flatten(float[] vertices,int count,int stride,int payloadOffset,int provoking) {
        for(int i=0;i<count;i++) for(int j=0;j<components;j++) if(modes[j]==1)
            vertices[i*stride+payloadOffset+j]=vertices[provoking*stride+payloadOffset+j];
    }
    public void interpolate(float[] triangle,int a,int b,int c,float e0,float e1,float e2,float iw0,float iw1,float iw2,float[] output) {
        float iw=e0*iw0+e1*iw1+e2*iw2;
        for(int j=0;j<components;j++) output[j]=switch(modes[j]) {
            case 1 -> triangle[a+j]; case 2 -> e0*triangle[a+j]+e1*triangle[b+j]+e2*triangle[c+j];
            default -> (e0*iw0*triangle[a+j]+e1*iw1*triangle[b+j]+e2*iw2*triangle[c+j])/iw;
        };
    }
}
