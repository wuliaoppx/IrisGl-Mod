$ErrorActionPreference = 'Stop'
$javaRoot = 'C:\Program Files\Zulu\zulu-21\bin'
$libRoot = Join-Path $env:USERPROFILE '.gradle\caches\minecraft\libraries'
$probeClasspath = @('build/tmp/uniform-probe', 'build/tmp/iris-extract',
    "$libRoot\it\unimi\dsi\fastutil\8.5.12\fastutil-8.5.12.jar",
    "$libRoot\com\google\guava\guava\32.1.2-jre\guava-32.1.2-jre.jar",
    "$libRoot\org\joml\joml\1.10.5\joml-1.10.5.jar",
    "$libRoot\org\lwjgl\lwjgl\3.3.3\lwjgl-3.3.3.jar",
    "$libRoot\org\lwjgl\lwjgl-opengl\3.3.3\lwjgl-opengl-3.3.3.jar") -join ';'
& "$javaRoot\javac.exe" -d build/tmp/uniform-probe src/main/java/dev/antimony/gl/glsl/IrisUniformFrame.java src/main/java/dev/antimony/core/test/IrisUniformFrameProbe.java
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$javaRoot\java.exe" --class-path $probeClasspath dev.antimony.core.test.IrisUniformFrameProbe build/tmp/makeup-extract/shaders/shaders.properties
exit $LASTEXITCODE
