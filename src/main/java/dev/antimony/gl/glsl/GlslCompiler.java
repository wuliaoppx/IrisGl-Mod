package dev.antimony.gl.glsl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GLSL (330 core subset) to Java source translator for the CPU post pipeline
 * (docs section 19). Scope is the MakeUp-UltraFast corpus as captured by the
 * phase-A shim dump (8 programs): float/vec2-4/ivec/bvec/mat3-4, componentwise
 * vector ops, swizzles, if/else, bounded for loops, ternary, break/continue,
 * user functions (positional params), texture(sampler, uv) via FragState,
 * gl_FragCoord via st.width/height. Unsupported constructs throw
 * TranslateException naming the construct; the executor skips that pass
 * rather than producing wrong pixels.
 *
 * <p>Emission model: every vector value is materialized as a Java
 * {@code float[]} via {@code GlslRuntime.vec(...)}; the AST carries
 * component expressions so common paths (scalar math, swizzle reads) stay
 * scalar-inline, while vector results collapse into one array expression at
 * the assignment boundary.</p>
 */
public final class GlslCompiler {
    /** Thrown when the source uses something outside the supported subset. */
    public static final class TranslateException extends RuntimeException {
        public TranslateException(String message) {
            super(message);
        }
    }

    // ---- types -------------------------------------------------------------

    private static final int T_FLOAT = 0;
    private static final int T_INT = 1;
    private static final int T_BOOL = 2;
    private static final int T_VEC2 = 3;
    private static final int T_VEC3 = 4;
    private static final int T_VEC4 = 5;
    private static final int T_IVEC2 = 6;
    private static final int T_IVEC3 = 7;
    private static final int T_IVEC4 = 8;
    private static final int T_BVEC2 = 9;
    private static final int T_BVEC3 = 10;
    private static final int T_BVEC4 = 11;
    private static final int T_MAT3 = 12;
    private static final int T_MAT4 = 13;
    private static final int T_SAMPLER = 14;
    private static final int T_VOID = 15;
    private static final int T_FLOAT_ARR = 16;
    private static final int T_INT_ARR = 17;
    private static final int T_VEC2_ARR = 18;
    private static final int T_VEC3_ARR = 19;
    private static final int T_VEC4_ARR = 20;
    private static final int T_MAT2 = 21;
    private static final int T_SAMPLER3D = 22, T_USAMPLER3D = 23;
    private static final int T_UINT=24,T_UVEC2=25,T_UVEC3=26,T_UVEC4=27;
    private static final int T_IVEC2_ARR=28,T_IVEC3_ARR=29,T_IVEC4_ARR=30;
    private static final int T_IMAGE3D=31,T_UIMAGE3D=32;
    private static final int T_SAMPLER2DSHADOW=33;
    private static boolean isUintVec(int t){return t>=T_UVEC2&&t<=T_UVEC4;}
    private static boolean integerType(int t){return t==T_INT||t==T_UINT||isIntVec(t)||isUintVec(t);}
    private static boolean isImage(int t){return t==T_IMAGE3D||t==T_UIMAGE3D;}
    private static int integerVecOf(int n,boolean unsigned){return unsigned?(n==2?T_UVEC2:n==3?T_UVEC3:T_UVEC4):(n==2?T_IVEC2:n==3?T_IVEC3:T_IVEC4);}


    private static final String[] TYPE_NAME = {
        "float", "int", "boolean", "float[]", "float[]", "float[]",
        "int[]", "int[]", "int[]", "boolean[]", "boolean[]", "boolean[]",
        "float[]", "float[]", "int", "void", "float[]", "int[]", "float[][]", "float[][]", "float[][]", "float[]", "int", "int", "int", "int[]", "int[]", "int[]", "int[][]", "int[][]", "int[][]", "int", "int", "int"
    };

    private static boolean isSampler(int type){return type==T_SAMPLER||type==T_SAMPLER3D||type==T_USAMPLER3D||type==T_SAMPLER2DSHADOW;}
    private static String samplerTypeName(int type){return switch(type){case T_SAMPLER->"sampler2D";case T_SAMPLER3D->"sampler3D";case T_USAMPLER3D->"usampler3D";case T_SAMPLER2DSHADOW->"sampler2DShadow";default->throw new TranslateException("sampler domain");};}
    private static int vecSize(int t) {
        return switch (t) {
            case T_FLOAT, T_INT, T_UINT, T_BOOL, T_SAMPLER, T_SAMPLER3D, T_USAMPLER3D, T_SAMPLER2DSHADOW, T_IMAGE3D, T_UIMAGE3D, T_VOID, T_FLOAT_ARR, T_INT_ARR -> 1;
            case T_VEC2, T_IVEC2, T_UVEC2, T_BVEC2, T_MAT2 -> 2;
            case T_VEC3, T_IVEC3, T_UVEC3, T_BVEC3, T_MAT3 -> 3;
            case T_VEC4, T_IVEC4, T_UVEC4, T_BVEC4, T_MAT4 -> 4;
            default -> 0;
        };
    }

    private static boolean isVec(int t) {
        return (t >= T_VEC2 && t <= T_BVEC4)||isUintVec(t);
    }

    private static boolean isFloatVec(int t) {
        return t >= T_VEC2 && t <= T_VEC4;
    }

    private static boolean isIntVec(int t) {
        return t >= T_IVEC2 && t <= T_IVEC4;
    }

    private static boolean isBoolVec(int t) {
        return t >= T_BVEC2 && t <= T_BVEC4;
    }

    private static boolean isMat(int t) {
        return t == T_MAT2 || t == T_MAT3 || t == T_MAT4;
    }

    /** "javaType#componentCount" - componentCount is 9/16 for matrices. */
    private static String typeSignature(int type) {
        int comps = isMat(type) ? vecSize(type) * vecSize(type) : vecSize(type);
        return TYPE_NAME[type] + "#" + comps;
    }

    private static boolean isArray(int type) { return type >= T_FLOAT_ARR && type <= T_VEC4_ARR || type>=T_IVEC2_ARR&&type<=T_IVEC4_ARR; }

    private static int arrayOf(int element) {
        return switch (element) {
            case T_FLOAT -> T_FLOAT_ARR; case T_INT -> T_INT_ARR;
            case T_VEC2 -> T_VEC2_ARR; case T_VEC3 -> T_VEC3_ARR; case T_VEC4 -> T_VEC4_ARR;
            case T_IVEC2->T_IVEC2_ARR;case T_IVEC3->T_IVEC3_ARR;case T_IVEC4->T_IVEC4_ARR;
            default -> throw new TranslateException("unsupported array element type " + TYPE_NAME[element]);
        };
    }

    private static int elementOf(int type) {
        return switch (type) {
            case T_FLOAT_ARR -> T_FLOAT; case T_INT_ARR -> T_INT;
            case T_VEC2_ARR -> T_VEC2; case T_VEC3_ARR -> T_VEC3; case T_VEC4_ARR -> T_VEC4;
            case T_IVEC2_ARR->T_IVEC2;case T_IVEC3_ARR->T_IVEC3;case T_IVEC4_ARR->T_IVEC4;
            default -> baseOf(type);
        };
    }

    private static int baseOf(int t) {
        if(isUintVec(t))return T_UINT;
        if (isIntVec(t)) {
            return T_INT;
        }
        if (isBoolVec(t)) {
            return T_BOOL;
        }
        if (isFloatVec(t)) {
            return T_FLOAT;
        }
        if (t == T_FLOAT_ARR) {
            return T_FLOAT;
        }
        if (t == T_INT_ARR) {
            return T_INT;
        }
        return t;
    }

    private static int typeOfName(String name) {
        return switch (name) {
            case "float" -> T_FLOAT;
            case "int" -> T_INT;
            case "bool" -> T_BOOL;
            case "vec2" -> T_VEC2;
            case "vec3" -> T_VEC3;
            case "vec4" -> T_VEC4;
            case "ivec2" -> T_IVEC2;
            case "ivec3" -> T_IVEC3;
            case "ivec4" -> T_IVEC4;
            case "bvec2" -> T_BVEC2;
            case "bvec3" -> T_BVEC3;
            case "bvec4" -> T_BVEC4;
            case "mat2" -> T_MAT2;
            case "mat3" -> T_MAT3;
            case "mat4" -> T_MAT4;
            case "sampler2D" -> T_SAMPLER;
            case "sampler2DShadow" -> T_SAMPLER2DSHADOW;
            case "sampler3D" -> T_SAMPLER3D;
            case "usampler3D" -> T_USAMPLER3D;
            case "uint"->T_UINT;case "uvec2"->T_UVEC2;case "uvec3"->T_UVEC3;case "uvec4"->T_UVEC4;
            case "image3D"->T_IMAGE3D;case "uimage3D"->T_UIMAGE3D;
            case "void" -> T_VOID;
            default -> -1;
        };
    }

    // ---- lexer -------------------------------------------------------------

    private static final int ID = 0;
    private static final int NUM = 1;
    private static final int OP = 2;

    private record Tok(int kind, String text) { }

    private final List<Tok> toks = new ArrayList<>();
    private int pos;

    private static List<Tok> lex(String src) {
        List<Tok> out = new ArrayList<>();
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            if (c == '#') {
                // corpus preprocessor leftovers: #version/#extension - skip line
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int s = i;
                while (i < n && Character.isJavaIdentifierPart(src.charAt(i))) {
                    i++;
                }
                out.add(new Tok(ID, src.substring(s, i)));
                continue;
            }
            if(c=='0'&&i+1<n&&(src.charAt(i+1)=='x'||src.charAt(i+1)=='X')){
                i+=2;int start=i;while(i<n&&Character.digit(src.charAt(i),16)>=0)i++;
                if(start==i)throw new TranslateException("invalid hexadecimal literal");long value=Long.parseUnsignedLong(src.substring(start,i),16);
                boolean uint=i<n&&(src.charAt(i)=='u'||src.charAt(i)=='U');if(uint)i++;
                if(value>0xffffffffL)throw new TranslateException("integer literal beyond 32 bits");out.add(new Tok(NUM,Long.toString(value)+(uint||value>Integer.MAX_VALUE?"u":"")));continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                int s = i;
                while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) {
                    i++;
                }
                // scientific notation: 2.7E-4 / 1e6 / 4.0E+10
                if (i < n && (src.charAt(i) == 'E' || src.charAt(i) == 'e')
                    && i + 1 < n && (Character.isDigit(src.charAt(i + 1))
                        || ((src.charAt(i + 1) == '+' || src.charAt(i + 1) == '-')
                            && i + 2 < n && Character.isDigit(src.charAt(i + 2))))) {
                    i++; // E
                    if (src.charAt(i) == '+' || src.charAt(i) == '-') {
                        i++;
                    }
                    while (i < n && Character.isDigit(src.charAt(i))) {
                        i++;
                    }
                }
                boolean suffixU=i<n&&(src.charAt(i)=='u'||src.charAt(i)=='U');
                if(suffixU){String number=src.substring(s,i);if(number.contains(".")||number.contains("e")||number.contains("E"))throw new TranslateException("floating uint literal");i++;out.add(new Tok(NUM,number+"u"));continue;}
                boolean suffixF = i < n && (src.charAt(i) == 'f' || src.charAt(i) == 'F');
                if (suffixF) {
                    i++;
                }
                String txt = src.substring(s, suffixF ? i - 1 : i);
                out.add(new Tok(NUM, txt + (txt.contains(".") || txt.contains("E") || txt.contains("e") ? "f" : "")));
                continue;
            }
            String[] two = { "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=" , "<<", ">>" };
            if (i + 1 < n) {
                String pair = src.substring(i, i + 2);
                boolean hit = false;
                for (String t2 : two) {
                    if (pair.equals(t2)) {
                        out.add(new Tok(OP, t2));
                        i += 2;
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    continue;
                }
            }
            out.add(new Tok(OP, String.valueOf(c)));
            i++;
        }
        return out;
    }

    private Tok peek() {
        if (pos >= toks.size()) {
            return new Tok(OP, "\0end");
        }
        return toks.get(pos);
    }

    private Tok peekAt(int ahead) {
        int i = pos + ahead;
        return i < toks.size() ? toks.get(i) : new Tok(OP, "\0end");
    }

    private Tok next() {
        if (pos >= toks.size()) {
            return new Tok(OP, "\0end");
        }
        return toks.get(pos++);
    }

    private boolean accept(String text) {
        if (peek().text.equals(text)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(String text) {
        if (!accept(text)) {
            throw new TranslateException("expected '" + text + "' but found '" + peek().text + "'");
        }
    }

    // ---- symbol tables -----------------------------------------------------

    private static final class Var {
        final String javaName;
        final int type;
        final boolean isField;
        /** Non-null only for the actual declared non-sampler uniform symbol. */
        final String uniformName;

        Var(String javaName, int type, boolean isField) {
            this(javaName, type, isField, null);
        }

        Var(String javaName, int type, boolean isField, String uniformName) {
            this.javaName = javaName;
            this.type = type;
            this.isField = isField;
            this.uniformName = uniformName;
        }
    }

    private Map<String, Var> globals = new LinkedHashMap<>();
    private final Map<String, Var> locals = new HashMap<>();
    private final List<String> fieldDecls = new ArrayList<>();
    private final StringBuilder globalInitializers = new StringBuilder();
    private final Map<String, UserFn> userFns = new LinkedHashMap<>();
    /** sampler uniform name -> texture unit int (assigned by the executor). */
    private final Map<String, Integer> samplerUnits = new LinkedHashMap<>();
    private final Map<String,String> samplerTypes = new LinkedHashMap<>();
    private final java.util.Set<String> derivativeInputs = new java.util.LinkedHashSet<>();
    /** Uniform name -> Java field name / GLSL type name, for the CPU executor. */
    private final Map<String, String> uniformFields = new LinkedHashMap<>();
    private final Map<String, String> uniformTypes = new LinkedHashMap<>();
    /** Direct references/calls in the current executable body, not in declarations. */
    private final java.util.Set<String> referencedUniforms = new java.util.LinkedHashSet<>();
    private final java.util.Set<UserFn> calledFunctions = new java.util.LinkedHashSet<>();
    /** Sampler names actually read by texture()/textureLod() in this program. */
    private final java.util.Set<String> usedSamplers = new java.util.LinkedHashSet<>();
    private int samplerCount;
    private int outSlotCount;
    private int indentUnits;
    private boolean sawGlFragCoord;
    /** true while translating a user function body (calls become instance). */
    private boolean inUserFn;
    /** last statement in the current function body was a return. */
    private boolean endedWithReturn;
    /** B1: true while translating a fullscreen-pass vertex stage. */
    private boolean vertexMode;
    private boolean computeMode;
    private boolean sawGlVertexId;
    private final int[] localSize={1,1,1};
    private int imageCount;
    private String imageAccessQualifier="readwrite";
    private final Map<String,Integer> imageUnits=new LinkedHashMap<>();
    private final Map<String,String> imageTypes=new LinkedHashMap<>(),imageAccess=new LinkedHashMap<>();
    private final java.util.Set<String> usedImages=new java.util.LinkedHashSet<>();
    /** vertex mode: declared "out"/"varying" outputs, GLSL name -> signature. */
    private final Map<String, String> vertexOutputs = new LinkedHashMap<>();
    private final List<GlslProgram.VertexInput> vertexInputs = new ArrayList<>();
    private final Map<String, ShaderVaryings.Variable> varyingInputs = new LinkedHashMap<>();
    private final Map<String, ShaderVaryings.Variable> varyingOutputs = new LinkedHashMap<>();
    private final java.util.Set<String> referencedVaryings = new java.util.LinkedHashSet<>();
    private ShaderVaryings.Interpolation interpolation = ShaderVaryings.Interpolation.SMOOTH;
    private boolean centroid, sampleQualifier;
    private int interfaceLocation = -1;

    private static final class UserFn {
        final String javaName;
        final int retType;
        final List<Var> params = new ArrayList<>();
        final List<String> paramNames = new ArrayList<>();
        final List<String> qualifiers = new ArrayList<>();
        final List<Tok> bodyToks = new ArrayList<>();
        final java.util.Set<String> referencedUniforms = new java.util.LinkedHashSet<>();
        final java.util.Set<String> referencedVaryings = new java.util.LinkedHashSet<>();
        final java.util.Set<String> derivativeInputs = new java.util.LinkedHashSet<>();
        final java.util.Set<String> usedSamplers = new java.util.LinkedHashSet<>();
        final java.util.Set<String> usedImages = new java.util.LinkedHashSet<>();
        final java.util.Set<UserFn> calledFunctions = new java.util.LinkedHashSet<>();

        UserFn(String javaName, int retType) {
            this.javaName = javaName;
            this.retType = retType;
        }
    }

    // ---- public API ---------------------------------------------------------

    /**
     * Translate one Iris-preprocessed GLSL fragment + its (trivial) vertex
     * stage into a Java class implementing {@link GlslProgram}.
     *
     * @return java source text, package dev.antimony.gl.glsl.gen
     */
    public Result translate(String className, String fragmentSource, String vertexSource) {
        vertexMode = false;computeMode=false;
        return translateImpl(className, fragmentSource);
    }

    /**
     * B1: translate one fullscreen-pass vertex stage into a {@code PostIdV}
     * class overriding {@code runVertex(VertexState)}. The corpus' vertex
     * stages write varyings that are constant across the fullscreen quad
     * (sky/cloud colours, light vectors, matrices); the executor runs the
     * vertex once per frame at the quad centre and copies the declared
     * outputs into every fragment instance by reflection.
     *
     * @return java source text, package dev.antimony.gl.glsl.gen
     */
    public Result translateVertex(String className, String vertexSource) {
        vertexMode = true;computeMode=false;
        return translateImpl(className, vertexSource);
    }

    /** Read the linked compute layout without executing or compiling a kernel. Only literal layout sizes are supported. */
    public static int[] declaredComputeLocalSize(String source){
        List<Tok> tokens=lex(source);int[] size={1,1,1};boolean found=false;java.util.Set<String> axes=new java.util.HashSet<>();
        for(int i=0;i+2<tokens.size();i++){
            if(!tokens.get(i).text.equals("layout")||!tokens.get(i+1).text.equals("(")||!tokens.get(i+2).text.startsWith("local_size_"))continue;
            found=true;int at=i+2;
            while(true){
                if(at+3>=tokens.size())throw new TranslateException("incomplete compute local size");
                String axis=tokens.get(at++).text;int index=switch(axis){case "local_size_x"->0;case "local_size_y"->1;case "local_size_z"->2;default->throw new TranslateException("unknown compute local-size axis");};
                if(!axes.add(axis)||!tokens.get(at++).text.equals("="))throw new TranslateException("invalid compute local size");
                String number=tokens.get(at++).text;int value;
                try{value=Integer.decode(number.replaceFirst("[uU]$",""));}catch(NumberFormatException invalid){throw new TranslateException("nonliteral compute local size");}
                if(value<1||value>1024)throw new TranslateException("unsupported compute local-size axis");size[index]=value;
                String next=tokens.get(at++).text;if(next.equals(")")){if(at+1>=tokens.size()||!tokens.get(at).text.equals("in")||!tokens.get(at+1).text.equals(";"))throw new TranslateException("invalid compute layout declaration");break;}
                if(!next.equals(","))throw new TranslateException("nonliteral compute local size");
            }
            i=at;
        }
        if(!found||(long)size[0]*size[1]*size[2]>1024)throw new TranslateException("missing or unsupported compute local size");
        return size;
    }
    public Result translateCompute(String className,String source){vertexMode=false;computeMode=true;return translateImpl(className,source);}
    private Result translateImpl(String className, String source) {
        toks.clear();
        toks.addAll(lex(source));
        pos = 0;
        globals = new LinkedHashMap<>();
        locals.clear();
        fieldDecls.clear();
        globalInitializers.setLength(0);
        userFns.clear();
        samplerUnits.clear(); samplerTypes.clear(); derivativeInputs.clear();
        imageUnits.clear();imageTypes.clear();imageAccess.clear();usedImages.clear();imageCount=0;java.util.Arrays.fill(localSize,1);
        uniformFields.clear();
        uniformTypes.clear();
        referencedUniforms.clear();
        calledFunctions.clear();
        usedSamplers.clear();
        samplerCount = 0;
        outSlotCount = 0;
        indentUnits = 0;
        sawGlFragCoord = false;
        sawGlVertexId=vertexMode&&toks.stream().anyMatch(token->token.kind()==ID&&token.text().equals("gl_VertexID"));
        vertexOutputs.clear();
        vertexInputs.clear();
        varyingInputs.clear(); varyingOutputs.clear(); referencedVaryings.clear();
        resetInterfaceQualifiers();
        hoistedNames.clear();
        hoistedFields.clear();
        structTypes.clear();
        assignTemp = 0;
        if (vertexMode) {
            globals.put("gl_Position", new Var("__clipPosition", T_VEC4, true));
        }

        StringBuilder cls = new StringBuilder();
        cls.append("package dev.antimony.gl.glsl.gen;\n\n")
            .append("import dev.antimony.gl.glsl.FragState;\n")
            .append("import dev.antimony.gl.glsl.GlslProgram;\n")
            .append("import dev.antimony.gl.glsl.GlslRuntime;\n")
            .append("import dev.antimony.gl.glsl.GlslIntegerRuntime;\n")
            .append("import dev.antimony.gl.glsl.ComputeState;\n");
        if (vertexMode) {
            cls.append("import dev.antimony.gl.glsl.VertexState;\n");
        }
        cls.append("\npublic final class ").append(className).append(" implements GlslProgram {\n");

        List<String> varyingIns = new ArrayList<>();
        parseHeader(cls, varyingIns);
        // Ordinary global initializers execute for each invocation, after binding.
        // Their uniform/helper dependencies must remain in the live input graph.

        if (!vertexMode) {
            // out slots
            cls.append("    public final float[][] out = new float[").append(Math.max(outSlotCount, 1)).append("][];\n");
        }

        // Names that user-function bodies reference: those main-locals must
        // live as fields (GLSL nested-scope visibility; Java needs fields).
        java.util.Set<String> fnReferenced = new java.util.HashSet<>();
        for (UserFn fn : userFns.values()) {
            for (Tok t : fn.bodyToks) {
                if (t.kind() == ID) {
                    fnReferenced.add(t.text());
                }
            }
        }
        hoistedNames.addAll(fnReferenced);

        // Fragment varying fill-in: the pass executor sets (u,v) per pixel
        // through this method, so the hot loop never touches reflection.
        if (!vertexMode && (varyingIns.contains("texcoord") || varyingIns.contains("texCoord"))) {
            cls.append("\n    @Override public void setFragment(float u, float v) {\n");
            for (String name : List.of("texcoord", "texCoord")) if (varyingIns.contains(name))
                cls.append("        ").append(name).append("[0] = u; ").append(name).append("[1] = v;\n");
            cls.append("    }\n");
        }

        {
            cls.append("\n    private static float[] __vertexSwizzle(float[] value, int... indices) {\n")
                .append("        float[] result = new float[indices.length];\n")
                .append("        for (int i = 0; i < indices.length; i++) result[i] = value[indices[i]];\n")
                .append("        return result;\n    }\n")
                .append("    private static float[] __vertexArithmetic(float[] a, float[] b, char op) {\n")
                .append("        float[] result = new float[Math.max(a.length, b.length)];\n")
                .append("        for (int i = 0; i < result.length; i++) {\n")
                .append("            float x = a[a.length == 1 ? 0 : i], y = b[b.length == 1 ? 0 : i];\n")
                .append("            result[i] = switch (op) { case '+' -> x+y; case '-' -> x-y; case '*' -> x*y; case '/' -> x/y; default -> x%y; };\n")
                .append("        }\n        return result;\n    }\n");
        }
        if (vertexMode) {
            cls.append("\n    @Override public boolean usesVertexId() { return ").append(sawGlVertexId).append("; }\n")
                .append("    private float[] __clipPosition = new float[4];\n")
                .append("    @Override public float[] vertexPosition() { return __clipPosition.clone(); }\n")
                .append("    private static final java.util.List<VertexInput> __inputs = java.util.List.of(");
            for (int i = 0; i < vertexInputs.size(); i++) {
                GlslProgram.VertexInput input = vertexInputs.get(i);
                if (i > 0) cls.append(", ");
                cls.append("new VertexInput(\"").append(input.name()).append("\", \"")
                    .append(input.javaField()).append("\", VertexInputType.").append(input.type().name()).append(")");
            }
            cls.append(");\n    @Override public java.util.List<VertexInput> vertexInputs() { return __inputs; }\n");
            cls.append("\n    @Override\n    public void runVertex(VertexState st) {\n")
                .append("        __clipPosition = new float[4];\n");
            for (String output : vertexOutputs.keySet()) {
                Var v = globals.get(output);
                cls.append("        ").append(v.javaName).append(" = ").append(defaultInit(v.type)).append(";\n");
            }
            cls.append("        if (st.hasVertexInputs()) {\n");
            for (int i = 0; i < vertexInputs.size(); i++) {
                GlslProgram.VertexInput input = vertexInputs.get(i);
                int type = globals.get(input.name()).type;
                cls.append("            ").append(input.javaField()).append(" = (").append(TYPE_NAME[type])
                    .append(") st.vertexInput(__inputs.get(").append(i).append("));\n");
            }
            cls.append("        }\n");
        } else {
            // main() -> run(FragState st)
            cls.append("\n    @Override\n    public void run(FragState st) {\n")
                .append("        for (int i = 0; i < out.length; i++) out[i] = new float[4];\n");
        }
        cls.append(globalInitializers);
        parseMainBody(cls);
        cls.append("    }\n");
        if(computeMode)cls.append("\n    @Override public void runCompute(ComputeState st) {run(st);}\n");
        if (vertexMode) {
            // GlslProgram's fragment entry is abstract; vertex-only classes
            // never rasterize fragments (the executor calls runVertex once).
            cls.append("\n    @Override\n    public void run(FragState st) {\n    }\n");
        }

        for (UserFn fn : userFns.values()) {
            emitUserFunction(cls, fn);
        }

        // hoisted fields (declared after methods - valid Java)
        for (Map.Entry<String, Var> e : hoistedFields.entrySet()) {
            Var v = e.getValue();
            cls.append("\n    private ").append(TYPE_NAME[v.type]).append(' ').append(v.javaName)
                .append("; // hoisted main-local shared with user functions\n");
        }

        cls.append("}\n");

        Result r = new Result();
        r.javaSource = cls.toString();
        r.samplerUnits.putAll(samplerUnits);
        r.samplerTypes.putAll(samplerTypes);
        r.compute=computeMode;r.localSize=localSize.clone();r.imageUnits.putAll(imageUnits);r.imageTypes.putAll(imageTypes);r.imageAccess.putAll(imageAccess);
        java.util.Set<String> liveDerivatives=new java.util.LinkedHashSet<>(derivativeInputs);
        java.util.Set<String> liveSamplers=new java.util.LinkedHashSet<>(usedSamplers);
        java.util.Set<String> liveImages=new java.util.LinkedHashSet<>(usedImages);
        r.uniformFields.putAll(uniformFields);
        r.uniformTypes.putAll(uniformTypes);
        java.util.Set<String> live = new java.util.LinkedHashSet<>(referencedUniforms);
        java.util.Set<String> liveVaryings = new java.util.LinkedHashSet<>(referencedVaryings);
        java.util.Set<UserFn> visited = new java.util.HashSet<>();
        List<UserFn> pending = new ArrayList<>(calledFunctions);
        for (int i = 0; i < pending.size(); i++) {
            UserFn fn = pending.get(i);
            if (!visited.add(fn)) continue;
            live.addAll(fn.referencedUniforms);
            liveVaryings.addAll(fn.referencedVaryings);
            liveDerivatives.addAll(fn.derivativeInputs);liveSamplers.addAll(fn.usedSamplers);liveImages.addAll(fn.usedImages);
            pending.addAll(fn.calledFunctions);
        }
        r.derivativeInputs.addAll(liveDerivatives);r.usedSamplers.addAll(liveSamplers);r.usedImages.addAll(liveImages);
        // Stable declaration order; declarations remain available to the post executor.
        for (String name : uniformFields.keySet()) if (live.contains(name)) r.usedUniforms.add(name);
        r.outSlots = Math.max(outSlotCount, 1);
        r.varyingIns.addAll(varyingIns);
        r.varyingInputs.putAll(varyingInputs); r.varyingOutputs.putAll(varyingOutputs);
        for (String name : varyingInputs.keySet()) if (liveVaryings.contains(name)) r.usedVaryings.add(name);
        r.usesGlFragCoord = sawGlFragCoord;
        r.usesGlVertexId = sawGlVertexId;
        if (vertexMode) {
            r.vertexOutputs.putAll(vertexOutputs);
            r.vertexInputs.addAll(vertexInputs);
        }
        return r;
    }

    public static final class Result {
        public String javaSource;
        public boolean compute;
        public int[] localSize={1,1,1};
        public final Map<String,Integer> imageUnits=new LinkedHashMap<>();
        public final Map<String,String> imageTypes=new LinkedHashMap<>(),imageAccess=new LinkedHashMap<>();
        public final java.util.Set<String> usedImages=new java.util.LinkedHashSet<>();
        /** sampler uniform name -> texture unit the executor must bind. */
        public final Map<String, Integer> samplerUnits = new LinkedHashMap<>();
        public final Map<String,String> samplerTypes = new LinkedHashMap<>();
        public final java.util.Set<String> derivativeInputs = new java.util.LinkedHashSet<>();
        /** sampler names the program actually reads (declared-but-unused excluded). */
        public final java.util.Set<String> usedSamplers = new java.util.LinkedHashSet<>();
        /** uniform name -> generated Java field name. */
        public final Map<String, String> uniformFields = new LinkedHashMap<>();
        /** uniform name -> "javaType#components" for the executor's value assignment. */
        public final Map<String, String> uniformTypes = new LinkedHashMap<>();
        /**
         * Non-sampler uniform symbols referenced by the translated entry body or
         * transitively called helpers. Uses resolved symbols, not source-name matches;
         * unreachable helpers, shadowing locals/parameters and declarations do not count.
         * Conservative across branches: this is executable reachability, not GLSL DCE.
         */
        public final java.util.Set<String> usedUniforms = new java.util.LinkedHashSet<>();
        public int outSlots;
        /** names of fragment "in" variables the executor must fill per frame. */
        public final List<String> varyingIns = new ArrayList<>();
        /** B1 vertex mode: declared "out"/"varying" outputs (GLSL name -> "javaType#components"). */
        public final Map<String, String> vertexOutputs = new LinkedHashMap<>();
        /** Typed fetched attributes; also exposed directly by the generated program. */
        public final List<GlslProgram.VertexInput> vertexInputs = new ArrayList<>();
        public final Map<String, ShaderVaryings.Variable> varyingInputs = new LinkedHashMap<>();
        public final Map<String, ShaderVaryings.Variable> varyingOutputs = new LinkedHashMap<>();
        public final java.util.Set<String> usedVaryings = new java.util.LinkedHashSet<>();
        public boolean usesGlFragCoord;
        public boolean usesGlVertexId;
    }

    // ---- header ---------------------------------------------------------

    private void parseHeader(StringBuilder cls, List<String> varyingIns) {
        while (pos < toks.size()) {
            Tok t = peek();
            if (t.kind == ID && (t.text.equals("main")
                || (t.text.equals("void") && peekAt(1).text.equals("main")))) {
                // main() reached (bare or after void); body handled by caller.
                // For "void main" the void token is consumed here too.
                if (t.text.equals("void")) {
                    next();
                }
                return;
            }
            if (readInterfaceQualifier(t.text)) { next(); continue; }
            if (t.kind == ID && t.text.equals("attribute")) {
                if (!vertexMode) throw new TranslateException("attribute outside vertex stage");
                next();
                parseUniformOrVarying(cls, varyingIns, "in");
                continue;
            }
            if (t.kind == ID && t.text.equals("layout")) {
                if(computeMode){
                    next();expect("(");java.util.Set<String> axes=new java.util.HashSet<>();
                    do{String axis=expectIdent();expect("=");int size=fixedArrayLength();
                        int index=switch(axis){case "local_size_x"->0;case "local_size_y"->1;case "local_size_z"->2;default->throw new TranslateException("unsupported compute layout "+axis);};
                        if(!axes.add(axis))throw new TranslateException("duplicate compute layout axis");localSize[index]=size;
                    }while(accept(","));expect(")");expect("in");expect(";");
                    if((long)localSize[0]*localSize[1]*localSize[2]>1024)throw new TranslateException("compute local size >1024");continue;
                }
                // layout(location = N) out vec4 NAME;
                next();
                expect("(");
                int loc = -1;
                while (!accept(")")) {
                    Tok k = next();
                    if (k.kind == NUM) {
                        loc = Integer.parseInt(k.text.replace("f", ""));
                    }
                }
                interfaceLocation = loc;
                while (readInterfaceQualifier(peek().text)) next();
                String kind = next().text;
                if (vertexMode || kind.equals("in")) {
                    if (!kind.equals("in") && !kind.equals("out")) throw new TranslateException("unsupported stage layout " + kind);
                    parseUniformOrVarying(cls, varyingIns, kind);
                    continue;
                }
                if (!kind.equals("out") || loc < 0) throw new TranslateException("unsupported fragment output layout");
                expect("vec4");
                String name = expectIdent();
                expect(";");
                globals.put(name, new Var("out[" + loc + "]", T_VEC4, true));
                resetInterfaceQualifiers();
                if (loc + 1 > outSlotCount) {
                    outSlotCount = loc + 1;
                }
                continue;
            }
            if (t.kind == ID && t.text.equals("uniform")) {
                next();
                parseUniformOrVarying(cls, varyingIns, "uniform");
                continue;
            }
            if (t.kind == ID && t.text.equals("in")) {
                next();
                parseUniformOrVarying(cls, varyingIns, "in");
                continue;
            }
            if (t.kind == ID && t.text.equals("out")) {
                // non-layout out (corpus: none in fragment stage with layout);
                next();
                parseUniformOrVarying(cls, varyingIns, "out");
                continue;
            }
            if (t.kind == ID && t.text.equals("varying")) {
                // legacy qualifier: fragment "in" / vertex "out"
                next();
                parseUniformOrVarying(cls, varyingIns, vertexMode ? "varying" : "in");
                continue;
            }
            if (t.kind == ID && t.text.equals("const")) {
                parseConst(cls);
                continue;
            }
            if (t.kind == ID && t.text.equals("struct")) {
                next(); // struct
                String typeName = expectIdent();
                structTypes.add(typeName);
                if (accept("{")) {
                    int d = 1;
                    while (d > 0 && pos < toks.size()) {
                        String s = next().text;
                        if (s.equals("{")) {
                            d++;
                        } else if (s.equals("}")) {
                            d--;
                        }
                    }
                }
                accept(";");
                continue;
            }
            if (t.kind == ID && (typeOfName(t.text) >= 0 || t.text.equals("void"))
                && peekAt(1).kind == ID && peekAt(2).text.equals("(")
                && !peekAt(1).text.equals("main")) {
                parseUserFunction();
                continue;
            }
            if (t.kind == ID && (typeOfName(t.text) >= 0 || isStructTypeName(t.text))
                && typeOfName(t.text) != T_VOID
                && (peekAt(1).kind == ID || peekAt(1).text.equals("[")) && !peekAt(1).text.equals("main")) {
                // global variable with optional initializer (struct instances
                // like `iris_FogParameters iris_Fog = ...` are dead in the
                // corpus: declared, initialized, never read)
                int type = typeOfName(t.text);
                if (type < 0) {
                    next();
                    String name = expectIdent();
                    globals.put(name, new Var("dead_" + mangle(name), T_FLOAT, true));
                    cls.append("    public static final float dead_").append(mangle(name)).append(" = 0.0f;\n");
                    if (accept("=")) skipToSemicolon();
                    expect(";");
                } else {
                    next();
                    parseGlobalVariables(cls, type, false);
                }
                continue;
            }
            throw new TranslateException("unsupported header construct at '" + t.text + "'");
        }
    }

    /** struct-typed names consumed at header level (dead instances). */
    private final java.util.Set<String> structTypes = new java.util.HashSet<>();

    private boolean isStructTypeName(String name) {
        return structTypes.contains(name);
    }

    private static String interfaceTypeName(int type) {
        return switch(type) {
            case T_FLOAT -> "float"; case T_INT -> "int"; case T_BOOL -> "bool";case T_UINT->"uint";case T_UVEC2->"uvec2";case T_UVEC3->"uvec3";case T_UVEC4->"uvec4";
            case T_VEC2 -> "vec2"; case T_VEC3 -> "vec3"; case T_VEC4 -> "vec4";
            case T_IVEC2 -> "ivec2"; case T_IVEC3 -> "ivec3"; case T_IVEC4 -> "ivec4";
            case T_BVEC2 -> "bvec2"; case T_BVEC3 -> "bvec3"; case T_BVEC4 -> "bvec4";
            case T_MAT2 -> "mat2"; case T_MAT3 -> "mat3"; case T_MAT4 -> "mat4";
            default -> throw new TranslateException("Unsupported varying GLSL type");
        };
    }
    private void resetInterfaceQualifiers() {
        interpolation = ShaderVaryings.Interpolation.SMOOTH; centroid = sampleQualifier = false; interfaceLocation = -1;imageAccessQualifier="readwrite";
    }
    private boolean readInterfaceQualifier(String text) {
        switch (text) {
            case "writeonly", "readonly" -> imageAccessQualifier=text;
            case "flat" -> interpolation = ShaderVaryings.Interpolation.FLAT;
            case "smooth" -> interpolation = ShaderVaryings.Interpolation.SMOOTH;
            case "noperspective" -> interpolation = ShaderVaryings.Interpolation.NOPERSPECTIVE;
            case "centroid" -> centroid = true;
            case "sample" -> sampleQualifier = true;
            default -> { return false; }
        }
        return true;
    }
    private void parseUniformOrVarying(StringBuilder cls, List<String> varyingIns, String kind) {
        while (readInterfaceQualifier(peek().text)) next();
        int type = parseType();
        do {
            parseInterfaceVariable(cls, varyingIns, kind, type);
        } while (accept(","));
        expect(";");
        resetInterfaceQualifiers();
    }

    private void parseInterfaceVariable(StringBuilder cls, List<String> varyingIns, String kind, int type) {
        String name = expectIdent();
        if (kind.equals("uniform")) {
            if(isImage(type)){
                int unit=imageCount++;imageUnits.put(name,unit);imageTypes.put(name,type==T_IMAGE3D?"image3D":"uimage3D");imageAccess.put(name,imageAccessQualifier);
                globals.put(name,new Var("i$"+unit,type,true));cls.append("    public int i$").append(unit).append(";\n");return;
            }
            if(!imageAccessQualifier.equals("readwrite"))throw new TranslateException("image access qualifier on non-image");
            if (isSampler(type)) {
                int unit = samplerCount++;
                samplerUnits.put(name, unit); samplerTypes.put(name,samplerTypeName(type));
                globals.put(name, new Var("s$" + unit, type, true));
                cls.append("    public int s$").append(unit).append("; // sampler '").append(name).append("'\n");
            } else {
                String java = mangle(name);
                globals.put(name, new Var(java, type, true, name));
                uniformFields.put(name, java);
                uniformTypes.put(name, typeSignature(type));
                // Vector/matrix uniforms default to zero-filled arrays so a
                // uniform Iris never uploads (frame-1 execution, optional
                // pack uniforms) reads as zeros instead of NPE-ing inside
                // the generated code. The initializer must match the field's
                // Java type: ivec/bvec fields are int[]/boolean[] arrays.
                String elem = TYPE_NAME[type].endsWith("[]") ? TYPE_NAME[type].substring(0, TYPE_NAME[type].length() - 2) : null;
                String init = elem != null
                        ? " = new " + elem + "[" + (isMat(type) ? vecSize(type) * vecSize(type) : vecSize(type)) + "]"
                        : "";
                cls.append("    public ").append(TYPE_NAME[type]).append(' ').append(java).append(init).append(";\n");
            }
            return;
        }
        if (vertexMode) {
            // Vertex inputs retain public fields for fullscreen compatibility;
            // explicit VertexState providers refresh and validate them per invocation.
            // Output names still match fragment fields for the existing reflection bridge.
            String java = mangle(name);
            String elem = TYPE_NAME[type].endsWith("[]") ? TYPE_NAME[type].substring(0, TYPE_NAME[type].length() - 2) : null;
            String init = elem != null
                    ? " = new " + elem + "[" + (isMat(type) ? vecSize(type) * vecSize(type) : vecSize(type)) + "]"
                    : "";
            globals.put(name, new Var(java, type, true));
            cls.append("    public ").append(TYPE_NAME[type]).append(' ').append(java).append(init).append(";\n");
            if (kind.equals("in")) {
                if (isMat(type) || isSampler(type) || type == T_VOID) {
                    throw new TranslateException("unsupported vertex input type for '" + name + "'");
                }
                GlslProgram.VertexInputType inputType = GlslProgram.VertexInputType.valueOf(
                    switch (type) {
                        case T_FLOAT -> "FLOAT"; case T_INT -> "INT"; case T_BOOL -> "BOOL";
                        case T_VEC2 -> "VEC2"; case T_VEC3 -> "VEC3"; case T_VEC4 -> "VEC4";
                        case T_IVEC2 -> "IVEC2"; case T_IVEC3 -> "IVEC3"; case T_IVEC4 -> "IVEC4";
                        case T_BVEC2 -> "BVEC2"; case T_BVEC3 -> "BVEC3"; case T_BVEC4 -> "BVEC4";
                        default -> throw new TranslateException("unsupported vertex input '" + name + "'");
                    });
                vertexInputs.add(new GlslProgram.VertexInput(name, java, inputType));
            } else {
                vertexOutputs.put(name, typeSignature(type));
                varyingOutputs.put(name, new ShaderVaryings.Variable(name, java, typeSignature(type), interfaceTypeName(type), interpolation, interfaceLocation, centroid, sampleQualifier));
            }
            return;
        }
        // varying "in": executor writes per-fragment via generated field
        String java = mangle(name);
        globals.put(name, new Var(java, type, true));
        String init = " = " + defaultInit(type);
        cls.append("    public ").append(TYPE_NAME[type]).append(' ').append(java).append(init).append(";\n");
        varyingIns.add(name);
        varyingInputs.put(name, new ShaderVaryings.Variable(name, java, typeSignature(type), interfaceTypeName(type), interpolation, interfaceLocation, centroid, sampleQualifier));
    }

    private void parseConst(StringBuilder cls) {
        next(); // const
        parseGlobalVariables(cls, parseType(), true);
    }

    /** Preserve declared globals, including fixed scalar/vector arrays; no guessed zero substitute. */
    private void parseGlobalVariables(StringBuilder cls, int elementType, boolean constant) {
        int prefixLength=-2;
        if(accept("[")){prefixLength=accept("]")?-1:fixedArrayLength();if(prefixLength>=0)expect("]");}
        do {
            String name = expectIdent(); String java = mangle(name);
            int len=prefixLength, type=elementType;
            if(accept("[")){
                if(prefixLength!=-2)throw new TranslateException("multidimensional global arrays unsupported");
                len=accept("]")?-1:fixedArrayLength();if(len>=0)expect("]");
            }
            if(len!=-2)type=arrayOf(elementType);
            globals.put(name,new Var(java,type,true));
            String init=isArray(type)?(len>=0?newArray(elementType,len):null):defaultInit(type);
            if(accept("="))init=isArray(type)?parseArrayInitializer(elementType,len):emit(parseExprNode(),type);
            else if(constant||init==null)throw new TranslateException("global/unsized array requires initializer: "+name);
            cls.append("    public ").append(constant?"static final ":"").append(TYPE_NAME[type]).append(' ').append(java);
            if(constant)cls.append(" = ").append(init);
            cls.append(";\n");
            if(!constant)globalInitializers.append("        ").append(java).append(" = ").append(init).append(";\n");
        } while(accept(","));
        expect(";");
    }

    private int fixedArrayLength() {
        Tok size = next();
        if (size.kind != NUM || size.text.endsWith("f")) throw new TranslateException("array length must be a positive integer literal");
        int len = Integer.parseInt(size.text);
        if (len <= 0 || len > 65536) throw new TranslateException("invalid fixed array length " + len);
        return len;
    }

    private String newArray(int elementType, int len) {
        if (isVec(elementType)) return "new " + TYPE_NAME[baseOf(elementType)] + "[" + len + "][" + vecSize(elementType) + "]";
        return "new " + TYPE_NAME[elementType] + "[" + len + "]";
    }

    private String parseArrayInitializer(int elementType, int len) {
        Tok ctor = next();
        if (typeOfName(ctor.text) != elementType) throw new TranslateException("array constructor element type mismatch");
        expect("[");
        int ctorLen=accept("]")?-1:fixedArrayLength();
        if(ctorLen>=0)expect("]"); expect("(");
        List<Expr> args = parseArgs();
        if(args.isEmpty()||args.size()>65536||(len>=0&&args.size()!=len)||(ctorLen>=0&&args.size()!=ctorLen))
            throw new TranslateException("array constructor length mismatch");
        List<String> values = new ArrayList<>();
        for (Expr arg : args) values.add(emit(arg, elementType));
        return "new " + TYPE_NAME[arrayOf(elementType)] + "{" + String.join(", ", values) + "}";
    }

    private String typeNameOfConstArray(int elemType) {
        return switch (elemType) {
            case T_FLOAT -> "float";
            case T_INT -> "int";
            case T_BOOL -> "boolean";
            default -> throw new TranslateException("const array of unsupported element type");
        };
    }

    // ---- user functions -----------------------------------------------------

    private void parseUserFunction() {
        int retType = parseType();
        String name = expectIdent();
        expect("(");
        UserFn fn = new UserFn("fn$" + name + "$" + userFns.size(), retType);
        while (!accept(")")) {
            accept("const");
            String qualifier = "in";
            if (peek().text.equals("in") || peek().text.equals("out") || peek().text.equals("inout")) qualifier = next().text;
            accept("const");
            fn.qualifiers.add(qualifier);
            int pt = parseType();
            String pn = expectIdent();
            fn.paramNames.add(pn);
            fn.params.add(new Var(mangle(pn), pt, false));
            accept(",");
        }
        expect("{");
        // capture the body tokens (balanced)
        int depth = 1;
        while (depth > 0) {
            Tok t = next();
            if (t.text.equals("{")) {
                depth++;
            } else if (t.text.equals("}")) {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            fn.bodyToks.add(t);
        }
        // params stored for emitUserFunction; body translation happens there.
        // Overloaded GLSL functions store under name#(v|s per param).
        StringBuilder sig = new StringBuilder();
        for (Var p : fn.params) {
            sig.append(p.type).append('_');
        }
        // store ONLY under the signature key; bare-name lookups resolve via
        // the #sig search in parseCall
        userFns.put(name + "#" + sig, fn);
    }

    private void emitUserFunction(StringBuilder cls, UserFn fn) {
        cls.append("\n    private ").append(TYPE_NAME[fn.retType]).append(' ').append(fn.javaName).append("(FragState st");
        for (int i = 0; i < fn.params.size(); i++) {
            Var p = fn.params.get(i);
            boolean ref = !fn.qualifiers.get(i).equals("in");
            cls.append(", ").append(ref && !isVec(p.type) ? TYPE_NAME[p.type] + "[]" : TYPE_NAME[p.type])
                .append(' ').append(ref ? "__ref_" + p.javaName : p.javaName);
        }
        cls.append(") {\n");
        boolean hasRefs = false;
        for (int i = 0; i < fn.params.size(); i++) {
            if (fn.qualifiers.get(i).equals("in")) continue;
            hasRefs = true;
            Var p = fn.params.get(i);
            cls.append("        ").append(TYPE_NAME[p.type]).append(' ').append(p.javaName).append(" = ");
            if (fn.qualifiers.get(i).equals("out")) cls.append(defaultInit(p.type));
            else cls.append("__ref_").append(p.javaName).append(isVec(p.type) ? ".clone()" : "[0]");
            cls.append(";\n");
        }
        if (hasRefs) cls.append("        try {\n");
        GlslCompiler sub = fork();
        sub.toks.clear(); sub.toks.addAll(fn.bodyToks); sub.pos = 0;
        sub.userFns.putAll(this.userFns); sub.inUserFn = true; sub.sawGlFragCoord = this.sawGlFragCoord;
        for (int i = 0; i < fn.params.size(); i++) sub.locals.put(fn.paramNames.get(i), fn.params.get(i));
        sub.parseStatements(cls, hasRefs ? "            " : "        ");
        fn.referencedUniforms.addAll(sub.referencedUniforms); fn.calledFunctions.addAll(sub.calledFunctions);
        fn.referencedVaryings.addAll(sub.referencedVaryings);
        fn.derivativeInputs.addAll(sub.derivativeInputs);fn.usedSamplers.addAll(sub.usedSamplers);fn.usedImages.addAll(sub.usedImages);
        mergeFork(sub);
        if (!sub.endedWithReturn) cls.append("        return").append(fn.retType == T_VOID ? "" : " " + defaultInit(fn.retType)).append(";\n");
        if (hasRefs) {
            cls.append("        } finally {\n");
            for (int i = 0; i < fn.params.size(); i++) {
                if (fn.qualifiers.get(i).equals("in")) continue;
                Var p = fn.params.get(i);
                if (isVec(p.type)) cls.append("            System.arraycopy(").append(p.javaName)
                    .append(", 0, __ref_").append(p.javaName).append(", 0, ").append(vecSize(p.type)).append(");\n");
                else cls.append("            __ref_").append(p.javaName).append("[0] = ").append(p.javaName).append(";\n");
            }
            cls.append("        }\n");
        }
        cls.append("    }\n"); endedWithReturn = false;
    }

    /** Fork sharing globals/userFns, with fresh locals and token cursor. */
    private GlslCompiler fork() {
        GlslCompiler s = new GlslCompiler();
        s.globals = this.globals;
        s.varyingInputs.putAll(this.varyingInputs);
        s.userFns.putAll(this.userFns);
        s.samplerUnits.putAll(this.samplerUnits);
        s.samplerTypes.putAll(this.samplerTypes);s.imageUnits.putAll(imageUnits);s.imageTypes.putAll(imageTypes);s.imageAccess.putAll(imageAccess);s.imageCount=imageCount;s.computeMode=computeMode;
        s.samplerCount = this.samplerCount;
        s.outSlotCount = this.outSlotCount;
        s.indentUnits = this.indentUnits;
        s.sawGlFragCoord = this.sawGlFragCoord;
        s.inUserFn = this.inUserFn;
        s.vertexMode = this.vertexMode;
        s.structTypes.addAll(this.structTypes);
        // Each fork records helper-local resource dependencies. emitUserFunction
        // saves them on UserFn; Result publishes only the reached call closure.
        return s;
    }

    /** Merge compiler bookkeeping only; helper resources are retained by UserFn and its reachable closure. */
    private void mergeFork(GlslCompiler sub) {
        samplerCount = Math.max(samplerCount, sub.samplerCount);imageCount=Math.max(imageCount,sub.imageCount);
        sawGlFragCoord |= sub.sawGlFragCoord;
    }

    /**
     * GLSL lets user functions read variables declared in main() (single
     * translation-unit scoping). Java does not, so those locals become private
     * instance fields; main keeps using them through the same names. The set
     * of referenced identifiers is computed BEFORE main is parsed (see
     * translate()), and shouldHoist() redirects their declarations.
     */
    private final java.util.Set<String> hoistedNames = new java.util.HashSet<>();

    /** name -> field var for hoisted main-locals (emitted at class end). */
    private final Map<String, Var> hoistedFields = new LinkedHashMap<>();

    private boolean shouldHoist(String glslName) {
        // A local shadow of a global must stay local. Otherwise a helper that
        // reads that global is rebound to main's local (and Java gets duplicate fields).
        return hoistedNames.contains(glslName) && !globals.containsKey(glslName);
    }

    /** Locals declared in main (name -> var), populated during main parse. */
    private final Map<String, Var> mainLocals = new LinkedHashMap<>();

    // ---- main body --------------------------------------------------------

    private void parseMainBody(StringBuilder cls) {
        accept("void"); // header already consumed 'void' for void main
        expect("main");
        expect("(");
        expect(")");
        expect("{");
        mainLocals.clear();
        parseStatements(cls, "        ");
    }

    private void parseStatements(StringBuilder out, String indent) {
        while (true) {
            Tok t = peek();
            if (t.text.equals("}")) {
                next();
                return;
            }
            if (pos >= toks.size()) {
                return;
            }
            parseStatement(out, indent);
        }
    }

    private void parseStatement(StringBuilder out, String indent) {
        Tok t = peek();
        if (t.text.equals(";")) {
            next(); // empty statement
            return;
        }
        switch (t.text) {
            case "{" -> {
                next();
                out.append(indent).append("{\n");
                Map<String, Var> saved = new HashMap<>(locals);
                parseStatements(out, indent + "    ");
                out.append(indent).append("}\n");
                locals.clear();
                locals.putAll(saved);
            }
            case "if" -> parseIf(out, indent);
            case "for" -> parseFor(out, indent);
            case "return" -> {
                next();
                endedWithReturn = true;
                if (!accept(";")) {
                    Expr e = parseExprNode();
                    expect(";");
                    out.append(indent).append("return ").append(emit(e, -1)).append(";\n");
                } else {
                    out.append(indent).append("return;\n");
                }
            }
            case "break" -> {
                next();
                expect(";");
                out.append(indent).append("break;\n");
            }
            case "continue" -> {
                next();
                expect(";");
                out.append(indent).append("continue;\n");
            }
            case "discard" -> {
                next();
                expect(";");
                out.append(indent).append("throw new dev.antimony.gl.glsl.DiscardException();\n");
            }
            default -> {
                if (t.kind == ID && (typeOfName(t.text) >= 0 || t.text.equals("const"))) {
                    parseLocalDecl(out, indent);
                    return;
                }
                if (t.kind == ID && isAssignmentAhead()) {
                    parseAssignmentStatement(out, indent, ";");
                    return;
                }
                // expression statement (calls)
                Expr e = parseExprNode();
                expect(";");
                if (e.refPrelude != null) for (String line : e.refPrelude) out.append(indent).append(line).append(";\n");
                String statement = e.java;
                if (!statement.isEmpty()) out.append(indent).append(statement).append(";\n");
                if (e.refPostlude != null) for (String line : e.refPostlude) out.append(indent).append(line).append(";\n");
            }
        }
    }

    /**
     * Lookahead: from the current identifier, does a path (.x / [i]) followed
     * by an assignment op (=, +=, ...) appear before ';' or another operator?
     */
    private boolean isAssignmentAhead() {
        int i = pos;
        // identifier
        if (toks.get(i).kind != ID) {
            return false;
        }
        i++;
        while (i < toks.size()) {
            String t = toks.get(i).text;
            if (t.equals(".") ) {
                i += 2; // '.' + ident
            } else if (t.equals("[")) {
                int d = 1;
                i++;
                while (i < toks.size() && d > 0) {
                    String s = toks.get(i).text;
                    if (s.equals("[")) {
                        d++;
                    } else if (s.equals("]")) {
                        d--;
                    } else if (s.equals(";")) {
                        return false;
                    }
                    i++;
                }
            } else if (t.equals("=") || t.equals("+=") || t.equals("-=") || t.equals("*=")
                || t.equals("/=") || t.equals("%=")) {
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

    private void parseLocalDecl(StringBuilder out, String indent) {
        boolean isConst = accept("const");
        int elementType = parseType();
        int prefixLength = -2;
        if (accept("[")) { prefixLength=accept("]")?-1:fixedArrayLength(); if(prefixLength>=0)expect("]"); }
        while (true) {
            int type = elementType;
            String name = expectIdent();
            String java = mangle(name) + "$local" + assignTemp++;
            int length=prefixLength;
            if (accept("[")) {
                if(prefixLength!=-2)throw new TranslateException("multidimensional local arrays unsupported");
                length=accept("]")?-1:fixedArrayLength();if(length>=0)expect("]");
            }
            if(length!=-2) {
                type=arrayOf(elementType);
                String initializer;
                if(accept("=")) initializer=parseArrayInitializer(elementType,length);
                else if(length>=0&&!isConst) initializer=newArray(elementType,length);
                else throw new TranslateException("const/unsized local array requires initializer: "+name);
                locals.put(name,new Var(java,type,false));
                out.append(indent).append(TYPE_NAME[type]).append(' ').append(java).append(" = ").append(initializer).append(";\n");
                if(!accept(",")){expect(";");return;}
                continue;
            }
            locals.put(name, new Var(java, type, false));
            mainLocals.put(name, locals.get(name));
            if (shouldHoist(name)) {
                // lives as an instance field shared with user functions
                hoistedFields.put(name, locals.get(name));
                globals.put(name, locals.get(name));
                locals.remove(name);
                String initH = defaultInit(type);
                List<String> copyOut = List.of();
                if (accept("=")) {
                    Expr e = parseExprNode();
                    copyOut = beginRefCall(e, out, indent);
                    initH = emit(e, type);
                }
                out.append(indent).append(java).append(" = ").append(initH).append(";\n");
                for (String line : copyOut) out.append(indent).append(line).append(";\n");
                if (!accept(",")) {
                    expect(";");
                    return;
                }
                continue;
            }
            String init = defaultInit(type);
            List<String> copyOut = List.of();
            if (accept("=")) {
                Expr e = parseExprNode();
                copyOut = beginRefCall(e, out, indent);
                // scalar local from vector-valued expression: take component
                // appropriate (corpus: float x = vecExpr.x is rare; first comp)
                if (!isVec(type) && !isMat(type) && e.isVec) {
                    init = emitScalar(e.comps.get(0));
                } else if (!isVec(type) && !isMat(type) && !e.isVec && isArrayExpr(e)) {
                    init = "(" + e.java + ")[0]";
                } else {
                    init = emit(e, type);
                }
            }
            out.append(indent).append(TYPE_NAME[type]).append(' ').append(java)
                .append(" = ").append(init).append(";\n");
            for (String line : copyOut) out.append(indent).append(line).append(";\n");
            if (!accept(",")) {
                expect(";");
                return;
            }
        }
    }

    private String defaultInit(int type) {
        return switch (type) {
            case T_FLOAT -> "0.0f";
            case T_INT,T_UINT,T_IMAGE3D,T_UIMAGE3D -> "0";
            case T_BOOL -> "false";
            case T_MAT2 -> "new float[4]"; case T_MAT3 -> "new float[9]"; case T_MAT4 -> "new float[16]";
            default -> "new " + TYPE_NAME[type] + "{" + zeros(type) + "}";
        };
    }

    private String zeros(int type) {
        int n = vecSize(type);
        if (isIntVec(type)||isUintVec(type)) {
            return String.join(", ",java.util.Collections.nCopies(n,"0"));
        }
        if (isBoolVec(type)) {
            return "false".repeat(1); // rare; corpus has none
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("0.0f");
        }
        return sb.toString();
    }

    private void parseIf(StringBuilder out, String indent) {
        next(); // if
        expect("(");
        Expr cond = parseExprNode();
        expect(")");
        String c = emitScalar(cond);
        out.append(indent).append("if (").append(c).append(") {\n");
        Map<String, Var> saved = new HashMap<>(locals);
        parseStatementBody(out, indent + "    ");
        out.append(indent).append("}");
        locals.clear();
        locals.putAll(saved);
        if (accept("else")) {
            Tok t = peek();
            if (t.text.equals("if")) {
                out.append(" else ");
                parseIf(out, indent);
            } else {
                out.append(" else {\n");
                Map<String, Var> saved2 = new HashMap<>(locals);
                parseStatementBody(out, indent + "    ");
                out.append(indent).append("}");
                locals.clear();
                locals.putAll(saved2);
            }
        }
        out.append("\n");
    }

    /** Parses either a braced block or a single statement (GLSL allows both). */
    private void parseStatementBody(StringBuilder out, String indent) {
        if (peek().text.equals("{")) {
            next();
            Map<String, Var> saved = new HashMap<>(locals);
            parseStatements(out, indent);
            locals.clear();
            locals.putAll(saved);
        } else {
            parseStatement(out, indent);
        }
    }

    private void parseFor(StringBuilder out, String indent) {
        next(); // for
        expect("(");
        Map<String, Var> saved = new HashMap<>(locals);
        String init = "";
        if (!accept(";")) {
            int type = parseType();
            String name = expectIdent();
            String java = mangle(name);
            locals.put(name, new Var(java, type, false));
            expect("=");
            Expr e = parseExprNode();
            init = TYPE_NAME[type] + " " + java + " = " + emit(e, type);
            expect(";");
        } else {
            // already consumed ';'
        }
        String cond = peek().text.equals(";") ? "true" : emitScalar(parseExprNode());
        expect(";");
        List<String> updates = new ArrayList<>();
        if (!peek().text.equals(")")) do {
            if (isAssignmentAhead()) {
                String name = expectIdent();
                Var target = locals.containsKey(name) ? locals.get(name) : globals.get(name);
                String op = next().text;
                if (target == null || !(op.equals("=") || op.equals("+=") || op.equals("-=") || op.equals("*=") || op.equals("/=")))
                    throw new TranslateException("unsupported for-update assignment target");
                Expr rhs = parseExprNode();
                if (op.equals("=")) updates.add(target.javaName + " = " + emit(rhs, target.type));
                else if (isFloatVec(target.type)) updates.add(target.javaName + " = __vertexArithmetic(" + target.javaName + ", "
                    + (isFloatVec(rhs.type) ? emit(rhs, rhs.type) : "new float[]{" + castNum(rhs) + "}") + ", '" + op.charAt(0) + "')");
                else updates.add(target.javaName + " " + op + " " + emit(rhs, target.type));
            } else updates.add(emitStatement(parseExprNode()));
        } while (accept(","));
        String update = String.join(", ", updates);
        expect(")");
        // ONE brace pair total: if the body is braced, parseStatements below
        // consumes that '{' and emits it - emitting our own would double it.
        Map<String, Var> saved2 = new HashMap<>(locals);
        if (peek().text.equals("{")) {
            next();
            out.append(indent).append("for (").append(init).append("; ").append(cond).append("; ")
                .append(update).append(") {\n");
            parseStatements(out, indent + "    ");
            out.append(indent).append("}\n");
        } else {
            out.append(indent).append("for (").append(init).append("; ").append(cond).append("; ")
                .append(update).append(") {\n");
            parseStatement(out, indent + "    ");
            out.append(indent).append("}\n");
        }
        locals.clear();
        locals.putAll(saved2);
        locals.clear();
        locals.putAll(saved);
    }

    // ---- expressions -----------------------------------------------------------

    private static final class Expr {
        String java;
        int type;
        List<Expr> comps;
        boolean isVec;
        /** java text evaluates to an array even though modeled scalar
         *  (function-call results: st.sample, runtime helpers, fn$ calls). */
        boolean arrayValued;
        String samplingMethod,samplingArguments;
        /** lvalue target for assignments: java text of the storage slot. */
        String lvalue;
        Var symbol;
        List<String> refPrelude;
        List<String> refPostlude;

        static Expr scalar(String java, int type) {
            Expr e = new Expr();
            e.java = java;
            e.type = type;
            return e;
        }

        static Expr vector(List<Expr> comps, int type) {
            Expr e = new Expr();
            e.comps = comps;
            e.type = type;
            e.isVec = true;
            return e;
        }
    }

    private Expr parseExprNode() {
        return parseTernary();
    }

    private Expr parseTernary() {
        Expr cond = parseOr();
        if (accept("?")) {
            Expr a = parseTernary();
            expect(":");
            Expr b = parseTernary();
            int rt = a.type;
            if(isVec(a.type)&&isVec(b.type)&&a.type==b.type){
                Expr r=Expr.scalar("("+emitScalar(cond)+" ? "+emit(a,a.type)+" : "+emit(b,b.type)+")",a.type);r.arrayValued=true;return r;
            }
            if (a.isVec || b.isVec) {
                int n = Math.max(a.isVec ? a.comps.size() : 1, b.isVec ? b.comps.size() : 1);
                List<Expr> comps = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    Expr ca = a.isVec ? a.comps.get(Math.min(i, a.comps.size() - 1)) : a;
                    Expr cb = b.isVec ? b.comps.get(Math.min(i, b.comps.size() - 1)) : b;
                    comps.add(Expr.scalar("(" + emitScalar(cond) + " ? " + castNum(ca) + " : " + castNum(cb) + ")", T_FLOAT));
                }
                return Expr.vector(comps, floatVecOf(n));
            }
            return Expr.scalar("(" + emitScalar(cond) + " ? " + a.java + " : " + b.java + ")", rt);
        }
        return cond;
    }

    private Expr parseOr() {
        Expr left = parseAnd();
        while (accept("||")) {
            Expr right = parseAnd();
            left = Expr.scalar("(" + emitScalar(left) + " || " + emitScalar(right) + ")", T_BOOL);
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseBitOr();
        while (accept("&&")) {
            Expr right = parseBitOr();
            left = Expr.scalar("(" + emitScalar(left) + " && " + emitScalar(right) + ")", T_BOOL);
        }
        return left;
    }

    private Expr bits(Expr a,Expr b,String op){
        if(!integerType(a.type)||!integerType(b.type)||isVec(a.type)||isVec(b.type))throw new TranslateException("bitwise requires scalar integer operands");
        boolean unsigned=a.type==T_UINT||b.type==T_UINT;
        if(op.equals("<<")||op.equals(">>"))return Expr.scalar("GlslIntegerRuntime.shift("+emitScalar(a)+", "+emitScalar(b)+", "+op.equals(">>")+", "+(a.type==T_UINT)+")",a.type);
        return Expr.scalar("("+emitScalar(a)+" "+op+" "+emitScalar(b)+")",unsigned?T_UINT:T_INT);
    }
    private Expr parseBitOr(){Expr a=parseBitXor();while(accept("|"))a=bits(a,parseBitXor(),"|");return a;}
    private Expr parseBitXor(){Expr a=parseBitAnd();while(accept("^"))a=bits(a,parseBitAnd(),"^");return a;}
    private Expr parseBitAnd(){Expr a=parseEquality();while(accept("&"))a=bits(a,parseEquality(),"&");return a;}
    private Expr parseShift(){Expr a=parseAdditive();while(peek().text.equals("<<")||peek().text.equals(">>")){String op=next().text;a=bits(a,parseAdditive(),op);}return a;}
    private Expr parseEquality() {
        Expr left = parseRelational();
        while (true) {
            String op = peek().text;
            if (op.equals("==") || op.equals("!=")) {
                next();
                Expr right = parseRelational();
                if(isVec(left.type)&&isVec(right.type)&&left.type==right.type){
                    if(vecSize(left.type)!=vecSize(right.type))throw new TranslateException("vector equality arity");
                    left=Expr.scalar("("+(op.equals("!=")?"!":"")+"GlslIntegerRuntime.equal("+emit(left,left.type)+", "+emit(right,right.type)+"))",T_BOOL);
                } else if (left.isVec && right.isVec) {
                    // vec == vec / vec != vec: fold to a single boolean via
                    // componentwise compare (corpus: clamp(...) != texcoordPast)
                    String neq = op.equals("!=") ? "true" : "false";
                    StringBuilder sb = new StringBuilder("(");
                    for (int i = 0; i < left.comps.size(); i++) {
                        if (i > 0) {
                            sb.append(neq.equals("true") ? " || " : " && ");
                        }
                        sb.append("(").append(emitScalar(left.comps.get(i))).append(" ")
                            .append(op).append(" ").append(emitScalar(right.comps.get(i))).append(")");
                    }
                    sb.append(")");
                    left = Expr.scalar(sb.toString(), T_BOOL);
                } else {
                    if(left.type==T_UINT&&right.type==T_FLOAT||left.type==T_FLOAT&&right.type==T_UINT)left=Expr.scalar("("+castNum(left)+" "+op+" "+castNum(right)+")",T_BOOL);
                    else left = Expr.scalar("(" + emitScalar(left) + " " + op + " " + emitScalar(right) + ")", T_BOOL);
                }
            } else {
                return left;
            }
        }
    }

    private Expr parseRelational() {
        Expr left = parseShift();
        while (true) {
            String op = peek().text;
            if (op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=")) {
                next();
                Expr right = parseShift();
                if (left.isVec && right.isVec) {
                    // GLSL lessThan/greaterThan via componentwise compare is
                    // invalid on vectors in GLSL; corpus doesn't use it.
                    throw new TranslateException("relational operator on vectors");
                }
                if(integerType(left.type)&&integerType(right.type)&&(left.type==T_UINT||right.type==T_UINT))
                    left=Expr.scalar("(Integer.compareUnsigned("+emitScalar(left)+", "+emitScalar(right)+") "+op+" 0)",T_BOOL);
                else if(left.type==T_UINT||right.type==T_UINT)left=Expr.scalar("("+castNum(left)+" "+op+" "+castNum(right)+")",T_BOOL);
                else left = Expr.scalar("(" + emitScalar(left) + " " + op + " " + emitScalar(right) + ")", T_BOOL);
            } else {
                return left;
            }
        }
    }

    private Expr parseAdditive() {
        Expr left = parseMultiplicative();
        while (true) {
            String op = peek().text;
            if (op.equals("+") || op.equals("-")) {
                next();
                Expr right = parseMultiplicative();
                left = arithmetic(left, right, op);
            } else {
                return left;
            }
        }
    }

    private Expr parseMultiplicative() {
        Expr left = parseUnary();
        while (true) {
            String op = peek().text;
            if (op.equals("*") || op.equals("/") || op.equals("%")) {
                next();
                Expr right = parseUnary();
                left = arithmetic(left, right, op);
            } else {
                return left;
            }
        }
    }

    /**
     * B1: user-function calls returning vectors are modeled as scalars with
     * arrayValued=true (their java text evaluates to float[]); expand them
     * into component exprs so vector arithmetic keeps every component
     * (corpus: dayBlend(...) * 1.2f, mix(dayBlend(...), vec3, x) - without
     * this only component 0 survived, silently or as a javac error).
     */
    private Expr vectorize(Expr e) {
        if (!e.isVec && isArrayExpr(e) && isFloatVec(e.type)) {
            int n = vecSize(e.type);
            List<Expr> comps = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                comps.add(Expr.scalar("(" + e.java + ")[" + i + "]", T_FLOAT));
            }
            return Expr.vector(comps, e.type);
        }
        return e;
    }

    private String floatArray(Expr value){
        if(isFloatVec(value.type))return emit(value,value.type);
        if(isIntVec(value.type)||isUintVec(value.type))return "GlslIntegerRuntime.toFloats("+emit(value,value.type)+", "+vecSize(value.type)+", "+isUintVec(value.type)+")";
        return "new float[]{"+castNum(value)+"}";
    }
    private Expr arithmetic(Expr a, Expr b, String op) {
        if (a.refPrelude != null || b.refPrelude != null) throw new TranslateException("scalar out/inout nested arithmetic requires statement sequencing");
        if(integerType(a.type)&&integerType(b.type)){
            boolean unsigned=baseOf(a.type)==T_UINT||baseOf(b.type)==T_UINT;
            if(isVec(a.type)||isVec(b.type)){
                int n=Math.max(vecSize(a.type),vecSize(b.type));if(isVec(a.type)&&isVec(b.type)&&vecSize(a.type)!=vecSize(b.type))throw new TranslateException("integer vector arity mismatch");
                String aa=isVec(a.type)?emit(a,a.type):"new int[]{"+emitScalar(a)+"}",bb=isVec(b.type)?emit(b,b.type):"new int[]{"+emitScalar(b)+"}";
                Expr result=Expr.scalar("GlslIntegerRuntime.arithmetic("+aa+", "+bb+", '"+op+"', "+unsigned+")",integerVecOf(n,unsigned));result.arrayValued=true;return result;
            }
            return Expr.scalar("GlslIntegerRuntime.scalar("+emitScalar(a)+", "+emitScalar(b)+", '"+op+"', "+unsigned+")",unsigned?T_UINT:T_INT);
        }
        if((isUintVec(a.type)||isIntVec(a.type)||isUintVec(b.type)||isIntVec(b.type))
            &&(a.type==T_FLOAT||b.type==T_FLOAT||isFloatVec(a.type)||isFloatVec(b.type))&&!isMat(a.type)&&!isMat(b.type)){
            int n=Math.max(vecSize(a.type),vecSize(b.type));
            Expr r=Expr.scalar("__vertexArithmetic("+floatArray(a)+", "+floatArray(b)+", '"+op+"')",floatVecOf(n));r.arrayValued=true;return r;
        }
        // Keep array-producing vertex expressions intact: scalar expansion repeats user calls.
        if (!isMat(a.type) && !isMat(b.type)
            && (isFloatVec(a.type) || isFloatVec(b.type))
            && (isArrayExpr(a) || isArrayExpr(b))) {
            int resultType = isFloatVec(a.type) ? a.type : b.type;
            String left = isFloatVec(a.type) ? emit(a, a.type) : "new float[]{" + castNum(a) + "}";
            String right = isFloatVec(b.type) ? emit(b, b.type) : "new float[]{" + castNum(b) + "}";
            Expr result = Expr.scalar("__vertexArithmetic(" + left + ", " + right + ", '" + op + "')", resultType);
            result.arrayValued = true;
            return result;
        }
        // Matrix multiplication consumes the whole vector. Expanding a function/sampler
        // result first evaluates it once per component (four ftransform calls in Solas).
        // Preserve the original operand and matrix multiplication order.
        if (isMat(a.type) && isVec(b.type)) {
            Expr result=Expr.scalar("GlslRuntime.mulMatVec(" + a.java + ", " + emit(b, -1) + ")", b.type);
            result.arrayValued=true;return result;
        }
        if (isMat(a.type) && isMat(b.type)) {
            return Expr.scalar("GlslRuntime.mulMatMat(" + a.java + ", " + emit(b, -1) + ")", a.type);
        }
        if (isVec(a.type) && isMat(b.type)) {
            Expr result=Expr.scalar("GlslRuntime.mulVecMat(" + emit(a, -1) + ", " + b.java + ")", a.type);
            result.arrayValued=true;return result;
        }
        a = vectorize(a);
        b = vectorize(b);
        // mat + vec (translation column add: m[3].xyz + v) and friends: vec op
        // with an array-valued scalar operand
        if (a.isVec && !b.isVec && isArrayExpr(b)) {
            List<Expr> comps = new ArrayList<>();
            for (int i = 0; i < a.comps.size(); i++) {
                comps.add(Expr.scalar("(" + emitScalar(a.comps.get(i)) + " " + op + " (" + b.java + ")[" + i + "])",
                    T_FLOAT));
            }
            return Expr.vector(comps, a.type);
        }
        if (!a.isVec && b.isVec && isArrayExpr(a)) {
            List<Expr> comps = new ArrayList<>();
            for (int i = 0; i < b.comps.size(); i++) {
                comps.add(Expr.scalar("((" + a.java + ")[" + i + "] " + op + " " + emitScalar(b.comps.get(i)) + ")",
                    T_FLOAT));
            }
            return Expr.vector(comps, b.type);
        }
        if (!a.isVec && !b.isVec) {
            if (a.type == T_INT && b.type == T_INT) return Expr.scalar("(" + a.java + " " + op + " " + b.java + ")", T_INT);
            return Expr.scalar("(" + castNum(a) + " " + op + " " + castNum(b) + ")", T_FLOAT);
        }
        int n = Math.max(a.isVec ? a.comps.size() : vecSize(a.type), b.isVec ? b.comps.size() : vecSize(b.type));
        List<Expr> comps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Expr ca = a.isVec ? a.comps.get(Math.min(i, a.comps.size() - 1)) : a;
            Expr cb = b.isVec ? b.comps.get(Math.min(i, b.comps.size() - 1)) : b;
            String ja = castNum(ca);
            String jb = castNum(cb);
            comps.add(Expr.scalar("(" + ja + " " + op + " " + jb + ")", T_FLOAT));
        }
        return Expr.vector(comps, floatVecOf(n));
    }

    /** Record that a texture()/textureLod() call reads sampler {@code s$N}. */
    private void noteImageUse(String javaExpr){
        for(var entry:imageUnits.entrySet())if(javaExpr.equals("i$"+entry.getValue())){usedImages.add(entry.getKey());return;}
    }
    private void noteSamplerUse(String javaExpr) {
        int at = javaExpr.lastIndexOf("s$");
        if (at < 0) {
            return;
        }
        try {
            int unit = Integer.parseInt(javaExpr.substring(at + 2));
            for (Map.Entry<String, Integer> e : samplerUnits.entrySet()) {
                if (e.getValue() == unit) {
                    usedSamplers.add(e.getKey());
                    return;
                }
            }
        } catch (NumberFormatException ignored) {
            // non-literal sampler expression: caller still samples correctly
        }
    }

    private int floatVecOf(int n) {
        return switch (n) {
            case 2 -> T_VEC2;
            case 3 -> T_VEC3;
            default -> T_VEC4;
        };
    }

    private int boolVecOf(int n) {
        return switch (n) {
            case 2 -> T_BVEC2;
            case 3 -> T_BVEC3;
            default -> T_BVEC4;
        };
    }

    private Expr parseUnary() {
        String op = peek().text;
        if(op.equals("~")){next();Expr value=parseUnary();if(!integerType(value.type)||isVec(value.type))throw new TranslateException("bitwise not requires integer");return Expr.scalar("(~"+emitScalar(value)+")",value.type);}
        if (op.equals("-") || op.equals("!") || op.equals("+")) {
            next();
            Expr e = parseUnary();
            if (op.equals("!")) {
                return Expr.scalar("(!" + emitScalar(e) + ")", T_BOOL);
            }
            if (op.equals("+")) {
                return e;
            }
            if(!e.isVec&&isArrayExpr(e)&&(isIntVec(e.type)||isUintVec(e.type))){Expr r=Expr.scalar("GlslIntegerRuntime.arithmetic(new int[]{0}, "+emit(e,e.type)+", '-', "+isUintVec(e.type)+")",e.type);r.arrayValued=true;return r;}
            if(!e.isVec&&isArrayExpr(e)&&isFloatVec(e.type)) {
                Expr result=Expr.scalar("GlslRuntime.negate("+emit(e,e.type)+")",e.type);result.arrayValued=true;return result;
            }
            if (e.isVec) {
                List<Expr> comps = new ArrayList<>();
                for (Expr c : e.comps) {
                    comps.add(Expr.scalar("(-" + c.java + ")", baseOf(c.type)));
                }
                return Expr.vector(comps, e.type);
            }
            return Expr.scalar("(-" + e.java + ")", e.type);
        }
        if (op.equals("++") || op.equals("--")) {
            next();
            Expr e = parseUnary();
            return Expr.scalar(op + e.java, e.type);
        }
        return parsePostfix();
    }

    private Expr parsePostfix() {
        Expr e = parsePrimary();
        while (true) {
            String t = peek().text;
            if (t.equals(".")) {
                next();
                String swz = expectIdent();
                if(isArray(e.type)&&swz.equals("length")){expect("(");expect(")");e=Expr.scalar("("+e.java+").length",T_INT);}
                else e = swizzle(e, swz);
            } else if (t.equals("++") || t.equals("--")) {
                next();
                e = Expr.scalar(e.java + t, e.type);
            } else if (t.equals("[")) {
                next();
                Expr idx = parseExprNode();
                expect("]");
                if (isArray(e.type)) {
                    int element = elementOf(e.type);
                    e = Expr.scalar("(" + e.java + ")[" + emitScalar(idx) + "]", element);
                    e.arrayValued = isVec(element) || isMat(element);
                } else if (isMat(e.type)) {
                    int n = vecSize(e.type);
                    e = Expr.scalar("GlslRuntime.matCol(" + e.java + ", " + emitScalar(idx) + ", " + n + ")",
                        floatVecOf(n));
                } else if (e.isVec) {
                    e = Expr.scalar("(" + e.java + "[" + emitScalar(idx) + "])", baseOf(e.type));
                } else {
                    e = Expr.scalar("(" + e.java + "[" + emitScalar(idx) + "])", baseOf(e.type));
                }
            } else {
                return e;
            }
        }
    }

    private Expr swizzle(Expr e, String swz) {
        if (e.refPrelude != null) throw new TranslateException("scalar out/inout nested swizzle requires statement sequencing");
        int n = swz.length();
        if(n==1&&e.samplingMethod!=null&&!"off".equalsIgnoreCase(System.getProperty("antimony.glsl.scalarSampler","on"))){
            int channel=componentIndex(swz.charAt(0));if(channel<0||channel>=vecSize(e.type))throw new TranslateException("bad texture swizzle");
            return Expr.scalar("st."+e.samplingMethod+"Channel("+e.samplingArguments+", "+channel+")",T_FLOAT);
        }
        if(!e.isVec&&isArrayExpr(e)&&isBoolVec(e.type)){
            List<String> indices=new ArrayList<>();for(int i=0;i<n;i++){int index=componentIndex(swz.charAt(i));if(index<0||index>=vecSize(e.type))throw new TranslateException("bad boolean swizzle");indices.add(Integer.toString(index));}
            if(n==1)return Expr.scalar("("+e.java+")["+indices.get(0)+"]",T_BOOL);
            Expr r=Expr.scalar("GlslIntegerRuntime.swizzle("+e.java+", "+String.join(", ",indices)+")",boolVecOf(n));r.arrayValued=true;return r;
        }
        if(!e.isVec&&isArrayExpr(e)&&(isIntVec(e.type)||isUintVec(e.type))){
            List<String> indices=new ArrayList<>();for(int i=0;i<n;i++){int index=componentIndex(swz.charAt(i));if(index<0||index>=vecSize(e.type))throw new TranslateException("bad integer swizzle");indices.add(Integer.toString(index));}
            if(n==1)return Expr.scalar("("+e.java+")["+indices.get(0)+"]",baseOf(e.type));
            Expr result=Expr.scalar("GlslIntegerRuntime.swizzle("+e.java+", "+String.join(", ",indices)+")",integerVecOf(n,isUintVec(e.type)));result.arrayValued=true;return result;
        }
        if (!e.isVec && isArrayExpr(e) && isFloatVec(e.type)) {
            List<String> indices = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = componentIndex(swz.charAt(i));
                if (index < 0 || index >= vecSize(e.type)) throw new TranslateException("bad swizzle ." + swz);
                indices.add(Integer.toString(index));
            }
            if (n == 1) return Expr.scalar("(" + e.java + ")[" + indices.get(0) + "]", T_FLOAT);
            Expr result = Expr.scalar("__vertexSwizzle(" + e.java + ", " + String.join(", ", indices) + ")", floatVecOf(n));
            result.arrayValued = true;
            return result;
        }
        if (!e.isVec) {
            // Non-vector swizzle sources: function results like texture()
            // return via a single scalar java expression that is actually an
            // array (st.sample). Emit array reads when the source text looks
            // like an expression producing float[].
            if (isArrayExpr(e)) {
                List<Expr> comps = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    int idx = componentIndex(swz.charAt(i));
                    if (idx < 0) {
                        throw new TranslateException("bad swizzle ." + swz);
                    }
                    comps.add(Expr.scalar("(" + e.java + "[" + idx + "])", T_FLOAT));
                }
                if (n == 1) {
                    return comps.get(0);
                }
                return Expr.vector(comps, floatVecOf(n));
            }
            throw new TranslateException("swizzle on scalar ." + swz);
        }
        List<Expr> comps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = componentIndex(swz.charAt(i));
            if (idx < 0 || idx >= e.comps.size()) {
                throw new TranslateException("bad swizzle ." + swz);
            }
            comps.add(e.comps.get(idx));
        }
        if (n == 1) {
            return comps.get(0);
        }
        return Expr.vector(comps, swizzleType(n, baseOf(e.type)));
    }

    /**
     * True when the expression's java text evaluates to float[]/int[] even
     * though we modeled it scalar (function-call results like st.sample(...)).
     */
    private boolean isArrayExpr(Expr e) {
        if (e.arrayValued) {
            return true;
        }
        if (e.java == null) return false;
        return e.java.startsWith("st.sample(") || e.java.startsWith("GlslRuntime.normalize(")
            || e.java.startsWith("GlslRuntime.cross(") || e.java.startsWith("GlslRuntime.reflect(")
            || e.java.startsWith("GlslRuntime.refract(") || e.java.startsWith("GlslRuntime.mulMatVec(")
            || e.java.startsWith("GlslRuntime.mulVecMat(") || e.java.startsWith("GlslRuntime.matCol(")
            || e.java.startsWith("GlslRuntime.mat3(") || e.java.startsWith("GlslRuntime.mat3FromMat4(");
    }

    private int swizzleType(int n, int base) {
        return switch (base) {
            case T_INT -> switch (n) { case 2 -> T_IVEC2; case 3 -> T_IVEC3; default -> T_IVEC4; };
            case T_UINT -> integerVecOf(n,true);
            case T_BOOL -> switch (n) { case 2 -> T_BVEC2; case 3 -> T_BVEC3; default -> T_BVEC4; };
            default -> switch (n) { case 2 -> T_VEC2; case 3 -> T_VEC3; default -> T_VEC4; };
        };
    }

    private int componentIndex(char c) {
        return switch (c) {
            case 'x', 'r', 's' -> 0;
            case 'y', 'g', 't' -> 1;
            case 'z', 'b', 'p' -> 2;
            case 'w', 'a', 'q' -> 3;
            default -> -1;
        };
    }

    private Expr parsePrimary() {
        Tok t = peek();
        if (t.text.equals("(")) {
            next();
            Expr e = parseExprNode();
            expect(")");
            return e;
        }
        if (t.kind == NUM) {
            next();
            if(t.text.endsWith("u")){long value=Long.parseUnsignedLong(t.text.substring(0,t.text.length()-1));if(value>0xffffffffL)throw new TranslateException("uint literal out of range");return Expr.scalar(Integer.toString((int)value),T_UINT);}
            return Expr.scalar(t.text, t.text.contains("f") ? T_FLOAT : T_INT);
        }
        if (t.kind == ID) {
            next();
            String name = t.text;
            if (name.equals("true") || name.equals("false")) {
                return Expr.scalar(name, T_BOOL);
            }
            if(name.equals("gl_VertexID")){
                if(!vertexMode)throw new TranslateException("vertex builtin outside vertex stage");
                sawGlVertexId=true;
                return Expr.scalar("((VertexState) st).vertexId()",T_INT);
            }
            if(name.equals("gl_GlobalInvocationID")){
                if(!computeMode)throw new TranslateException("compute builtin outside compute stage");Expr result=Expr.scalar("st.globalInvocationID()",T_UVEC3);result.arrayValued=true;return result;
            }
            if (name.equals("gl_FragCoord")) {
                if(computeMode)throw new TranslateException("fragment builtin outside fragment stage");
                sawGlFragCoord = true;
                // vec4(x, y, z, 1) - emit per-component special exprs
                List<Expr> comps = List.of(
                    Expr.scalar("(st.fragU() * st.width())", T_FLOAT),
                    Expr.scalar("(st.fragV() * st.height())", T_FLOAT),
                    Expr.scalar("st.fragZ()", T_FLOAT),
                    Expr.scalar("st.fragW()", T_FLOAT));
                return Expr.vector(comps, T_VEC4);
            }
            if (typeOfName(name) >= 0 && peek().text.equals("[")) {
                int elementType = typeOfName(name);
                next(); int len = fixedArrayLength(); expect("]"); expect("(");
                List<Expr> elems = parseArgs();
                if (elems.size() != len) throw new TranslateException("array constructor length mismatch");
                List<String> values = new ArrayList<>();
                for (Expr elem : elems) values.add(emit(elem, elementType));
                return Expr.scalar("new " + TYPE_NAME[arrayOf(elementType)] + "{" + String.join(", ", values) + "}", arrayOf(elementType));
            }
            if (accept("(")) {
                return parseCall(name);
            }
            Var v = locals.get(name);
            if (v == null) {
                v = globals.get(name);
            }
            if (v == null) {
                throw new TranslateException("unknown identifier '" + name + "'");
            }
            if (v.uniformName != null) referencedUniforms.add(v.uniformName);
            if (v == globals.get(name) && varyingInputs.containsKey(name)) referencedVaryings.add(name);
            if (isSampler(v.type)||isImage(v.type)) {
                return Expr.scalar(v.javaName, v.type);
            }
            if (isMat(v.type)) {
                // keep the matrix whole: arithmetic() routes it to runtime
                // matrix helpers; indexing m[i] yields a column vector.
                return Expr.scalar(v.javaName, v.type);
            }
            if (isVec(v.type)) {
                int n = vecSize(v.type);
                String cast = v.type == T_IVEC2 || v.type == T_IVEC3 || v.type == T_IVEC4 ? "(int) "
                    : (v.type == T_BVEC2 || v.type == T_BVEC3 || v.type == T_BVEC4 ? "(boolean) " : "");
                List<Expr> comps = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    int bt = baseOf(v.type);
                    Expr component=Expr.scalar(cast + v.javaName + "[" + i + "]", bt);
                    component.lvalue=v.javaName+"["+i+"]";component.symbol=v;
                    comps.add(component);
                }
                Expr result = Expr.vector(comps, v.type); result.lvalue = v.javaName; result.symbol=v; return result;
            }
            Expr result = Expr.scalar(v.javaName, v.type); result.lvalue = v.javaName; result.symbol=v; return result;
        }
        throw new TranslateException("unexpected token '" + t.text + "'");
    }

    private Expr parseCall(String name) {
        int ctor = typeOfName(name);
        if (ctor >= 0) {
            if (accept("[")) {
                int len = fixedArrayLength(); expect("]"); expect("(");
                List<Expr> elems = parseArgs();
                if (elems.size() != len) throw new TranslateException("array constructor length mismatch");
                List<String> values = new ArrayList<>();
                for (Expr elem : elems) values.add(emit(elem, ctor));
                return Expr.scalar("new " + TYPE_NAME[arrayOf(ctor)] + "{" + String.join(", ", values) + "}", arrayOf(ctor));
            }
            List<Expr> args = parseArgs();
            return construct(ctor, args);
        }
        // user function (possibly overloaded: store key name#vvs suffix)
        List<Expr> args = parseArgs();
        StringBuilder signature = new StringBuilder(name).append('#');
        for (Expr arg : args) signature.append(arg.type).append('_');
        UserFn fn = userFns.get(signature.toString());
        if (fn == null) {
            int bestCost = Integer.MAX_VALUE;
            for (var entry : userFns.entrySet()) {
                UserFn candidate = entry.getValue();
                if (!entry.getKey().startsWith(name + "#") || candidate.params.size() != args.size()) continue;
                int cost = 0;
                for (int i = 0; i < args.size(); i++) {
                    int from = args.get(i).type, to = candidate.params.get(i).type;
                    if (from == to) continue;
                    if ((from == T_INT||from==T_UINT) && to == T_FLOAT || from==T_INT&&to==T_UINT) cost++;
                    else { cost = Integer.MAX_VALUE; break; }
                }
                if (cost < bestCost) { fn = candidate; bestCost = cost; }
                else if (cost != Integer.MAX_VALUE && cost == bestCost) throw new TranslateException("ambiguous overload " + name);
            }
        }
        if (fn != null) {
            calledFunctions.add(fn);
            return emitUserCall(fn, args);
        }
        return emitBuiltin(name, args);
    }

    /** Emits an instance user-function call carrying FragState first. */
    private Expr emitUserCall(UserFn fn, List<Expr> args) {
        StringBuilder call = new StringBuilder(fn.javaName).append("(st");
        List<String> before = new ArrayList<>(), after = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            Expr arg = args.get(i); Var parameter = fn.params.get(i);
            if (arg.refPrelude != null) throw new TranslateException("scalar out/inout requires a statement boundary (direct call, initializer or assignment)");
            if (isSampler(parameter.type)) noteSamplerUse(arg.java);if(isImage(parameter.type))noteImageUse(arg.java);
            call.append(", ");
            if (fn.qualifiers.get(i).equals("in")) call.append(emit(arg, parameter.type));
            else {
                if (arg.type != parameter.type || arg.lvalue == null && (!arg.isVec || arg.comps.stream().anyMatch(component->component.lvalue==null)))
                    throw new TranslateException("out/inout argument must be a matching writable lvalue");
                if(arg.symbol!=null&&arg.symbol.uniformName!=null)throw new TranslateException("out/inout cannot write a uniform");
                if (isVec(parameter.type)) {
                    if(arg.lvalue!=null)call.append(arg.lvalue);
                    else {
                        java.util.Set<String> targets=new java.util.HashSet<>();
                        for(Expr component:arg.comps){
                            if(component.symbol!=null&&component.symbol.uniformName!=null)throw new TranslateException("out/inout cannot write a uniform component");
                            if(!targets.add(component.lvalue))throw new TranslateException("out/inout swizzle has repeated components");
                        }
                        String tmp="__refarg"+assignTemp++;
                        String initial=fn.qualifiers.get(i).equals("out")?"new "+TYPE_NAME[baseOf(parameter.type)]+"["+vecSize(parameter.type)+"]":emit(arg,parameter.type);
                        before.add(TYPE_NAME[parameter.type]+" "+tmp+" = "+initial);
                        call.append(tmp);
                        for(int component=0;component<arg.comps.size();component++)after.add(arg.comps.get(component).lvalue+" = "+tmp+"["+component+"]");
                    }
                }
                else {
                    String tmp = "__refarg" + assignTemp++;
                    before.add(TYPE_NAME[parameter.type] + "[] " + tmp + " = new " + TYPE_NAME[parameter.type] + "[]{" + arg.java + "}");
                    call.append(tmp); after.add(arg.lvalue + " = " + tmp + "[0]");
                }
            }
        }
        call.append(')');
        Expr result = Expr.scalar(call.toString(), fn.retType);
        result.arrayValued = isVec(fn.retType) || isMat(fn.retType);
        if (!before.isEmpty()) { result.refPrelude = before; result.refPostlude = after; }
        return result;
    }

    private List<Expr> parseArgs() {
        List<Expr> args = new ArrayList<>();
        while (!accept(")")) {
            args.add(parseExprNode());
            accept(",");
        }
        return args;
    }

    private Expr construct(int ctor, List<Expr> args) {
        if(isVec(ctor)&&!isBoolVec(ctor)&&args.size()==1&&isVec(args.get(0).type)&&!isBoolVec(args.get(0).type)){
            Expr value=args.get(0);int n=vecSize(ctor);if(vecSize(value.type)<n)throw new TranslateException("vector conversion arity");
            String call;
            if(isFloatVec(ctor))call="GlslIntegerRuntime.toFloats("+emit(value,value.type)+", "+n+(isFloatVec(value.type)?"":", "+(baseOf(value.type)==T_UINT))+")";
            else call="GlslIntegerRuntime.toInts("+emit(value,value.type)+", "+n+(isFloatVec(value.type)?", "+(baseOf(ctor)==T_UINT):"")+")";
            Expr result=Expr.scalar(call,ctor);result.arrayValued=true;return result;
        }
        if (ctor == T_MAT2) {
            if (args.size() == 1 && args.get(0).type == T_MAT2) return args.get(0);
            List<Expr> columns = new ArrayList<>();
            for (Expr arg : args) {
                arg = vectorize(arg);
                if (arg.isVec) columns.addAll(arg.comps); else columns.add(arg);
            }
            if (columns.size() != 4) throw new TranslateException("mat2 constructor requires four components");
            Expr result = Expr.scalar("new float[]{" + joinNums(columns) + "}", T_MAT2);
            result.arrayValued = true; return result;
        }
        if (ctor == T_MAT4) {
            // mat4(16 scalars) -> GlslRuntime.mat4(...)
            if (args.size() == 16) {
                return Expr.scalar("GlslRuntime.mat4(" + joinNums(args) + ")", T_MAT4);
            }
            // mat4(mat4) identity pass-through
            if (args.size() == 1 && !args.get(0).isVec && args.get(0).type == T_MAT4) {
                return args.get(0);
            }
            // mat4(mat3): upper-left 3x3, rest identity
            if (args.size() == 1 && !args.get(0).isVec && args.get(0).type == T_MAT3) {
                return Expr.scalar("GlslRuntime.mat4FromMat3(" + args.get(0).java + ")", T_MAT4);
            }
            // mat4(scalar): diagonal (corpus: mat4(1.0f))
            if (args.size() == 1 && !args.get(0).isVec && args.get(0).type == T_FLOAT) {
                String s = emitScalar(args.get(0));
                if (s.equals("1.0f") || s.equals("1")) {
                    return Expr.scalar("GlslRuntime.identity4()", T_MAT4);
                }
                throw new TranslateException("mat4(diagonal " + s + ")");
            }
            // mat4(vec4, vec4, vec4, vec4): four columns -> 16 flattened comps
            if (args.size() == 4) {
                boolean allVec4 = true;
                for (Expr a : args) {
                    if (!a.isVec || a.comps.size() != 4) {
                        allVec4 = false;
                        break;
                    }
                }
                if (allVec4) {
                    List<Expr> flat = new ArrayList<>();
                    for (Expr a : args) {
                        flat.addAll(a.comps);
                    }
                    return Expr.scalar("GlslRuntime.mat4(" + joinNums(flat) + ")", T_MAT4);
                }
            }
            throw new TranslateException("mat4 constructor arity " + args.size());
        }
        if (ctor == T_MAT3) {
            // mat3(mat4): take upper-left 3x3 (corpus uses this for view space)
            if (args.size() == 1 && !args.get(0).isVec && args.get(0).type == T_MAT4) {
                return Expr.scalar("GlslRuntime.mat3FromMat4(" + args.get(0).java + ")", T_MAT3);
            }
            // mat3(mat3): identity pass-through
            if (args.size() == 1 && !args.get(0).isVec && args.get(0).type == T_MAT3) {
                return args.get(0);
            }
            // mat3(9 scalars) / mat3(vec3,vec3,vec3) -> GlslRuntime.mat3(...)
            List<Expr> flat = new ArrayList<>();
            for (Expr a : args) {
                if (a.isVec) {
                    flat.addAll(a.comps);
                } else {
                    flat.add(a);
                }
            }
            if (flat.size() == 9) {
                return Expr.scalar("GlslRuntime.mat3(" + joinNums(flat) + ")", T_MAT3);
            }
            throw new TranslateException("matrix constructor " + TYPE_NAME[ctor]
                + " arity " + flat.size());
        }
        if (ctor == T_FLOAT || ctor == T_INT || ctor == T_BOOL || ctor==T_UINT) {
            if (args.size() != 1 || isVec(args.get(0).type) || isMat(args.get(0).type)) throw new TranslateException("scalar constructor arity/type");
            Expr arg = args.get(0);
            if(ctor==T_UINT){String v=arg.type==T_FLOAT?"GlslIntegerRuntime.toUint("+emitScalar(arg)+")":arg.type==T_BOOL?"("+emitScalar(arg)+"?1:0)":emitScalar(arg);return Expr.scalar(v,T_UINT);}
            String value = ctor == T_FLOAT ? castNum(arg) : ctor == T_INT ?
                (arg.type == T_BOOL ? "(" + emitScalar(arg) + " ? 1 : 0)" : "(int)(" + emitScalar(arg) + ")")
                : arg.type == T_BOOL ? emitScalar(arg) : "(" + emitScalar(arg) + " != 0)";
            return Expr.scalar(value, ctor);
        }
        for (int i = 0; i < args.size(); i++) args.set(i, vectorize(args.get(i)));
        int n = vecSize(ctor);
        int base = baseOf(ctor);
        List<Expr> comps = new ArrayList<>();
        if (args.size() == 1 && !args.get(0).isVec && n > 1) {
            for (int i = 0; i < n; i++) {
                comps.add(Expr.scalar(castComponent(args.get(0), base), base));
            }
            return Expr.vector(comps, ctor);
        }
        List<Expr> flat = new ArrayList<>();
        for (Expr a : args) {
            if (a.isVec) {
                flat.addAll(a.comps);
            } else {
                flat.add(a);
            }
        }
        if (flat.size() < n) {
            throw new TranslateException("constructor arity " + flat.size() + " < " + n);
        }
        for (int i = 0; i < n; i++) {
            comps.add(Expr.scalar(castComponent(flat.get(i), base), base));
        }
        return Expr.vector(comps, ctor);
    }

    private String joinNums(List<Expr> flat) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < flat.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(castNum(flat.get(i)));
        }
        return sb.toString();
    }

    private String castComponent(Expr a, int base) {
        if(base==T_UINT)return a.type==T_FLOAT?"GlslIntegerRuntime.toUint("+emitScalar(a)+")":a.type==T_BOOL?"("+emitScalar(a)+"?1:0)":emitScalar(a);
        if (a.type == base) {
            return a.java;
        }
        if (base == T_FLOAT) {
            return castNum(a);
        }
        if (base == T_INT) {
            return "(int) " + a.java;
        }
        return "(" + a.java + " ? true : false)";
    }

    // ---- builtins ---------------------------------------------------------------

    private Expr textureCall(String name,List<Expr> args){
        boolean lod=name.equals("textureLod"),bias=!lod&&args.size()==3;
        if(lod?args.size()!=3:args.size()!=2&&args.size()!=3)throw new TranslateException("texture call arity");
        Expr sampler=args.get(0),uv=args.get(1);
        if(!isSampler(sampler.type)||sampler.isVec)throw new TranslateException("texture arg0 must be sampler");
        if(bias&&sampler.type!=T_SAMPLER)throw new TranslateException("biased lookup currently requires a 2D color sampler");
        if((lod||bias)&&(args.get(2).isVec||args.get(2).type!=T_FLOAT&&args.get(2).type!=T_INT))throw new TranslateException("texture LOD/bias must be scalar");
        if(sampler.type==T_USAMPLER3D)throw new TranslateException("unsigned 3D shader values require uint/uvec support; no signed float substitute");
        if(sampler.type==T_SAMPLER2DSHADOW){
            if(!name.equals("texture")&&!lod||uv.type!=T_VEC3)throw new TranslateException("comparison texture requires texture(sampler2DShadow, vec3)");
            noteSamplerUse(sampler.java);
            return Expr.scalar("st."+(lod?"sampleShadowLod":"sampleShadow")+"("+sampler.java+", "+emit(uv,T_VEC3)+(lod?", "+castNum(args.get(2)):"")+")",T_FLOAT);
        }
        int coordinateType=sampler.type==T_SAMPLER3D?T_VEC3:T_VEC2;
        if(uv.type!=coordinateType)throw new TranslateException("texture coordinate dimensional/type mismatch for "+samplerTypeName(sampler.type));
        if(name.equals("texture2D")&&sampler.type!=T_SAMPLER||name.equals("texture3D")&&sampler.type!=T_SAMPLER3D)
            throw new TranslateException("legacy texture function dimensional mismatch");
        noteSamplerUse(sampler.java);
        String method=sampler.type==T_SAMPLER3D?(lod?"sample3DLod":"sample3D"):(lod?"sampleLod":bias?"sampleBias":"sample");
        Expr result=Expr.scalar("st."+method+"("+sampler.java+", "+emit(uv,coordinateType)+(lod||bias?", "+castNum(args.get(2)):"")+")",T_VEC4);
        result.arrayValued=true;
        if(sampler.type==T_SAMPLER){result.samplingMethod=method;result.samplingArguments=result.java.substring(method.length()+4,result.java.length()-1);}
        return result;
    }
    private Expr integerCompare(List<Expr> args,boolean greater){
        Expr a=args.get(0),b=args.get(1);if(vecSize(a.type)!=vecSize(b.type))throw new TranslateException("integer compare vector arity");
        Expr r=Expr.scalar("GlslIntegerRuntime.compare("+emit(a,a.type)+", "+emit(b,b.type)+", "+greater+", "+(baseOf(a.type)==T_UINT||baseOf(b.type)==T_UINT)+")",boolVecOf(vecSize(a.type)));r.arrayValued=true;return r;
    }
    private Expr emitBuiltin(String name, List<Expr> args) {
        switch (name) {
            case "imageStore" -> {
                if(args.size()!=3||!isImage(args.get(0).type)||args.get(1).type!=T_IVEC3||args.get(2).type!=(args.get(0).type==T_IMAGE3D?T_VEC4:T_UVEC4))throw new TranslateException("imageStore typed arity/domain");
                noteImageUse(args.get(0).java);
                return Expr.scalar("st."+(args.get(0).type==T_IMAGE3D?"storeImage3D":"storeUnsignedImage3D")+"("+args.get(0).java+", "+emit(args.get(1),T_IVEC3)+", "+emit(args.get(2),args.get(2).type)+")",T_VOID);
            }
            case "mix" -> {
                return componentwise("GlslRuntime.mix", args);
            }
            case "dot" -> {
                return Expr.scalar("GlslRuntime.dot(" + emit(args.get(0), -1) + ", " + emit(args.get(1), -1) + ")",
                    T_FLOAT);
            }
            case "length" -> {
                if (args.get(0).type == T_FLOAT || args.get(0).type == T_INT)
                    return Expr.scalar("Math.abs(" + castNum(args.get(0)) + ")", T_FLOAT);
                return Expr.scalar("GlslRuntime.length(" + emit(args.get(0), -1) + ")", T_FLOAT);
            }
            case "distance" -> {
                return Expr.scalar("GlslRuntime.distance(" + emit(args.get(0), -1) + ", "
                    + emit(args.get(1), -1) + ")", T_FLOAT);
            }
            case "normalize" -> {
                return Expr.scalar("GlslRuntime.normalize(" + emit(args.get(0), -1) + ")", args.get(0).type);
            }
            case "cross" -> {
                return Expr.scalar("GlslRuntime.cross(" + emit(args.get(0), -1) + ", " + emit(args.get(1), -1) + ")",
                    T_VEC3);
            }
            case "reflect" -> {
                return Expr.scalar("GlslRuntime.reflect(" + emit(args.get(0), -1) + ", "
                    + emit(args.get(1), -1) + ")", args.get(0).type);
            }
            case "refract" -> {
                return Expr.scalar("GlslRuntime.refract(" + emit(args.get(0), -1) + ", "
                    + emit(args.get(1), -1) + ", " + emitScalar(args.get(2)) + ")", args.get(0).type);
            }
            case "texture", "texture2D", "texture3D", "textureLod" -> { return textureCall(name,args); }
            case "dFdx", "dFdy" -> {
                if(args.size()!=1)throw new TranslateException("derivative arity");
                Expr value=args.get(0); ShaderVaryings.Variable varying=null;
                for(ShaderVaryings.Variable candidate:varyingInputs.values())
                    if(value.symbol!=null&&value.symbol.isField&&globals.get(candidate.name())==value.symbol)varying=candidate;
                if(varying==null||!(value.type==T_FLOAT||isFloatVec(value.type)))throw new TranslateException("derivative currently requires direct float varying input");
                derivativeInputs.add(varying.name());
                List<Expr> components=new ArrayList<>();
                for(int i=0;i<vecSize(value.type);i++)components.add(Expr.scalar("st.derivative(\""+varying.name()+"\", "+i+", "+name.equals("dFdy")+")",T_FLOAT));
                return components.size()==1?components.get(0):Expr.vector(components,value.type);
            }
            case "texelFetch" -> {
                if(args.size()==3&&args.get(0).type==T_SAMPLER&&args.get(1).type==T_IVEC2&&args.get(2).type==T_INT){
                    noteSamplerUse(args.get(0).java);
                    Expr result=Expr.scalar("st.fetch2D("+args.get(0).java+", "+emit(args.get(1),T_IVEC2)+", "+emitScalar(args.get(2))+")",T_VEC4);
                    result.arrayValued=true;return result;
                }
                if(args.size()!=3||(args.get(0).type!=T_SAMPLER3D&&args.get(0).type!=T_USAMPLER3D)||args.get(1).type!=T_IVEC3||args.get(2).type!=T_INT)throw new TranslateException("texelFetch sampler/integer coordinate/LOD domain");
                noteSamplerUse(args.get(0).java);boolean unsigned=args.get(0).type==T_USAMPLER3D;
                Expr result=Expr.scalar("st."+(unsigned?"fetchUnsigned3D":"fetch3D")+"("+args.get(0).java+", "+emit(args.get(1),T_IVEC3)+", "+emitScalar(args.get(2))+")",unsigned?T_UVEC4:T_VEC4);result.arrayValued=true;return result;
            }
            case "shadow2D", "shadow2DEXT" -> {
                if(args.size()!=2||args.get(0).isVec||args.get(0).type!=T_SAMPLER2DSHADOW||args.get(1).type!=T_VEC3)
                    throw new TranslateException("shadow2D requires sampler2DShadow and vec3");
                noteSamplerUse(args.get(0).java);
                Expr result=Expr.scalar("st.shadow2D("+args.get(0).java+", "+emit(args.get(1),T_VEC3)+")",T_VEC4);
                result.arrayValued=true;return result;
            }
            case "equal", "notEqual" -> {
                if(args.size()!=2||!isVec(args.get(0).type)||!isVec(args.get(1).type)||!integerType(args.get(0).type)||!integerType(args.get(1).type)||args.get(0).type!=args.get(1).type)throw new TranslateException(name+" currently requires matching integer vectors");
                Expr result=Expr.scalar("GlslIntegerRuntime.equalComponents("+emit(args.get(0),args.get(0).type)+", "+emit(args.get(1),args.get(1).type)+", "+name.equals("notEqual")+")",boolVecOf(vecSize(args.get(0).type)));result.arrayValued=true;return result;
            }
            case "any", "all" -> {
                if(args.size()!=1||!isBoolVec(args.get(0).type))throw new TranslateException(name+" requires a boolean vector");
                return Expr.scalar("GlslIntegerRuntime."+name+"("+emit(args.get(0),args.get(0).type)+")",T_BOOL);
            }
            case "greaterThan" -> {
                if(args.size()==2&&integerType(args.get(0).type)&&integerType(args.get(1).type)&&isVec(args.get(0).type)&&isVec(args.get(1).type))return integerCompare(args,true);
                // greaterThan(vec a, vec b) -> bvec; corpus uses .x of the
                // result immediately, so componentwise compare suffices.
                Expr a = args.get(0);
                Expr b = args.get(1);
                if (!a.isVec || !b.isVec) {
                    throw new TranslateException("greaterThan on scalars");
                }
                List<Expr> comps = new ArrayList<>();
                for (int i = 0; i < a.comps.size(); i++) {
                    comps.add(Expr.scalar("(" + emitScalar(a.comps.get(i)) + " > "
                        + emitScalar(b.comps.get(i)) + ")", T_BOOL));
                }
                return Expr.vector(comps, boolVecOf(a.comps.size()));
            }
            case "lessThan" -> {
                if(args.size()==2&&integerType(args.get(0).type)&&integerType(args.get(1).type)&&isVec(args.get(0).type)&&isVec(args.get(1).type))return integerCompare(args,false);
                Expr a = args.get(0);
                Expr b = args.get(1);
                if (!a.isVec || !b.isVec) {
                    throw new TranslateException("lessThan on scalars");
                }
                List<Expr> comps = new ArrayList<>();
                for (int i = 0; i < a.comps.size(); i++) {
                    comps.add(Expr.scalar("(" + emitScalar(a.comps.get(i)) + " < "
                        + emitScalar(b.comps.get(i)) + ")", T_BOOL));
                }
                return Expr.vector(comps, boolVecOf(a.comps.size()));
            }
            case "min", "max", "clamp", "step", "smoothstep", "mod" -> {
                return componentwise(builtinJava(name), args);
            }
            default -> {
                return componentwise(builtinJava(name), args);
            }
        }
    }

    private String builtinJava(String name) {
        return switch (name) {
            case "abs" -> "Math.abs";
            case "floor" -> "GlslRuntime.floor";
            case "ceil" -> "GlslRuntime.ceil";
            case "sqrt" -> "GlslRuntime.sqrt";
            case "pow" -> "GlslRuntime.pow";
            case "exp" -> "GlslRuntime.exp";
            case "exp2" -> "GlslRuntime.exp2";
            case "log" -> "GlslRuntime.log";
            case "log2" -> "GlslRuntime.log2";
            case "sign" -> "GlslRuntime.sign";
            case "fract" -> "GlslRuntime.fract";
            case "clamp" -> "GlslRuntime.clamp";
            case "min" -> "Math.min";
            case "max" -> "Math.max";
            case "step" -> "GlslRuntime.step";
            case "smoothstep" -> "GlslRuntime.smoothstep";
            case "mod" -> "GlslRuntime.mod";
            case "sin" -> "GlslRuntime.sin";
            case "cos" -> "GlslRuntime.cos";
            case "tan" -> "GlslRuntime.tan";
            case "atan" -> "GlslRuntime.atan";
            case "isnan" -> "GlslRuntime.isnan";
            case "inversesqrt" -> "GlslRuntime.inversesqrt";
            default -> throw new TranslateException("unsupported builtin '" + name + "'");
        };
    }

    /**
     * Componentwise application of a scalar static function to possibly-vector
     * args (GLSL broadcasts scalars). Returns a vector when arg0 is a vector.
     */
    private Expr componentwise(String fn, List<Expr> args) {
        for (int i = 0; i < args.size(); i++) {
            args.set(i, vectorize(args.get(i)));
        }
        boolean integerOverload=java.util.Set.of("Math.min","Math.max","GlslRuntime.clamp").contains(fn)&&args.stream().allMatch(a->integerType(a.type));
        if(integerOverload){
            int count=fn.equals("GlslRuntime.clamp")?3:2;if(args.size()!=count)throw new TranslateException("integer builtin arity");
            boolean unsigned=args.stream().anyMatch(a->baseOf(a.type)==T_UINT);String op=fn.equals("Math.min")?"min":fn.equals("Math.max")?"max":"clamp";
            int n=1;for(Expr value:args)if(isVec(value.type)){if(n!=1&&n!=vecSize(value.type))throw new TranslateException("integer builtin vector arity");n=vecSize(value.type);}
            if(n==1){String a=emitScalar(args.get(0)),b=emitScalar(args.get(1));String code=op.equals("clamp")?"GlslIntegerRuntime.min(GlslIntegerRuntime.max("+a+", "+b+", "+unsigned+"), "+emitScalar(args.get(2))+", "+unsigned+")":"GlslIntegerRuntime."+op+"("+a+", "+b+", "+unsigned+")";return Expr.scalar(code,unsigned?T_UINT:T_INT);}
            List<String> arrays=new ArrayList<>();for(Expr value:args)arrays.add(isVec(value.type)?emit(value,value.type):"new int[]{"+emitScalar(value)+"}");
            Expr r=Expr.scalar("GlslIntegerRuntime.builtin("+arrays.get(0)+", "+arrays.get(1)+", "+(count==3?arrays.get(2):"null")+", \""+op+"\", "+unsigned+")",integerVecOf(n,unsigned));r.arrayValued=true;return r;
        }
        boolean anyVec = false;
        for (Expr a : args) {
            anyVec |= a.isVec;
        }
        if (!anyVec) {
            StringBuilder sb = new StringBuilder(fn).append('(');
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(integerOverload?emitScalar(args.get(i)):castNum(args.get(i)));
            }
            sb.append(')');
            return Expr.scalar(sb.toString(), integerOverload?T_INT:T_FLOAT);
        }
        int n = 1;
        for (Expr a : args) {
            if (a.isVec) {
                n = a.comps.size();
                break;
            }
        }
        List<Expr> comps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder(fn).append('(');
            for (int k = 0; k < args.size(); k++) {
                if (k > 0) {
                    sb.append(", ");
                }
                Expr a = args.get(k);
                Expr c = a.isVec ? a.comps.get(Math.min(i, a.comps.size() - 1)) : a;
                sb.append(integerOverload?emitScalar(c):castNum(c));
            }
            sb.append(')');
            comps.add(Expr.scalar(sb.toString(), integerOverload?T_INT:T_FLOAT));
        }
        return Expr.vector(comps, integerOverload?swizzleType(n,T_INT):floatVecOf(n));
    }

    // ---- statement emit ----------------------------------------------------------

    /** Emits an assignment statement (detects lvalue = expr) or an expression. */
    private String emitStatement(Expr e) {
        return e.java;
    }

    /**
     * The parse of "a = expr" flows through parseTernary -> parseOr ... but we
     * handle assignment at the statement level by peeking: parseStatement
     * splits identifier-path '=' expr before calling parseExprNode. See
     * parseAssignmentStatement.
     */
    private void parseAssignmentStatement(StringBuilder out, String indent, String term) {
        // left side: identifier path with swizzle/index
        StringBuilder left = new StringBuilder();
        Tok t = next();
        left.append(t.text);
        int lVec = -1;
        String lBase = t.text;
        Var lv = locals.get(lBase);
        if (lv == null) {
            lv = globals.get(lBase);
        }
        if (lv == null) {
            throw new TranslateException("assignment to unknown '" + lBase + "'");
        }
        lVec = lv.type;
        // path segments
        List<Integer> swzIdx = new ArrayList<>();
        boolean indexed = false;
        Expr indexExpr = null;
        while (true) {
            String p = peek().text;
            if (p.equals(".")) {
                next();
                String swz = expectIdent();
                for (int i = 0; i < swz.length(); i++) {
                    swzIdx.add(componentIndex(swz.charAt(i)));
                }
            } else if (p.equals("[")) {
                next();
                indexExpr = parseExprNode();
                expect("]");
                indexed = true;
            } else {
                break;
            }
        }
        String op = next().text; // = += -= *= /=
        Expr rhs = parseExprNode();
        expect(term);
        List<String> copyOut = beginRefCall(rhs, out, indent);
        emitAssignment(out, indent, lv, swzIdx, indexed, indexExpr, op, rhs);
        for (String line : copyOut) out.append(indent).append(line).append(";\n");
    }

    /** A direct call at a statement boundary evaluates once, then copies scalar outputs back. */
    private List<String> beginRefCall(Expr expr, StringBuilder out, String indent) {
        if (expr.refPrelude == null) return List.of();
        for (String line : expr.refPrelude) out.append(indent).append(line).append(";\n");
        List<String> after = expr.refPostlude;
        expr.refPrelude = null; expr.refPostlude = null;
        return after;
    }

    /** Monotonic suffix for hoisted array-valued assignment temporaries. */
    private int assignTemp;

    private void emitAssignment(StringBuilder out, String indent, Var lv, List<Integer> swzIdx,
                                boolean indexed, Expr indexExpr, String op, Expr rhs) {
        String target = lv.javaName;
        if(!indexed&&swzIdx.isEmpty()&&!op.equals("=")&&(lv.type==T_UINT||isIntVec(lv.type)||isUintVec(lv.type))){
            Expr left=Expr.scalar(target,lv.type);left.arrayValued=isVec(lv.type);
            Expr result=arithmetic(left,rhs,String.valueOf(op.charAt(0)));out.append(indent).append(target).append(" = ").append(emit(result,lv.type)).append(";\n");return;
        }
        if((isIntVec(lv.type)||isUintVec(lv.type))&&(indexed||!swzIdx.isEmpty())){
            java.util.Set<Integer> seen=new java.util.HashSet<>();for(int c:swzIdx)if(c<0||c>=vecSize(lv.type)||!seen.add(c))throw new TranslateException("invalid integer assignment swizzle");
            if(indexed&&!swzIdx.isEmpty())throw new TranslateException("indexed integer swizzle unsupported");
            String index=null;if(indexed){index="__index"+assignTemp++;out.append(indent).append("int ").append(index).append(" = ").append(emitScalar(indexExpr)).append(";\n");}
            int count=indexed?1:swzIdx.size();boolean vector=isVec(rhs.type);if(vector&&vecSize(rhs.type)!=count)throw new TranslateException("integer swizzle RHS arity");
            String tmp="__irhs"+assignTemp++;out.append(indent).append(vector?"int[] ":"int ").append(tmp).append(" = ").append(vector?"("+emit(rhs,rhs.type)+").clone()":castComponent(rhs,baseOf(lv.type))).append(";\n");
            for(int i=0;i<count;i++){String location=target+"["+(indexed?index:swzIdx.get(i))+"]",value=tmp+(vector?"["+i+"]":"");
                out.append(indent).append(location).append(" = ").append(op.equals("=")?value:"GlslIntegerRuntime.scalar("+location+", "+value+", '"+op.charAt(0)+"', "+isUintVec(lv.type)+")").append(";\n");}
            return;
        }
        if (isVec(lv.type)) {
            java.util.Set<Integer> components = new java.util.HashSet<>();
            for (int component : swzIdx) {
                if (component < 0 || component >= vecSize(lv.type) || !components.add(component)) {
                    throw new TranslateException("invalid or repeated assignment swizzle component");
                }
            }
        }
        // GLSL evaluates the complete RHS before any component store (including overlapping swizzles).
        if (isFloatVec(lv.type) && !indexed && (!op.equals("=") || swzIdx.size() > 1)
            && (rhs.isVec || isArrayExpr(rhs))) {
            String tmp = "__rhs" + (assignTemp++);
            out.append(indent).append("float[] ").append(tmp).append(" = ").append(emit(rhs, rhs.type)).append(";\n");
            int count = rhs.isVec ? rhs.comps.size() : vecSize(rhs.type);
            List<Expr> snapshot = new ArrayList<>();
            for (int i = 0; i < count; i++) snapshot.add(Expr.scalar(tmp + "[" + i + "]", T_FLOAT));
            rhs = Expr.vector(snapshot, floatVecOf(count));
        } else if (isFloatVec(lv.type) && !indexed && !rhs.isVec && !isArrayExpr(rhs)
            && ((!op.equals("=") && swzIdx.isEmpty()) || swzIdx.size() > 1)) {
            String tmp = "__rhs" + (assignTemp++);
            out.append(indent).append("float ").append(tmp).append(" = ").append(castNum(rhs)).append(";\n");
            rhs = Expr.scalar(tmp, T_FLOAT);
        }
        if (lv.type == T_SAMPLER || (!isVec(lv.type) && !isMat(lv.type))) {
            if (!swzIdx.isEmpty() || indexed) {
                throw new TranslateException("assign into scalar sub-part");
            }
            String v = emit(rhs, lv.type);
            if (op.equals("=")) {
                out.append(indent).append(target).append(" = ").append(v).append(";\n");
            } else {
                out.append(indent).append(target).append(' ').append(op).append(' ').append(v).append(";\n");
            }
            return;
        }
        // vector/matrix target
        int n = vecSize(lv.type);
        if (indexed) {
            if (!swzIdx.isEmpty()) {
                throw new TranslateException("assign into indexed+swizzle");
            }
            String v = emit(rhs, baseOf(lv.type));
            if (op.equals("=")) {
                out.append(indent).append(target).append('[').append(emitScalar(indexExpr)).append("] = ")
                    .append(v).append(";\n");
            } else {
                out.append(indent).append(target).append('[').append(emitScalar(indexExpr)).append("] ")
                    .append(op).append(' ').append(v).append(";\n");
            }
            return;
        }
        if (swzIdx.isEmpty()) {
            // whole-object assignment; vector rhs collapses into array literal
            String v = emit(rhs, lv.type);
            if (op.equals("=")) {
                out.append(indent).append(target).append(" = ").append(v).append(";\n");
            } else {
                // compound on arrays: expand per component against the rhs
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    String rv;
                    if (rhs.isVec) {
                        rv = emitScalar(rhs.comps.get(Math.min(i, rhs.comps.size() - 1)));
                    } else {
                        rv = castNum(rhs);
                    }
                    sb.append(indent).append(target).append('[').append(i).append("] = ")
                        .append(target).append('[').append(i).append("] ")
                        .append(op.charAt(0)).append(' ').append(rv).append(";\n");
                }
                out.append(sb);
            }
            return;
        }
        // swizzle assignment: one component or multiple. Compound operators
        // (+= -= *= /=) must keep the old component value on the right hand
        // side - emitting a plain assignment here silently turned every
        // "blockColor.rgb *= ao" into "blockColor.rgb = ao" and was the B1
        // black screen (deferred wrote final_ao ~= 1.0 over the whole frame,
        // composite1 wrote bloom 0.0).
        String compound = op.equals("=") ? null : String.valueOf(op.charAt(0));
        if (swzIdx.size() == 1) {
            String v = emit(rhs, baseOf(lv.type));
            int idx = swzIdx.get(0);
            componentAssign(out, indent, target, idx, compound, v);
            return;
        }
        // multi-component: rhs must be vector with same size; if the rhs is an
        // array-valued expression (texture()/normalize()/a user function) it is
        // evaluated ONCE into a temp - expanding it per component re-ran the
        // whole expression (and re-ran the pack's 10-step cloud march 3x).
        if (!rhs.isVec) {
            if (isArrayExpr(rhs)) {
                String tmp = "__sw" + (assignTemp++);
                out.append(indent).append("float[] ").append(tmp).append(" = ").append(rhs.java).append(";\n");
                for (int i = 0; i < swzIdx.size(); i++) {
                    componentAssign(out, indent, target, swzIdx.get(i), compound, tmp + "[" + i + "]");
                }
                return;
            }
            // scalar broadcast across the swizzle
            String v = emit(rhs, baseOf(lv.type));
            for (int i = 0; i < swzIdx.size(); i++) {
                componentAssign(out, indent, target, swzIdx.get(i), compound, v);
            }
            return;
        }
        if (rhs.comps.size() != swzIdx.size()) {
            throw new TranslateException("swizzle assignment arity mismatch");
        }
        for (int i = 0; i < swzIdx.size(); i++) {
            componentAssign(out, indent, target, swzIdx.get(i), compound, emitScalar(rhs.comps.get(i)));
        }
    }

    /**
     * Emit one component store honouring a compound operator: a plain "=" writes
     * the value, "+="/"*="/etc. keep the component's old value on the right.
     */
    private void componentAssign(StringBuilder out, String indent, String target, int idx,
                                 String compound, String valueExpr) {
        out.append(indent).append(target).append('[').append(idx).append("] = ");
        if (compound != null) {
            out.append(target).append('[').append(idx).append(']').append(' ').append(compound).append(' ');
        }
        out.append(valueExpr).append(";\n");
    }

    // ---- shared emit ---------------------------------------------------------------

    /** Collapses a vector expr into a Java float[] expression. */
    private String emit(Expr e, int expected) {
        if (e.refPrelude != null) throw new TranslateException("scalar out/inout requires a statement boundary (direct call, initializer or assignment)");
        if(expected==T_FLOAT&&e.type==T_UINT)return castNum(e);
        if(expected==T_UINT&&e.type==T_FLOAT)return "GlslIntegerRuntime.toUint("+emitScalar(e)+")";
        if (e.isVec) {
            StringBuilder sb = new StringBuilder("new " + TYPE_NAME[baseOf(e.type)] + "[]{");
            for (int i = 0; i < e.comps.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(emitScalar(e.comps.get(i)));
            }
            sb.append('}');
            return sb.toString();
        }
        return e.java;
    }

    private String emitScalar(Expr e) {
        if (e.refPrelude != null) throw new TranslateException("scalar out/inout requires a statement boundary (direct call, initializer or assignment)");
        if (e.isVec) {
            throw new TranslateException("vector used where scalar expected: " + e.java);
        }
        return e.java;
    }

    private String castNum(Expr c) {
        if(c.type==T_UINT)return "GlslIntegerRuntime.toFloat("+emitScalar(c)+")";
        if (c.type == T_FLOAT) {
            return c.java;
        }
        if (c.type == T_INT) {
            return "(float) " + c.java;
        }
        if (c.type == T_BOOL) {
            return "(" + c.java + " ? 1.0f : 0.0f)";
        }
        // vector-typed scalar-modeled value (texture() results, matrix cols):
        // read component 0 of the array-valued expression
        if (isArrayExpr(c)) {
            return "((" + c.java + ")[0])";
        }
        throw new TranslateException("numeric cast of vector component: " + c.java);
    }

    // ---- helpers -----------------------------------------------------------

    private String mangle(String glslName) {
        return switch (glslName) {
            case "boolean", "byte", "char", "class", "double", "final", "float", "int",
                "long", "new", "package", "private", "protected", "public", "return",
                "short", "static", "switch", "this", "throw", "true", "false",
                "void", "while", "for", "if", "else", "do", "try", "catch",
                "instanceof", "native", "super", "volatile", "abstract",
                "assert", "enum", "extends", "implements", "import", "interface",
                "null", "break", "case", "const", "continue", "default", "goto",
                "finally", "synchronized", "transient" -> glslName + "_";
            default -> glslName;
        };
    }

    private String expectIdent() {
        Tok t = next();
        if (t.kind != ID) {
            throw new TranslateException("expected identifier, found '" + t.text + "'");
        }
        return t.text;
    }

    private int parseType() {
        Tok t = next();
        int ty = typeOfName(t.text);
        if (ty < 0) {
            throw new TranslateException("expected type, found '" + t.text + "'");
        }
        return ty;
    }

    private void skipToSemicolon() {
        while (!peek().text.equals(";") && pos < toks.size()) {
            next();
        }
    }

    private void skipStruct() {
        next(); // struct
        expectIdent();
        if (accept("{")) {
            int d = 1;
            while (d > 0 && pos < toks.size()) {
                String t = next().text;
                if (t.equals("{")) {
                    d++;
                } else if (t.equals("}")) {
                    d--;
                }
            }
        }
        accept(";");
    }
}
