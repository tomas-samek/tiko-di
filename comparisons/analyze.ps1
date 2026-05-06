# Reads comparisons/results/*.csv and prints a markdown comparison table:
# wall time and per-phase nanoTime stats (min, median, max) for each framework.
#
# Run after run-all.ps1.

[CmdletBinding()]
param(
    [string]$ResultsDir = (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'results')
)

$ErrorActionPreference = 'Stop'
$culture = [System.Globalization.CultureInfo]::InvariantCulture

if (-not (Test-Path $ResultsDir)) { throw "no results dir at $ResultsDir -- run run-all.ps1 first" }

function Stats {
    param([double[]]$values)
    if (-not $values -or $values.Count -eq 0) { return $null }
    $sorted = $values | Sort-Object
    $n = $sorted.Count
    $median = if ($n % 2 -eq 1) { $sorted[[int][math]::Floor($n / 2)] } else { ($sorted[$n / 2 - 1] + $sorted[$n / 2]) / 2 }
    return [pscustomobject]@{
        N = $n
        Min = $sorted[0]
        Median = $median
        Max = $sorted[-1]
    }
}

function Format-Ms {
    param([double]$ns)
    return ($ns / 1e6).ToString('N1', $culture)
}

function Format-MsDouble {
    param([double]$ms)
    return $ms.ToString('N1', $culture)
}

# Wall time
$wallRows = Get-Content (Join-Path $ResultsDir 'wall.csv') | Where-Object { $_ -and -not $_.StartsWith('#') -and -not $_.StartsWith('name,') }
$wallByName = @{}
foreach ($row in $wallRows) {
    $parts = $row.Split(',')
    if ($parts.Count -lt 3) { continue }
    $name = $parts[0]
    $ms = [double]$parts[2]
    if (-not $wallByName.ContainsKey($name)) { $wallByName[$name] = [System.Collections.ArrayList]::new() }
    $null = $wallByName[$name].Add($ms)
}

# Per-framework Bench CSVs
$benchByName = @{}
Get-ChildItem $ResultsDir -Filter '*.csv' | Where-Object { $_.Name -ne 'wall.csv' } | ForEach-Object {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($_.Name)
    $rows = Get-Content $_.FullName | Where-Object { $_ -and -not $_.StartsWith('sample,') -and -not $_.StartsWith('#') }
    $createNs = [System.Collections.ArrayList]::new()
    $getANs = [System.Collections.ArrayList]::new()
    $getBNs = [System.Collections.ArrayList]::new()
    $closeNs = [System.Collections.ArrayList]::new()
    $totalNs = [System.Collections.ArrayList]::new()
    foreach ($row in $rows) {
        $parts = $row.Split(',')
        if ($parts.Count -lt 7) { continue }
        # Only iter=0 (cold inside JVM) -- that's what -Iter 1 produces.
        if ([int]$parts[1] -ne 0) { continue }
        $null = $createNs.Add([double]$parts[2])
        $null = $getANs.Add([double]$parts[3])
        $null = $getBNs.Add([double]$parts[4])
        $null = $closeNs.Add([double]$parts[5])
        $null = $totalNs.Add([double]$parts[6])
    }
    $benchByName[$name] = @{
        create = $createNs.ToArray()
        getA = $getANs.ToArray()
        getB = $getBNs.ToArray()
        close = $closeNs.ToArray()
        total = $totalNs.ToArray()
    }
}

# Print table
$frameworks = @('plain', 'tiko', 'dagger', 'avaje', 'micronaut', 'guice', 'hk2', 'spring') |
    Where-Object { $wallByName.ContainsKey($_) -or $benchByName.ContainsKey($_) }
$jvm = if ($wallByName.ContainsKey('jvm')) { Stats ($wallByName['jvm'].ToArray()) } else { $null }

"## Wall time (cold JVM, ms)"
""
"| framework | n | min | median | max |"
"|---|---:|---:|---:|---:|"
if ($jvm) { "| _jvm baseline (`java -version`)_ | $($jvm.N) | $(Format-MsDouble $jvm.Min) | $(Format-MsDouble $jvm.Median) | $(Format-MsDouble $jvm.Max) |" }
foreach ($name in $frameworks) {
    if (-not $wallByName.ContainsKey($name)) { continue }
    $s = Stats ($wallByName[$name].ToArray())
    "| $name | $($s.N) | $(Format-MsDouble $s.Min) | $(Format-MsDouble $s.Median) | $(Format-MsDouble $s.Max) |"
}
""

"## Internal phases -- median ms (cold iter=0)"
""
"| framework | n | create | first_get_a | first_get_b | close | total |"
"|---|---:|---:|---:|---:|---:|---:|"
foreach ($name in $frameworks) {
    if (-not $benchByName.ContainsKey($name)) { continue }
    $b = $benchByName[$name]
    $cs = Stats $b.create
    $as = Stats $b.getA
    $bs = Stats $b.getB
    $closeS = Stats $b.close
    $ts = Stats $b.total
    if (-not $cs) { continue }
    "| $name | $($cs.N) | $(Format-Ms $cs.Median) | $(Format-Ms $as.Median) | $(Format-Ms $bs.Median) | $(Format-Ms $closeS.Median) | $(Format-Ms $ts.Median) |"
}
