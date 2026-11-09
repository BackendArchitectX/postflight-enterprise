$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$project = $PSScriptRoot
$reportDir = Join-Path $project 'qa-reports'
$exitStatus = 0
$build = Join-Path $env:TEMP ('postflight-qa-classes-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $build | Out-Null
try {
    Write-Host ''
    Write-Host 'PostFlight Enterprise - Automated QA (Java 17)' -ForegroundColor Cyan
    Write-Host 'Your normal project data will NOT be changed.' -ForegroundColor Green
    if (-not (Get-Command javac -ErrorAction SilentlyContinue)) { throw 'JDK 17+ is needed. javac was not found on PATH.' }
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java 17+ is needed. java was not found on PATH.' }
    Write-Host 'Compiling the actual Java backend and QA runner...' -ForegroundColor Gray
    $files = @(Get-ChildItem -Path 'backend/src/main/java/com/postflight/core','backend/src/main/java/com/postflight/local' -Filter '*.java' -Recurse | ForEach-Object FullName)
    $files += (Join-Path $project 'qa/PostFlightQARunner.java')
    & javac --release 17 -encoding UTF-8 -d $build $files
    if ($LASTEXITCODE -ne 0) { throw 'QA compilation failed. Please check the output.' }
    Write-Host 'Running isolated checks on a free localhost port...' -ForegroundColor Cyan
    & java --add-modules jdk.httpserver -cp $build PostFlightQARunner $project $reportDir
    $exitStatus = $LASTEXITCODE
    if ($exitStatus -ne 0) { Write-Host 'Some tests failed. Open qa-reports\POSTFLIGHT_QA_RESULTS.html for details.' -ForegroundColor Yellow }
    else { Write-Host 'Automated checks completed successfully.' -ForegroundColor Green }
    Write-Host ''
    Write-Host ('Report: ' + (Join-Path $reportDir 'POSTFLIGHT_QA_RESULTS.html')) -ForegroundColor Cyan
    Write-Host ''
    & (Join-Path $project 'qa/LIVE_READONLY.ps1') -ReportDir $reportDir
    $html = Join-Path $reportDir 'POSTFLIGHT_QA_RESULTS.html'
    if (Test-Path $html) { Start-Process $html }
} finally {
    Remove-Item -LiteralPath $build -Recurse -Force -ErrorAction SilentlyContinue
}

if ($exitStatus -ne 0) { exit $exitStatus }
