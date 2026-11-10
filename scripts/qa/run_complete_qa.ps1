# PostFlight complete local QA orchestrator.
# Public mode is tested inside localhost-only Docker.
# Editable API workflows are tested against isolated temporary files, never user data.
param([switch]$KeepContainers)
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

function Assert-Exit([string]$message) {
    if ($LASTEXITCODE -ne 0) { throw "$message (exit $LASTEXITCODE)" }
}
function Find-JDK {
    $choices = @($env:POSTFLIGHT_QA_JDK_HOME, "C:\Program Files\Java\jdk-21", "C:\Program Files\Java\jdk-25", "C:\Program Files\Java\jdk-17", $env:JAVA_HOME)
    foreach($jdkCandidate in $choices) {
        if ($jdkCandidate -and (Test-Path (Join-Path $jdkCandidate "bin\javac.exe")) -and (Test-Path (Join-Path $jdkCandidate "bin\java.exe"))) { return $jdkCandidate }
    }
    $cmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source) { return (Split-Path (Split-Path $cmd.Source)) }
    throw "JDK 17+ not found. Set POSTFLIGHT_QA_JDK_HOME to a JDK 17/21/25 directory."
}
function Wait-Health([string]$url,[string]$tag) {
    for($i=0;$i -lt 35;$i++){
        try {
            $h = Invoke-RestMethod "$url/api/health" -TimeoutSec 3 -ErrorAction Stop
            if ($h.status -eq "UP") { Write-Host "PASS: $tag healthy"; return }
        } catch { Start-Sleep -Milliseconds 600 }
    }
    throw "$tag did not become healthy at $url"
}
$project = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $project
$branch = (git branch --show-current).Trim()
Assert-Exit "Cannot read current branch"
if ($branch -notin @("main","feat/zernio-visitor-readonly","feat/operational-workflows","feat/governance-workflows")) { throw "Use a PostFlight development branch; currently on $branch" }
if (-not (Test-Path "scripts\qa\full_browser_audit.cjs")) { throw "Missing QA script. Pull the latest feature branch first." }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path $project "qa-reports\complete-$stamp"
$work = Join-Path $env:TEMP ("PostFlight-qa-"+[guid]::NewGuid().ToString("N"))
$toolsRoot = Join-Path $env:TEMP "PostFlight-qa-node"
$containerName = "postflight-qa-readonly"
$demoBase = "http://127.0.0.1:10003"
$editBase = "http://127.0.0.1:10004"
$javaProcess = $null
$docker = $null
$containerStarted = $false
$failed = $false
$summary = @()
New-Item -ItemType Directory -Force -Path $out,$work | Out-Null
Write-Host "PostFlight COMPLETE QA - $stamp"
Write-Host "Reports: $out"
Write-Host "Safety: test runtime is disposable, no production or social accounts are touched."

try {
    Write-Host "[1/7] Check Java, Docker, Python, Node and npm"
    $docker = Get-Command docker.exe -ErrorAction Stop
    $node = Get-Command node.exe -ErrorAction Stop
    $npm = Get-Command npm.cmd -ErrorAction Stop
    $python = Get-Command py.exe -ErrorAction SilentlyContinue
    $pythonArgs = @("-3")
    if(-not $python){ $python = Get-Command python.exe -ErrorAction Stop; $pythonArgs = @() }
    $jdk = Find-JDK
    Write-Host "Using JDK: $jdk"
    & (Join-Path $jdk "bin\javac.exe") -version
    Assert-Exit "javac failed"
    & $python.Source @pythonArgs --version
    Assert-Exit "Python 3 failed"
    & $node.Source --version
    Assert-Exit "Node failed"
    & $docker.Source version --format "{{.Server.Version}}"
    Assert-Exit "Docker engine failed"

    Write-Host "[2/7] Build isolated public Docker image"
    & $docker.Source build -t postflight-enterprise:qa-all .
    Assert-Exit "Docker build failed"
    $found = @(& $docker.Source ps -aq --filter "name=^/$containerName$")
    if($found.Count -gt 0 -and $found[0]){
        & $docker.Source rm -f $containerName | Out-Null
        Assert-Exit "Previous QA container removal failed"
    }
    & $docker.Source run --rm -d --name $containerName -p 127.0.0.1:10003:10000 postflight-enterprise:qa-all | Out-Null
    Assert-Exit "Docker container startup failed"
    $containerStarted=$true
    Wait-Health $demoBase "Public read-only QA container"
    $demoState = Invoke-RestMethod "$demoBase/api/bootstrap" -ErrorAction Stop
    if($demoState.runtime.mode -ne "public-read-only"){throw "Docker QA is not read-only"}
    $summary += "PASS Docker public mode"

    Write-Host "[3/7] Start isolated editable Java backend with copied seed data"
    $classes = Join-Path $work "classes"
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    $javaFiles = @(Get-ChildItem "backend/src/main/java/com/postflight/core","backend/src/main/java/com/postflight/local" -Filter "*.java" -Recurse -File | Select-Object -ExpandProperty FullName)
    if($javaFiles.Count -lt 4){throw "No Java source files found"}
    & (Join-Path $jdk "bin\javac.exe") --release 17 -encoding UTF-8 -d $classes @javaFiles
    Assert-Exit "Java 17 compilation failed"
    $root = Join-Path $work "runtime"
    New-Item -ItemType Directory -Force -Path (Join-Path $root "docs"),(Join-Path $root "data") | Out-Null
    Copy-Item "docs\seed.json" (Join-Path $root "docs\seed.json")
    Copy-Item "web" (Join-Path $root "web") -Recurse
    $oldDemo=$env:POSTFLIGHT_PUBLIC_DEMO
    $oldPort=$env:PORT
    try {
        $env:POSTFLIGHT_PUBLIC_DEMO="false"
        $env:PORT="10004"
        $javaArgs=@("--add-modules","jdk.httpserver",('-Dpostflight.root="'+$root+'"'),"-cp",('"'+$classes+'"'),"com.postflight.local.Main","10004")
        $javaProcess = Start-Process -FilePath (Join-Path $jdk "bin\java.exe") -ArgumentList $javaArgs -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $out "editable-stdout.log") -RedirectStandardError (Join-Path $out "editable-stderr.log")
    } finally {
        $env:POSTFLIGHT_PUBLIC_DEMO=$oldDemo
        $env:PORT=$oldPort
    }
    Wait-Health $editBase "Disposable editable Java server"
    $editState = Invoke-RestMethod "$editBase/api/bootstrap" -ErrorAction Stop
    if($editState.runtime.mode -eq "public-read-only"){throw "Editable QA server unexpectedly read-only"}
    $summary += "PASS isolated editable server"

    Write-Host "[4/7] Run backend lifecycle, policy, media, security and frontend-state contracts"
    foreach($test in @("scripts/test_integration.py","scripts/test_extensions.py","scripts/test_security.py","scripts/test_operational_workflows.py","scripts/test_governance_workflows.py")){
        & $python.Source @pythonArgs $test $editBase
        Assert-Exit "$test"
        $summary += "PASS $test"
    }
    & $node.Source "scripts/test_frontend_state.cjs" $editBase
    Assert-Exit "Frontend state contracts"
    $summary += "PASS frontend state contracts"

    Write-Host "[5/7] Ensure temporary Playwright dependency exists"
    $pwManifest=Join-Path $toolsRoot "node_modules\playwright\package.json"
    $pwInstalled=if(Test-Path $pwManifest){(Get-Content $pwManifest -Raw | ConvertFrom-Json).version}else{""}
    if($pwInstalled -ne "1.65.0"){
        New-Item -ItemType Directory -Force -Path $toolsRoot | Out-Null
        & $npm.Source install --prefix $toolsRoot --no-save --no-audit --no-fund playwright@1.65.0
        Assert-Exit "Pinned Playwright npm installation"
    }
    $oldNodePath=$env:NODE_PATH
    $oldDemoUrl=$env:PF_QA_DEMO
    $oldEditUrl=$env:PF_QA_EDIT
    $oldOutput=$env:PF_QA_OUTPUT
    try {
        $env:NODE_PATH=Join-Path $toolsRoot "node_modules"
        $env:PF_QA_DEMO=$demoBase
        $env:PF_QA_EDIT=$editBase
        $env:PF_QA_OUTPUT=$out
        Write-Host "[6/7] Test 14 routes, buttons, nested tabs, modal interactions and user journeys"
        & $node.Source "scripts/qa/full_browser_audit.cjs"
        Assert-Exit "Browser UI audit"
    } finally {
        $env:NODE_PATH=$oldNodePath
        $env:PF_QA_DEMO=$oldDemoUrl
        $env:PF_QA_EDIT=$oldEditUrl
        $env:PF_QA_OUTPUT=$oldOutput
    }
    $summary += "PASS browser route and workflow audit"

    Write-Host "[7/7] Create truthful test report"
    $report=Get-Content (Join-Path $out "browser-report.json") -Raw | ConvertFrom-Json
    $summary += ("Routes audited: "+$report.coverage.routesInspected)
    $summary += ("Visible controls inventoried: "+$report.coverage.controlsInventory)
    $summary += ("Controls interacted: "+$report.coverage.controlsClicked)
    $summary += ("Nested controls discovered: "+$report.coverage.nestedControlsDiscovered)
    $summary += ("Known placeholder actions: "+$report.coverage.unimplementedControls)
    $summary += ("Explicitly untested controls: "+$report.coverage.unexercisedControls)
    $summary += "NOT TESTED: real Zernio OAuth, external accounts, publishing, third-party webhooks"
    $summary | Set-Content (Join-Path $out "SUMMARY.txt")
    Write-Host "SUCCESS: all executed local QA assertions passed"
    $summary | ForEach-Object { Write-Host $_ }
    Write-Host "Screenshots, logs and inventories: $out"
} catch {
    $failed = $true
    $message = $_.Exception.Message
    Write-Host "FAILED: $message" -ForegroundColor Red
    Write-Host "See artifacts: $out"
    @("FAIL $message") | Set-Content (Join-Path $out "SUMMARY.txt")
} finally {
    if($javaProcess){
        try { if(-not $javaProcess.HasExited){ Stop-Process -Id $javaProcess.Id -Force -ErrorAction Stop } }
        catch { Write-Warning "Could not stop test Java process: $_" }
    }
    if($containerStarted -and -not $KeepContainers -and $docker){
        & $docker.Source rm -f $containerName | Out-Null
    }
    if(Test-Path $work){Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue}
    Write-Host "The original application data and public Render deployment were not modified."
}
if($failed){exit 1}else{exit 0}
