# E-P6(b) 옛 Primary 재초기화(Patroni 설계 8-3). E-P1 처럼 현재 Primary 를 docker kill 하고, 60초 뒤 되살리는 대신 그 노드의
# 컨테이너와 데이터 볼륨(이 프로젝트의 exchange-patroni_patroni-db-N-data)을 지우고 같은 이름으로 다시 만든다. Patroni 는 빈
# 데이터 디렉터리를 보고 리더에서 베이스백업(`--no-slot`)으로 Standby 를 만든다. 사람 조작 없이 동기 Standby 로 돌아와야 한다.
#
# 이 실행기는 E-P6(b) 전용 축을 판정하지 않는다. 회차 판정은 E-P1 축(patroni_axes --scenario E-P1)으로 따로 내고,
# 재초기화 축(재초기화 시작부터 멤버 walsender 전까지 ACTIVE 0·acked 0, 백업 스트림 sync 0, --no-slot 전달)은 원시 파일로 계산한다.
# 재초기화 시각(reinit_start)과 백업 명령줄(basebackup_cmdlines)은 timeline.json 에 적는다.
param([switch] $Build, [switch] $KeepStack, [switch] $NoK6)
. (Join-Path $PSScriptRoot 'ep-lib.ps1')
$inject = {
    param($tl)
    $primary = Get-EpPrimaryContainer
    Assert-EpContainer $primary
    & docker kill $primary | Out-Null
    return @{ injected = $primary; role_injected = 'primary'; sync_member = $primary }
}
$recover = {
    param($tl)
    $name = $tl.injected
    Assert-EpContainer $name
    $volume = "exchange-patroni_$name-data"
    # 이 프로젝트의 DB 볼륨만 지운다. 보존 볼륨(exchange-chaos_chaos-pgdata, failstop-keep-*)은 이름으로 막는다.
    if ($volume -notmatch '^exchange-patroni_patroni-db-[12]-data$') { throw "지우지 않는 볼륨: $volume" }
    $label = (& docker volume inspect -f '{{index .Labels "com.docker.compose.project"}}' $volume 2>$null | Out-String).Trim()
    if ($label -ne 'exchange-patroni') { throw "볼륨의 compose 프로젝트가 exchange-patroni 가 아니다: $volume ($label)" }
    Invoke-EpCompose rm -f -s $name | Out-Null
    & docker volume rm $volume | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "볼륨 삭제 실패: $volume" }
    $tl.reinit_volume = $volume
    $tl.reinit_start = Get-EpNow
    Invoke-EpCompose up -d --no-deps $name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "재생성 실패: $name" }
    # 백업 명령줄을 잡는다(검토 R1: --no-slot 전달). 작은 DB 는 몇 초면 끝나므로 0.2초마다 /proc 을 읽는다.
    # 멤버가 streaming 이 되면 멈춘다. 오래 붙잡지 않는다(그동안 승격 관찰이 멈춘다).
    $seen = [System.Collections.Generic.List[string]]::new()
    $until = (Get-EpNow) + 90
    while ((Get-EpNow) -lt $until) {
        $lines = @(& docker exec $name sh -c 'for p in /proc/[0-9]*; do tr ''\000'' '' '' < $p/cmdline 2>/dev/null; echo; done' 2>$null |
            Where-Object { $_ -match 'pg_basebackup' })
        foreach ($l in $lines) { if (-not $seen.Contains($l.Trim())) { $seen.Add($l.Trim()) } }
        $c = Get-EpCluster
        $m = if ($c) { @($c.members | Where-Object { $_.name -eq $name }) | Select-Object -First 1 } else { $null }
        if ($m -and $m.state -eq 'streaming') { $tl.reinit_streaming_seen = Get-EpNow; break }
        Start-Sleep -Milliseconds 200
    }
    $tl.basebackup_cmdlines = @($seen)
}
$code = @(Invoke-EpExperiment -Scenario 'E-P6b' -Inject $inject -Recover $recover -HoldSec 60 -ExpectEpochChange -ExpectSuspension `
    -K6:(-not $NoK6) -Build:$Build -KeepStack:$KeepStack)
exit ([int]($code[-1]))
