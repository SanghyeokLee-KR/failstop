# 설계 7-1 의 실행 순서를 한 번에 돈다(Prep 은 따로 돌았다). 단계별 종료 코드와 판정 요지를 driver.log 에 남긴다.
# E2 는 연속 세 번 PASS 이고 원인 A 경로('재생이 판정 없이 끝났다' 줄)를 한 번 이상 탔으면 멈추고, 아니면 다섯 번까지 돈다(7-2 Y2).
# E1 은 결과와 무관하게 다섯 번을 모두 돈다(7-2 Y3). B1 은 500ms 가 N/A 면 100ms 로 한 번 더 돈다(7-2 Y6).
param([string] $From = 'X1')
$ErrorActionPreference = 'Continue'
$here = $PSScriptRoot
$dlog = Join-Path $here 'driver.log'
function Say([string] $t) { $l = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $t; Add-Content -Path $dlog -Value $l -Encoding utf8; Write-Host $l }
function Step([string] $label, [string[]] $a) {
    Say "START $label :: $($a -join ' ')"
    & pwsh -NoProfile -File (Join-Path $here 'step.ps1') -Label $label @a
    $rc = $LASTEXITCODE
    Say "END $label rc=$rc"
    return $rc
}
function Grep-Verdict([string] $label, [string] $pattern) {
    $f = Join-Path $here 'commands.log'
    $m = @(Select-String -Path $f -Pattern $pattern -Encoding utf8 | Select-Object -Last 1)
    if ($m.Count) { Say "VERDICT $label :: $($m[0].Line)" }
}
$order = @('X1', 'B1', 'E2', 'E1', 'Regress', 'Final')
$start = [array]::IndexOf($order, $From)
if ($start -lt 0) { throw "모르는 시작 단계: $From" }

if ($start -le 0) {
    Step 'X1' @('-Phase', 'X1', '-WithX6') | Out-Null
    Grep-Verdict 'X1' 'X1 verdict='
    Grep-Verdict 'X6' 'X6 '
}
if ($start -le 1) {
    Step 'B1' @('-Phase', 'B1') | Out-Null
    Grep-Verdict 'B1' 'B1 verdict='
    $b1 = Get-Content (Join-Path $here 'B1\result.json') -Raw -Encoding utf8 | ConvertFrom-Json
    if ($b1.verdict -eq 'N/A') {
        Step 'B1-r2' @('-Phase', 'B1', '-SubDir', 'B1-r2', '-B1ResetMs', '100') | Out-Null
        Grep-Verdict 'B1-r2' 'B1 verdict='
    }
}
if ($start -le 2) {
    $streak = 0; $causeA = 0
    foreach ($n in 1..5) {
        $sub = "E2-r$n"
        Step $sub @('-Phase', 'Chaos', '-Experiments', 'E2', '-SubDir', $sub) | Out-Null
        Grep-Verdict $sub "CHAOS $sub "
        $j = Join-Path $here "$sub-s4b.json"
        $ok = $false
        if (Test-Path $j) {
            $r = Get-Content $j -Raw -Encoding utf8 | ConvertFrom-Json
            $e = @($r.experiments)[0]
            $ok = ($e.verdict -eq 'PASS')
            foreach ($c in 'chaos-api-1', 'chaos-api-2') { $causeA += [int]$e.logs.$c.replayNoVerdict }
        }
        if ($ok) { $streak++ } else { $streak = 0 }
        Say "E2 round=$n pass=$ok streak=$streak causeA_lines=$causeA"
        if ($streak -ge 3 -and $causeA -gt 0) { Say 'E2 stop: three consecutive PASS with cause A path'; break }
    }
}
if ($start -le 3) {
    foreach ($n in 1..5) {
        $sub = "E1-r$n"
        Step $sub @('-Phase', 'X8', '-X8Experiments', 'E1', '-SubDir', $sub) | Out-Null
        Grep-Verdict $sub 'X8 E1 rc='
    }
}
if ($start -le 4) {
    Step 'regress' @('-Phase', 'Chaos', '-Experiments', 'E4,E5,E6,E7,COLD', '-SubDir', 'regress') | Out-Null
    Grep-Verdict 'regress' 'CHAOS regress '
}
if ($start -le 5) {
    Step 'Final' @('-Phase', 'Final') | Out-Null
}
Say 'DRIVER DONE'
