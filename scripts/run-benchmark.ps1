# Skill-benchmark cell runner (Windows / PowerShell).
#
# Given a generated project directory and cell metadata, runs `mvn verify`
# and emits a row template the benchmark recorder pastes into the
# results-report's "Per-run data (raw)" table.
#
# The agent-invocation half (driving Claude / GPT / Gemini / Cursor on
# the locked prompt) is intentionally manual — each agent has its own
# UI that doesn't script cleanly. This script handles the deterministic
# part: build + capture + format.
#
# Usage:
#   ./scripts/run-benchmark.ps1 -ProjectDir C:\path\to\generated `
#       -Cell "tiko+skill, specified" `
#       -Prompt specified `
#       -Agent "claude-opus-4-7[1m] (SKILL.md auto-loaded)" `
#       -RunId 1 `
#       -Transcript "https://gist.github.com/..." `
#       -Source    "https://github.com/.../tree/run1"
#
# Optional:
#   -TokensIn / -TokensOut  if the agent reports them (paste from UI)
#   -CorrectionTurns        integer count of human/agent fix-up turns

param(
    [Parameter(Mandatory=$true)] [string] $ProjectDir,
    [Parameter(Mandatory=$true)] [string] $Cell,
    [Parameter(Mandatory=$true)] [ValidateSet("lean","specified")] [string] $Prompt,
    [Parameter(Mandatory=$true)] [string] $Agent,
    [Parameter(Mandatory=$true)] [int]    $RunId,
    [string] $Transcript = "(paste link)",
    [string] $Source     = "(paste link)",
    [int]    $TokensIn   = -1,
    [int]    $TokensOut  = -1,
    [int]    $CorrectionTurns = 0
)

if (-not (Test-Path $ProjectDir)) {
    Write-Error "ProjectDir not found: $ProjectDir"
    exit 2
}

# Resolve the mvn binary. Default to whatever's on PATH; allow override
# via the MVN environment variable for machines (e.g. Windows dev boxes)
# where mvn lives in a fixed install path not on PATH.
$mvn = if ($env:MVN) { $env:MVN } else { "mvn" }

$start = Get-Date
Push-Location $ProjectDir
try {
    $logFile = Join-Path $ProjectDir "benchmark-mvn.log"
    Write-Host "[harness] running '$mvn verify' in $ProjectDir (log: $logFile)"
    & $mvn verify *> $logFile
    $exit = $LASTEXITCODE
} finally {
    Pop-Location
}
$elapsed = (Get-Date) - $start
$wallSeconds = [math]::Round($elapsed.TotalSeconds, 1)

$firstBuildPass = if ($exit -eq 0) { "yes" } else { "no" }

$ti = if ($TokensIn  -lt 0) { "?" } else { $TokensIn  }
$to = if ($TokensOut -lt 0) { "?" } else { $TokensOut }

# Rubric vector is left blank; the human scorer fills it after reading the
# generated source. Format mirrors rubric.md's column order:
# B1 B2 B3 T1 T2 T3 T4 T5 O1 O2 O3 O4 O5 H1 H2 H3
$rubricVector = "_ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _"

Write-Host ""
Write-Host "===== ROW TEMPLATE (paste into results md) ====="
Write-Host ""
$row = "| $Cell | $Prompt | $Agent | $RunId | $ti | $to | $wallSeconds | $firstBuildPass | $CorrectionTurns | $rubricVector | _ | $Transcript | $Source |"
Write-Host $row
Write-Host ""
Write-Host "===== END ROW ====="
Write-Host ""
Write-Host "mvn exit code: $exit"
Write-Host "log: $logFile"
