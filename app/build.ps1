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
$jar = Join-Path $jdk 'bin\jar.exe'
$keytool = Join-Path $jdk 'bin\keytool.exe'
$aapt2 = Join-Path $tools 'aapt2.exe'
$d8 = Join-Path $tools 'd8.bat'
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'apksigner.bat'
$stage = Join-Path $env:TEMP ('qlc-' + [guid]::NewGuid().ToString('N'))
$dist = Join-Path $app 'dist'
$keystore = Join-Path $app 'debug.keystore'
$output = Join-Path $dist 'video-clean-v0.3.12.apk'
$env:JAVA_HOME = $jdk
$env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path

Write-Host "workspace   : $workspace"
Write-Host "sdk         : $sdk (platform $platform)"
Write-Host "build-tools : $tools"
Write-Host "jdk         : $jdk"

foreach ($needed in @($androidJarSource, $javac, $jar, $keytool, $aapt2, $d8, $zipalign, $apksigner)) {
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
    Run-Native 'DEX conversion' { & $d8 --min-api 26 --lib $androidJar --output (Join-Path $stage 'dex') $classesJar $serviceJar }

    Write-Host 'Packaging Android resources'
    $compiledRes = Join-Path $stage 'resources.zip'
    Run-Native 'Resource compilation' { & $aapt2 compile --dir (Join-Path $stage 'res') -o $compiledRes }
    $unsigned = Join-Path $stage 'out\module.apk'
    Run-Native 'APK linking' { & $aapt2 link -o $unsigned --manifest (Join-Path $stage 'AndroidManifest.xml') -I $androidJar --min-sdk-version 26 --target-sdk-version 34 $compiledRes }
    # Both assemblies are needed on Windows PowerShell 5.1: FileSystem supplies ZipFile,
    # Compression supplies ZipArchiveMode.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Add-Type -AssemblyName System.IO.Compression
    $archive = [System.IO.Compression.ZipFile]::Open($unsigned, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $stage 'dex\classes.dex'), 'classes.dex') | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $stage 'META-INF') -Recurse -File | ForEach-Object {
            $relative = $_.FullName.Substring($stage.Length + 1).Replace('\', '/')
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, $relative) | Out-Null
        }
    } finally { $archive.Dispose() }
    $aligned = Join-Path $stage 'out\module-aligned.apk'
    Run-Native 'APK alignment' { & $zipalign -f 4 $unsigned $aligned }

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
        $tempRoot = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
        if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetFileName($resolved).StartsWith('qlc-')) {
            throw "Refusing to remove unexpected build directory: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
