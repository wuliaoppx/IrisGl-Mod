package dev.antimony.gl.glsl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Offline self-test: translates every captured program in a shim dump
 * directory and compiles the generated sources with the javax.tools compiler,
 * printing one line per program. Run from the repo root:
 * {@code java -cp build/classes/java/main dev.antimony.gl.glsl.GlslSelfTest <dumpDir> <outDir>}
 */
public final class GlslSelfTest {
    public static void main(String[] args) throws Exception {
        Path dump = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);
        List<Path> frags;
        try (Stream<Path> s = Files.list(dump)) {
            frags = s.filter(p -> p.getFileName().toString().contains("-fragment"))
                .sorted().toList();
        }
        int ok = 0;
        int compileOk = 0;
        for (Path f : frags) {
            String name = f.getFileName().toString();
            String vertex = dump.resolve(name.replace("-fragment", "-vertex")).toString();
            if (!Files.exists(Path.of(vertex))) {
                vertex = dump.resolve(name.replace("-fragment", "-vertex.glsl")).toString();
            }
            String fragSrc = Files.readString(f);
            String vertSrc = Files.exists(Path.of(vertex)) ? Files.readString(Path.of(vertex)) : "";
            String clsName = "Gen" + name.substring(0, name.indexOf('-'));
            try {
                GlslCompiler c = new GlslCompiler();
                GlslCompiler.Result r = c.translate(clsName, fragSrc, vertSrc);
                Files.writeString(outDir.resolve(clsName + ".java"), r.javaSource);
                ok++;
                System.out.println("TRANSLATED " + name + " -> " + clsName
                    + " samplers=" + r.samplerUnits + " outs=" + r.outSlots
                    + " varyings=" + r.varyingIns.size()
                    + (r.usesGlFragCoord ? " [fragCoord]" : ""));
            } catch (GlslCompiler.TranslateException te) {
                System.out.println("FAIL " + name + " : " + te.getMessage());
                StackTraceElement[] stt = te.getStackTrace();
                for (int k = 0; k < Math.min(5, stt.length); k++) {
                    System.out.println("    at " + stt[k]);
                }
            }
        }
        System.out.println("translated " + ok + "/" + frags.size());
        if (ok > 0) {
            // compile all generated sources together
            javax.tools.JavaCompiler jc = javax.tools.ToolProvider.getSystemJavaCompiler();
            if (jc == null) {
                System.out.println("no system java compiler (JRE?) - skipping compile check");
                return;
            }
            List<String> opts = List.of("-d", outDir.resolve("classes").toString(),
                "-cp", "build/classes/java/main");
            try (Stream<Path> s = Files.list(outDir)) {
                List<String> sources = s.filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toString).toList();
                List<String> all = new java.util.ArrayList<>(opts);
                all.addAll(sources);
                int rc = jc.run(null, System.out, System.err, all.toArray(new String[0]));
                System.out.println(rc == 0 ? "COMPILE OK" : "COMPILE FAILED rc=" + rc);
            }
        }
    }
}
