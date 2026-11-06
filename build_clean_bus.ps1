$ErrorActionPreference = 'Stop'
Set-Location 'c:\Users\a3991\Documents\my_pro\clean_bus_app'

$JAVA_HOME = 'C:\Users\a3991\jdk-21'
$JAVA = Join-Path $JAVA_HOME 'bin\java.exe'
$JAVAC = Join-Path $JAVA_HOME 'bin\javac.exe'
$JAR = Join-Path $JAVA_HOME 'bin\jar.exe'
$TOOLS = 'C:\Users\a3991\android-build-tools'
$AAPT2 = Join-Path $TOOLS 'aapt2.exe'
$D8 = Join-Path $TOOLS 'r8.jar'
$UBER = Join-Path $TOOLS 'uber-apk-signer.jar'
$ANDROID_JAR = Join-Path $TOOLS 'android.jar'

# 0. 版本号自增逻辑
$versionFile = 'version.json'
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

$verObj = [PSCustomObject]@{
    versionCode = $newCode
    versionName = $newName
}
$verObj | ConvertTo-Json | Set-Content $versionFile -Encoding UTF8
Write-Host ">>> Building CleanBus version: v$newName (versionCode: $newCode)"

# 1. 编译 Java
Write-Host '[1/4] Compiling Native Java...'
Remove-Item -Path 'build\classes' -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path 'build\classes' -Force | Out-Null
$javaFiles = Get-ChildItem -Path 'app\src\main\java' -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
& $JAVAC -encoding UTF-8 -cp $ANDROID_JAR -d 'build\classes' $javaFiles
if ($LASTEXITCODE -ne 0) {
    Write-Error "JAVAC compilation failed with exit code $LASTEXITCODE"
    exit 1
}

# 2. 生成 DEX
Write-Host '[2/4] D8 desugaring to classes.dex...'
Remove-Item -Path 'build\dex' -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path 'build\dex' -Force | Out-Null
$classes = Get-ChildItem -Path 'build\classes' -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName
& $JAVA -cp $D8 com.android.tools.r8.D8 --min-api 26 --output 'build\dex' $classes
if ($LASTEXITCODE -ne 0) {
    Write-Error "D8 failed with exit code $LASTEXITCODE"
    exit 1
}

# 3. AAPT2 编译资源与链接打包 (带入版本号)
Write-Host "[3/4] AAPT2 compile & link (injecting v$newName, code $newCode)..."
Remove-Item -Path 'build\unaligned.apk' -Force -ErrorAction SilentlyContinue
Remove-Item -Path 'build\res.zip' -Force -ErrorAction SilentlyContinue

& $AAPT2 compile --dir 'app\src\main\res' -o 'build\res.zip'
if ($LASTEXITCODE -ne 0) {
    Write-Error "AAPT2 compile failed with exit code $LASTEXITCODE"
    exit 1
}

& $AAPT2 link -o 'build\unaligned.apk' -I $ANDROID_JAR -R 'build\res.zip' --manifest 'app\src\main\AndroidManifest.xml' `
    --min-sdk-version 26 --target-sdk-version 34 `
    --version-code $newCode --version-name $newName --replace-version
if ($LASTEXITCODE -ne 0) {
    Write-Error "AAPT2 link failed with exit code $LASTEXITCODE"
    exit 1
}

# 写入 classes.dex 到 APK
& $JAR -uf 'build\unaligned.apk' -C 'build\dex' classes.dex
if ($LASTEXITCODE -ne 0) {
    Write-Error "JAR update failed with exit code $LASTEXITCODE"
    exit 1
}

# 4. Uber-apk-signer 签名
Write-Host '[4/4] Signing APK with V1/V2/V3...'
Remove-Item -Path 'build\out' -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path 'build\out' -Force | Out-Null
& $JAVA -jar $UBER --apks 'build\unaligned.apk' --out 'build\out'
if ($LASTEXITCODE -ne 0) {
    Write-Error "Signing failed with exit code $LASTEXITCODE"
    exit 1
}

$signedApk = "build\out\unaligned-aligned-debugSigned.apk"
$releasesDir = "$PSScriptRoot\releases"
if (!(Test-Path $releasesDir)) {
    New-Item -ItemType Directory -Path $releasesDir -Force | Out-Null
}
$versionedApkName = "$releasesDir\CleanBus_v$newName.apk"
$releaseApkName = "$releasesDir\CleanBus_Release.apk"

Copy-Item $signedApk $versionedApkName -Force
Copy-Item $signedApk $releaseApkName -Force

Write-Host "BUILD SUCCESS: Created $versionedApkName and $releaseApkName"
Get-Item $versionedApkName, $releaseApkName | Select-Object Name, Length, LastWriteTime
