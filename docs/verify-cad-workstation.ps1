# Pre-flight check for the Stage 3-6 CAD workstation (docs/setup-cad-workstation.md).
# ASCII-only on purpose: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI (cp949),
# which corrupts non-ASCII text and breaks string terminators (see
# windows-migration.md section 7.9 for the exact failure this caused before).
#
# This script only checks preconditions -- it does not install or configure anything.
# Run it after docs/setup-cad-workstation.md sections 1-4, before section 6's smoke tests.

$ErrorActionPreference = 'Continue'
$failures = 0
$warnings = 0

function Check-Pass([string]$msg) {
    Write-Host "  [OK] $msg" -ForegroundColor Green
}
function Check-Fail([string]$msg) {
    Write-Host "  [FAIL] $msg" -ForegroundColor Red
    $script:failures++
}
function Check-Warn([string]$msg) {
    Write-Host "  [WARN] $msg" -ForegroundColor Yellow
    $script:warnings++
}

Write-Host "== 1. CAD applications ==" -ForegroundColor Cyan

$autocadFound = $false
foreach ($base in @("C:\Program Files\Autodesk", "C:\Program Files (x86)\Autodesk")) {
    if (Test-Path $base) {
        $hit = Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "AutoCAD*" }
        if ($hit) { $autocadFound = $true }
    }
}
if ($autocadFound) { Check-Pass "AutoCAD install directory found" } else { Check-Fail "AutoCAD install directory not found under Program Files" }

$sketchupFound = $false
foreach ($base in @("C:\Program Files\SketchUp", "C:\Program Files (x86)\SketchUp")) {
    if (Test-Path $base) { $sketchupFound = $true }
}
if ($sketchupFound) { Check-Pass "SketchUp install directory found" } else { Check-Fail "SketchUp install directory not found" }

$maxFound = $false
foreach ($base in @("C:\Program Files\Autodesk")) {
    if (Test-Path $base) {
        $hit = Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "3ds Max*" }
        if ($hit) { $maxFound = $true }
    }
}
if ($maxFound) { Check-Pass "3ds Max install directory found" } else { Check-Fail "3ds Max install directory not found" }

Write-Host ""
Write-Host "== 2. Vendor MCP clones ==" -ForegroundColor Cyan

$vendorRoot = "C:\hermes-projects\vendor"
foreach ($name in @("CAD-MCP", "sketchup-mcp", "3dsmax-mcp")) {
    $path = Join-Path $vendorRoot $name
    if (Test-Path $path) { Check-Pass "$name cloned at $path" } else { Check-Fail "$name not found at $path" }
}

Write-Host ""
Write-Host "== 3. Toolchain ==" -ForegroundColor Cyan

foreach ($cmd in @("git", "uv", "python")) {
    $found = Get-Command $cmd -ErrorAction SilentlyContinue
    if ($found) { Check-Pass "$cmd found: $($found.Source)" } else { Check-Fail "$cmd not on PATH" }
}

Write-Host ""
Write-Host "== 4. mcp-acad-assist ==" -ForegroundColor Cyan

$repoRoot = Split-Path -Parent $PSScriptRoot
$venvPython = Join-Path $repoRoot "mcp-acad-assist\.venv\Scripts\python.exe"
if (Test-Path $venvPython) {
    Check-Pass "venv found at $venvPython"
    $toolCount = & $venvPython -c "from acad_assist import server; print(len(server.mcp._tool_manager.list_tools()))" 2>&1
    if ($toolCount -match "^\d+$" -and [int]$toolCount -eq 18) {
        Check-Pass "server.py registers 18 tools"
    } else {
        Check-Fail "server.py did not report 18 tools (got: $toolCount)"
    }
} else {
    Check-Fail "mcp-acad-assist venv not found -- run: cd mcp-acad-assist; python -m venv .venv; .venv\Scripts\python.exe -m pip install -e `".[dev]`""
}

Write-Host ""
Write-Host "== 5. Hermes gateway + MCP registration ==" -ForegroundColor Cyan

$hermesHome = [Environment]::GetEnvironmentVariable("HERMES_HOME", "User")
if ($hermesHome) { Check-Pass "HERMES_HOME=$hermesHome" } else { Check-Warn "HERMES_HOME not set as a user env var" }

$hermesExe = "$env:LOCALAPPDATA\hermes\bin\hermes.exe"
if (Test-Path $hermesExe) {
    Check-Pass "hermes.exe found"
    $mcpList = & $hermesExe mcp list 2>&1 | Out-String
    foreach ($server in @("acad-read", "acad-write", "cad-pipeline", "sketchup", "max3d")) {
        if ($mcpList -match [regex]::Escape($server)) {
            if ($mcpList -match "$([regex]::Escape($server))\s.*enabled") {
                Check-Pass "$server registered and enabled"
            } else {
                Check-Warn "$server listed but may not be enabled -- check 'hermes mcp list' manually"
            }
        } else {
            Check-Fail "$server not registered in config.yaml"
        }
    }
} else {
    Check-Fail "hermes.exe not found at $hermesExe -- Stage 0 setup incomplete"
}

Write-Host ""
Write-Host "== 6. HERMES_CAD_ROOT ==" -ForegroundColor Cyan

$cfgPath = "$env:LOCALAPPDATA\hermes\config.yaml"
if (Test-Path $cfgPath) {
    $cfgText = Get-Content $cfgPath -Raw
    if ($cfgText -match "HERMES_CAD_ROOT") {
        Check-Pass "HERMES_CAD_ROOT is set in config.yaml"
    } else {
        Check-Fail "HERMES_CAD_ROOT not found in config.yaml -- acad-read/acad-write need it"
    }
    if ($cfgText -match "D:\\\\hermes-projects" -or $cfgText -match "D:\\hermes-projects") {
        Check-Warn "config.yaml still references D:\hermes-projects -- this dev machine had no D: drive, verify this one does"
    }
} else {
    Check-Fail "config.yaml not found at $cfgPath"
}

Write-Host ""
Write-Host "== Summary ==" -ForegroundColor Cyan
Write-Host "  failures: $failures   warnings: $warnings"
if ($failures -eq 0) {
    Write-Host "  Ready for docs/setup-cad-workstation.md section 6 smoke tests." -ForegroundColor Green
} else {
    Write-Host "  Fix the [FAIL] items above before running smoke tests." -ForegroundColor Red
}
