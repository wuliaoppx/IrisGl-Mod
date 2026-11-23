package dev.antimony.gl.glsl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.nio.charset.StandardCharsets;

/** Validates the on-disk diagnostic format without touching a shader pack. */
public final class CpuShaderConversionManifestProbe {
    public static void main(String[] args) throws Exception {
        Path root=Path.of("build/tmp/cpu-conversion-manifest-probe");Files.createDirectories(root);
        String javaSource="package probe; public final class Converted { }\n";
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(javaSource.getBytes(StandardCharsets.UTF_8)));
        var stage=new CpuShaderConversionManifest.StageResult("vertex","source-hash","translated-and-javac-compiled","CPU resources and semantic equivalence unverified","Converted.java",digest,javaSource);
        var program=new CpuShaderConversionManifest.ProgramResult("SHADOW_TERRAIN_CUTOUT","ShadowCutout","shadow","GREATER 0.1","[0, 1]","NONE",List.of(stage));
        var manifest=new CpuShaderConversionManifest.Manifest("Solas Shader V3.7b.zip","Iris test","minecraft:overworld","CPU_SELECTED_IRIS_PATCH_VANILLA","pipeline-id","program-set-id","resolver-id",false,false,List.of(program));
        Path file=CpuShaderConversionManifest.write(manifest,root);
        Properties properties=new Properties();try(var reader=Files.newBufferedReader(file)){properties.load(reader);}
        check("false".equals(properties.getProperty("originalPackModified")),"source pack is never modified");
        check("false".equals(properties.getProperty("fullCpuCompatibility")),"preflight cannot claim full compatibility");
        check(digest.equals(properties.getProperty("program.0.stage.0.cpuArtifactSha256")),"artifact hash exported");
        Path artifact=file.getParent().resolve(properties.getProperty("program.0.stage.0.cpuArtifact"));
        check(javaSource.equals(Files.readString(artifact)),"generated Java intermediate exported exactly");
        check("translated-and-javac-compiled".equals(properties.getProperty("program.0.stage.0.status")),"stage status retained");
        cacheIdentityRegression();
        System.out.println("CPU conversion manifest probe OK: exact intermediate/hash, pack immutable, compile status separated from compatibility, source-pair cache invalidation");
    }
    private static void cacheIdentityRegression() throws Exception {
        int id=1_234_567;String first="layout(location=0) out vec4 c;void main(){c=vec4(1.0);}";
        String second="layout(location=0) out vec4 c;void main(){c=vec4(2.0);}";
        var a=GlslProgramLoader.load(id,first,"");var b=GlslProgramLoader.load(id,first,"// vertex A");
        check(a!=null&&b!=null&&a!=b,"vertex change invalidates paired fragment conversion");
        b=GlslProgramLoader.load(id,second,"// vertex A");
        check(b!=null&&b!=a,"fragment change invalidates converted program");
        java.lang.reflect.Field f=b.program.getClass().getField("out");
        b.program.run(new FragState(){public float width(){return 1;}public float height(){return 1;}public float[] sample(int unit,float u,float v){throw new AssertionError();}});
        float[][] out=(float[][])f.get(b.program);check(out[0][0]==2f,"new source executes rather than stale cached lowering");
        check(GlslProgramLoader.load(id,"","")==null,"empty fragment reports absent");
        check(GlslProgramLoader.failure(id)==null,"empty fragment clears stale failure");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
