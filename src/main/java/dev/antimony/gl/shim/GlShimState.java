package dev.antimony.gl.shim;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CPU-side GL state that backs the phase-6 {@link GlShim} answers: current
 * program, fake uniform/attribute locations, buffer/texture/sampler bindings
 * and capability bits. Everything is keyed so Iris' caching (by location, by
 * id) sees consistent values across frames.
 *
 * <p>This is deliberately separate from {@code dev.antimony.gl.state.GlState}:
 * GlState mirrors the vanilla blaze3d funnel for the rasterizer, while this
 * registry models the <em>mod</em> GL context Iris thinks it owns. They must
 * not share objects - mixing them is exactly the attribution bug class that
 * regression A (texture mirror) came from.</p>
 */
public final class GlShimState {
    private GlShimState() {
    }

    private static final AtomicInteger CURRENT_PROGRAM = new AtomicInteger();
    private static final Map<Integer, Integer> CAPS = new ConcurrentHashMap<>();
    private static final int[] UNIT_TEXTURES = new int[16];
    private static final int[] UNIT_SAMPLERS = new int[16];
    private static int activeUnit;
    private static int boundBufferArray;
    private static int boundBufferElement;

    // ------------------------------------------------------------------
    // Phase-A shader registry: every program Iris thinks it built on the
    // driver, with its captured GLSL. This is the ground truth for the
    // CPU-side post-processing pipeline (docs section 19).
    // ------------------------------------------------------------------

    /** Immutable snapshot of one shader/program as Iris submitted it. */
    public static final class ShaderRecord {
        public final int id;
        public final int type;              // GL_VERTEX_SHADER / GL_FRAGMENT_SHADER / 0 for programs
        public final String source;         // captured GLSL (null for programs)
        public final List<Integer> attached; // shader ids attached to a program
        // Baked at link time: Iris deletes shader objects right after linking,
        // so per-program source must be snapshotted eagerly.
        public String vertexSource;
        public String fragmentSource;
        public String computeSource;

        ShaderRecord(int id, int type, String source, List<Integer> attached) {
            this.id = id;
            this.type = type;
            this.source = source;
            this.attached = attached;
        }
    }

    private static final Map<Integer, ShaderRecord> SHADERS = new ConcurrentHashMap<>();
    private static final Map<Integer, ShaderRecord> PROGRAMS = new ConcurrentHashMap<>();
    private static final AtomicInteger compileCounter = new AtomicInteger();

    public static void createShader(int id, int type) {
        SHADERS.put(id, new ShaderRecord(id, type, null, null));
    }

    public static void setShaderSource(int id, String source) {
        ShaderRecord old = SHADERS.get(id);
        if (old != null) {
            SHADERS.put(id, new ShaderRecord(id, old.type, source, null));
        }
    }

    public static void deleteShader(int id) {
        SHADERS.remove(id);
    }

    public static void createProgram(int id) {
        PROGRAMS.put(id, new ShaderRecord(id, 0, null, new java.util.ArrayList<>()));
    }

    public static void attachShader(int program, int shader) {
        ShaderRecord p = PROGRAMS.get(program);
        if (p != null && !p.attached.contains(shader)) {
            p.attached.add(shader);
        }
    }

    public static void detachShader(int program, int shader) {
        ShaderRecord p = PROGRAMS.get(program);
        if (p != null) {
            p.attached.remove((Integer) shader);
        }
    }

    // GL_VERTEX_SHADER = 0x8B31, GL_FRAGMENT_SHADER = 0x8B30
    private static final int GL_VERTEX_SHADER = 0x8B31;
    private static final int GL_FRAGMENT_SHADER = 0x8B30;

    /**
     * Bake the currently attached shader sources into the program record.
     * Called from glLinkProgram; after this the program is self-contained even
     * if Iris deletes the shader objects (it does, right after linking).
     */
    public static void linkProgram(int program) {
        ShaderRecord p = PROGRAMS.get(program);
        if (p == null) {
            return;
        }
        for (int sid : p.attached) {
            ShaderRecord s = SHADERS.get(sid);
            if (s == null || s.source == null) {
                continue;
            }
            if (s.type == GL_VERTEX_SHADER) {
                p.vertexSource = s.source;
            } else if (s.type == GL_FRAGMENT_SHADER) {
                p.fragmentSource = s.source;
            } else if (s.type == 0x91B9) {
                p.computeSource = s.source;
            }
        }
        dumpProgramSources(program, p);
    }

    // ------------------------------------------------------------------
    // Phase-A source dump: writes the captured (already Iris-preprocessed,
    // i.e. #include-resolved) GLSL of every linked program to disk when
    // -Dantimony.shaderDump=on. That dump is the ground-truth corpus for the
    // A2 GLSL->CPU compiler; the pack's raw files are thin shells whose real
    // logic lives in included lib/common chunks, so only this view shows what
    // the driver actually compiles. Diagnostic only: never throws outward.
    // ------------------------------------------------------------------
    private static final boolean DUMP_ENABLED =
        "on".equals(System.getProperty("antimony.shaderDump", "off"));
    private static final java.nio.file.Path DUMP_DIR =
        java.nio.file.Path.of(System.getProperty("antimony.shaderDump.dir", "antimony-shader-dump"));
    private static final java.util.Set<Integer> DUMPED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger DUMP_SEQ = new AtomicInteger();

    private static void dumpProgramSources(int program, ShaderRecord p) {
        if (!DUMP_ENABLED || p == null || !DUMPED.add(program)) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(DUMP_DIR);
            int seq = DUMP_SEQ.incrementAndGet();
            if (p.vertexSource != null) {
                java.nio.file.Files.writeString(
                    DUMP_DIR.resolve(String.format("%02d-p%d-vertex.glsl", seq, program)),
                    p.vertexSource);
            }
            if(p.computeSource!=null) {
                java.nio.file.Files.writeString(DUMP_DIR.resolve(String.format("%02d-p%d-compute.glsl",seq,program)),p.computeSource);
            }
            if (p.fragmentSource != null) {
                java.nio.file.Files.writeString(
                    DUMP_DIR.resolve(String.format("%02d-p%d-fragment.glsl", seq, program)),
                    p.fragmentSource);
            }
        } catch (Throwable t) {
            // dump is diagnostic only
        }
    }

    public static void deleteProgram(int id) {
        PROGRAMS.remove(id);
        // Keep uniform location keys: they are stable fake handles; stale
        // entries are bounded by pipeline reloads and harmless.
    }

    public static ShaderRecord shader(int id) {
        return SHADERS.get(id);
    }

    public static ShaderRecord program(int id) {
        return PROGRAMS.get(id);
    }

    /** Query only the linked source. Shader object deletion cannot erase the compute layout. */
    public static int[] computeLocalSize(int program) {
        ShaderRecord record=PROGRAMS.get(program);
        if(record==null||record.computeSource==null)throw new IllegalStateException("No linked compute source for local-size query: "+program);
        return dev.antimony.gl.glsl.GlslCompiler.declaredComputeLocalSize(record.computeSource);
    }

    /** Number of shader compiles captured since launch (diagnostic). */
    public static int compileCount() {
        return compileCounter.get();
    }

    public static void markCompile() {
        compileCounter.incrementAndGet();
    }

    /** Current program's capture as a debug string (used by the audit log). */
    public static String describeProgram(int program) {
        ShaderRecord p = PROGRAMS.get(program);
        if (p == null) {
            return "program " + program + " (no record)";
        }
        String vs = p.vertexSource == null ? "-" : (p.vertexSource.length() + "ch");
        String fs = p.fragmentSource == null ? "-" : (p.fragmentSource.length() + "ch");
        return "program " + program + " vertex=" + vs + " fragment=" + fs
            + " attached=" + p.attached.size();
    }

    // ------------------------------------------------------------------
    // Phase-A3 uniform mirror: the last value Iris uploaded per
    // (program, uniform location). Composite passes set their uniforms from
    // ProgramUniforms right before Program.use() returns, so the executor can
    // read them back by name->location without touching the driver.
    // ------------------------------------------------------------------

    /** One recorded uniform upload. {@code kind} says how to interpret it. */
    public static final class UniformValue {
        public final String kind;   // 1f..4f, 1i..4i, m3, m4
        public final float[] f;     // float payload (matrices: 9 or 16, column-major)
        public final int[] i;       // int payload

        UniformValue(String kind, float[] f, int[] i) {
            this.kind = kind;
            this.f = f;
            this.i = i;
        }
    }

    private static final Map<Long, UniformValue> UNIFORMS = new ConcurrentHashMap<>();

    private static long uniformKey(int program, int location) {
        return ((long) program << 32) | (location & 0xFFFFFFFFL);
    }

    public static void recordUniformF(int program, int location, String kind, float... values) {
        if (location < 0) {
            return;
        }
        UNIFORMS.put(uniformKey(program, location), new UniformValue(kind, values.clone(), new int[0]));
    }

    public static void recordUniformI(int program, int location, String kind, int... values) {
        if (location < 0) {
            return;
        }
        UNIFORMS.put(uniformKey(program, location), new UniformValue(kind, new float[0], values.clone()));
    }

    public static void recordUniformMatrix(int program, int location, String kind, java.nio.FloatBuffer value) {
        if (location < 0 || value == null) {
            return;
        }
        int n = kind.equals("m3") ? 9 : 16;
        float[] m = new float[n];
        int base = value.position();
        for (int i = 0; i < n && base + i < value.limit(); i++) {
            m[i] = value.get(base + i);
        }
        UNIFORMS.put(uniformKey(program, location), new UniformValue(kind, m, new int[0]));
    }

    public static void recordUniformMatrix(int program, int location, String kind, float[] value) {
        if (location < 0 || value == null) {
            return;
        }
        int n = kind.equals("m3") ? 9 : 16;
        float[] m = new float[n];
        System.arraycopy(value, 0, m, 0, Math.min(n, value.length));
        UNIFORMS.put(uniformKey(program, location), new UniformValue(kind, m, new int[0]));
    }

    public static UniformValue uniformValue(int program, int location) {
        return UNIFORMS.get(uniformKey(program, location));
    }

    // ------------------------------------------------------------------
    // B4: capture the uniform uploads Iris makes through blaze3d
    // ------------------------------------------------------------------
    // Iris' uniform objects do not call LWJGL directly; they funnel through
    // com.mojang.blaze3d.systems.RenderSystem.glUniform*/glUniformMatrix*.
    // The coremod only rewrites Iris classes that touch org.lwjgl directly, so
    // before B4 *no* standard uniform ever reached this table and the whole CPU
    // post chain ran on synthetic zeros: no camera matrices, no sun position,
    // no time of day, volumetricDayMixer = 0 (which makes pow(x, 0) = 1 and
    // turns the godrays into a flat 50% wash over every pixel). These helpers
    // are called from RenderSystemMixin at HEAD, i.e. record-only - the real GL
    // upload still runs untouched, so they can never change rendering.

    /** Record {@code comps} floats of a {@code glUniformNf} upload. */
    public static void captureUniformF(int location, String kind, java.nio.FloatBuffer values, int comps) {
        if (location < 0 || values == null) {
            return;
        }
        int base = values.position();
        float[] out = new float[comps];
        for (int i = 0; i < comps && base + i < values.limit(); i++) {
            out[i] = values.get(base + i);
        }
        recordUniformF(currentProgram(), location, kind, out);
    }

    /** Record {@code comps} ints of a {@code glUniformNi} upload. */
    public static void captureUniformI(int location, String kind, java.nio.IntBuffer values, int comps) {
        if (location < 0 || values == null) {
            return;
        }
        int base = values.position();
        int[] out = new int[comps];
        for (int i = 0; i < comps && base + i < values.limit(); i++) {
            out[i] = values.get(base + i);
        }
        recordUniformI(currentProgram(), location, kind, out);
    }

    public static void captureUniform1i(int location, int value) {
        recordUniformI(currentProgram(), location, "1i", value);
    }

    public static void captureUniformMatrix(int location, String kind, java.nio.FloatBuffer values) {
        recordUniformMatrix(currentProgram(), location, kind, values);
    }

    /** The texture object bound to {@code unit} (0 when nothing is bound). */
    public static int unitTexture(int unit) {
        return unit >= 0 && unit < UNIT_TEXTURES.length ? UNIT_TEXTURES[unit] : 0;
    }

    /** GL_CURRENT_PROGRAM */
    private static final int GL_CURRENT_PROGRAM = 0x8B86;

    public static void useProgram(int program) {
        CURRENT_PROGRAM.set(program);
    }

    public static int currentProgram() {
        return CURRENT_PROGRAM.get();
    }

    private record ProgramName(int program, String name) { }
    private static final Map<ProgramName,Integer> NATIVE_LOCATIONS=new ConcurrentHashMap<>();
    private record TextureParameter(int texture,int parameter) { }
    private static final Map<TextureParameter,Integer> TEXTURE_PARAMETERS=new ConcurrentHashMap<>();
    public static void observeUniformLocation(int program,String name,int location){NATIVE_LOCATIONS.put(new ProgramName(program,name),location);}
    public static Integer observedUniformLocation(int program,String name){return NATIVE_LOCATIONS.get(new ProgramName(program,name));}
    public static void textureParameter(int texture,int parameter,int value){if(texture>0)TEXTURE_PARAMETERS.put(new TextureParameter(texture,parameter),value);}
    public static int textureParameter(int texture,int parameter){return TEXTURE_PARAMETERS.getOrDefault(new TextureParameter(texture,parameter),-1);}
    private record SamplerParameter(int sampler,int parameter){}
    private static final Map<SamplerParameter,Integer> SAMPLER_PARAMETERS=new ConcurrentHashMap<>();
    public static void samplerParameter(int sampler,int parameter,int value){if(sampler>0)SAMPLER_PARAMETERS.put(new SamplerParameter(sampler,parameter),value);}
    public static int samplerParameter(int sampler,int parameter){return SAMPLER_PARAMETERS.getOrDefault(new SamplerParameter(sampler,parameter),-1);}
    public static int unitSampler(int unit){return unit>=0&&unit<UNIT_SAMPLERS.length?UNIT_SAMPLERS[unit]:0;}

    /** Stable fake uniform location per exact (program, name) pair. */
    public static int uniformLocation(int program, String name) {
        return LOCATIONS.computeIfAbsent(new ProgramName(program, name), k -> uniformCounter.incrementAndGet());
    }

    public static int attribLocation(int program, String name) {
        return ATTRIB_LOCATIONS.computeIfAbsent(new ProgramName(program, name), k -> attribCounter.incrementAndGet());
    }

    private static final AtomicInteger uniformCounter = new AtomicInteger();
    private static final AtomicInteger attribCounter = new AtomicInteger();
    private static final Map<ProgramName, Integer> LOCATIONS = new ConcurrentHashMap<>();
    private static final Map<ProgramName, Integer> ATTRIB_LOCATIONS = new ConcurrentHashMap<>();

    public static void setCap(int cap, boolean enabled) {
        CAPS.put(cap, enabled ? 1 : 0);
    }

    public static int getCap(int cap) {
        return CAPS.getOrDefault(cap, 0);
    }

    public static void bindTexture(int target, int texture) {
        if (activeUnit >= 0 && activeUnit < UNIT_TEXTURES.length) {
            UNIT_TEXTURES[activeUnit] = texture;
            UNIT_STAMPS[activeUnit] = ++STAMP;
        }
    }

    public static void bindTextureUnit(int unit, int texture) {
        if (unit >= 0 && unit < UNIT_TEXTURES.length) {
            UNIT_TEXTURES[unit] = texture;
            UNIT_STAMPS[unit] = ++STAMP;
        }
    }

    /**
     * Monotonic stamp of the last texture bind per unit. The A3 executor uses
     * it to tell which unit a pass' sampler was actually bound to when several
     * units happen to reference the same texture object.
     */
    private static final long[] UNIT_STAMPS = new long[16];
    private static long STAMP;

    public static long unitStamp(int unit) {
        return unit >= 0 && unit < UNIT_STAMPS.length ? UNIT_STAMPS[unit] : 0L;
    }

    public static void bindSampler(int unit, int sampler) {
        if (unit >= 0 && unit < UNIT_SAMPLERS.length) {
            UNIT_SAMPLERS[unit] = sampler;
        }
    }

    public static void activeTexture(int textureUnit) {
        // GL_TEXTURE0 = 0x84C0
        int unit = textureUnit - 0x84C0;
        if (unit >= 0 && unit < UNIT_TEXTURES.length) {
            activeUnit = unit;
        }
    }

    public static int activeUnitTexture() {
        return UNIT_TEXTURES[Math.max(0, Math.min(activeUnit, UNIT_TEXTURES.length - 1))];
    }

    public static void bindBuffer(int target, int buffer) {
        // GL_ARRAY_BUFFER = 0x8892, GL_ELEMENT_ARRAY_BUFFER = 0x8893
        if (target == 0x8892) {
            boundBufferArray = buffer;
        } else if (target == 0x8893) {
            boundBufferElement = buffer;
        }
    }

    /** Core getInteger set Iris actually queries while the pipeline is up. */
    public static int getInteger(int pname) {
        // GL_CURRENT_PROGRAM
        if (pname == GL_CURRENT_PROGRAM) {
            return CURRENT_PROGRAM.get();
        }
        return getIntegerIndexed(pname, 0);
    }

    public static int getIntegerIndexed(int pname, int index) {
        // Reasonable limit constants so Iris' sizing loops terminate quickly:
        // GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS = 0x8B4D -> 16
        // GL_MAX_TEXTURE_IMAGE_UNITS = 0x8872 -> 16
        // GL_MAX_TEXTURE_SIZE = 0x0D33 -> 2048
        return switch (pname) {
            case 0x8B4D, 0x8872 -> 16;
            case 0x0D33 -> 2048;
            case 0x8B86 -> CURRENT_PROGRAM.get();
            case 0x8069 -> UNIT_TEXTURES.length; // GL_MAX_ARRAY_TEXTURE_LAYERS fallback
            default -> 0;
        };
    }

    public static int getTexParam(int target, int pname) {
        return 0;
    }
}
