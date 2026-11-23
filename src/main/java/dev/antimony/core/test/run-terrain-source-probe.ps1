$ErrorActionPreference = 'Stop'
# Directed javac only; this script never starts Gradle or a game.
# Reuse the already-resolved project dependency classpath from the existing fog probe.
$seed = Get-Content -LiteralPath build/tmp/fog-probe/javac.args
if ($seed.Count -lt 10 -or $seed[8] -ne '-classpath') { throw 'Missing resolved fog-probe classpath' }
$out = 'build/tmp/b3-source-probe'
$stubDirectory = Join-Path $out 'stub-src/net/irisshaders/iris'
New-Item -ItemType Directory -Force $stubDirectory | Out-Null
# Only the static bootstrap/config facade is stubbed. The fixture executes the actual
# Iris ProgramSource, ProgramFallbackResolver, ShaderAttributeInputs and TransformPatcher.
$stub = @'
package net.irisshaders.iris;
public final class Iris {
    private static final net.irisshaders.iris.config.IrisConfig CONFIG =
        new net.irisshaders.iris.config.IrisConfig(java.nio.file.Path.of("fixture-unused.properties"),
            java.nio.file.Path.of("fixture-unused-excluded.json"));
    public static net.irisshaders.iris.config.IrisConfig getIrisConfig() { return CONFIG; }
}
'@
$utf8 = [System.Text.UTF8Encoding]::new($false)
$stubPath = Join-Path $stubDirectory 'Iris.java'
[System.IO.File]::WriteAllText((Join-Path (Get-Location) $stubPath), $stub, $utf8)
& javac --release 21 -proc:none -d $out src/main/java/dev/antimony/gl/glsl/IrisTerrainSources.java src/main/java/dev/antimony/core/test/IrisTerrainSourcesProbe.java
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& javac --release 21 -proc:none -cp build/tmp/iris-extract -d $out $stubPath
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$cp = $out + ';' + $seed[9].Trim('"') + ';' + (Join-Path (Get-Location) 'build/tmp/iris-extract') + ';' + (Join-Path (Get-Location) 'build/tmp/iris-extract/META-INF/jarjar/*') + ';' + (Join-Path (Get-Location) 'libs/sodium-api.jar')
$javaArgs = @('-cp', ('"' + $cp.Replace('\','/') + '"'), 'dev.antimony.gl.glsl.IrisTerrainSourcesProbe', "$out/fixture-game")
$argPath = Join-Path $out 'java.args'
[System.IO.File]::WriteAllLines((Join-Path (Get-Location) $argPath), $javaArgs, $utf8)
& java "@$argPath"
exit $LASTEXITCODE
