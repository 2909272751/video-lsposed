param([switch]$KeepBuildDirectory)
$ErrorActionPreference = 'Stop'

# Paths are DERIVED from $PSScriptRoot, never hardcoded: the workspace path contains
# non-ASCII characters, and a .ps1 without a UTF-8 BOM is decoded as ANSI by Windows
# PowerShell 5.1, which would corrupt them. Deriving keeps this file pure ASCII and
# therefore encoding-independent. Override with $env:QLC_SDK / $env:QLC_JDK if needed.
$app = $PSScriptRoot
$workspace = Split-Path $app -Parent | Split-Path -Parent
$buildRoot = Join-Path $workspace '.android_build_tools'
$sdk = if ($env:QLC_SDK) { $env:QLC_SDK } else { Join-Path $buildRoot 'android-sdk' }
$jdk = if ($env:QLC_JDK) { $env:QLC_JDK } else {
    $jdkRoot = Join-Path $buildRoot 'jdk17'
    if (-not (Test-Path -LiteralPath $jdkRoot)) { throw "JDK root missing: $jdkRoot" }
    (Get-ChildItem -LiteralPath $jdkRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
}
$platform = (Get-ChildItem -LiteralPath (Join-Path $sdk 'platforms') -Directory |
    Sort-Object Name -Descending | Select-Object -First 1).Name
$tools = (Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object Name -Descending | Select-Object -First 1).FullName
$androidJarSource = Join-Path $sdk "platforms\$platform\android.jar"
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'
$jar = Join-Path $jdk 'bin\jar.exe'
$keytool = Join-Path $jdk 'bin\keytool.exe'
$aapt2 = Join-Path $tools 'aapt2.exe'
# Some build-tools installs ship a d8.bat whose d8.jar is missing (34.0.0 has
# lib\apksigner.jar only), and d8.bat then runs a classpath that does not exist and
# dies with a bare ClassNotFoundException. Resolve the D8 jar explicitly instead.
# Order matters: an explicit R8_JAR wins, then a standalone modern r8-<ver>.jar under
# <sdk>\d8, and only then whatever d8.jar the SDK ships. The d8.jar bundled with old
# build-tools (R8 3.3.20) dies here with "Cannot invoke String.length() because
# <parameter1> is null" while dexing, so it must be the last resort, not the first.
$d8Candidates = @()
if ($env:R8_JAR) { $d8Candidates += $env:R8_JAR }
$d8Candidates += @(Get-ChildItem -LiteralPath (Join-Path $sdk 'd8') -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'r8-*.jar' } | Sort-Object Name -Descending | ForEach-Object FullName)
# Sort by the build-tools directory version, newest first. Plain -Recurse enumeration
# returns 34.0.0 before 35.0.0, and that older jar bundles R8 3.3.20, which dies while
# dexing with "Cannot invoke String.length() because <parameter1> is null".
$d8Candidates += @(Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq 'd8.jar' } |
    Sort-Object { [version]($_.Directory.Parent.Name) } -Descending | ForEach-Object FullName)
$d8Jar = $d8Candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (-not $d8Jar) { throw "No D8 jar found. Set `$env:R8_JAR to a com.android.tools:r8 jar from dl.google.com/dl/android/maven2." }
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'apksigner.bat'
# Staging directory. $env:TEMP first, but a workspace-local fallback when TEMP is not
# writable by the toolchain: some sandboxes confine spawned processes (javac, d8) to the
# workspace, and javac then fails with "could not create parent directories".
$stageParent = $env:TEMP
if ($env:QLC_BUILD_TMP) {
    $stageParent = $env:QLC_BUILD_TMP
} else {
    $probe = Join-Path $workspace '.qlc-build-probe'
    try {
        New-Item -ItemType Directory -Force -Path $probe | Out-Null
        Set-Content -LiteralPath (Join-Path $probe 'probe.txt') -Value 'probe' -Encoding ASCII
        Remove-Item -LiteralPath $probe -Recurse -Force -ErrorAction SilentlyContinue
        $stageParent = Join-Path $workspace '.qlc-build'
        New-Item -ItemType Directory -Force -Path $stageParent | Out-Null
    } catch {
        $stageParent = $env:TEMP
    }
}
$stage = Join-Path $stageParent ('qlc-' + [guid]::NewGuid().ToString('N'))
$dist = Join-Path $app 'dist'
$keystore = Join-Path $app 'debug.keystore'
$output = Join-Path $dist 'video-clean-v0.3.53.apk'
$env:JAVA_HOME = $jdk
$env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path

Write-Host "workspace   : $workspace"
Write-Host "sdk         : $sdk (platform $platform)"
Write-Host "build-tools : $tools"
Write-Host "jdk         : $jdk"

foreach ($needed in @($androidJarSource, $javac, $java, $jar, $keytool, $aapt2, $zipalign, $apksigner)) {
    if (-not (Test-Path -LiteralPath $needed)) { throw "Build tool missing: $needed" }
}
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Copy-Item -LiteralPath $androidJarSource -Destination $stage
$androidJar = Join-Path $stage 'android.jar'
foreach ($folder in @('stub-src', 'src', 'res', 'META-INF', 'libs')) {
    Copy-Item -LiteralPath (Join-Path $app $folder) -Destination $stage -Recurse -Force
}
Copy-Item -LiteralPath (Join-Path $app 'AndroidManifest.xml') -Destination $stage
foreach ($folder in @('stubs', 'classes', 'dex', 'out')) {
    New-Item -ItemType Directory -Path (Join-Path $stage $folder) -Force | Out-Null
}

function Run-Native([string]$name, [scriptblock]$action) {
    & $action
    if ($LASTEXITCODE -ne 0) { throw "$name failed with exit code $LASTEXITCODE" }
}

try {
    $stubs = @(Get-ChildItem -LiteralPath (Join-Path $stage 'stub-src') -Recurse -Filter '*.java' | ForEach-Object FullName)
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $stage 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
    $serviceJar = Join-Path $stage 'libs\service-classes.jar'
    Write-Host 'Compiling API stubs and module'
    Run-Native 'API stub compilation' { & $javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath $androidJar -d (Join-Path $stage 'stubs') @stubs }
    $compilePath = (Join-Path $stage 'stubs') + ';' + $serviceJar
    Run-Native 'Module compilation' { & $javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath $androidJar -classpath $compilePath -d (Join-Path $stage 'classes') @sources }
    $classesJar = Join-Path $stage 'classes.jar'
    Run-Native 'JAR creation' { & $jar -cf $classesJar -C (Join-Path $stage 'classes') . }
    Run-Native 'DEX conversion' { & $java -Xmx3072M -cp $d8Jar com.android.tools.r8.D8 --min-api 26 --lib $androidJar --output (Join-Path $stage 'dex') $classesJar $serviceJar }

    Write-Host 'Packaging Android resources'
    # aapt2 and zipalign are native binaries. On a workspace whose path contains
    # non-ASCII characters they fail with "failed to open directory ... (2)" when the
    # path is passed as an absolute argument, while the same directory opens fine when
    # it is resolved relative to the current directory. So both are invoked from inside
    # the staging directory with relative paths; only the final artifact leaves with an
    # absolute path (apksigner is a JVM tool and handles Unicode paths correctly).
    Push-Location -LiteralPath $stage
    try {
        Run-Native 'Resource compilation' { & $aapt2 compile --dir res -o resources.zip }
        Run-Native 'APK linking' { & $aapt2 link -o out/module.apk --manifest AndroidManifest.xml -I android.jar --min-sdk-version 26 --target-sdk-version 34 resources.zip }
        # Both assemblies are needed on Windows PowerShell 5.1: FileSystem supplies ZipFile,
        # Compression supplies ZipArchiveMode.
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        Add-Type -AssemblyName System.IO.Compression
        $unsigned = Join-Path $stage 'out\module.apk'
        $archive = [System.IO.Compression.ZipFile]::Open($unsigned, [System.IO.Compression.ZipArchiveMode]::Update)
        try {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $stage 'dex\classes.dex'), 'classes.dex') | Out-Null
            Get-ChildItem -LiteralPath (Join-Path $stage 'META-INF') -Recurse -File | ForEach-Object {
                $relative = $_.FullName.Substring($stage.Length + 1).Replace('\', '/')
                [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, $relative) | Out-Null
            }
        } finally { $archive.Dispose() }
        Run-Native 'APK alignment' { & $zipalign -f 4 out/module.apk out/module-aligned.apk }
    } finally { Pop-Location }
    $aligned = Join-Path $stage 'out\module-aligned.apk'

    # Local test key only. Preserve app/debug.keystore to allow in-place upgrades.
    $env:QQLIVE_TEST_KEYPASS = 'android'
    if (-not (Test-Path -LiteralPath $keystore)) {
        Run-Native 'Test key generation' { & $keytool -genkeypair -keystore $keystore -storepass:env QQLIVE_TEST_KEYPASS -keypass:env QQLIVE_TEST_KEYPASS -alias qqliveclean -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=QQLiveClean, O=Local, C=CN' }
    }
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    Run-Native 'APK signing' { & $apksigner sign --ks $keystore --ks-key-alias qqliveclean --ks-pass env:QQLIVE_TEST_KEYPASS --key-pass env:QQLIVE_TEST_KEYPASS --out $output $aligned }
    Run-Native 'Signature verification' { & $apksigner verify $output }
    Write-Host "Built $output"
    (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash
} finally {
    Remove-Item Env:QQLIVE_TEST_KEYPASS -ErrorAction SilentlyContinue
    if (-not $KeepBuildDirectory -and (Test-Path -LiteralPath $stage)) {
        $resolved = [IO.Path]::GetFullPath($stage)
        $stageRoot = [IO.Path]::GetFullPath($stageParent).TrimEnd('\') + '\'
        if (-not $resolved.StartsWith($stageRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetFileName($resolved).StartsWith('qlc-')) {
            throw "Refusing to remove unexpected build directory: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
