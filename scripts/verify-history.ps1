param([string]$DatasetDirectory = (Join-Path $PSScriptRoot ('../data/history-' + [guid]::NewGuid().ToString('N'))))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$app = Join-Path $root 'app/build/install/app/bin/app.bat'
New-Item -ItemType Directory -Path $DatasetDirectory -Force | Out-Null
$DatasetDirectory = (Resolve-Path $DatasetDirectory).Path
$db = Join-Path $DatasetDirectory 'history.db'

function Invoke-JDrift([string[]]$Arguments) {
    $output = & $app @Arguments 2> (Join-Path $DatasetDirectory 'last-command.stderr.log')
    if ($LASTEXITCODE -ne 0) { throw "JDrift failed: $Arguments; inspect last-command.stderr.log" }
    return ($output -join "`n") | ConvertFrom-Json
}
function Require-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

$ids = @{}
foreach ($scenario in @('sustained', 'improved', 'healthy', 'regressed', 'new-method', 'lower-load')) {
    $recording = Join-Path $DatasetDirectory "$scenario.jfr"
    & $java -cp (Join-Path $root 'scenario-harness/build/classes/java/main') dev.jdrift.scenarios.ScenarioRecorder $scenario $recording
    if ($LASTEXITCODE -ne 0) { throw "Recording generation failed: $scenario" }
    $import = Invoke-JDrift @('import', $db, $recording)
    $ids[$scenario] = $import.reportId
}
$duplicate = Invoke-JDrift @('import', $db, (Join-Path $DatasetDirectory 'sustained.jfr'))
Require-Condition ($duplicate.reportId -eq $ids['sustained']) 'Duplicate import created another report'
$reports = @(Invoke-JDrift @('list', $db, 'synthetic-cpu', 'test'))
Require-Condition ($reports.Count -eq 6) 'Unexpected report count'
$history = Invoke-JDrift @('history', $db, 'synthetic-cpu', 'test', $ids['sustained'])
$history | ConvertTo-Json -Depth 40 | Set-Content (Join-Path $DatasetDirectory 'history.json')
$improvement = $history.points | Where-Object reportId -EQ $ids['improved']
Require-Condition (($improvement.changes | Where-Object key -EQ 'work.threadCpu.perOperation').assessment -eq 'MEASURED_IMPROVEMENT') 'Improved fixture did not establish a measured improvement; investigate runtime evidence'
$regression = Invoke-JDrift @('compare', $db, $ids['improved'], $ids['regressed'])
Require-Condition (($regression.points[0].changes | Where-Object key -EQ 'work.threadCpu.perOperation').assessment -eq 'POTENTIAL_REGRESSION') 'Regressed fixture did not establish higher CPU cost'
$last = Invoke-JDrift @('history', $db, 'synthetic-cpu', 'test', $ids['sustained'], '--last', '2')
Require-Condition ($last.points.Count -eq 2) 'Last-N selection failed'
$newMethod = $last.points[0].activities | Where-Object method -Like '*#alternativeHotLoop*'
Require-Condition ($newMethod.status -eq 'FIRST_OBSERVED_IN_RETAINED_HISTORY') 'New method was not identified'
$lower = $last.points[1]
Require-Condition (($lower.changes | Where-Object key -EQ 'jvm.cpu.observationMean').assessment -eq 'INSUFFICIENT_EVIDENCE') 'CPU load was incorrectly treated as efficiency evidence'
Require-Condition (($lower.changes | Where-Object key -EQ 'work.threadCpu.perOperation').assessment -eq 'INSUFFICIENT_EVIDENCE') 'Different load profiles were treated as comparable'
$hot = ($history.points | Where-Object reportId -EQ $ids['regressed']).activities | Where-Object method -Like '*#hotLoop*'
Require-Condition ($hot.status -eq 'REOBSERVED_AFTER_GAP') 'Reappearing activity was marked new'
$from = [DateTimeOffset]::FromUnixTimeSeconds([long][Math]::Floor([decimal]$last.points[0].capturedAtEpochNanos / 1000000000)).ToString('yyyy-MM-ddTHH:mm:ssZ')
$range = Invoke-JDrift @('history', $db, 'synthetic-cpu', 'test', $ids['sustained'], '--from', $from)
Require-Condition ($range.points.Count -eq 2) 'Capture-time range selection failed'
$history.points | ForEach-Object {
    $metric = $_.changes | Where-Object key -EQ 'work.threadCpu.perOperation'
    [pscustomobject]@{Build=$_.build; CpuNanosecondsPerOperation=$metric.candidate; Assessment=$metric.assessment}
} | Format-Table
Write-Output "Validated dataset and database: $DatasetDirectory"
