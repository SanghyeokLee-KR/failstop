# rules.md 의 사전 규칙으로 ab/result.json 을 판정한다. 결과는 judgement.json 이다.
# 사용: pwsh -NoProfile -File judge.ps1
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$r = Get-Content (Join-Path $here 'ab\result.json') -Raw -Encoding utf8 | ConvertFrom-Json
function Mean($xs) { ($xs | Measure-Object -Average).Average }
function Range($xs) { $m = $xs | Measure-Object -Minimum -Maximum; [ordered]@{ min = $m.Minimum; max = $m.Maximum; spread = $m.Maximum - $m.Minimum } }

$rows = foreach ($c in $r.cells) {
    $lbPath = Join-Path $here "ab\lb_status_$($c.label).json"
    $lb = $(if (Test-Path $lbPath) { Get-Content $lbPath -Raw -Encoding utf8 | ConvertFrom-Json } else { $null })
    [ordered]@{
        label = $c.label; arm = $c.arm; probeRc = $c.probeRc
        acceptPerSec = [double]$c.probe.acceptPerSec
        appendMeanMs = [double]$c.prom.appendMeanMs; appendP95Ms = [double]$c.prom.appendP95Ms; appendP99Ms = [double]$c.prom.appendP99Ms
        prologueMs = $c.probe.prologueMs; insertMs = $c.probe.insertMs; commitMs = $c.probe.commitMs; lockHoldMs = $c.probe.lockHoldMs
        applyPerSec = $c.probe.applyPerSec; p95OrderMs = $c.probe.p95OrderMs
        leaderInstance = $c.prom.leaderInstance; appenderCount = $c.prom.appenderCount
        leader5xx = $c.prom.leader5xx; nonLeader5xx = $c.prom.nonLeader5xx; server5xx = [double]$c.prom.http5xx
        lbClient5xx = $(if ($lb) { [double]$lb.client_5xx.k6 + [double]$lb.client_5xx.bots } else { $null })
        invariantFail = @($c.summary.fail).Count; invariantChecks = $c.summary.checks; na = $c.summary.na; skip = $c.summary.skip
        drained = $c.drain.drained; hostCpuMeanPct = $c.hostCpuBefore.meanPct
        k6Rc = $c.probe.k6Rc; connection = $c.probe.connection; deadlocks = $c.probe.deadlocks
    }
}
$A = @($rows | Where-Object { $_.arm -eq 'A' }); $B = @($rows | Where-Object { $_.arm -eq 'B' })

$inv = [ordered]@{ ok = (@($rows | Where-Object { $_.invariantFail -ne 0 -or $_.probeRc -ne 0 -or -not $_.drained }).Count -eq 0) }
$leaderKnown = (@($rows | Where-Object { -not $_.leaderInstance -or $null -eq $_.leader5xx }).Count -eq 0)
$l5A = ($A | ForEach-Object { [double]$_.leader5xx } | Measure-Object -Maximum).Maximum
$l5B = ($B | ForEach-Object { [double]$_.leader5xx } | Measure-Object -Maximum).Maximum
$leader = [ordered]@{ leaderKnown = $leaderKnown; maxA = $l5A; maxB = $l5B; ok = $(if ($leaderKnown) { $l5B -le $l5A } else { $null }) }
function AppendRule($key) {
    $a = @($A | ForEach-Object { $_[$key] }); $b = @($B | ForEach-Object { $_[$key] })
    $inc = (Mean $b) - (Mean $a); $aa = (Range $a).spread
    [ordered]@{ meanA = Mean $a; meanB = Mean $b; increase = $inc; aaSpread = $aa; ok = ($inc -le 0 -or $inc -lt $aa) }
}
$am = AppendRule 'appendMeanMs'; $ap = AppendRule 'appendP95Ms'
$accA = @($A | ForEach-Object { $_.acceptPerSec }); $accB = @($B | ForEach-Object { $_.acceptPerSec })
$accept = [ordered]@{ meanA = Mean $accA; meanB = Mean $accB; rangeA = Range $accA; rangeB = Range $accB
    relDiff = ((Mean $accB) - (Mean $accA)) / (Mean $accA)
    reproduced = (($accB | Measure-Object -Maximum).Maximum -lt ($accA | Measure-Object -Minimum).Minimum) }
$s5A = @($A | ForEach-Object { $_.server5xx }); $s5B = @($B | ForEach-Object { $_.server5xx })
$verdict = $(if ($inv.ok -and $leader.ok -and $am.ok -and $ap.ok) { 'PASS' } elseif ($null -eq $leader.ok) { 'UNDECIDED' } else { 'FAIL' })
$out = [ordered]@{ rules = 'rules.md'; cells = $rows; invariants = $inv; leader5xx = $leader; appendMean = $am; appendP95 = $ap
    acceptNotJudged = $accept; server5xxNotJudged = [ordered]@{ meanA = Mean $s5A; meanB = Mean $s5B; rangeA = Range $s5A; rangeB = Range $s5B }
    verdict = $verdict }
[System.IO.File]::WriteAllText((Join-Path $here 'judgement.json'), ($out | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
$rows | ForEach-Object { '{0} {1} acc={2:N1} am={3:N3} p95={4:N2} p99={5:N2} L5={6} S5={7:N0} LB5={8:N0} inv={9} lock={10:N3} pro={11:N3} ins={12:N3} apply={13:N1} cpu={14}' -f $_.label, $_.arm, $_.acceptPerSec, $_.appendMeanMs, $_.appendP95Ms, $_.appendP99Ms, $_.leader5xx, $_.server5xx, $_.lbClient5xx, $_.invariantFail, $_.lockHoldMs, $_.prologueMs, $_.insertMs, $_.applyPerSec, $_.hostCpuMeanPct }
"append mean: A {0:N3} B {1:N3} inc {2:N3} AA {3:N3} ok={4}" -f $am.meanA, $am.meanB, $am.increase, $am.aaSpread, $am.ok
"append p95 : A {0:N3} B {1:N3} inc {2:N3} AA {3:N3} ok={4}" -f $ap.meanA, $ap.meanB, $ap.increase, $ap.aaSpread, $ap.ok
"leader5xx maxA={0} maxB={1} ok={2}; invariants ok={3}" -f $leader.maxA, $leader.maxB, $leader.ok, $inv.ok
"accept A mean {0:N1} [{1:N1}..{2:N1}] B mean {3:N1} [{4:N1}..{5:N1}] rel {6:P1} reproduced={7}" -f $accept.meanA, $accept.rangeA.min, $accept.rangeA.max, $accept.meanB, $accept.rangeB.min, $accept.rangeB.max, $accept.relDiff, $accept.reproduced
"server5xx A mean {0:N0} B mean {1:N0}" -f (Mean $s5A), (Mean $s5B)
"VERDICT $verdict"
