param(
    [ValidateSet("debug", "release")]
    [string]$Variant = "debug"
)

$ErrorActionPreference = "Stop"
$projectDirectory = $PSScriptRoot
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Install JDK 21 and set JAVA_HOME, or use Android Studio's JDK 21."
}
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT -and
    -not (Test-Path -LiteralPath (Join-Path $projectDirectory "local.properties"))) {
    throw "Install Android SDK API 35/build-tools 35.0.0 and set ANDROID_HOME (or sdk.dir in local.properties)."
}

$taskVariant = (Get-Culture).TextInfo.ToTitleCase($Variant)
Push-Location $projectDirectory
try {
    & ./gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug ":app:assemble$taskVariant"
    if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE." }
    $outputDirectory = Join-Path $projectDirectory "dist"
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $apk = Join-Path $projectDirectory "app/build/outputs/apk/$Variant/app-$Variant.apk"
    $destination = Join-Path $outputDirectory "gemma-translator-s24-$Variant.apk"
    Copy-Item -LiteralPath $apk -Destination $destination
    Write-Output "APK ready: $destination"
} finally {
    Pop-Location
}
