package dev.antimony.gl.shim;

import dev.antimony.gl.GlCompat;
import dev.antimony.core.render.IrisGlRenderer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CPU stand-in for the LWJGL OpenGL methods mods call directly (phase 6).
 *
 * <p>The phase-6 coremod rewrites {@code INVOKESTATIC org/lwjgl/opengl/GLxx.glYyy}
 * inside mod classes (Iris 1.8.14: 85 classes, signatures in {@code
 * build/iris-gl-signatures.txt}) into calls to this class with the identical
 * signature. Behaviour is mode-dependent:</p>
 *
 * <ul>
 *   <li><b>Not intercepting</b> (report/off, renderer failed, title screen):
 *       forward to the real LWJGL method, byte-for-byte the vanilla session.</li>
 *   <li><b>Intercepting</b> ({@code glcompat=on} and the software renderer owns
 *       the frame): answer from CPU state - object ids from a private counter,
 *       compiles fake success, binds/uniforms recorded, queries consistent,
 *       everything else a counted no-op. The driver never sees the call.</li>
 * </ul>
 *
 * <p>Forwarding uses the highest GL facade that exposes the call (GL46C covers
 * all core up to 4.6; DSA/ARB entry points stay on their extension classes).
 * The forwarding branches are deliberately written as plain static calls so a
 * missing facade fails the compile, not the user's session.</p>
 */
public final class GlShim {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Antimony");
    private static final java.util.concurrent.atomic.AtomicBoolean LOGGED_FIRST_CAPTURE =
        new java.util.concurrent.atomic.AtomicBoolean();
    private GlShim() {
    }

    /** True when rewritten calls must be answered on the CPU. */
    private static boolean absorbing() {
        return GlCompat.intercepting() && IrisGlRenderer.shouldReplace();
    }

    /** glcompat=on switch (session-wide), independent of frame-level takeover. */
    private static boolean intercepting() {
        return GlCompat.intercepting();
    }

    // ---- counters & registries (CPU-side object model) --------------------

    private static final AtomicInteger NEXT_ID = new AtomicInteger(100_000_000);
    /** GL_TRUE = 1 in glGetShaderi/glGetProgrami queries. */
    private static final int GL_TRUE = 1;
    /** GL_COMPILE_STATUS = 0x8B81, GL_LINK_STATUS = 0x8B82, GL_CURRENT_PROGRAM = 0x8B86. */
    private static final int GL_COMPILE_STATUS = 0x8B81;
    private static final int GL_LINK_STATUS = 0x8B82;
    private static final int GL_CURRENT_PROGRAM = 0x8B86;
    /** GL_FRAMEBUFFER = 0x8D40; GL_FRAMEBUFFER_COMPLETE = 0x8CD5. */
    private static final int GL_FRAMEBUFFER = 0x8D40;
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;

    private static int nextId() {
        return NEXT_ID.incrementAndGet();
    }

    private static int fakeProgramId() {
        return nextId();
    }

    private static int fakeShaderId() {
        return nextId();
    }

    private static int fakeBufferId() {
        return nextId();
    }

    private static int fakeFramebufferId() {
        return nextId();
    }

    private static int fakeTextureId() {
        return nextId();
    }

    private static int fakeSamplerId() {
        return nextId();
    }

    // ---- shader / program objects ------------------------------------------
    // Dual-path lifecycle: while glcompat=on (intercepting), lifecycle calls
    // STILL go to the real driver (native rendering keeps working) and are
    // SIMULTANEOUSLY recorded into GlShimState, because pipeline creation
    // happens before the frame-level takeover (shouldReplace) turns on - the
    // registry must fill during setup. While absorbing (frame takeover),
    // newly created objects would become CPU-only fake ids; source capture in
    // nglShaderSource is gate-free because the GLSL text is the input to the
    // CPU post pipeline (docs section 19) and costs nothing to keep.

    public static int glCreateProgram() {
        int id = org.lwjgl.opengl.GL20.glCreateProgram();
        if (intercepting()) {
            GlShimState.createProgram(id);
        }
        return id;
    }

    public static void glDeleteProgram(int program) {
        org.lwjgl.opengl.GL20.glDeleteProgram(program);
        if (intercepting()) {
            GlShimState.deleteProgram(program);
        }
    }

    public static int glCreateShader(int type) {
        int id = org.lwjgl.opengl.GL20.glCreateShader(type);
        if (intercepting()) {
            GlShimState.createShader(id, type);
        }
        return id;
    }

    public static void glDeleteShader(int shader) {
        org.lwjgl.opengl.GL20.glDeleteShader(shader);
        if (intercepting()) {
            GlShimState.deleteShader(shader);
        }
    }

    /**
     * Iris reaches the source through ShaderWorkarounds.safeShaderSource, which
     * calls GL20C.nglShaderSource(IIJJ) directly - the only ngl* call site in
     * the pack-facing classes, so the JS rewrite must accept the "ngl" prefix
     * for this name. Descriptor must match the call site exactly: (IIJJ)V.
     * The strings pointer is a NUL-terminated array of one pointer to a
     * NUL-terminated UTF-8 chunk. Capture is gate-free: recording the GLSL
     * text costs nothing and must not depend on frame-level takeover state
     * (pipeline creation runs before takeover).
     */
    public static void nglShaderSource(int shader, int count, long strings, long lengths) {
        String source = null;
        try {
            if (strings != 0 && count > 0) {
                long ptr = org.lwjgl.system.MemoryUtil.memGetAddress(strings);
                if (ptr != 0) {
                    source = org.lwjgl.system.MemoryUtil.memUTF8(ptr);
                }
            }
        } catch (Throwable t) {
            source = null;
        }
        org.lwjgl.opengl.GL20.nglShaderSource(shader, count, strings, lengths);
        if (intercepting()) {
            GlShimState.setShaderSource(shader, source);
            GlShimState.markCompile();
            if (LOGGED_FIRST_CAPTURE.compareAndSet(false, true)) {
                LOGGER.info("[Antimony] first GLSL source captured: shader#{} {} chars (driver compile still native)",
                    shader, source == null ? -1 : source.length());
            }
        }
    }

    public static void glCompileShader(int shader) {
        if (!intercepting()) {
            org.lwjgl.opengl.GL20.glCompileShader(shader);
        }
        // Absorbing mode: fake compile always succeeds (glGetShaderi answers
        // GL_TRUE); intercepting mode: the real compile already ran above.
    }

    public static void glAttachShader(int program, int shader) {
        if (!intercepting()) {
            org.lwjgl.opengl.GL20.glAttachShader(program, shader);
        }
        GlShimState.attachShader(program, shader);
    }

    public static void glDetachShader(int program, int shader) {
        if (!intercepting()) {
            org.lwjgl.opengl.GL20.glDetachShader(program, shader);
        }
        GlShimState.detachShader(program, shader);
    }

    public static void glLinkProgram(int program) {
        if (!intercepting()) {
            org.lwjgl.opengl.GL20.glLinkProgram(program);
        }
        GlShimState.linkProgram(program);
    }

    public static int glGetShaderi(int shader, int pname) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL20.glGetShaderi(shader, pname);
        }
        return pname == GL_COMPILE_STATUS ? GL_TRUE : 0;
    }

    public static String glGetShaderInfoLog(int shader) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL20.glGetShaderInfoLog(shader);
        }
        return "";
    }

    public static int glGetProgrami(int program, int pname) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL20.glGetProgrami(program, pname);
        }
        return pname == GL_LINK_STATUS ? GL_TRUE : 0;
    }

    public static void glGetProgramiv(int program, int pname, int[] params) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glGetProgramiv(program, pname, params);
            return;
        }
        if(pname==0x8267){
            if(params==null||params.length<3)throw new IllegalArgumentException("Compute local-size query needs three components");
            int[] size=GlShimState.computeLocalSize(program);System.arraycopy(size,0,params,0,3);return;
        }
        if (params != null && params.length > 0) {
            params[0] = glGetProgrami(program, pname);
        }
    }

    public static String glGetProgramInfoLog(int program) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL20.glGetProgramInfoLog(program);
        }
        return "";
    }

    public static void glUseProgram(int program) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glUseProgram(program);
            return;
        }
        GlShimState.useProgram(program);
    }

    public static int glGetUniformLocation(int program, CharSequence name) {
        if (!absorbing()) {
            int location=org.lwjgl.opengl.GL20.glGetUniformLocation(program, name);
            if(intercepting())GlShimState.observeUniformLocation(program,name.toString(),location);
            return location;
        }
        // Stable per (program, name) fake location so Iris' caching by location
        // stays consistent across frames.
        return GlShimState.uniformLocation(program, name.toString());
    }

    public static int glGetAttribLocation(int program, CharSequence name) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL20.glGetAttribLocation(program, name);
        }
        return GlShimState.attribLocation(program, name.toString());
    }

    public static void glBindAttribLocation(int program, int index, CharSequence name) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glBindAttribLocation(program, index, name);
        }
    }

    public static String glGetActiveUniform(int program, int index, int maxLength, java.nio.IntBuffer length, java.nio.IntBuffer size, java.nio.IntBuffer type) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL20.glGetActiveUniform(program, index, length, size);
        }
        // Iris only walks active uniforms to build its cache; an empty set is
        // consistent with "no GPU uniforms exist on the CPU side".
        return "";
    }

    // LWJGL convenience overload: glGetActiveUniform(program, index, length, size)
    // -> String (type buffer omitted). Same absorption semantics as the 3-variant.
    public static String glGetActiveUniform(int program, int index, java.nio.IntBuffer length, java.nio.IntBuffer size) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL20.glGetActiveUniform(program, index, length, size);
        }
        return "";
    }

    // LWJGL convenience overload with maxLength hint: (program, index, maxLength,
    // length, size) -> String. maxLength only bounds the returned name; the
    // String-returning forward ignores it.
    public static String glGetActiveUniform(int program, int index, int maxLength, java.nio.IntBuffer length, java.nio.IntBuffer size) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL20.glGetActiveUniform(program, index, length, size);
        }
        return "";
    }

    // Uniform setters are the phase-A3 measurement point: Iris uploads every
    // composite-pass uniform through IrisRenderSystem -> GL32C -> here, so
    // recording the value here gives the CPU pass executor exactly what the
    // driver would have received (values equal to zero are never uploaded -
    // the generated fields default to zero, so that case is already correct).

    public static void glUniform1f(int location, float v0) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glUniform1f(location, v0);
        }
        if (intercepting()) {
            GlShimState.recordUniformF(GlShimState.currentProgram(), location, "1f", v0);
        }
    }

    public static void glUniform1i(int location, int v0) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glUniform1i(location, v0);
        }
        if (intercepting()) {
            GlShimState.recordUniformI(GlShimState.currentProgram(), location, "1i", v0);
        }
    }

    public static void glUniform2f(int location, float v0, float v1) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glUniform2f(location, v0, v1);
        }
        if (intercepting()) {
            GlShimState.recordUniformF(GlShimState.currentProgram(), location, "2f", v0, v1);
        }
    }

    public static void glUniform2i(int location, int v0, int v1) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glUniform2i(location, v0, v1);
        }
        if (intercepting()) {
            GlShimState.recordUniformI(GlShimState.currentProgram(), location, "2i", v0, v1);
        }
    }

    public static void glUniform3f(int location, float v0, float v1, float v2) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glUniform3f(location, v0, v1, v2);
        }
        if (intercepting()) {
            GlShimState.recordUniformF(GlShimState.currentProgram(), location, "3f", v0, v1, v2);
        }
    }

    public static void glUniform3i(int location, int v0, int v1, int v2) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glUniform3i(location, v0, v1, v2);
        }
        if (intercepting()) {
            GlShimState.recordUniformI(GlShimState.currentProgram(), location, "3i", v0, v1, v2);
        }
    }

    public static void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glUniform4f(location, v0, v1, v2, v3);
        }
        if (intercepting()) {
            GlShimState.recordUniformF(GlShimState.currentProgram(), location, "4f", v0, v1, v2, v3);
        }
    }

    public static void glUniform4i(int location, int v0, int v1, int v2, int v3) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glUniform4i(location, v0, v1, v2, v3);
        }
        if (intercepting()) {
            GlShimState.recordUniformI(GlShimState.currentProgram(), location, "4i", v0, v1, v2, v3);
        }
    }

    public static void glUniformMatrix3fv(int location, boolean transpose, java.nio.FloatBuffer value) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glUniformMatrix3fv(location, transpose, value);
        }
        if (intercepting()) {
            GlShimState.recordUniformMatrix(GlShimState.currentProgram(), location, "m3", value);
        }
    }

    public static void glUniformMatrix4fv(int location, boolean transpose, float[] value) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glUniformMatrix4fv(location, transpose, value);
        }
        if (intercepting()) {
            GlShimState.recordUniformMatrix(GlShimState.currentProgram(), location, "m4", value);
        }
    }

    public static void glUniformMatrix4fv(int location, boolean transpose, java.nio.FloatBuffer value) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glUniformMatrix4fv(location, transpose, value);
        }
        if (intercepting()) {
            GlShimState.recordUniformMatrix(GlShimState.currentProgram(), location, "m4", value);
        }
    }

    public static int glGetUniformBlockIndex(int program, CharSequence uniformBlockName) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL32.glGetUniformBlockIndex(program, uniformBlockName);
        }
        // GL_INVALID_INDEX = 0xFFFFFFFF: "no such block" keeps block-binding
        // setup loops short-circuited in Iris.
        return 0xFFFFFFFF;
    }

    public static void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL32.glUniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding);
        }
    }

    // ---- buffers ------------------------------------------------------------

    public static int glGenBuffers() {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL15.glGenBuffers();
        }
        return fakeBufferId();
    }

    public static void glGenBuffers(int[] buffers) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL15.glGenBuffers(buffers);
            return;
        }
        if (buffers != null) {
            for (int i = 0; i < buffers.length; i++) {
                buffers[i] = fakeBufferId();
            }
        }
    }

    public static int glCreateBuffers() {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL45.glCreateBuffers();
        }
        return fakeBufferId();
    }

    public static void glCreateBuffers(int[] buffers) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL45.glCreateBuffers(buffers);
            return;
        }
        if (buffers != null) {
            for (int i = 0; i < buffers.length; i++) {
                buffers[i] = fakeBufferId();
            }
        }
    }

    // DSA: single-texture creation. Mirror of the buffer variant.
    public static int glCreateTextures(int target) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL45.glCreateTextures(target);
        }
        return fakeTextureId();
    }

    public static void glDeleteBuffers(int buffer) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL15.glDeleteBuffers(buffer);
        }
    }

    public static void glBindBuffer(int target, int buffer) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL15.glBindBuffer(target, buffer);
            return;
        }
        GlShimState.bindBuffer(target, buffer);
    }

    public static void glBufferData(int target, float[] data, int usage) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL15.glBufferData(target, data, usage);
        }
    }

    public static void glBufferStorage(int target, long data, int flags) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL44.glBufferStorage(target, data, flags);
        }
    }

    public static void glBufferSubData(int target, long offset, java.nio.ByteBuffer data) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL15.glBufferSubData(target, offset, data);
        }
    }

    public static void glNamedBufferData(int buffer, float[] data, int usage) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL45.glNamedBufferData(buffer, data, usage);
        }
    }

    public static void glBindBufferBase(int target, int index, int buffer) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL30.glBindBufferBase(target, index, buffer);
        }
    }

    public static void glClearBufferSubData(int target, int internalformat, long offset, long size, int format, int type, int[] data) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL43.glClearBufferSubData(target, internalformat, offset, size, format, type, data);
        }
    }

    public static int glGetInteger(int pname, int index) {
        if (!absorbing()) {
            int[] out = new int[1];
            org.lwjgl.opengl.GL30.glGetIntegeri_v(pname, index, out);
            return out[0];
        }
        return GlShimState.getIntegerIndexed(pname, index);
    }

    // ---- textures & samplers --------------------------------------------------

    public static void glBindTexture(int target, int texture) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glBindTexture(target, texture);
        }
        if (intercepting()) {
            GlShimState.bindTexture(target, texture);
        }
    }

    public static void glBindTextureUnit(int unit, int texture) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL45.glBindTextureUnit(unit, texture);
        }
        if (intercepting()) {
            GlShimState.bindTextureUnit(unit, texture);
        }
    }

    public static void glTexParameteri(int target, int pname, int param) {
        if(intercepting())GlShimState.textureParameter(GlShimState.activeUnitTexture(),pname,param);
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glTexParameteri(target, pname, param);
        }
    }

    public static void glTexParameterf(int target, int pname, float param) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glTexParameterf(target, pname, param);
        }
    }

    public static void glTexParameteriv(int target, int pname, int[] params) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glTexParameteriv(target, pname, params);
        }
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, java.nio.ByteBuffer pixels) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        }
    }

    // 1D texture upload (Iris standard-macro probes use it for LUT textures).
    public static void glTexImage1D(int target, int level, int internalformat, int width, int border, int format, int type, java.nio.ByteBuffer pixels) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glTexImage1D(target, level, internalformat, width, border, format, type, pixels);
        }
    }

    public static void glTexImage3D(int target, int level, int internalformat, int width, int height, int depth, int border, int format, int type, java.nio.ByteBuffer pixels) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL12.glTexImage3D(target, level, internalformat, width, height, depth, border, format, type, pixels);
        }
    }

    public static void glCopyTexImage2D(int target, int level, int internalformat, int x, int y, int width, int height, int border) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glCopyTexImage2D(target, level, internalformat, x, y, width, height, border);
        }
    }

    public static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
        }
    }

    public static void glCopyTextureSubImage2D(int texture, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL45.glCopyTextureSubImage2D(texture, level, xoffset, yoffset, x, y, width, height);
        }
    }

    public static void glGenerateMipmap(int target) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL30.glGenerateMipmap(target);
        }
    }

    public static void glGenerateTextureMipmap(int texture) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL45.glGenerateTextureMipmap(texture);
        }
    }

    // DSA per-texture parameter setters (GL45 facade). No-op while absorbing:
    // texture state lives in the CPU mirror, not in a real GL object.
    public static void glTextureParameterf(int texture, int pname, float param) {
        if (intercepting()) GlShimState.textureParameter(texture, pname, Math.round(param));
        if (!absorbing()) {
            org.lwjgl.opengl.GL45.glTextureParameterf(texture, pname, param);
        }
    }

    public static void glTextureParameteri(int texture, int pname, int param) {
        if(intercepting())GlShimState.textureParameter(texture,pname,param);
        if (!absorbing()) {
            org.lwjgl.opengl.GL45.glTextureParameteri(texture, pname, param);
        }
    }

    public static void glTextureParameteriv(int texture, int pname, int[] params) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL45.glTextureParameteriv(texture, pname, params);
        }
    }

    public static int glGenSamplers(int amount) {
        if (!absorbing()) {
            int[] out = new int[amount];
            org.lwjgl.opengl.GL33.glGenSamplers(out);
            return out.length > 0 ? out[0] : 0;
        }
        return fakeSamplerId();
    }

    // LWJGL convenience overload: single sampler id.
    public static int glGenSamplers() {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL33.glGenSamplers();
        }
        return fakeSamplerId();
    }

    public static void glDeleteSamplers(int sampler) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL33.glDeleteSamplers(sampler);
        }
    }

    public static void glBindSampler(int unit, int sampler) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL33.glBindSampler(unit, sampler);
        }
        if (intercepting()) {
            GlShimState.bindSampler(unit, sampler);
        }
    }

    public static void glBindSamplers(int first, int[] samplers) {
        if (!absorbing()) {
            org.lwjgl.opengl.ARBMultiBind.glBindSamplers(first, samplers);
        }
    }

    public static void glSamplerParameteri(int sampler, int pname, int param) {
        if(intercepting())GlShimState.samplerParameter(sampler,pname,param);
        if (!absorbing()) {
            org.lwjgl.opengl.GL33.glSamplerParameteri(sampler, pname, param);
        }
    }

    public static void glSamplerParameterf(int sampler, int pname, float param) {
        if(intercepting())GlShimState.samplerParameter(sampler,pname,Math.round(param));
        if (!absorbing()) {
            org.lwjgl.opengl.GL33.glSamplerParameterf(sampler, pname, param);
        }
    }

    public static void glSamplerParameteriv(int sampler, int pname, int[] params) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL33.glSamplerParameteriv(sampler, pname, params);
        }
    }

    public static int glGetTextureParameteri(int texture, int pname) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL45.glGetTextureParameteri(texture, pname);
        }
        return 0;
    }

    public static int glGetTexParameteri(int target, int pname) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL11.glGetTexParameteri(target, pname);
        }
        return GlShimState.getTexParam(target, pname);
    }

    public static void glClearTexImage(int texture, int level, int format, int type, int[] data) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL44.glClearTexImage(texture, level, format, type, data);
        }
    }

    // ---- framebuffers ----------------------------------------------------------

    public static int glCreateFramebuffers() {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL45.glCreateFramebuffers();
        }
        return fakeFramebufferId();
    }

    public static void glGenFramebuffers(int[] framebuffers) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL30.glGenFramebuffers(framebuffers);
            return;
        }
        if (framebuffers != null) {
            for (int i = 0; i < framebuffers.length; i++) {
                framebuffers[i] = fakeFramebufferId();
            }
        }
    }

    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(target, attachment, textarget, texture, level);
        }
    }

    public static void glNamedFramebufferTexture(int framebuffer, int attachment, int texture, int level) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL45.glNamedFramebufferTexture(framebuffer, attachment, texture, level);
        }
    }

    public static void glNamedFramebufferDrawBuffers(int framebuffer, int[] bufs) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL45.glNamedFramebufferDrawBuffers(framebuffer, bufs);
        }
    }

    public static void glNamedFramebufferReadBuffer(int framebuffer, int mode) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL45.glNamedFramebufferReadBuffer(framebuffer, mode);
        }
    }

    public static int glCheckFramebufferStatus(int target) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL30.glCheckFramebufferStatus(target);
        }
        return target == GL_FRAMEBUFFER ? GL_FRAMEBUFFER_COMPLETE : 0;
    }

    public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL30.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        }
    }

    public static void glBlitNamedFramebuffer(int readFramebuffer, int drawFramebuffer, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL45.glBlitNamedFramebuffer(readFramebuffer, drawFramebuffer, srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        }
    }

    public static void glCopyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ, int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ, int srcWidth, int srcHeight, int srcDepth) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL43.glCopyImageSubData(srcName, srcTarget, srcLevel, srcX, srcY, srcZ, dstName, dstTarget, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, srcDepth);
        }
    }

    public static void glDrawBuffers(int[] bufs) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glDrawBuffers(bufs);
        }
    }

    public static void glReadBuffer(int mode) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glReadBuffer(mode);
        }
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, float[] pixels) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glReadPixels(x, y, width, height, format, type, pixels);
        }
    }

    // ---- state / caps / misc -----------------------------------------------------

    public static void glEnable(int cap) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glEnable(cap);
            return;
        }
        GlShimState.setCap(cap, true);
    }

    public static void glDisable(int cap) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glDisable(cap);
            return;
        }
        GlShimState.setCap(cap, false);
    }

    public static void glEnablei(int cap, int index) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL30.glEnablei(cap, index);
        }
    }

    public static void glDisablei(int cap, int index) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL30.glDisablei(cap, index);
        }
    }

    public static void glActiveTexture(int texture) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL13.glActiveTexture(texture);
        }
        if (intercepting()) {
            GlShimState.activeTexture(texture);
        }
    }

    public static void glBlendFuncSeparateiARB(int buf, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        if (!absorbing()) {
            org.lwjgl.opengl.ARBDrawBuffersBlend.glBlendFuncSeparateiARB(buf, srcRGB, dstRGB, srcAlpha, dstAlpha);
        }
    }

    public static void glPolygonMode(int face, int mode) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glPolygonMode(face, mode);
        }
    }

    public static void glClear(int mask) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glClear(mask);
        }
    }

    public static int glGetInteger(int pname) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL11.glGetInteger(pname);
        }
        return GlShimState.getInteger(pname);
    }

    public static void glGetIntegerv(int pname, int[] params) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glGetIntegerv(pname, params);
            return;
        }
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                params[i] = GlShimState.getIntegerIndexed(pname, i);
            }
        }
    }

    public static void glGetFloatv(int pname, float[] params) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glGetFloatv(pname, params);
        }
    }

    public static String glGetStringi(int name, int index) {
        if (!absorbing()) {
            return org.lwjgl.opengl.GL30.glGetStringi(name, index);
        }
        return "";
    }

    public static void glEnableVertexAttribArray(int index) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glEnableVertexAttribArray(index);
        }
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
        }
    }

    public static void glVertexAttrib4f(int index, float x, float y, float z, float w) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL20.glVertexAttrib4f(index, x, y, z, w);
        }
    }

    public static void glDrawElements(int mode, int count, int type, long indices) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL11.glDrawElements(mode, count, type, indices);
        }
    }

    // ---- compute / images / SSBO ----------------------------------------------------

    public static void glDispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL43.glDispatchCompute(numGroupsX, numGroupsY, numGroupsZ);
        }
    }

    public static void glDispatchComputeIndirect(long indirect) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL43.glDispatchComputeIndirect(indirect);
        }
    }

    public static void glBindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL42.glBindImageTexture(unit, texture, level, layered, layer, access, format);
        }
    }

    public static void glBindImageTextureEXT(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        glBindImageTexture(unit, texture, level, layered, layer, access, format);
    }

    public static void glMemoryBarrier(int barriers) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL42.glMemoryBarrier(barriers);
        }
    }

    public static void glBindBufferBase0(int target, int index, int buffer) {
        // never generated; placeholder removed
    }

    // ---- debug output --------------------------------------------------------------

    public static void glPushDebugGroup(int source, int id, CharSequence message) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL43.glPushDebugGroup(source, id, message);
        }
    }

    public static void glPopDebugGroup() {
        if (!absorbing()) {
            org.lwjgl.opengl.GL43.glPopDebugGroup();
        }
    }

    public static void glObjectLabel(int identifier, int name, CharSequence label) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL43.glObjectLabel(identifier, name, label);
        }
    }

    public static void glDebugMessageCallback(org.lwjgl.opengl.GLDebugMessageCallbackI callback, long userParam) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL43.glDebugMessageCallback(callback, userParam);
        }
    }

    public static void glDebugMessageControl(int source, int type, int severity, int[] ids, boolean enabled) {
        if (!absorbing()) {
            org.lwjgl.opengl.GL43.glDebugMessageControl(source, type, severity, ids, enabled);
        }
    }

    public static void glDebugMessageCallbackARB(org.lwjgl.opengl.GLDebugMessageARBCallbackI callback, long userParam) {
        if (!absorbing()) {
            org.lwjgl.opengl.ARBDebugOutput.glDebugMessageCallbackARB(callback, userParam);
        }
    }

    public static void glDebugMessageControlARB(int source, int type, int severity, int[] ids, boolean enabled) {
        if (!absorbing()) {
            org.lwjgl.opengl.ARBDebugOutput.glDebugMessageControlARB(source, type, severity, ids, enabled);
        }
    }

    public static void glDebugMessageCallbackAMD(org.lwjgl.opengl.GLDebugMessageAMDCallbackI callback, long userParam) {
        if (!absorbing()) {
            org.lwjgl.opengl.AMDDebugOutput.glDebugMessageCallbackAMD(callback, userParam);
        }
    }

    public static void glDebugMessageEnableAMD(int source, int type, int[] ids, boolean enabled) {
        if (!absorbing()) {
            org.lwjgl.opengl.AMDDebugOutput.glDebugMessageEnableAMD(source, type, ids, enabled);
        }
    }

    /** Compatibility accounting for the coverage report. */
    public static long shimmedCalls() {
        return SHIMMED.get();
    }

    private static final AtomicLong SHIMMED = new AtomicLong();
}
