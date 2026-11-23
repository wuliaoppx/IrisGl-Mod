package dev.antimony.gl.glsl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.security.MessageDigest;

/** Writes an auditable conversion/preflight manifest; it never edits source packs or claims runtime equivalence. */
public final class CpuShaderConversionManifest {
    private static final java.util.concurrent.atomic.AtomicInteger PREFLIGHT_IDS=new java.util.concurrent.atomic.AtomicInteger(2_000_000_000);
    private CpuShaderConversionManifest() { }

    public record StageResult(String stage, String source, String status, String reason,
                              String cpuArtifact, String cpuArtifactSha256, String cpuArtifactSource) { }
    public record ProgramResult(String key, String originalProgram, String resolvedProgram,
                                String alphaTest, String drawBuffers, String blend,
                                List<StageResult> stages) {
        public ProgramResult { stages=List.copyOf(stages); }
    }
    public record Manifest(String pack, String irisVersion, String dimension, String transform,
                           String pipeline, String programSet, String resolver,
                           boolean originalPackModified, boolean executable, List<ProgramResult> programs) {
        public Manifest { programs=List.copyOf(programs); }
    }

    public static ProgramResult preflight(IrisTerrainSources.Source source) {
        var id=source.identity();
        List<StageResult> results=new ArrayList<>();
        check("vertex",source.vertex(),id.vertexSha256(),true,results);
        if(source.geometry()!=null)unsupportedStage("geometry",source.geometry(),results);
        if(source.tessControl()!=null)unsupportedStage("tessControl",source.tessControl(),results);
        if(source.tessEval()!=null)unsupportedStage("tessEval",source.tessEval(),results);
        check("fragment",source.fragment(),id.fragmentSha256(),false,results);
        String draw="unknown",blend="unknown";
        if(source.drawDirectives()!=null){draw=source.drawDirectives().unknownDrawBuffers()?"unknown":source.drawDirectives().drawBuffers().toString();blend=source.drawDirectives().blendOverride()+"/"+source.drawDirectives().bufferBlendOverrides();}
        return new ProgramResult(id.shaderKey(),id.requestedProgram(),id.resolvedProgram(),id.alphaTest(),draw,blend,results);
    }

    private static void check(String stage,String source,String sourceHash,boolean vertex,List<StageResult> out){
        if(source==null||source.isBlank()){out.add(new StageResult(stage,sourceHash,"absent","stage not supplied by resolved program","none","absent",""));return;}
        int id=PREFLIGHT_IDS.getAndIncrement();
        GlslProgramLoader.Loaded loaded;
        if(vertex)loaded=GlslProgramLoader.loadVertex(id,source);
        else loaded=GlslProgramLoader.load(id,source,"");
        String failure=loaded==null?(vertex?GlslProgramLoader.vertexFailure(id):GlslProgramLoader.failure(id)):null;
        out.add(new StageResult(stage,sourceHash,loaded==null?"rejected":"translated-and-javac-compiled",loaded==null?String.valueOf(failure):"CPU invocation/resource/equivalence not verified",loaded==null?"none":loaded.className+".java",loaded==null?"absent":hash(loaded.meta.javaSource),loaded==null?"":loaded.meta.javaSource));
    }

    private static void unsupportedStage(String stage,String source,List<StageResult> out){
        out.add(new StageResult(stage,hash(source),"rejected","CPU backend does not translate this stage", "none","absent",""));
    }

    public static Manifest fromCapture(IrisTerrainSources.Capture capture){
        if(capture.sources().isEmpty())return new Manifest("unknown","unknown","unknown",IrisTerrainSources.TRANSFORM,capture.pipelineIdentity(),"unknown","unknown",false,false,List.of());
        var first=capture.sources().get(0).identity();
        return new Manifest(first.packName(),first.irisVersion(),first.dimension(),first.transform(),first.pipeline(),first.programSet(),first.resolver(),false,false,capture.sources().stream().map(CpuShaderConversionManifest::preflight).toList());
    }

    public static Path write(Manifest manifest,Path gameDirectory) throws IOException {
        Path root=gameDirectory.resolve("antimony-shader-dump");Files.createDirectories(root);
        Path directory=Files.createTempDirectory(root,"cpu-conversion-");Properties p=new Properties();
        p.setProperty("format","antimony-cpu-shader-manifest-v1");p.setProperty("pack",manifest.pack());p.setProperty("irisVersion",manifest.irisVersion());p.setProperty("dimension",manifest.dimension());p.setProperty("transform",manifest.transform());p.setProperty("pipeline",manifest.pipeline());p.setProperty("programSet",manifest.programSet());p.setProperty("resolver",manifest.resolver());p.setProperty("originalPackModified",Boolean.toString(manifest.originalPackModified()));p.setProperty("fullCpuCompatibility",Boolean.toString(manifest.executable()));p.setProperty("semantics","stage compile status is not execution equivalence; no shader draw/image writes/resource lifecycle implied");p.setProperty("programCount",Integer.toString(manifest.programs().size()));
        for(int i=0;i<manifest.programs().size();i++){ProgramResult r=manifest.programs().get(i);String prefix="program."+i+".";p.setProperty(prefix+"key",r.key());p.setProperty(prefix+"original",r.originalProgram());p.setProperty(prefix+"resolved",r.resolvedProgram());p.setProperty(prefix+"alphaTest",r.alphaTest());p.setProperty(prefix+"drawBuffers",r.drawBuffers());p.setProperty(prefix+"blend",r.blend());p.setProperty(prefix+"stageCount",Integer.toString(r.stages().size()));for(int j=0;j<r.stages().size();j++){StageResult s=r.stages().get(j);String sp=prefix+"stage."+j+".";p.setProperty(sp+"name",s.stage());p.setProperty(sp+"sourceSha256",s.source());p.setProperty(sp+"status",s.status());p.setProperty(sp+"reason",s.reason());p.setProperty(sp+"cpuArtifact",s.cpuArtifact());p.setProperty(sp+"cpuArtifactSha256",s.cpuArtifactSha256());if(!s.cpuArtifact().equals("none")){var artifact=directory.resolve(s.cpuArtifact());Files.writeString(artifact,s.cpuArtifactSource(),StandardCharsets.UTF_8);}}}
        Path file=directory.resolve("manifest.properties");try(var writer=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){p.store(writer,"Generated CPU translation manifest; source-only, not compatibility certification");}return file;
    }
    private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
