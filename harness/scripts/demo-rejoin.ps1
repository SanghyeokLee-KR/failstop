# 옛 주 DB 가 예비로 다시 붙는지 지켜보는 도구. harness/scripts/demo-db-failover.ps1 이 dot-source 한다(return·reset·run).
# 시험: pwsh -NoProfile -File harness/scripts/tests/demo-rejoin.Tests.ps1 (Docker 에 명령을 보내지 않는다)
# 쓰는 것: $Global:Demo(demo-common.ps1), Get-DemoCluster, Get-DemoLeader, Get-DemoNow, ConvertTo-IsoUtc.

$clusterName = 'exchange-chaos-demo'
$walRemovedPattern = 'requested WAL segment \S+ has already been removed'

# 옛 주 DB 가 예비로 다시 붙는지 지켜보고, 못 붙으면 patronictl reinit 으로 넘어간다.
# 2026-09-27 첫 측정 회차에서 되감기(pg_rewind)는 성공했지만 되감은 노드가 마지막 공통 체크포인트의 REDO 지점(분기점보다 약 72MB
# 앞)부터 다시 읽을 WAL 을 찾지 못해 'starting' 에 10분 머물렀다. Patroni 가 되감기 전에 단일 사용자 모드로 크래시 복구를 하며 새
# 체크포인트를 쓰는 순간 자기 pg_wal 의 옛 세그먼트를 재활용했고(wal_keep_size 16MB, ADR 0036 결정 4), 새 주 DB 도 그 세그먼트를
# 지운 뒤였다(로그: requested WAL segment ... has already been removed). Patroni 의 remove_data_directory_on_rewind_failure 는
# pg_rewind 자체가 실패할 때만 걸려 이 경우를 풀지 못한다. 그래서 그 로그가 보이거나 StuckSec 안에 streaming 이 되지 않으면
# 새 주 DB 에서 --no-slot 베이스백업으로 다시 만든다(patroni.demo.yml basebackup). 로컬 1.6GB 에 약 10초였다.
function Get-MemberState([string] $Member) {
    $c = Get-DemoCluster
    if (-not $c) { return $null }
    $m = @($c.members | Where-Object { $_.name -eq $Member }) | Select-Object -First 1
    if ($m) { return [string]$m.state }
    return $null
}

function Invoke-Reinit([string] $Member) {
    $leader = (Get-DemoLeader).Leader
    if (-not $leader -or $leader -eq $Member) { throw "reinit 할 수 없다(리더 $leader, 대상 $Member)" }
    Write-Host "  $Member 이 예비로 붙지 못한다. 새 주 DB($leader)에서 다시 만든다(patronictl reinit)" -ForegroundColor Yellow
    & docker exec $Global:Demo.Members[$leader] patronictl -c /patroni/patroni.yml reinit $clusterName $Member --force | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "patronictl reinit $Member 실패" }
}

function New-RejoinWatch([string] $Member, [double] $StartedAt, [int] $StuckSec = 60) {
    return [pscustomobject]@{ Member = $Member; Container = $Global:Demo.Members[$Member]; StartedAt = $StartedAt
        SinceIso = (ConvertTo-IsoUtc ($StartedAt - 2)); StuckSec = $StuckSec; Method = $null; Reason = $null
        ReinitAt = $null; StreamingAt = $null; NextLogCheck = 0.0 }
}

# 한 번 살핀다(되풀이해서 부른다). 막히지 않는다.
function Step-RejoinWatch($Watch) {
    if ($Watch.StreamingAt) { return }
    $now = Get-DemoNow
    if ((Get-MemberState $Watch.Member) -eq 'streaming') {
        $Watch.StreamingAt = $now
        if (-not $Watch.Method) { $Watch.Method = 'rewind' }
        Write-Host ("  {0} 예비로 붙음({1}) 켠 뒤 {2:N1}초" -f $Watch.Member, $Watch.Method, ($now - $Watch.StartedAt))
        return
    }
    if ($Watch.ReinitAt -or $now -lt $Watch.NextLogCheck) { return }
    $Watch.NextLogCheck = $now + 2
    $log = Get-ContainerLogSince $Watch.Container $Watch.SinceIso
    $removed = $log -match $walRemovedPattern
    $stuck = ($now - $Watch.StartedAt) -ge $Watch.StuckSec
    if ($removed -or $stuck) {
        $Watch.Reason = if ($removed) { 'wal_segment_removed' } else { "not_streaming_after_$($Watch.StuckSec)s" }
        Invoke-Reinit $Watch.Member
        $Watch.ReinitAt = Get-DemoNow
        $Watch.Method = 'rewind_then_reinit'
    }
}

# 컨테이너 로그(표준 오류 포함) 가운데 그 시각 뒤의 것. docker logs 는 로그를 표준 오류로도 내므로 cmd 로 합쳐 받는다.
function Get-ContainerLogSince([string] $Container, [string] $SinceIso) {
    return (& cmd /c "docker logs --since $SinceIso $Container 2>&1" | Out-String)
}
