param(
    [string]$DumpDir = ''
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../../../../..')).Path
Push-Location $repo
try {
    $output = 'build/b3-vertex-probe/classes'
    New-Item -ItemType Directory -Force $output | Out-Null
    $sources = @(
        'GlslProgram.java', 'FragState.java', 'CpuImage.java', 'VertexState.java',
        'GlslRuntime.java', 'GlslCompiler.java', 'GlslVertexProbe.java', 'GlslSelfTest.java'
    ) | ForEach-Object { Join-Path 'src/main/java/dev/antimony/gl/glsl' $_ }
    # Existing main classes supply GlslRuntime's ShadowMap dependency; no full Gradle build.
    & javac -encoding UTF-8 -proc:none --class-path 'build/classes/java/main' -d $output @sources
    if ($LASTEXITCODE -ne 0) { throw "isolated javac failed: $LASTEXITCODE" }
    $classpath = "$output;build/classes/java/main"
    if ($DumpDir) {
        & java --class-path $classpath dev.antimony.gl.glsl.GlslVertexProbe $DumpDir
    } else {
        & java --class-path $classpath dev.antimony.gl.glsl.GlslVertexProbe
    }
    if ($LASTEXITCODE -ne 0) { throw "vertex probe failed: $LASTEXITCODE" }
    if ($DumpDir) {
        $lines = & java --class-path $classpath dev.antimony.gl.glsl.GlslSelfTest $DumpDir 'build/b3-vertex-probe/fullscreen-selftest'
        $rc = $LASTEXITCODE
        $lines | Write-Output
        if ($rc -ne 0 -or $lines -match '^FAIL ' -or $lines -match 'COMPILE FAILED' -or -not ($lines -contains 'COMPILE OK')) {
            throw 'existing fullscreen compiler selftest failed'
        }
    }
} finally {
    Pop-Location
}
