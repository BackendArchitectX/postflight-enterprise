# Runs on Windows PowerShell 5.1+ from the repository root.
# Builds and tests the local read-only Zernio demo without contacting a social account.
$ErrorActionPreference = "Stop"
$container = "postflight-zernio-test"
$image = "postflight-enterprise:zernio-test"
$base = "http://127.0.0.1:10001"

if ((git branch --show-current).Trim() -ne "feat/zernio-visitor-readonly") {
    throw "Switch to feat/zernio-visitor-readonly before running this test."
}
Write-Host "1/5: Building Java 17 Docker image..."
docker build -t $image .
if ($LASTEXITCODE -ne 0) { throw "Docker image build failed." }

$existing = @(docker ps -aq --filter "name=^/$container$")
if ($existing.Count -gt 0 -and $existing[0]) {
    docker rm -f $container | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to replace previous test container." }
}
Write-Host "2/5: Starting isolated localhost container..."
docker run --rm -d --name $container -p 127.0.0.1:10001:10000 $image | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to start test container." }

$health = $null
for ($i = 0; $i -lt 20; $i++) {
    try {
        $health = Invoke-RestMethod "$base/api/health" -TimeoutSec 3
        break
    } catch { Start-Sleep -Milliseconds 500 }
}
if ($null -eq $health -or $health.status -ne "UP") { throw "Health endpoint unavailable." }
$bootstrap = Invoke-RestMethod "$base/api/bootstrap"
if ($bootstrap.runtime.mode -ne "public-read-only") { throw "Read-only mode is not active." }
Write-Host "3/5: PASS backend health and read-only mode"

$html = (Invoke-WebRequest $base -UseBasicParsing).Content
$js = (Invoke-WebRequest "$base/assets/app.js" -UseBasicParsing).Content
$visitor = (Invoke-WebRequest "$base/assets/zernio-visitor.js" -UseBasicParsing).Content
if ($html -notmatch "zernio-visitor.js" -or
    $js -notmatch "Try the integrations" -or
    $js -notmatch "PUBLIC DEMO" -or
    $visitor -notmatch "zernio-sample-btn") { throw "New visitor UI assets missing." }
Write-Host "4/5: PASS new UI, clear demo label and Try example button"

function Get-HttpStatus([scriptblock]$Request) {
    try {
        & $Request | Out-Null
        return 200
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        throw
    }
}
$missingKeyStatus = Get-HttpStatus { Invoke-WebRequest "$base/api/zernio/accounts" -UseBasicParsing -ErrorAction Stop }
$writeStatus = Get-HttpStatus { Invoke-WebRequest "$base/api/drafts" -Method POST -ContentType "application/json" -Body "{}" -UseBasicParsing -ErrorAction Stop }
if ($missingKeyStatus -ne 400 -or $writeStatus -ne 403) {
    throw "Expected GET without key=400 and public POST=403. Got $missingKeyStatus / $writeStatus."
}
Write-Host "5/5: PASS missing-key check and public write protection"
Write-Host ""
Write-Host "SUCCESS: All 5 local Zernio demo checks passed."
Write-Host "Public URL preview requires no login or key. Real Zernio inspection remains optional."
Write-Host "Opening the updated Integrations page..."
Start-Process "$base/#/integrations"
