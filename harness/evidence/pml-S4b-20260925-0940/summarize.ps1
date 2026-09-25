# 결과 파일에서 README 표에 옮길 값을 뽑는다(읽기 전용).
$here = $PSScriptRoot
foreach ($j in Get-ChildItem $here -Filter '*-s4b.json' | Sort-Object Name) {
    $r = Get-Content $j.FullName -Raw -Encoding utf8 | ConvertFrom-Json
    foreach ($e in @($r.experiments)) {
        $old = $null; $dem = ''
        $ic = @{}
        foreach ($c in 'chaos-api-1', 'chaos-api-2') {
            $cnt = $e.intakeCounters.$c
            if ($cnt) { foreach ($p in $cnt.PSObject.Properties) { if ($p.Value -gt 0) { $ic["$c.$($p.Name -replace 'exchange_order_intake_','')"] = $p.Value } } }
        }
        $lg = @()
        foreach ($c in 'chaos-api-1', 'chaos-api-2') { $l = $e.logs.$c; if ($l) { $lg += ('{0}: rnv={1} rbf={2} unh={3} auth500={4} gone={5} fatal={6} dem={7} out={8}' -f $c.Substring(6), $l.replayNoVerdict, $l.rebuildFailed, $l.unhandledException, $l.authFailure500, $l.clientGone, $l.loopFatal, $l.partitionDemotion, $l.outageResponse) } }
        '{0} {1} {2} rto={3} epoch={4}->{5} lb502={6} lb503={7} lb504={8} lb500={9} o500={10} rr={11}/{12} intake=[{13}] logs=[{14}] hostOther={15}/{16}' -f $r.sub, $e.experiment, $e.verdict, $e.rtoSeconds, $e.epochs.old, $e.epochs.new,
            $e.lbFaultWindow.status502, $e.lbFaultWindow.status503, $e.lbFaultWindow.status504, $e.lbFaultWindow.status500, $e.orders500.ok, $e.recoveryRuns.success, $e.recoveryRuns.mismatch,
            (($ic.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' '), ($lg -join ' | '), $r.host.before.otherBusyProcesses, $r.host.after.otherBusyProcesses
    }
}
