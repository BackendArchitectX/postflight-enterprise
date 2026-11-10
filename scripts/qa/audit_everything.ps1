<#
PostFlight audit starter. Conservatively reports what was actually tested.
Supports PowerShell 5.1. Does not need Clerk credentials for default modes.
This is NOT a complete live role/CTA audit; read the generated summary.
#>
[CmdletBinding()]
param(
  [ValidateSet('Quick','Complete','Extended','LiveAuth','ReportOnly')][string]$Mode = 'Complete',
  [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $root
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$out = Join-Path $root ('qa-reports\exhaustive-' + $stamp)
$report = New-Object 'System.Collections.Generic.List[string]'
$report.Add('# PostFlight audit (partial automated verification)')
$report.Add('')
$report.Add('Mode: ' + $Mode)
$report.Add('This run does NOT claim every CTA or live Clerk role is verified.')
$report.Add('')
$commands = New-Object 'System.Collections.Generic.List[string]'
$commands.Add('Static control/API discovery')
if ($Mode -eq 'Quick' -or $Mode -eq 'Complete' -or $Mode -eq 'Extended') {
  $commands.Add('Java 17-source compilation and disposable workspace security tests')
  $commands.Add('Node JavaScript syntax checks')
  $commands.Add('Python inventory and UI-heading regression tests')
}
if ($Mode -eq 'Complete' -or $Mode -eq 'Extended') {
  $commands.Add('Existing isolated complete-qa suite (Docker, Python, Playwright)')
}
if ($Mode -eq 'Extended') {
  $commands.Add('Extended disposable API and WCAG checks; unimplemented mandatory categories explicitly BLOCKED')
}
if ($Mode -eq 'LiveAuth') {
  $commands.Add('Supervised Clerk role test: BLOCKED (not automated)')
}
Write-Host ('PostFlight audit mode: ' + $Mode)
$commands | ForEach-Object { Write-Host ('  - ' + $_) }
if ($DryRun) { Write-Host 'DryRun: no changes made.'; exit 0 }
if ($Mode -eq 'ReportOnly') {
  $prior = @(Get-ChildItem (Join-Path $root 'qa-reports') -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'exhaustive-*' } | Sort-Object LastWriteTime -Descending)
  if ($prior.Count -eq 0) { Write-Host 'No previous exhaustive audit report exists.'; exit 2 }
  $summary = Join-Path $prior[0].FullName 'SUMMARY.md'
  if (Test-Path $summary) { Get-Content $summary } else { Write-Host 'Summary not available' }
  exit 0
}
if ($Mode -eq 'LiveAuth') {
  Write-Host 'BLOCKED: live Clerk testing needs authorized development accounts and supervised browser actions.'
  Write-Host 'Use separate browser profiles for OWNER/VIEWER; test GET and denied POST/PUT on isolated workspaces.'
  exit 2
}
# Extended mode delegates to the existing verified Complete runner with additional
# disposable API + accessibility tests, then generates a strict gap-aware report.
if ($Mode -eq 'Extended') {
  $startEpoch = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
  $extendedOut = Join-Path $root ('qa-reports\\extended-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
  $oldExtended = [Environment]::GetEnvironmentVariable('PF_EXTENDED_QA','Process')
  try {
    $env:PF_EXTENDED_QA = '1'
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'audit_everything.ps1') -Mode Complete
    $completeExit = $LASTEXITCODE
  } finally {
    if ($null -eq $oldExtended) {
      Remove-Item Env:PF_EXTENDED_QA -ErrorAction SilentlyContinue
    } else {
      $env:PF_EXTENDED_QA = $oldExtended
    }
  }
  $py = Get-Command python.exe -ErrorAction SilentlyContinue
  $pyArgs = @()
  if (-not $py) { $py = Get-Command py.exe -ErrorAction Stop; $pyArgs = @('-3') }
  & $py.Source @pyArgs (Join-Path $PSScriptRoot 'extended_report.py') `
      --qa-root (Join-Path $root 'qa-reports') `
      --output $extendedOut `
      --started-epoch $startEpoch `
      --complete-exit $completeExit
  $extendedExit = $LASTEXITCODE
  Write-Host ('Extended QA report: ' + $extendedOut)
  exit $extendedExit
}

# Reject port/container conflicts instead of stopping or replacing unrelated resources.
if ($Mode -eq 'Complete' -or $Mode -eq 'Extended') {
  foreach ($port in @(10003,10004)) {
    $occupied = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($occupied) { throw ('QA port '+ $port +' is already in use; no processes were stopped.') }
  }
  if (Get-Command docker -ErrorAction SilentlyContinue) {
    $existing = & docker ps -a --filter 'name=^/postflight-qa-readonly$' --format '{{.Names}}' 2>$null
    if ($existing -contains 'postflight-qa-readonly') { throw 'Existing postflight-qa-readonly container; will not remove it.' }
  }
}
New-Item -ItemType Directory -Force -Path $out | Out-Null
$passed = $false
try {
  $branch = (& git branch --show-current).Trim()
  $sha = (& git rev-parse HEAD).Trim()
  $report.Add('Branch: ' + $branch + ' ; SHA: ' + $sha)
  $dirty = @(& git status --porcelain)
  $report.Add('Working tree entries: ' + $dirty.Count + ' (not modified by audit)')
  $python = Get-Command python -ErrorAction SilentlyContinue
  if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
  if (-not $python) { throw 'Python 3 required for static discovery' }
  & $python.Source (Join-Path $PSScriptRoot 'discover_controls.py') --repo $root --out $out
  if ($LASTEXITCODE -ne 0) { throw 'Static inventory failed' }
  $report.Add('Static control/API inventory: generated, NOT automatically verified')
  $report.Add('')
  if ($Mode -eq 'Quick' -or $Mode -eq 'Complete' -or $Mode -eq 'Extended') {
    $temp = Join-Path $env:TEMP ('PostFlight-audit-v3-' + [guid]::NewGuid().ToString('N'))
    try {
      $classes = Join-Path $temp 'classes'
      New-Item -ItemType Directory -Force -Path $classes | Out-Null
      $sources = @(Get-ChildItem -Path @((Join-Path $root 'backend/src/main/java/com/postflight/core'),
                       (Join-Path $root 'backend/src/main/java/com/postflight/local')) -Recurse -Filter '*.java' |
                       ForEach-Object FullName)
      & javac --release 17 -encoding UTF-8 -d $classes $sources
      if ($LASTEXITCODE -ne 0) { throw 'Java 17-source compilation failed' }
      $checks = @('scripts/qa/WorkspaceSecurityCheck.java','scripts/qa/WorkspaceDraftSecurityCheck.java')
      & javac --release 17 -encoding UTF-8 -cp $classes -d $classes $checks
      if ($LASTEXITCODE -ne 0) { throw 'Java security test compilation failed' }
      & java -cp $classes com.postflight.local.WorkspaceSecurityCheck
      if ($LASTEXITCODE -ne 0) { throw 'Workspace security tests failed' }
      & java -cp $classes com.postflight.local.WorkspaceDraftSecurityCheck
      if ($LASTEXITCODE -ne 0) { throw 'Draft security tests failed' }
      $report.Add('PASS: Java compilation and workspace unit regressions')
    } finally {
      if (Test-Path $temp) { Remove-Item $temp -Recurse -Force }
    }
    $assets = @(Get-ChildItem (Join-Path $root 'web/assets') -Filter '*.js' -File)
    foreach ($asset in $assets) {
      & node --check $asset.FullName
      if ($LASTEXITCODE -ne 0) { throw ('JavaScript syntax failed: '+$asset.Name) }
    }
    $report.Add('PASS: ' + $assets.Count + ' frontend JavaScript file syntax checks')
    & $python.Source (Join-Path $PSScriptRoot 'test_audit_discovery.py')
    if ($LASTEXITCODE -ne 0) { throw 'Inventory test self-check failed' }
    & $python.Source (Join-Path $PSScriptRoot 'test_ui_header.py')
    if ($LASTEXITCODE -ne 0) { throw 'Workspace heading regression test failed' }
    $report.Add('PASS: inventory regression and workspace heading contract')
  }
  if ($Mode -eq 'Complete' -or $Mode -eq 'Extended') {
    # The existing runner expects an unconfigured Clerk test environment.
    $envBackup = @{}
    foreach ($entry in @(Get-ChildItem Env:)) {
      if ($entry.Name -like 'POSTFLIGHT_CLERK_*' -or $entry.Name -like 'POSTFLIGHT_COGNITO_*' -or
          $entry.Name -eq 'POSTFLIGHT_PUBLIC_DEMO' -or $entry.Name -eq 'POSTFLIGHT_PRIVATE_PROXY') {
        $envBackup[$entry.Name] = $entry.Value
      }
    }
    try {
      foreach ($name in @($envBackup.Keys)) { Remove-Item ('Env:'+$name) -ErrorAction SilentlyContinue }
      & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run_complete_qa.ps1')
      if ($LASTEXITCODE -ne 0) { throw 'Existing complete QA suite failed' }
      $report.Add('PASS: Existing complete QA; see its separate generated report')
    } finally {
      foreach ($key in @($envBackup.Keys)) { Set-Item ('Env:'+$key) $envBackup[$key] }
    }
  }
  if ($Mode -eq 'Extended') {
    $report.Add('BLOCKED: Extended browser engines, mutation, load, live-auth and manual accessibility checks not implemented')
    throw 'Extended coverage is not implemented; do not claim an exhaustive PASS.'
  }
  $passed = $true
} catch {
  $report.Add('FAIL / BLOCKED: ' + $_.Exception.Message)
  Write-Host ('AUDIT FAILED: ' + $_.Exception.Message) -ForegroundColor Red
} finally {
  $report.Add('')
  $report.Add('Unverified: real Clerk OWNER/VIEWER HTTP matrix, browser modal behavior, third-party Zernio actions,')
  $report.Add('cross-workspace browser state, crash recovery, load tests and full WCAG review.')
  $report.Add('Verdict: PARTIAL AUTOMATED EVIDENCE ONLY. Full V3 mandate remains incomplete.')
  $report | Set-Content -Path (Join-Path $out 'SUMMARY.md') -Encoding UTF8
  Write-Host ('Report: '+$out)
}
if ($passed) { Write-Host 'PASS: executed automated subset; NOT full product certification.'; exit 0 }
exit 1
