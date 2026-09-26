# E1 결과에서 README 표에 옮길 값을 뽑는다(읽기 전용).
$here = $PSScriptRoot
function Val($arr, $key) { foreach ($s in @($arr)) { if ($s -like "$key=*") { return [double]($s.Split('=')[-1]) } }; return $null }
foreach ($d in Get-ChildItem $here -Directory -Filter 'E1-r*' | Sort-Object Name) {
    $rp = Join-Path $d.FullName 'E1\result.json'
    if (-not (Test-Path $rp)) { "$($d.Name) no result"; continue }
    $r = Get-Content $rp -Raw -Encoding utf8 | ConvertFrom-Json
    $v = Get-Content (Join-Path $d.FullName 'E1\verdict.json') -Raw -Encoding utf8 | ConvertFrom-Json
    $s = $r.settlement.'18080'; $s2 = $r.settlement.'28080'
    $c = $r.contention.'18080'
    $c429 = 0; foreach ($x in @($c)) { if ($x -like '*|429}=*') { $c429 += [double]$x.Split('=')[-1] } }
    $inv = @($v.invariants | Where-Object { -not $_.ok } | ForEach-Object { "$($_.window):$($_.check)=$($_.violatedRows)" })
    '{0} verdict={1} gate={2} dem={3} acqLT={4} o500={5} unh={6} retry55P03={7} retry57014={8} exhausted={9}/{10} p99={11} rec={12} epoch={13}->{14} c429={15} errLines={16}/{17} invFail=[{18}] host={19}/{20}' -f $d.Name, $r.verdict, $r.gateVerdict, $r.demotions, $r.acquireLockTimeouts, $r.orders500.ok, ($r.logs.'chaos-api-1'.unhandledException + $r.logs.'chaos-api-2'.unhandledException),
        ((Val $s 'exchange_settlement_transient_retries_by_state_total{sqlstate=55P03}') + (Val $s2 'exchange_settlement_transient_retries_by_state_total{sqlstate=55P03}')),
        ((Val $s 'exchange_settlement_transient_retries_by_state_total{sqlstate=57014}') + (Val $s2 'exchange_settlement_transient_retries_by_state_total{sqlstate=57014}')),
        (Val $s 'exchange_settlement_transient_exhausted_total'), (Val $s2 'exchange_settlement_transient_exhausted_total'),
        $v.metrics.injectP99Ms, [math]::Round([double]$v.metrics.recoverySec, 2), $v.metrics.epochBefore, $v.metrics.epochAfter, $c429,
        $r.logs.'chaos-api-1'.errorLines, $r.logs.'chaos-api-2'.errorLines, ($inv -join ' '), $r.host.before.otherBusyProcesses, $r.host.after.otherBusyProcesses
}
