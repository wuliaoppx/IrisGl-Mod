package dev.antimony.gl.glsl;

import dev.antimony.AntimonyMod;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiles a captured Iris fragment program to a live {@link GlslProgram}
 * instance at runtime (phase A3).
 *
 * <p>A2 translates GLSL to Java source offline in {@code GlslSelfTest}; the
 * post-processing chain needs the same translation <em>inside the game</em>,
 * because which programs exist is decided by the shader pack that is loaded
 * when the world starts. The game runs on a full JDK (Zulu 21 ships javac), so
 * {@code javax.tools} compiles the generated source once per program id and a
 * private class loader links it against this mod's classes.</p>
 *
 * <p>Failure is never fatal: a program that does not translate/compile is
 * reported once and the pass that wanted it is skipped.</p>
 */
public final class GlslProgramLoader {
    /** One translated + compiled program plus the metadata the executor needs. */
    public static final class Loaded {
        public final GlslProgram program;
        public final GlslCompiler.Result meta;
        public final String className;

        Loaded(GlslProgram program, GlslCompiler.Result meta, String className) {
            this.program = program;
            this.meta = meta;
            this.className = className;
        }
    }

    private static final Map<Integer, Loaded> CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, String> FAILURES = new ConcurrentHashMap<>();
    private static final Map<Integer, String> SOURCE_KEYS = new ConcurrentHashMap<>();
    /** B1: vertex-stage classes keyed by the same program id. */
    private static final Map<Integer, Loaded> VCACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, String> VFAILURES = new ConcurrentHashMap<>();
    private static final Map<Integer, String> VSOURCE_KEYS = new ConcurrentHashMap<>();
    private static final Map<Integer,String> CSOURCES=new ConcurrentHashMap<>(),CFAILURES=new ConcurrentHashMap<>();
    private static final Map<Integer,Loaded> CCACHE=new ConcurrentHashMap<>();
    private static Path workDir;
    private static URLClassLoader classLoader;
    private static boolean loggedCompiler;
    private static final List<Path> COMPILED_DIRS = new ArrayList<>();

    private GlslProgramLoader() {
    }

    /** Drop every compiled program (shader pack reload). */
    public static void reset() {
        CACHE.clear();
        FAILURES.clear();
        SOURCE_KEYS.clear();
        VCACHE.clear();
        VFAILURES.clear();
        VSOURCE_KEYS.clear();
        CSOURCES.clear();CFAILURES.clear();CCACHE.clear();
        classLoader = null;
    }

    public static String failure(int programId) {
        return FAILURES.get(programId);
    }

    public static String vertexFailure(int programId) {
        return VFAILURES.get(programId);
    }

    /**
     * Translate + compile {@code fragmentSource} for {@code programId}.
     * Returns null when the program cannot be translated or compiled; the
     * reason is then available from {@link #failure(int)}.
     */
    public static synchronized Loaded load(int programId, String fragmentSource, String vertexSource) {
        if (fragmentSource == null || fragmentSource.isBlank()) {
            CACHE.remove(programId); FAILURES.remove(programId); SOURCE_KEYS.remove(programId);
            return null;
        }
        String key = sourcePairKey(fragmentSource,vertexSource==null?"":vertexSource);
        Loaded cached = CACHE.get(programId);
        if (cached != null && key.equals(SOURCE_KEYS.get(programId))) {
            return cached;
        }
        String failure = FAILURES.get(programId);
        if (failure != null && key.equals(SOURCE_KEYS.get(programId))) {
            return null;
        }
        SOURCE_KEYS.put(programId, key);
        FAILURES.remove(programId);
        CACHE.remove(programId);

        String className = "Post" + programId;
        GlslCompiler.Result meta;
        try {
            GlslCompiler compiler = new GlslCompiler();
            meta = compiler.translate(className, fragmentSource, vertexSource == null ? "" : vertexSource);
        } catch (Throwable t) {
            String reason = "translate: " + t.getMessage();
            FAILURES.put(programId, reason);
            AntimonyMod.LOG.warn("[Antimony] post program {} not translated ({})", programId, reason);
            return null;
        }

        try {
            GlslProgram program = compile(programId, className, meta);
            Loaded loaded = new Loaded(program, meta, className);
            CACHE.put(programId, loaded);
            AntimonyMod.LOG.info("[Antimony] post program {} compiled on CPU: {} samplers={} outs={} varyings={}",
                programId, className, meta.samplerUnits, meta.outSlots, meta.varyingIns);
            return loaded;
        } catch (Throwable t) {
            String reason = "compile: " + t;
            FAILURES.put(programId, reason);
            AntimonyMod.LOG.warn("[Antimony] post program {} not compiled: {}", programId, t.toString());
            return null;
        }
    }

    /**
     * B1: translate + compile the vertex stage of {@code programId} into a
     * {@code PostIdV} class overriding {@code runVertex}. Never fatal: the
     * pass then falls back to the fragment-only path (varying defaults).
     */
    public static synchronized Loaded loadVertex(int programId, String vertexSource) {
        if (vertexSource == null || vertexSource.isBlank()) {
            VCACHE.remove(programId); VFAILURES.remove(programId); VSOURCE_KEYS.remove(programId);
            return null;
        }
        String key = sourceKey(vertexSource);
        Loaded cached = VCACHE.get(programId);
        if (cached != null && key.equals(VSOURCE_KEYS.get(programId))) {
            return cached;
        }
        String failure = VFAILURES.get(programId);
        if (failure != null && key.equals(VSOURCE_KEYS.get(programId))) {
            return null;
        }
        VSOURCE_KEYS.put(programId, key);
        VFAILURES.remove(programId);
        VCACHE.remove(programId);

        String className = "Post" + programId + "V";
        GlslCompiler.Result meta;
        try {
            GlslCompiler compiler = new GlslCompiler();
            meta = compiler.translateVertex(className, vertexSource);
        } catch (Throwable t) {
            String reason = "translate: " + t.getMessage();
            VFAILURES.put(programId, reason);
            AntimonyMod.LOG.warn("[Antimony] post vertex {} not translated ({})", programId, reason);
            return null;
        }

        try {
            GlslProgram program = compile(programId, className, meta);
            Loaded loaded = new Loaded(program, meta, className);
            VCACHE.put(programId, loaded);
            AntimonyMod.LOG.info("[Antimony] vertex {} compiled on CPU: {} inputs={} outputs={} samplers={}",
                programId, className, meta.vertexInputs, meta.vertexOutputs, meta.samplerUnits);
            return loaded;
        } catch (Throwable t) {
            String reason = "compile: " + t;
            VFAILURES.put(programId, reason);
            AntimonyMod.LOG.warn("[Antimony] post vertex {} not compiled: {}", programId, t.toString());
            return null;
        }
    }

    public static String computeFailure(int id){return CFAILURES.get(id);}
    public static synchronized Loaded loadCompute(int id,String source){
        if(source==null||source.isBlank()){CCACHE.remove(id);CFAILURES.remove(id);CSOURCES.remove(id);return null;}
        String key=sourceKey(source);
        if(key.equals(CSOURCES.get(id)))return CCACHE.get(id);
        CSOURCES.put(id,key);CCACHE.remove(id);CFAILURES.remove(id);String name="Compute"+Integer.toUnsignedString(id);
        try{GlslCompiler.Result meta=new GlslCompiler().translateCompute(name,source);GlslProgram program=compile(id,name,meta);Loaded loaded=new Loaded(program,meta,name);CCACHE.put(id,loaded);
            AntimonyMod.LOG.info("[Antimony] compute {} compiled on CPU: local={} samplers={} images={} (dispatch/resource production is separate)",id,java.util.Arrays.toString(meta.localSize),meta.samplerTypes,meta.imageTypes);return loaded;
        }catch(Throwable error){CFAILURES.put(id,error.toString());AntimonyMod.LOG.warn("[Antimony] compute {} not compiled: {}",id,error.toString());return null;}
    }
    private static GlslProgram compile(int programId, String className, GlslCompiler.Result meta) throws Exception {
        Path dir = workDir();
        Files.createDirectories(dir);
        Path src = dir.resolve(className + ".java");
        Files.writeString(src, meta.javaSource, StandardCharsets.UTF_8);

        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException("no system java compiler (need a JDK)");
        }
        if (!loggedCompiler) {
            loggedCompiler = true;
            AntimonyMod.LOG.info("[Antimony] post program compiler: {}", javac.getClass().getName());
        }
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        List<String> args = new ArrayList<>();
        args.add("-encoding");
        args.add("UTF-8");
        args.add("-proc:none");
        args.add("-nowarn");
        args.add("-d");
        args.add(classes.toString());
        args.add("-cp");
        args.add(classpath());
        args.add(src.toString());
        java.io.ByteArrayOutputStream diagnostics = new java.io.ByteArrayOutputStream();
        int rc = javac.run(null, diagnostics, diagnostics, args.toArray(new String[0]));
        if (rc != 0) {
            String text = diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
            AntimonyMod.LOG.warn("[Antimony] javac failed (rc={}) for {}:\n{}", rc, src, text);
            throw new IllegalStateException("javac rc=" + rc + " for " + src
                + (text.isEmpty() ? "" : ": " + text.substring(0, Math.min(400, text.length()))));
        }

        URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()},
            GlslProgramLoader.class.getClassLoader());
        Class<?> type = Class.forName("dev.antimony.gl.glsl.gen." + className, true, loader);
        return (GlslProgram) type.getDeclaredConstructor().newInstance();
    }

    /**
     * Classpath for javac: this mod's own jar (resolved from the class
     * resource, because NeoForge's transforming loader does not always expose
     * a file code source for mods) plus the JVM's own class path.
     */
    private static String classpath() {
        java.util.LinkedHashSet<String> entries = new java.util.LinkedHashSet<>();
        try {
            URL url = GlslProgramLoader.class.getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                entries.add(containerOf(url.toString()));
            }
        } catch (Throwable ignored) {
            // fall through to the resource lookup
        }
        try {
            // NeoForge's transforming loader reports resources through a
            // union/jar file system ("union:/D:/.../mods/x.jar!/dev/..."), so
            // peel the container path out by hand instead of trusting the URL
            // scheme; a null code source is normal there.
            URL resource = GlslProgramLoader.class.getResource("GlslProgramLoader.class");
            if (resource != null) {
                entries.add(containerOf(resource.toString()));
            }
        } catch (Throwable ignored) {
            // keep whatever the code source gave us
        }
        String runtime = System.getProperty("java.class.path", "");
        for (String part : runtime.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!part.isBlank() && !part.equals("/") && !part.equals("\\")) {
                entries.add(part);
            }
        }
        entries.removeIf(entry -> entry.isBlank() || entry.equals("/") || entry.equals("\\"));
        if (entries.isEmpty()) {
            entries.add(Path.of(System.getProperty("user.dir", ".")).toString());
        }
        String joined = String.join(java.io.File.pathSeparator, entries);
        if (!loggedClasspath) {
            loggedClasspath = true;
            AntimonyMod.LOG.info("[Antimony] post compiler classpath: {}", joined);
        }
        return joined;
    }

    private static boolean loggedClasspath;

    private static String sourceKey(String source) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
    private static String sourcePairKey(String first,String second){
        return sourceKey(first.length()+":"+first+second.length()+":"+second);
    }

    /**
     * Container path (jar file or classes directory) of a class resource URL.
     * Handles the shapes NeoForge produces:
     * {@code union:/D:/.../mods/x.jar%23201}, {@code jar:file:/D:/...!/pkg/C.class},
     * {@code file:/D:/.../build/classes/java/main/dev/...}.
     */
    static String containerOf(String url) {
        String text = url;
        for (String scheme : new String[]{"union:", "jar:", "file:"}) {
            if (text.startsWith(scheme)) {
                text = text.substring(scheme.length());
            }
        }
        int bang = text.indexOf("!/");
        if (bang >= 0) {
            text = text.substring(0, bang);
        }
        int encodedHash = text.indexOf("%23");
        if (encodedHash >= 0) {
            text = text.substring(0, encodedHash);
        }
        int hash = text.indexOf('#');
        if (hash >= 0) {
            text = text.substring(0, hash);
        }
        text = text.replace("%20", " ");
        if (text.length() > 2 && text.charAt(0) == '/' && text.charAt(2) == ':') {
            text = text.substring(1);
        }
        text = text.replace('/', java.io.File.separatorChar);
        if (text.endsWith(".class")) {
            // directory class path: keep the classes root, not the .class file
            String suffix = String.join(java.io.File.separator,
                "dev", "antimony", "gl", "glsl") + java.io.File.separator;
            int index = text.lastIndexOf(suffix);
            if (index > 0) {
                text = text.substring(0, index);
            }
        }
        return text;
    }

    private static Path workDir() {
        if (workDir == null) {
            Path base;
            try {
                var mc = net.minecraft.client.Minecraft.getInstance();
                base = mc != null && mc.gameDirectory != null
                    ? mc.gameDirectory.toPath()
                    : Path.of(System.getProperty("user.dir", "."));
            } catch (Throwable t) {
                base = Path.of(System.getProperty("user.dir", "."));
            }
            workDir = base.resolve("antimony-post-gen");
        }
        return workDir;
    }
}
