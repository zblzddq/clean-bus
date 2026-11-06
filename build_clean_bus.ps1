$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$JAVA_HOME = if (Test-Path 'C:\Users\a3991\jdk-21\bin\javac.exe') {
    'C:\Users\a3991\jdk-21'
} elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $env:JAVA_HOME
} elseif (Test-Path 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin\javac.exe') {
    'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
} else {
    'C:\Users\a3991\jdk-21'
}

$JAVA = Join-Path $JAVA_HOME 'bin\java.exe'
$JAVAC = Join-Path $JAVA_HOME 'bin\javac.exe'
$JAR = Join-Path $JAVA_HOME 'bin\jar.exe'
$TOOLS = if ($env:ANDROID_BUILD_TOOLS -and (Test-Path $env:ANDROID_BUILD_TOOLS)) { $env:ANDROID_BUILD_TOOLS } else { 'C:\Users\a3991\android-build-tools' }
$AAPT2 = Join-Path $TOOLS 'aapt2.exe'
$R8_JAR = Join-Path $TOOLS 'r8.jar'
$UBER = Join-Path $TOOLS 'uber-apk-signer.jar'
$ANDROID_JAR = Join-Path $TOOLS 'android.jar'
$PROGUARD_RULES = Join-Path $PSScriptRoot 'proguard-rules.pro'

# 0. 开工前工具依赖自检
$requiredTools = @(
    @{ Name = "Java Runtime (java.exe)"; Path = $JAVA },
    @{ Name = "Java Compiler (javac.exe)"; Path = $JAVAC },
    @{ Name = "Jar Archiver (jar.exe)"; Path = $JAR },
    @{ Name = "Android AAPT2 (aapt2.exe)"; Path = $AAPT2 },
    @{ Name = "Android R8 (r8.jar)"; Path = $R8_JAR },
    @{ Name = "Android Platform JAR (android.jar)"; Path = $ANDROID_JAR },
    @{ Name = "Uber APK Signer (uber-apk-signer.jar)"; Path = $UBER }
)

$missingTools = @()
foreach ($tool in $requiredTools) {
    if (-not (Test-Path $tool.Path)) {
        $missingTools += " - $($tool.Name): $($tool.Path)"
    }
}
if ($missingTools.Count -gt 0) {
    Write-Error "Pre-flight check failed! Missing required build tools:`n$($missingTools -join "`n")"
    exit 1
}

# 1. 内存计算待构建版本号 (暂不写回磁盘，避免构建失败半提交)
$versionFile = Join-Path $PSScriptRoot 'version.json'
if (Test-Path $versionFile) {
    $ver = Get-Content $versionFile -Raw | ConvertFrom-Json
    $newCode = [int]$ver.versionCode + 1
    $parts = $ver.versionName.Split('.')
    if ($parts.Count -eq 3) {
        $patch = [int]$parts[2] + 1
        $newName = "$($parts[0]).$($parts[1]).$patch"
    } else {
        $newName = "$($ver.versionName).1"
    }
} else {
    $newCode = 1
    $newName = "1.0.1"
}
Write-Host ">>> Preparing CleanBus build: v$newName (versionCode: $newCode)"

$buildDir = Join-Path $PSScriptRoot 'build'

# 2. 编译 Native Java
Write-Host '[1/4] Compiling Native Java...'
$classesDir = Join-Path $buildDir 'classes'
Remove-Item -Path $classesDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
$javaSrcDir = Join-Path $PSScriptRoot 'app\src\main\java'
$javaFiles = Get-ChildItem -Path $javaSrcDir -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
& $JAVAC -encoding UTF-8 -cp $ANDROID_JAR -d $classesDir $javaFiles
if ($LASTEXITCODE -ne 0) {
    Write-Error "JAVAC compilation failed with exit code $LASTEXITCODE"
    exit 1
}

# 3. R8 混淆、剪裁与脱糖至单个 classes.dex
Write-Host '[2/4] R8 release optimization & desugaring to classes.dex...'
$dexDir = Join-Path $buildDir 'dex'
Remove-Item -Path $dexDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $dexDir -Force | Out-Null
$classes = Get-ChildItem -Path $classesDir -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName

$r8Args = @(
    '-cp', $R8_JAR,
    'com.android.tools.r8.R8',
    '--release',
    '--min-api', '26',
    '--lib', $ANDROID_JAR,
    '--output', $dexDir
)
if (Test-Path $PROGUARD_RULES) {
    $r8Args += @('--pg-conf', $PROGUARD_RULES)
}
$r8Args += $classes

& $JAVA $r8Args
if ($LASTEXITCODE -ne 0) {
    Write-Error "R8 failed with exit code $LASTEXITCODE"
    exit 1
}

# 4. AAPT2 编译资源与链接打包 (带入版本号)
Write-Host "[3/4] AAPT2 compile & link (injecting v$newName, code $newCode)..."
$unalignedApk = Join-Path $buildDir 'unaligned.apk'
$resZip = Join-Path $buildDir 'res.zip'
Remove-Item -Path $unalignedApk -Force -ErrorAction SilentlyContinue
Remove-Item -Path $resZip -Force -ErrorAction SilentlyContinue

$resDir = Join-Path $PSScriptRoot 'app\src\main\res'
$manifestFile = Join-Path $PSScriptRoot 'app\src\main\AndroidManifest.xml'

& $AAPT2 compile --dir $resDir -o $resZip
if ($LASTEXITCODE -ne 0) {
    Write-Error "AAPT2 compile failed with exit code $LASTEXITCODE"
    exit 1
}

& $AAPT2 link -o $unalignedApk -I $ANDROID_JAR -R $resZip --manifest $manifestFile `
    --min-sdk-version 26 --target-sdk-version 34 `
    --version-code $newCode --version-name $newName --replace-version
if ($LASTEXITCODE -ne 0) {
    Write-Error "AAPT2 link failed with exit code $LASTEXITCODE"
    exit 1
}

# 写入 classes.dex 到 APK
& $JAR -uf $unalignedApk -C $dexDir classes.dex
if ($LASTEXITCODE -ne 0) {
    Write-Error "JAR update failed with exit code $LASTEXITCODE"
    exit 1
}

# 5. Uber-apk-signer 签名
Write-Host '[4/4] Signing APK with V1/V2/V3...'
$outDir = Join-Path $buildDir 'out'
Remove-Item -Path $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$signerArgs = @('-jar', $UBER, '--apks', $unalignedApk, '--out', $outDir)

# 从环境变量读取 release keystore
$ksPath = if ($env:CLEANBUS_KS) { $env:CLEANBUS_KS } elseif ($env:KS_PATH) { $env:KS_PATH } else { $null }
$ksPass = if ($env:CLEANBUS_KS_PASS) { $env:CLEANBUS_KS_PASS } elseif ($env:KS_PASS) { $env:KS_PASS } else { $null }

if ($ksPath -and (Test-Path $ksPath)) {
    Write-Host "Signing with release keystore: $ksPath"
    $signerArgs += @('--ks', $ksPath)
    if ($ksPass) {
        $signerArgs += @('--ksPass', $ksPass)
    }
} else {
    Write-Host "No release keystore provided via CLEANBUS_KS, signing with default debug key..."
}

& $JAVA $signerArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "Signing failed with exit code $LASTEXITCODE"
    exit 1
}

$signedFiles = Get-ChildItem -Path $outDir -Filter '*.apk'
if (-not $signedFiles -or $signedFiles.Count -eq 0) {
    Write-Error "No signed APK found in $outDir"
    exit 1
}
$signedApk = $signedFiles[0].FullName

$releasesDir = Join-Path $PSScriptRoot 'releases'
if (!(Test-Path $releasesDir)) {
    New-Item -ItemType Directory -Path $releasesDir -Force | Out-Null
}
$versionedApkName = Join-Path $releasesDir "CleanBus_v$newName.apk"
$releaseApkName = Join-Path $releasesDir "CleanBus_Release.apk"

Copy-Item $signedApk $versionedApkName -Force
Copy-Item $signedApk $releaseApkName -Force

# 6. 只有在构建和签名全部成功后，才原子写回 version.json
$verObj = [PSCustomObject]@{
    versionCode = $newCode
    versionName = $newName
}
$tempVerFile = Join-Path $PSScriptRoot 'version.json.tmp'
$verObj | ConvertTo-Json | Set-Content $tempVerFile -Encoding UTF8
Move-Item -Path $tempVerFile -Destination $versionFile -Force
Write-Host ">>> Version atomically updated in version.json: v$newName (versionCode: $newCode)"

Write-Host "BUILD SUCCESS: Created $versionedApkName and $releaseApkName"
Get-Item $versionedApkName, $releaseApkName | Select-Object Name, Length, LastWriteTime
