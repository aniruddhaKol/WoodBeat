# Builds the Woodbeat APK with the local toolchain in C:\workspace\Android.
# -DebugBuild builds the debug variant; default is release (signed with the debug key, not for store upload).
param(
    [string]$Android = 'C:\workspace\Android',
    [switch]$DebugBuild,
    [string]$Output = (Join-Path $PSScriptRoot 'build\Woodbeat.apk')
)
$jdk = Join-Path $Android 'jdk-17.0.21.0.2+1'   # Gradle 8.13 runs on Java 17-23
$sdk = Join-Path $Android 'Sdk'
$tmp = Join-Path $Android 'tmp'
$gradle = Join-Path $Android 'gradle-8.13\bin\gradle.bat'
$variant = if ($DebugBuild) { 'debug' } else { 'release' }
New-Item -ItemType Directory -Force $tmp | Out-Null

# Build number +1 on every build (Android versionCode).
$numberFile = Join-Path $PSScriptRoot 'build-number.txt'
$build = 1 + [int]((Get-Content -LiteralPath $numberFile -ErrorAction SilentlyContinue | Select-Object -First 1) -as [int])
Set-Content -LiteralPath $numberFile -Value $build -Encoding ascii
Write-Host "Build number $build"

cmd /c "set `"JAVA_HOME=$jdk`"&& set `"ANDROID_HOME=$sdk`"&& set `"ANDROID_SDK_ROOT=$sdk`"&& set `"TEMP=$tmp`"&& set `"TMP=$tmp`"&& set `"GRADLE_USER_HOME=$Android\gradle-home`"&& `"$gradle`" -p `"$PSScriptRoot`" assemble$variant --project-cache-dir `"$Android\gradle-home\project-cache`" --console=plain 2>&1"
if ($LASTEXITCODE -ne 0) { throw "Build failed ($LASTEXITCODE)." }

if ($DebugBuild) { $Output = $Output -replace '\.apk$', '-debug.apk' }
$apk = Get-ChildItem (Join-Path $PSScriptRoot "build\outputs\apk\$variant") -Filter *.apk | Select-Object -First 1
Copy-Item $apk.FullName $Output -Force
Write-Host "APK: $Output"
