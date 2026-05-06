# Builds each comparison subproject that exists and runs its Bench harness in
# fresh JVMs. Captures wall-clock time externally and per-phase nanoTime CSV
# from the Bench's stderr output.
#
# Output: comparisons/results/{name}.csv (Bench rows) and comparisons/results/wall.csv
# (one row per JVM invocation: name,wall_ms).

[CmdletBinding()]
param(
    [int]$Samples = 10,
    [int]$Iter = 1,
    [string[]]$Only,
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$resultsDir = Join-Path $root 'results'
$null = New-Item -ItemType Directory -Force -Path $resultsDir

$mvnPath = 'W:\tools\apache-maven\bin\mvn.cmd'
if (-not (Test-Path $mvnPath)) {
    $mvnPath = (Get-Command mvn -ErrorAction SilentlyContinue).Source
    if (-not $mvnPath) { throw "mvn not found on PATH and not at W:\tools\apache-maven\bin\mvn.cmd" }
}

function Get-RuntimeClasspath {
    param([string]$AppPomPath)
    $cpFile = [System.IO.Path]::GetTempFileName()
    try {
        & $mvnPath -q -f $AppPomPath dependency:build-classpath "-Dmdep.outputFile=$cpFile" "-Dmdep.includeScope=runtime" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "mvn dependency:build-classpath failed for $AppPomPath" }
        return (Get-Content $cpFile -Raw).Trim()
    } finally {
        Remove-Item $cpFile -ErrorAction SilentlyContinue
    }
}

function Run-Comparison {
    param(
        [string]$Name,
        [string]$Dir
    )

    $appPom = Join-Path $Dir 'app\pom.xml'
    if (-not (Test-Path $appPom)) {
        Write-Host "  ${Name}: no app/pom.xml, skipping" -ForegroundColor DarkGray
        return
    }

    if (-not $NoBuild) {
        Write-Host "  ${Name}: building..." -ForegroundColor DarkCyan
        & $mvnPath -q -f (Join-Path $Dir 'pom.xml') package -DskipTests | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "$Name build failed" }
    }

    $appClasses = Join-Path $Dir 'app\target\classes'
    $cp = "$appClasses;$(Get-RuntimeClasspath $appPom)"

    $csvOut = Join-Path $resultsDir "$Name.csv"
    if (Test-Path $csvOut) { Remove-Item $csvOut }
    Add-Content -Path $csvOut -Value 'sample,iter,create_ns,first_get_a_ns,first_get_b_ns,close_ns,total_ns'

    Write-Host "  ${Name}: $Samples cold-JVM samples..." -ForegroundColor Cyan
    for ($s = 0; $s -lt $Samples; $s++) {
        $stdout = Join-Path $resultsDir "$Name.run$s.stdout"
        $stderr = Join-Path $resultsDir "$Name.run$s.stderr"
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        # cmd /c is reliable for stdout/stderr redirection of native processes.
        $cmdLine = "java -cp `"$cp`" $env:BENCH_MAIN_CLASS_OVERRIDE"
        if (-not $env:BENCH_MAIN_CLASS_OVERRIDE) {
            $cmdLine = "java -cp `"$cp`" io.tiko.comparisons.$Name.app.Bench $Iter"
        }
        & cmd /c "$cmdLine 1>`"$stdout`" 2>`"$stderr`""
        $sw.Stop()
        if ($LASTEXITCODE -ne 0) {
            Write-Host "    sample $s exit=$LASTEXITCODE -- see $stderr" -ForegroundColor Red
            continue
        }
        Add-Content -Path (Join-Path $resultsDir 'wall.csv') -Value "$Name,$s,$([math]::Round($sw.Elapsed.TotalMilliseconds, 3))"
        Get-Content $stderr | Where-Object { $_ -match '^\d' } | ForEach-Object {
            Add-Content -Path $csvOut -Value "$s,$_"
        }
    }
}

function Run-JvmBaseline {
    Write-Host "  jvm baseline: $Samples samples..." -ForegroundColor Cyan
    Add-Content -Path (Join-Path $resultsDir 'wall.csv') -Value '# jvm-only baseline (java -version)'
    for ($s = 0; $s -lt $Samples; $s++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        & cmd /c "java -version 1>nul 2>nul"
        $sw.Stop()
        Add-Content -Path (Join-Path $resultsDir 'wall.csv') -Value "jvm,$s,$([math]::Round($sw.Elapsed.TotalMilliseconds, 3))"
    }
}

# Reset wall.csv per run.
Set-Content -Path (Join-Path $resultsDir 'wall.csv') -Value 'name,sample,wall_ms'

$frameworks = @('plain', 'tiko', 'dagger', 'avaje', 'micronaut', 'guice', 'hk2', 'spring')
if ($Only) { $frameworks = $frameworks | Where-Object { $Only -contains $_ } }

Write-Host "Running comparisons (samples=$Samples, iter=$Iter)..." -ForegroundColor Green
Run-JvmBaseline
foreach ($name in $frameworks) {
    $dir = Join-Path $root $name
    if (-not (Test-Path $dir)) {
        Write-Host "  ${name}: directory missing, skipping" -ForegroundColor DarkGray
        continue
    }
    Run-Comparison -Name $name -Dir $dir
}

Write-Host "Done. Results under $resultsDir" -ForegroundColor Green
