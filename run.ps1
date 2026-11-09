$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$root = $PSScriptRoot
Write-Host 'PostFlight Enterprise — Java 17 local mode' -ForegroundColor Cyan
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) { throw 'Please install JDK 17 or newer and add javac to PATH.' }
New-Item -ItemType Directory -Force -Path 'build/classes' | Out-Null
$sourceFiles = @(Get-ChildItem -Path 'backend/src/main/java/com/postflight/core','backend/src/main/java/com/postflight/local' -Filter '*.java' -Recurse | ForEach-Object FullName)
& javac --release 17 -encoding UTF-8 -d 'build/classes' $sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
Write-Host 'Starting http://127.0.0.1:8080/ (Ctrl+C to stop)...' -ForegroundColor Green
& java --add-modules jdk.httpserver -cp 'build/classes' com.postflight.local.Main 8080
