# Samsung PRISM Theme 3 - Slice 1 Fixture Workflow Execution PowerShell Script
$ErrorActionPreference = "Continue"

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "SAMSUNG PRISM THEME 3 - SLICE 1 FIXTURE WORKFLOW EXECUTION" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

$sdkDir = "D:\Softwares\Android\SDK"
if (Test-Path "$sdkDir\platform-tools\adb.exe") {
    $env:Path = "$sdkDir\platform-tools;$sdkDir\emulator;$env:Path"
}

Write-Host "`n[1] Running Slice 1 Fixture Tests with Gradle..." -ForegroundColor Yellow
.\gradlew.bat testDebugUnitTest --tests com.chockXlate.teachablevoice.runtime.Slice1FixtureWorkflowExecutionTest

Write-Host "`n[2] Building Clean Debug APK..." -ForegroundColor Yellow
.\gradlew.bat assembleDebug

Write-Host "`n[3] Deploying to Online Pixel 6 Emulator/Device..." -ForegroundColor Yellow
adb wait-for-device
adb install -r "app\build\outputs\apk\debug\app-debug.apk"

Write-Host "`n[4] Starting Application Process..." -ForegroundColor Yellow
adb shell am force-stop com.chockXlate.teachablevoice
adb shell am start -n com.chockXlate.teachablevoice/.MainActivity
$pid = (adb shell pidof com.chockXlate.teachablevoice 2>&1 | Out-String).Trim()
Write-Host "Application Running with PID: $pid" -ForegroundColor Green

Write-Host "`n================================================================" -ForegroundColor Cyan
Write-Host "SLICE 1 VERIFICATION COMPLETED" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
