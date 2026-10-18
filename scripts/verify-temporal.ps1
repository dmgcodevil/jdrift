param([string]$DatasetDirectory = (Join-Path $PSScriptRoot '../data'))
$ErrorActionPreference = 'Stop'

function Require-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

$summaries = foreach ($scenario in @('burst', 'sustained', 'healthy')) {
    $evidence = Get-Content -LiteralPath (Join-Path $DatasetDirectory "$scenario.evidence.json") -Raw | ConvertFrom-Json
    Require-Condition ($evidence.schemaVersion -eq 'temporal-1') "$scenario missing schema version"
    $stackIds = @{}
    foreach ($stack in $evidence.stacks) { $stackIds[$stack.id] = $true }
    foreach ($window in $evidence.windows) {
        $attributed = ($window.stacks | Measure-Object -Property samples -Sum).Sum
        Require-Condition (($attributed + $window.samplesWithoutStack) -eq $window.executionSamples) "$scenario sample accounting mismatch"
        foreach ($entry in $window.stacks) {
            Require-Condition ($stackIds.ContainsKey($entry.stackId)) "$scenario dangling evidence reference"
        }
        if ($window.cpuObservationCount -eq 0) {
            Require-Condition ($null -eq $window.meanRecordedJvmCpuFraction) "$scenario missing CPU observation encoded as a value"
        }
    }
    $hot = @($evidence.methods | Where-Object method -EQ 'dev.jdrift.scenarios.ScenarioRecorder#hotLoop()V')
    if ($scenario -eq 'healthy') {
        Require-Condition ($hot.Count -eq 0) 'Healthy recording unexpectedly contains hot-loop samples'
    } else {
        Require-Condition ($hot.Count -eq 1) "$scenario lacks usable hot-loop evidence; regenerate and inspect the fixture"
        $expected = if ($scenario -eq 'burst') { 'LOCALIZED_SAMPLES' } else { 'SUSTAINED_SAMPLES' }
        Require-Condition ($hot[0].pattern -eq $expected) "$scenario did not establish expected temporal pattern"
    }
    [pscustomobject]@{
        Scenario = $scenario
        Samples = ($evidence.windows | Measure-Object -Property executionSamples -Sum).Sum
        HotLoopSamples = if ($hot.Count) { $hot[0].samples } else { 0 }
        Pattern = if ($hot.Count) { $hot[0].pattern } else { 'NOT_OBSERVED' }
    }
}
$summaries | Format-Table
