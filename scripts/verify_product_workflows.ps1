# Local product smoke validation. No external social account or API key is needed.
$ErrorActionPreference = "Stop"
$image = "postflight-enterprise:product-test"
$container = "postflight-product-test"
$base = "http://127.0.0.1:10002"

if ((git branch --show-current).Trim() -ne "feat/zernio-visitor-readonly") { throw "Switch to feature branch first." }

Write-Host "1/6: Compile Java 17 and build Docker image"
docker build -t $image .
if ($LASTEXITCODE -ne 0) { throw "Docker build failed." }
$old = @(docker ps -aq --filter "name=^/$container$")
if ($old.Count -gt 0 -and $old[0]) { docker rm -f $container | Out-Null }
docker run --rm -d --name $container -p 127.0.0.1:10002:10000 $image | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Could not start Docker container." }

Write-Host "2/6: Verify Java API health and public mode"
$health = $null
for ($i = 0; $i -lt 20; $i++) {
    try { $health = Invoke-RestMethod "$base/api/health" -TimeoutSec 3; break }
    catch { Start-Sleep -Milliseconds 500 }
}
if ($null -eq $health -or $health.status -ne "UP") { throw "Health check failed." }
$stateBefore = Invoke-RestMethod "$base/api/bootstrap"
if ($stateBefore.runtime.mode -ne "public-read-only") { throw "Public demo mode missing." }

Write-Host "3/6: Test actual stateless financial policy checks"
$risk = Invoke-RestMethod -Uri "$base/api/demo/preflight" -Method POST -ContentType "application/json" -Body (@{ content = "Invest now! Guaranteed 20% returns and instant profits. Contact advice@demo.example today."; platform = "linkedin"; hasMedia = $false; altText = "" } | ConvertTo-Json -Compress)
if ($risk.status -ne "BLOCKED" -or $risk.summary.critical -lt 1) { throw "Risky content was not blocked." }
Write-Host "PASS: Unverified return guarantee blocked"
$safer = Invoke-RestMethod -Uri "$base/api/demo/preflight" -Method POST -ContentType "application/json" -Body (@{ content = "Learn about budgeting and financial planning. Investing involves risk, and past performance does not guarantee future results."; platform = "linkedin"; hasMedia = $false; altText = "" } | ConvertTo-Json -Compress)
if ($safer.status -eq "BLOCKED" -or [string]::IsNullOrWhiteSpace($safer.fingerprint)) { throw "Safer sample failed." }
Write-Host "PASS: Safer sample analyzed; fingerprint created"
$stateAfter = Invoke-RestMethod "$base/api/bootstrap"
if ($stateAfter.drafts.Count -ne $stateBefore.drafts.Count) { throw "Stateless preview modified saved drafts." }

Write-Host "4/6: Enforce protected writes and reject cross-origin requests"
function Status-For([scriptblock]$Action) {
    try { & $Action | Out-Null; return 200 }
    catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }; throw }
}
$blocked = Status-For { Invoke-WebRequest "$base/api/drafts" -Method POST -ContentType "application/json" -Body "{}" -UseBasicParsing -ErrorAction Stop }
$crossOrigin = Status-For { Invoke-WebRequest "$base/api/demo/preflight" -Method POST -ContentType "application/json" -Headers @{ Origin = "https://unexpected.example" } -Body "{}" -UseBasicParsing -ErrorAction Stop }
if ($blocked -ne 403 -or $crossOrigin -ne 403) { throw "API security checks failed: $blocked / $crossOrigin" }

Write-Host "5/6: Verify Zernio is GET-only and requires visitor credentials"
$noKey = Status-For { Invoke-WebRequest "$base/api/zernio/live" -UseBasicParsing -ErrorAction Stop }
$post = Status-For { Invoke-WebRequest "$base/api/zernio/live" -Method POST -ContentType "application/json" -Body "{}" -UseBasicParsing -ErrorAction Stop }
if ($noKey -ne 400 -or $post -ne 405) { throw "Zernio read-only check failed: $noKey / $post" }

Write-Host "6/6: Verify navigation and scripts"
$html = (Invoke-WebRequest $base -UseBasicParsing).Content
$app = (Invoke-WebRequest "$base/assets/app.js" -UseBasicParsing).Content
$exp = (Invoke-WebRequest "$base/assets/experience.js" -UseBasicParsing).Content
$css = Invoke-WebRequest "$base/assets/experience.css" -UseBasicParsing
if ($html -notmatch "experience.js" -or $html -notmatch "experience.css" -or $app -notmatch "Try PostFlight" -or $app -notmatch "Zernio Live Monitor" -or $exp -notmatch "/api/demo/preflight" -or $exp -notmatch "/api/zernio/live" -or $css.StatusCode -ne 200) { throw "Frontend workflow assets are missing." }
Write-Host ""
Write-Host "SUCCESS: 6/6 workflow checks passed"
Write-Host "Real Zernio data requires a connected social account and a restricted read-only key."
Start-Process "$base/#/playground"
