# PostFlight Enterprise read-only diagnostics - Windows PowerShell 5.1 compatible.
# This script makes GET requests only. It cannot edit drafts, publish posts, or issue certificates.
param([string]$ReportDir = (Join-Path $PSScriptRoot '..\qa-reports'))
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8080'
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$items = New-Object 'System.Collections.Generic.List[object]'

function Record([string]$Name, [bool]$Ok, [string]$Detail) {
    [void]$items.Add([pscustomobject]@{
        Check = $Name
        Status = $(if ($Ok) { 'PASS' } else { 'FAIL' })
        Detail = $Detail
    })
    $color = if ($Ok) { 'Green' } else { 'Yellow' }
    $prefix = if ($Ok) { 'PASS ' } else { 'FAIL ' }
    Write-Host ('  ' + $prefix + $Name + ' - ' + $Detail) -ForegroundColor $color
}

function GetApi([string]$Path) {
    Invoke-RestMethod -Uri ($base + $Path) -Method GET -TimeoutSec 7
}

function GetRows([string]$Path) {
    # Windows PowerShell can wrap Invoke-RestMethod JSON arrays in an extra array layer.
    # Always flatten the response into a List so Count and item properties are reliable.
    $raw = GetApi $Path
    $rows = New-Object 'System.Collections.Generic.List[object]'
    if ($null -ne $raw) {
        foreach ($item in $raw) {
            if ($null -eq $item) { continue }
            if ($item -is [System.Array]) {
                foreach ($nested in $item) {
                    if ($null -ne $nested) { [void]$rows.Add($nested) }
                }
            } else {
                [void]$rows.Add($item)
            }
        }
    }
    return ,$rows
}

Write-Host 'PostFlight LIVE read-only checks (127.0.0.1:8080)' -ForegroundColor Cyan
Write-Host '  No records will be created or modified.' -ForegroundColor DarkGray
try {
    $health = GetApi '/api/health'
} catch {
    Write-Host '  Server not reachable. Live checks skipped; isolated Java results are unaffected.' -ForegroundColor Yellow
    exit 0
}
Record 'Health is UP' ($health.status -eq 'UP') ([string]$health.status)
Record 'Publishing disabled' ($health.publishing -eq 'DISABLED') ([string]$health.publishing)

try {
    $session = GetApi '/api/session'
    Record 'Demo unauthenticated' (-not [bool]$session.authenticated) ([string]$session.role)
} catch {
    Record 'Demo unauthenticated' $false ('Request failed: ' + $_.Exception.Message)
}

try {
    $audit = GetApi '/api/audit'
    $events = New-Object 'System.Collections.Generic.List[object]'
    foreach ($event in $audit.events) { [void]$events.Add($event) }
    Record 'Audit chain valid' ([bool]$audit.chainValid) ('Events: ' + $events.Count)
} catch {
    Record 'Audit chain valid' $false ('Request failed: ' + $_.Exception.Message)
}

try {
    $metrics = GetApi '/api/metrics'
    Record 'Local metrics accessible' ($metrics.totalDrafts -ge 1) ('Drafts: ' + $metrics.totalDrafts + ', Certificates: ' + $metrics.releaseCertificates)
} catch {
    Record 'Local metrics accessible' $false ('Request failed: ' + $_.Exception.Message)
}

try {
    $drafts = GetRows '/api/drafts'
    $target = $null
    foreach ($draft in $drafts) {
        if ([string]$draft.id -eq 'PF-2103') { $target = $draft; break }
    }
    if ($null -ne $target) {
        Record 'PF-2103 located' $true ('Version: ' + $target.version + ', status: ' + $target.status)
        $runs = GetRows '/api/validations'
        $current = $null
        foreach ($run in $runs) {
            if ([string]$run.draftId -eq 'PF-2103') {
                if ($null -eq $current -or [string]$run.createdAt -gt [string]$current.createdAt) { $current = $run }
            }
        }
        if ($null -ne $current) {
            $matching = ([string]$target.version -eq [string]$current.draftVersion)
            Record 'PF-2103 validation covers current version' $matching ('Draft v' + $target.version + ', latest validation v' + $current.draftVersion + ', verdict: ' + $current.verdict)
        } else {
            Record 'PF-2103 validation covers current version' $false 'No validation run found for this draft.'
        }
    } else {
        Write-Host '  SKIP PF-2103: Not present. This is expected if sample data was reset.' -ForegroundColor DarkYellow
    }
} catch {
    Record 'Draft and validation retrieval' $false ('Request failed: ' + $_.Exception.Message)
}

$issued = New-Object 'System.Collections.Generic.List[object]'
try {
    $issued = GetRows '/api/releases'
    Record 'Issued certificate list accessible' $true ('Found: ' + $issued.Count)
} catch {
    Record 'Issued certificate list accessible' $false ('Request failed: ' + $_.Exception.Message)
}

if ($issued.Count -gt 0) {
    $allSigned = $true
    $problems = New-Object 'System.Collections.Generic.List[string]'
    foreach ($release in $issued) {
        $id = [string]$release.id
        if ([string]::IsNullOrWhiteSpace($id)) {
            $allSigned = $false
            [void]$problems.Add('Certificate without an ID')
            continue
        }
        try {
            $safeId = [uri]::EscapeDataString($id)
            $verify = GetApi ('/api/releases/' + $safeId + '/verify')
            if ($verify.signatureValid -ne $true -or $verify.published -ne $false) {
                $allSigned = $false
                [void]$problems.Add(($id + ' signatureValid=' + $verify.signatureValid + ', published=' + $verify.published))
            }
        } catch {
            $allSigned = $false
            [void]$problems.Add(($id + ': ' + $_.Exception.Message))
        }
    }
    $detail = 'Checked: ' + $issued.Count
    if ($problems.Count -gt 0) { $detail += '; ' + ($problems.ToArray() -join '; ') }
    Record 'Stored local certificates verify and are unpublished' $allSigned $detail
} else {
    Write-Host '  SKIP certificate verification: No issued local certificates found.' -ForegroundColor DarkYellow
}

$groups = @{}
foreach ($release in $issued) {
    $key = ([string]$release.draftId) + '|' + ([string]$release.contentHash)
    if (-not $groups.ContainsKey($key)) { $groups[$key] = 0 }
    $groups[$key] = $groups[$key] + 1
}
$duplicateCount = 0
foreach ($key in $groups.Keys) { if ($groups[$key] -gt 1) { $duplicateCount++ } }
Write-Host ('  INFO: ' + $duplicateCount + ' draft/content groups have multiple certificates (this build permits them).') -ForegroundColor DarkYellow

# ToArray() avoids a Windows PowerShell generic-list binder error from @($items).
$result = [pscustomobject]@{
    timestamp = (Get-Date).ToString('o')
    target = $base
    mode = 'READ_ONLY'
    results = $items.ToArray()
    duplicateCertificateGroups = $duplicateCount
}
$jsonPath = Join-Path $ReportDir 'POSTFLIGHT_LIVE_READONLY.json'
$csvPath = Join-Path $ReportDir 'POSTFLIGHT_LIVE_READONLY.csv'
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
$items.ToArray() | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding UTF8

$passed = 0; $failed = 0
foreach ($item in $items) {
    if ($item.Status -eq 'PASS') { $passed++ }
    elseif ($item.Status -eq 'FAIL') { $failed++ }
}
Write-Host ('LIVE diagnostics: ' + $passed + ' PASS, ' + $failed + ' FAIL.') -ForegroundColor $(if ($failed -eq 0) { 'Green' } else { 'Yellow' })
Write-Host ('Saved live report: ' + $jsonPath) -ForegroundColor Cyan
Write-Host 'The separate 213-check Java suite result is not changed by these live diagnostics.' -ForegroundColor DarkGray
