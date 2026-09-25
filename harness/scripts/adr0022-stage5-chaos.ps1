# ADR 0022 5단계 장애 시험 주행. 4단계 이미지(B4)로 E2, E4, E5, E6, E7 을 차례로 돌린다.
# -LatchBeforeTraffic 이면 6단계 대역 모드 주행이다. 6단계 이미지로 실험마다 기동 직후, 실험 스크립트 전에 래치를 건다
# (ADR 0022 7절 6-b 래치 전 확인 5). 래치는 되돌릴 수 없고 실험마다 down -v 가 그 DB 를 버린다.
# 실험마다 볼륨을 파기하고 새로 세운다(e4·e5·e7 머리 주석의 실행 전제). down -v 전에 판정 파일, DB 쪽 증거,
# 도커 사건, 연결 표본을 harness/evidence/adr0022-stage5-<YYYYMMDD>/<실험>/ 로 떨어뜨린다. 대역 모드는 adr0022-stage6-chaos-<YYYYMMDD>/ 다.
# -SnapshotEvidence 이면 ADR 0022 7단계 스냅샷 경계 재생 주행이다. 실험 스크립트 전과 뒤에 harness/scale/snapshot_evidence.py 로
# 스냅샷·복구 증거를 뜨고 전역 모드도 래치 상태를 남긴다. 증거 기본 폴더는 adr0022-stage7-snapshot-<YYYYMMDD>/<global|band>/ 다.
# 실험 COLD 는 7단계 콜드 재기동 칸(adr0022-stage7-cold-restart.ps1)이다. 두 api 노드를 멈췄다가 올리므로 도달 사건은 stop·start 다.
# -Protocol 2 이면 ADR 0034 S3 관문 주행이다(마켓분할_리더_S3_설계 7-3 X2~X4). 실험마다 스택을 세운 직후, 실험 스크립트 전에
# harness/scale/protocol_latch.py 로 P2 로 전환하고 전환 JSON 을 실험 폴더에 남긴다. 전환이 실패하면 그 실험은 BLOCKED 다.
# 실험 동안 잔고 행 대기를 표본으로 뜨고, 실험 스크립트가 끝나면 스택을 내리기 전에 harness/scale/s3_common_axes.py judge 로
# 공통 판정 축(설계 7-3: summary.sql, 정산 23514·잠금 계획 이탈·접수 40P01·접수 500·교착 0, 예약 양성 축, 드레인 뒤 정산 전
# 예약 0, 리더 노드의 정지·강등 문구 0, 장애 창 밖 ReservationUnsettledStale 0)을 s3_common.json 에 남긴다. 실험 판정이 PASS 여도
# 공통 판정이 거짓이면 분리 판정은 FAIL(공통 판정)이다.
# -Protocol 2 의 분리 판정에는 설계 7-3 X2·X3 의 사전 규칙(rpo_records 0, 사후 권위 run C13A 0)도 건다(FAIL(rpo_records)).
# -PartitionAxes 이면 ADR 0034 S4 관문 주행이다(마켓분할_리더_S4_설계 7-2 X2~X5). 실험 스크립트 전후로
# harness/scale/s4_partition_axes.py snapshot 을 뜨고, 끝나면 judge 로 파티션 축(창 밖 접수 경합 상한, 창 밖 파티션 경보 0,
# 리더 노드 강등 0, LB 로그의 no live upstreams·502 기록)을 partition_axes.json 에 남긴다. 거짓이면 분리 판정은 FAIL(파티션 축)이다.
# E5·E7 은 공통 판정에 옛 리더 강등 문구의 양성 대조(--expect-demotion)를 더한다. E4 는 판정 뒤 합류 단계를 둔다. docker kill 한
# 옛 리더를 docker start 로 되살려 180초 안에 팔로워로 합류하는지(부팅 획득 없음, 리스 행 불변, 강등 계수 0) 보고 rejoin.json 에
# 남긴다. 합류가 거짓이면 분리 판정은 FAIL(합류)다. -PartitionAxes 는 -Protocol 2 와 함께만 돈다.
# -Relay 이면 ADR 0034 S6 WebSocket 중계 주행이다(마켓분할_리더_S6_설계 4-19, 7-2 W5). CHAOS_COMPOSE_OVERLAYS 를 중계 오버레이로
# 채워 스택마다 브로커와 broker 모드 앱 노드, nginx.relay.conf 를 겹친다. 스택을 세우고 P2 로 전환한 뒤 오버레이가 실제로 걸렸는지
# (chaos-broker healthy, 두 앱 컨테이너의 EXCHANGE_WS_RELAY_MODE=broker, chaos-lb 의 nginx.relay.conf 마운트) 확인해
# relay-preflight.json 에 남기고, 하나라도 아니면 그 실험은 BLOCKED 다. 확인을 통과해야 k6 관찰자(pml-s6-watch-<실험>)와 미러 신선도
# 표본기를 띄우고, 다음 down -v 전에 멈춘다. E4 는 harness/scale/s6_relay_axes.py judge --experiment W5 로 중계 축을 relay_axes.json 에
# 남기고, 거짓이면 분리 판정은 FAIL(중계 축)이다.
# -WsGateAxes 이면 실험 스크립트와 공통 판정 뒤, 분리 판정 전에 s6_relay_axes.py gate 로 WS 수용 판정의 스택 표본을 ws_gate.json 에
# 남긴다(설계 7-2 W0·W5). -Relay 가 없으면 simple 모드 판정(팔로워 seq 0·리스 epoch·503, 리더와 LB 101)이고, 있으면 broker 모드 판정
# (두 노드 gate.open 1·101)이다. 거짓이면 분리 판정은 FAIL(WS 게이트 축)이다. 공통 판정이 봇을 멈춘 뒤라 LB 핸드셰이크가 봇 쓰기에
# 밀려 빠진 1차 노드를 건너뛰지 않는다. -Relay·-WsGateAxes 는 -Protocol 2 이상과 함께만 돈다. -Relay 가 없는데 오버레이 목록에 중계
# 오버레이가 남아 있으면 시작하지 않는다(W0 는 기본 스택이다).
# -Protocol 3 이면 ADR 0034 S7 관문 주행이다(마켓분할_리더_S7_설계 4-13, 5-2). -P3 와 함께만 돌고 -P3 는 -Relay 를 함께 켠다(P3 에서는
# 두 노드가 모두 중계에 기댄다). CHAOS_COMPOSE_OVERLAYS 를 중계·P3 오버레이(relay;p3 순서)로 채우고, 스택을 세운 뒤
# protocol_latch.py transition --target 3 --latch-band 로 P3 에 올린다. 이어 Q0 준비 판정(lib.ps1 Invoke-P3Preflight)을
# p3-preflight.json 에 남기고 하나라도 거짓이면 그 실험은 BLOCKED 다. E2·E4·E5·E6·E7·COLD 는 -Resource(matching-p0 또는 matching-p1)가
# 없으면 시작하지 않고 실험 스크립트에 그 자원을 넘긴다. 실험 동안 희생 epoch 표본기(s7_axes.py epoch-sampler)를 띄우고, 공통 판정
# (s3_common_axes.py judge --protocol 3 --resource) 뒤에 P3 공통 축(구간 경계 증분 segment-judge, 희생 epoch 집합의 구 쓰기 증분
# victim-writes, 생존 파티션 ACK 간격 ack-interval)을 p3_axes.json 에 남긴다. 희생 노드가 두 파티션을 쥐었으면 생존 파티션이 없어
# ACK 간격은 판정하지 않고 적기만 한다. 축이 거짓이면 분리 판정은 FAIL(P3 공통 판정), 양성 축이 모자라면 NOT RUN(P3 공통 판정)이다.
# E4 의 중계 판정은 W5 대신 W6(희생·생존 노드, 영향·비영향 마켓)이고, 웹 관찰 파일이 없으면 분리 판정은 NOT RUN(웹 관찰)이다.
# -WebObserveUrl 을 주면 E4 동안 harness/scripts/s6-web-resync.mjs 로 웹 거래 화면을 관찰하고 kill 시각을 표지 파일로 넘긴다(웹은
# s7-p3-gate.ps1 이 빌드해 띄운다). -SurvivorLoadOps 를 주면 생존 파티션 마켓만 치는 k6 개방 루프(calibrate.js, MARKET_HINT=1)를 실험
# 동안 더한다. -ConsolidateOn 을 주면 준비 판정 뒤 두 파티션을 그 노드로 모은다(Q3b). 합류 단계는 팔로워 상태 게이지를 모든 계열
# (파티션마다)에서 본다.
# 사용: pwsh -NoProfile -File harness/scripts/adr0022-stage5-chaos.ps1 -Image exchange-chaos-app:<태그> [-LatchBeforeTraffic] [-SnapshotEvidence] [-Protocol 2|3] [-PartitionAxes] [-Relay] [-WsGateAxes]
#       [-P3 -Resource matching-p1 [-WebObserveUrl http://localhost:3000/exchange/KRW-BTC] [-SurvivorLoadOps 20] [-ConsolidateOn chaos-api-1]]
# 실험마다 down -v 를 하므로 보존 스택(CHAOS_PROJECT 미설정 또는 exchange-chaos)에서는 시작하지 않는다(하네스 검증설계 §2-4 보존
# 스택 가드). 환경 변수가 빠지면 프로젝트가 exchange-chaos 가 되어 보존 볼륨을 지우기 때문이다. 이미지 태그는 절차가 정한다
# (7단계 절차는 -Ref 로 구운 태그를 넘긴다). -Protocol 2·3 은 CHAOS_PROJECT=exchange-pml 과 -Image exchange-chaos-app:pml 에서만 돈다(S3 설계 7-0).
# 종료코드: 0 전 실험 PASS 이고 연결 고갈 없음, 1 그 밖, 5 BLOCKED(시작 전 조건).
# 비밀 파일(harness/.env.chaos)은 compose 에 경로로만 넘기고 읽지 않는다.
param(
    [string] $Image = '',
    [string] $Experiments = 'E2,E4,E5,E6,E7',
    [string] $EvidenceDir = '',
    [string] $LocalLogDir = '',
    [int] $AppendPool = 14,
    [switch] $LatchBeforeTraffic,
    [switch] $SnapshotEvidence,
    [int] $SnapshotWaitSec = 180,
    [ValidateSet(1, 2, 3)][int] $Protocol = 1,
    [switch] $PartitionAxes,
    [int] $RejoinTimeoutSec = 180,
    [switch] $Relay,
    [switch] $WsGateAxes,
    [int] $WatchSec = 1200,
    [switch] $P3,
    [string] $Resource = '',
    [string] $WebObserveUrl = '',
    [int] $WebObserveSec = 300,
    [int] $SurvivorLoadOps = 0,
    [string] $ConsolidateOn = ''
)

$ErrorActionPreference = 'Stop'
# psql·python 의 UTF-8 출력을 코드 페이지로 잘못 풀면 한글 뒤 ASCII 표지까지 먹힌다.
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
. (Join-Path $PSScriptRoot 'lib.ps1')
# P3 에서는 두 노드가 모두 마켓 절반만 쥐어 WS 가 중계에만 기댄다(S7 설계 2-9·4-10). 그래서 -P3 는 -Relay 를 함께 켠다.
if ($P3) { $Relay = $true }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$date = Get-Date -Format 'yyyyMMdd'
$runName = $(if ($SnapshotEvidence) { 'adr0022-stage7-snapshot' } elseif ($LatchBeforeTraffic) { 'adr0022-stage6-chaos' } elseif ($P3 -or $Protocol -eq 3) { 'pml-s7-chaos' } elseif ($Relay -or $WsGateAxes) { 'pml-s6-chaos' } elseif ($PartitionAxes) { 'pml-s4-chaos' } elseif ($Protocol -eq 2) { 'pml-s3-chaos' } else { 'adr0022-stage5' })
$runDir = "$runName-$date"
if ($SnapshotEvidence) { $runDir = Join-Path $runDir $(if ($LatchBeforeTraffic) { 'band' } else { 'global' }) }
if (-not $EvidenceDir) { $EvidenceDir = Join-Path $repo "harness\evidence\$runDir" }
# 컨테이너 로그는 요청 식별자와 계정 흔적을 담아 저장소 밖에 두고 해시만 증거에 남긴다.
if (-not $LocalLogDir) { $LocalLogDir = Join-Path ([System.IO.Path]::GetTempPath()) $runDir }
$runLog = Join-Path $EvidenceDir 'commands.log'

$scripts = [ordered]@{ E2 = 'e2-db-cut.ps1'; E4 = 'e4-leader-kill.ps1'; E5 = 'e5-net-partition.ps1'
    E6 = 'e6-spike-failover.ps1'; E7 = 'e7-gray-pause.ps1'; COLD = 'adr0022-stage7-cold-restart.ps1' }
# 목표 계층 도달을 보이는 도커 사건. E2 는 Toxiproxy 프록시 계층 주입이라 도커 사건이 없다.
$reachActions = @{ E2 = @(); E4 = @('kill', 'die'); E5 = @('disconnect', 'connect'); E6 = @('kill', 'die'); E7 = @('pause', 'unpause')
    COLD = @('stop', 'start') }
$eventActions = @('kill', 'die', 'stop', 'start', 'restart', 'oom', 'pause', 'unpause', 'disconnect', 'connect')
$apiNodes = @('chaos-api-1', 'chaos-api-2')
# 중계 관찰자(ws_relay_watch.js)가 받는 마켓. W6 의 영향·비영향 마켓은 이 가운데 -Resource 파티션의 마켓과 나머지다(S7 설계 4-13).
$watchMarkets = 'KRW-BTC,KRW-ETH,KRW-XRP'
# 스냅샷 채집기가 로그와 지표를 뜨는 노드와 포트. lib.ps1 의 포트를 그대로 넘긴다.
$snapshotApi = 'chaos-api-1={0},chaos-api-2={1}' -f $Global:CHAOS.Api1Port, $Global:CHAOS.Api2Port

# 같은 마켓 안에서는 접수 락이 append 를 통째로 직렬화하므로 seq 순서의 created_at 역행은 DB 서버 시계 역행이다.
$inversionSql = "SELECT json_build_object('rows', count(*), 'inversions', count(*) FILTER (WHERE prev > created_at), " +
    "'max_regression_ms', max(extract(epoch FROM (prev - created_at)) * 1000) FILTER (WHERE prev > created_at)) " +
    "FROM (SELECT created_at, lag(created_at) OVER (PARTITION BY market_id ORDER BY seq) AS prev FROM command_journal) t"

function Write-RunLog {
    param([string] $Text)
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $Text
    Add-Content -Path $runLog -Value $line -Encoding utf8
    Write-Host $line
}

function Save-Text {
    param([string] $Path, [string] $Text)
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

function Invoke-Logged {
    param([string] $Exe, [string[]] $ArgList, [string] $Log)
    Write-RunLog ('RUN {0} {1}' -f $Exe, ($ArgList -join ' '))
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Push-Location $repo
    try { & $Exe @ArgList *> $Log; $rc = $LASTEXITCODE }
    finally { Pop-Location; $ErrorActionPreference = $eap }
    Write-RunLog "RC $rc"
    return $rc
}

function Get-RelPath {
    param([string] $Path)
    return [System.IO.Path]::GetRelativePath($repo, $Path)
}

# invariants.sql 을 psql 로 통째로 돌린 출력에서 C22 팔마다 행 수를 센다. 술어를 이 파일에 복제하지 않는다. V48 객체가 있는 스키마는
# C22' 판이라 구간 경계 팔(boundary)이 더 있다(ADR 0034 S7, 하네스 검증설계 §4-1).
function Get-C22Arms {
    param([string] $Text, $Rc)
    $inC22 = $false; $seen = $false; $failed = $false; $time = 0; $market = 0; $boundary = 0
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -like '== C22 *') { $inC22 = $true; $seen = $true; continue }
        if (-not $inC22) { continue }
        if ($line -like '== *') { break }
        if ($line -match 'ERROR:') { $failed = $true }
        elseif ($line -like 'time|*') { $time++ }
        elseif ($line -like 'market|*') { $market++ }
        elseif ($line -like 'boundary|*') { $boundary++ }
    }
    if ($Rc -ne 0 -or -not $seen -or $failed) { return [ordered]@{ ok = $false; timeArm = $null; marketArm = $null; boundaryArm = $null } }
    return [ordered]@{ ok = $true; timeArm = $time; marketArm = $market; boundaryArm = $boundary }
}

# 사건 줄은 실험 전에 띄운 docker events 스트림이 파일로 흘린 것이다. 네트워크 사건의 컨테이너 id 는 down 전 이름으로 바꾼다.
function Get-DockerEvents {
    param([string] $RawPath)
    $names = @{}
    foreach ($line in @(& docker ps -a --no-trunc --format '{{.ID}} {{.Names}}' 2>$null)) {
        $parts = "$line".Split(' ')
        if ($parts.Count -eq 2) { $names[$parts[0]] = $parts[1] }
    }
    $events = @()
    foreach ($line in @(Get-Content $RawPath -Encoding utf8)) {
        try { $e = $line | ConvertFrom-Json } catch { continue }
        if ($eventActions -notcontains $e.Action) { continue }
        $target = if ($e.Type -eq 'network') { $names[[string]$e.Actor.Attributes.container] } else { [string]$e.Actor.Attributes.name }
        $events += [ordered]@{ time = [DateTimeOffset]::FromUnixTimeSeconds([long]$e.time).ToString('o')
            type = $e.Type; action = $e.Action; container = $target }
    }
    return , $events
}

# 팔로워 상태 게이지(exchange_follower_catchup_state)의 모든 계열 값. 계열은 파티션마다 하나다. 긁지 못하면 $null 이다.
function Get-FollowerStates {
    param([int] $Port)
    try { $text = Invoke-RestMethod -Uri "http://localhost:$Port/actuator/prometheus" -TimeoutSec 5 } catch { return $null }
    $values = @([regex]::Matches(($text | Out-String), 'exchange_follower_catchup_state\{[^}]*\}\s+([-0-9.eE]+)') |
        ForEach-Object { [double]$_.Groups[1].Value })
    return , $values
}

# E4 합류 단계(S4 설계 7-2 X2). docker kill 한 옛 리더를 되살려 팔로워로 합류하는지 본다. '기동은 늘 팔로워'의 스택 확인이다.
# 되살린 노드가 liveness UP 과 팔로워 상태 1 을 내고 /actuator/partitions 에서 FOLLOWER 이며, 그 노드 로그에 승격 문구가 없고
# (부팅 획득 없음), leader_lease 의 holder·epoch 가 되살리기 전과 같고, 그 노드의 파티션 강등 계수가 0 이어야 참이다.
# 팔로워 상태는 모든 계열(파티션마다)이 1 이어야 한다. P1·P2 는 계열이 하나라 종전과 같고, P3 는 되살린 노드가 두 파티션 모두
# 팔로워이고 옛 파티션을 되찾지 않는 것(결정 4)을 본다(S7 설계 4-13).
function Invoke-RejoinStep {
    param([string] $Dir)
    $res = [ordered]@{ ok = $false }
    $ports = @{ 'chaos-api-1' = $Global:CHAOS.Api1Port; 'chaos-api-2' = $Global:CHAOS.Api2Port }
    $stopped = @('chaos-api-1', 'chaos-api-2' | Where-Object { (& docker inspect -f '{{.State.Running}}' $_ 2>$null | Out-String).Trim() -ne 'true' })
    if ($stopped.Count -ne 1) { $res['사유'] = "멈춘 api 노드가 하나가 아니다($($stopped -join ','))"; Save-Text (Join-Path $Dir 'rejoin.json') ($res | ConvertTo-Json -Depth 6); return $res }
    $node = $stopped[0]; $port = $ports[$node]
    $res['node'] = $node
    $leaseSql = "SELECT coalesce(json_agg(json_build_object('resource', resource, 'holder', holder, 'epoch', epoch) ORDER BY resource), '[]'::json) FROM leader_lease"
    $leaseBefore = Invoke-Psql $leaseSql
    $startedAt = [DateTimeOffset]::UtcNow
    Write-RunLog "REJOIN docker start $node"
    & docker start $node *> $null
    $res['startRc'] = $LASTEXITCODE
    $joined = Wait-For {
        $live = $false
        try { $live = (Invoke-RestMethod -Uri "http://localhost:$port/actuator/health/liveness" -TimeoutSec 3).status -eq 'UP' } catch { $live = $false }
        $states = Get-FollowerStates -Port $port
        $parts = $null
        try { $parts = Invoke-RestMethod -Uri "http://localhost:$port/actuator/partitions" -TimeoutSec 3 } catch { $parts = $null }
        $follower = $parts -and @($parts.partitions | Where-Object { $_.state -ne 'FOLLOWER' }).Count -eq 0 -and @($parts.partitions).Count -gt 0
        $allOne = $null -ne $states -and $states.Count -gt 0 -and @($states | Where-Object { $_ -ne 1 }).Count -eq 0
        $live -and $allOne -and $follower
    } -TimeoutSec $RejoinTimeoutSec -PollSec 2
    $res['joinedAt'] = $(if ($joined) { $joined.ToString('o') } else { $null })
    $res['followerStates'] = Get-FollowerStates -Port $port
    $res['joinSeconds'] = $(if ($joined) { [math]::Round(($joined - $startedAt.UtcDateTime).TotalSeconds, 1) } else { $null })
    $since = $startedAt.ToUnixTimeSeconds()
    $logText = (& cmd /c "docker logs --since $since $node 2>&1" | Out-String)
    $res['promotionLines'] = ([regex]::Matches($logText, '리더 승격')).Count
    $res['followerBootLines'] = ([regex]::Matches($logText, '팔로워로 기동')).Count
    $leaseAfter = Invoke-Psql $leaseSql
    $res['leaseBefore'] = $leaseBefore; $res['leaseAfter'] = $leaseAfter
    $demotions = 0.0; $scraped = $false
    try {
        $text = Invoke-RestMethod -Uri "http://localhost:$port/actuator/prometheus" -TimeoutSec 5
        $scraped = $true
        foreach ($m in [regex]::Matches(($text | Out-String), 'exchange_partition_demotions_total\{[^}]*\}\s+([-0-9.eE]+)')) { $demotions += [double]$m.Groups[1].Value }
    } catch { $scraped = $false }
    $res['demotions'] = $(if ($scraped) { $demotions } else { $null })
    $res['ok'] = [bool]$joined -and $res['promotionLines'] -eq 0 -and $res['followerBootLines'] -ge 1 -and
        ($leaseBefore -eq $leaseAfter) -and $scraped -and $demotions -eq 0
    Save-Text (Join-Path $Dir 'rejoin.json') ($res | ConvertTo-Json -Depth 6)
    Write-RunLog ("REJOIN {0} ok={1} joinSeconds={2} promotionLines={3}" -f $node, $res['ok'], $res['joinSeconds'], $res['promotionLines'])
    return $res
}

# 판정 JSON 한 개를 읽는다. 없거나 깨졌으면 $null 이다.
function Read-JudgeFile {
    param([string] $Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    try { return (Get-Content -LiteralPath $Path -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable) } catch { return $null }
}

# P3 공통 판정의 S7 몫(하네스 검증설계 §3 P3 실험 공통 판정, S7 설계 5-2). 공통 판정(s3_common_axes.py)이 봇을 멈추고 비운 뒤에 부른다.
#   1) 구간 경계 증분: 전환 직후 표본(protocol.json)과 지금 표본(segment_post.json)이 이력 행마다 같다(segment.json).
#   2) 희생 epoch 집합의 구 쓰기 증분: 주입 직전 -Resource 행의 holder 가 쥔 파티션 epoch 마다 주입 뒤 행 수가 늘지 않았다
#      (victim_writes.json, epoch_samples.jsonl).
#   3) 생존 파티션 ACK 간격: 희생 노드가 쥐지 않은 파티션 마켓의 NEW 간격 최대가 기준선 + 1초 이하(ack_interval.json). 희생 노드가
#      두 파티션을 모두 쥐었으면 생존 파티션이 없어 판정하지 않고 적기만 한다.
#   4) 희생 노드가 -Resource 밖의 파티션도 쥐었으면 그 파티션의 RTO < 30초(rto_<자원>.json, Q3b 의 '두 파티션 모두').
# 결과는 p3_axes.json 이다. fail 은 거짓 축, notRun 은 양성 축이 모자란 판정이다.
function Invoke-P3CommonAxes {
    param([string] $Dir, [string] $Console)
    $res = [ordered]@{ fail = @(); notRun = @(); files = [ordered]@{} }
    Invoke-Logged 'python' @('harness/scale/s7_axes.py', 'segment-count', '--out', (Get-RelPath (Join-Path $Dir 'segment_post.json'))) `
        (Join-Path $Dir 'segment_post.log') | Out-Null
    Invoke-Logged 'python' @('harness/scale/s7_axes.py', 'segment-judge', '--pre', (Get-RelPath (Join-Path $Dir 'protocol.json')),
        '--post', (Get-RelPath (Join-Path $Dir 'segment_post.json')), '--out', (Get-RelPath (Join-Path $Dir 'segment.json'))) `
        (Join-Path $Dir 'segment.log') | Out-Null
    $judges = [ordered]@{ segment = Read-JudgeFile (Join-Path $Dir 'segment.json') }
    $t0Hit = Select-String -Path $Console -Pattern 'T0=(\S+)' | Select-Object -First 1
    $injectAt = $(if ($t0Hit) { $t0Hit.Matches[0].Groups[1].Value } else { $null })
    $res['injectAt'] = $injectAt
    if ($injectAt -and $Resource) {
        Invoke-Logged 'python' @('harness/scale/s7_axes.py', 'victim-writes', '--samples', (Get-RelPath (Join-Path $Dir 'epoch_samples.jsonl')),
            '--inject-at', $injectAt, '--resource', $Resource, '--out', (Get-RelPath (Join-Path $Dir 'victim_writes.json'))) `
            (Join-Path $Dir 'victim_writes.log') | Out-Null
        $judges['victimWrites'] = Read-JudgeFile (Join-Path $Dir 'victim_writes.json')
        $victim = @()
        if ($judges['victimWrites'] -and $judges['victimWrites']['facts']) { $victim = @($judges['victimWrites']['facts']['victim_resources']) }
        # 희생 노드가 -Resource 밖의 파티션도 쥐었으면(Q3b) 그 파티션의 RTO 도 잰다. -Resource 파티션의 RTO 는 실험 스크립트가 잰다.
        $victimMap = $(if ($judges['victimWrites'] -and $judges['victimWrites']['facts']) { $judges['victimWrites']['facts']['victim_map'] } else { $null })
        if ($victimMap) {
            foreach ($epoch in @($victimMap.Keys)) {
                $r = $victimMap[$epoch]
                if ($r -eq $Resource) { continue }
                $rtoFile = Join-Path $Dir "rto_$r.json"
                Invoke-Logged 'python' @('harness/scale/s7_axes.py', 'partition-rto', '--resource', $r, '--old-epoch', "$epoch",
                    '--inject-at', $injectAt, '--out', (Get-RelPath $rtoFile)) (Join-Path $Dir "rto_$r.log") | Out-Null
                $judges["rto_$r"] = Read-JudgeFile $rtoFile
            }
        }
        $split = Get-PartitionMarkets
        $survivorRes = @('matching-p0', 'matching-p1' | Where-Object { $victim -notcontains $_ })
        if ($victim.Count -eq 0 -or -not $split) {
            $res['ackInterval'] = '희생 파티션이나 파티션 마켓을 읽지 못했다'
            $res['notRun'] += 'ackInterval'
        } elseif ($survivorRes.Count -eq 0) {
            $res['ackInterval'] = '생존 파티션 없음(희생 노드가 두 파티션을 쥐었다). 판정하지 않고 적기만 한다'
        } else {
            $survivorMarkets = (@($survivorRes | ForEach-Object { $split[$_] }) | ForEach-Object { $_ }) -join ','
            $affectedMarkets = @($split[$Resource]) -join ','
            Invoke-Logged 'python' @('harness/scale/s7_axes.py', 'ack-interval', '--survivor-markets', $survivorMarkets,
                '--affected-markets', $affectedMarkets, '--inject-at', $injectAt, '--out', (Get-RelPath (Join-Path $Dir 'ack_interval.json'))) `
                (Join-Path $Dir 'ack_interval.log') | Out-Null
            $judges['ackInterval'] = Read-JudgeFile (Join-Path $Dir 'ack_interval.json')
        }
    } else {
        $res['사유'] = '주입 시각(T0) 줄이나 -Resource 가 없어 희생 epoch 집합과 ACK 간격을 볼 수 없다'
        $res['notRun'] += @('victimWrites', 'ackInterval')
    }
    foreach ($k in $judges.Keys) {
        $v = $(if ($judges[$k]) { [string]$judges[$k]['verdict'] } else { $null })
        $res['files'][$k] = $v
        if ($v -eq 'PASS') { continue }
        if ($v -eq 'NOT RUN') { $res['notRun'] += $k } else { $res['fail'] += $k }
    }
    $res['ok'] = $res['fail'].Count -eq 0 -and $res['notRun'].Count -eq 0
    Save-Text (Join-Path $Dir 'p3_axes.json') ($res | ConvertTo-Json -Depth 8)
    return $res
}

function Invoke-Experiment {
    param([string] $Exp)
    $dir = Join-Path $EvidenceDir $Exp
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $row = [ordered]@{ experiment = $Exp; script = "harness/scripts/$($scripts[$Exp])"; image = $Image; imageId = $imageId }
    $sampler = $null
    $eventsProc = $null
    $balanceSampler = $null
    $freshness = $null
    $watch = $null
    $stop = Join-Path $dir 'stop-conn'
    $stopBalance = Join-Path $dir 'stop-balance'
    $stopFresh = Join-Path $dir 'stop-freshness'
    $epochSampler = $null
    $stopEpoch = Join-Path $dir 'stop-epoch'
    $k6Load = $null
    $webObs = $null
    $markFile = Join-Path $dir 'kill-mark.txt'
    try {
        Write-RunLog "START $Exp"
        $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
        # 공통 판정의 프로메테우스 창 시작. 스택을 세우기 전 시각이라 전환의 노드 재기동까지 덮는다.
        $stackFrom = [DateTimeOffset]::UtcNow
        $code = Invoke-ComposeQuiet '--profile obs up -d --wait'
        if ($code -ne 0) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = "compose up 종료코드 $code"; return $row }
        $running = [ordered]@{}
        foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-bots') { $running[$c] = (& docker inspect -f '{{.Image}}' $c 2>$null | Out-String).Trim() }
        $row['containerImages'] = $running
        if (@($running.Values | Where-Object { $_ -ne $imageId }).Count -gt 0) {
            $row['분리 판정'] = 'BLOCKED'; $row['사유'] = '고정한 이미지로 뜨지 않았다'; return $row
        }
        if ($Protocol -ge 2) {
            # 실험 스크립트 전에 모드를 올린다(S3 설계 7-2, S7 설계 4-13). 도구가 봇을 멈추고 드레인한 뒤 두 노드를 다시 띄우고 봇을
            # 되살린다. P3 는 명령 대역 래치를 함께 건다(폐기용 DB 만, ADR 0034 결정한 값 9).
            $transitionArgs = @('harness/scale/protocol_latch.py', 'transition', '--target', "$Protocol")
            if ($Protocol -eq 3) { $transitionArgs += '--latch-band' }
            $transitionArgs += @('--out', (Get-RelPath (Join-Path $dir 'protocol.json')))
            $protocolRc = Invoke-Logged 'python' $transitionArgs (Join-Path $dir 'protocol.log')
            $row['protocolTransition'] = [ordered]@{ rc = $protocolRc; file = 'protocol.json' }
            if ($protocolRc -ne 0) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = "P$Protocol 전환 실패(rc=$protocolRc). protocol.json 을 본다"; return $row }
        }
        if ($P3) {
            # Q0 준비 판정(S7 설계 5-2 Q0). 전환 도구가 앱 노드를 다시 띄운 뒤라 그 컨테이너의 환경과 파티션 배정을 본다.
            $preflight = Invoke-P3Preflight -Dir $dir -TransitionFile (Join-Path $dir 'protocol.json') -RunId "Q0-$Exp-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
            $row['p3Preflight'] = [ordered]@{ ok = $preflight['ok']; failed = $preflight['failed']; relocated = $preflight['relocated']; file = 'p3-preflight.json' }
            if (-not $preflight['ok']) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = 'Q0 준비 판정 실패. p3-preflight.json 을 본다'; return $row }
            if ($ConsolidateOn) {
                # Q3b. 두 파티션을 한 노드로 모은 뒤 그 노드를 주입 대상으로 삼는다(S7 설계 5-2).
                $moved = Move-PartitionsTo -Node $ConsolidateOn
                Save-Text (Join-Path $dir 'consolidate.json') ($moved | ConvertTo-Json -Depth 6)
                $row['consolidate'] = [ordered]@{ node = $ConsolidateOn; ok = $moved['ok']; file = 'consolidate.json' }
                if (-not $moved['ok']) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = "두 파티션을 $ConsolidateOn 으로 모으지 못했다. consolidate.json 을 본다"; return $row }
            }
        } elseif ($Relay) {
            # 오버레이가 실제로 걸렸는지 본다(S6 설계 4-19). 전환 도구가 앱 노드를 다시 띄운 뒤라 그 컨테이너의 환경을 본다.
            $preflight = Test-RelayOverlay
            Save-Text (Join-Path $dir 'relay-preflight.json') ($preflight | ConvertTo-Json -Depth 6)
            $row['relayPreflight'] = $preflight
            if (-not $preflight['ok']) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = '중계 오버레이 확인 실패. relay-preflight.json 을 본다'; return $row }
        }
        if ($Relay) {
            $watch = Start-RelayWatch -Name "pml-s6-watch-$Exp" -OutDir $dir -DurationSec $WatchSec -Markets $watchMarkets
            $row['relayWatch'] = $watch
            Write-RunLog "WATCH start $($watch.name) rc=$($watch.rc)"
            if ($watch.rc -ne 0) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = "관찰자 기동 실패(rc=$($watch.rc))"; return $row }
            Remove-Item $stopFresh -Force -ErrorAction SilentlyContinue
            $freshArgs = @('harness/scale/s6_relay_axes.py', 'freshness', '--out', (Get-RelPath (Join-Path $dir 'freshness.jsonl')),
                '--stop-file', (Get-RelPath $stopFresh))
            if ($P3) {
                # 표본 마켓(KRW-BTC)의 파티션을 ACCEPTING 으로 쥔 노드를 소유 노드로 짝짓는다(S7 설계 4-13).
                $split = Get-PartitionMarkets
                $btcResource = $(if ($split) { @($split.Keys | Where-Object { @($split[$_]) -contains 'KRW-BTC' }) | Select-Object -First 1 } else { $null })
                if (-not $btcResource) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = 'KRW-BTC 의 파티션을 읽지 못했다'; return $row }
                $freshArgs += @('--resource', $btcResource)
            }
            $freshness = Start-Process -FilePath 'python' -WorkingDirectory $repo -NoNewWindow -PassThru -ArgumentList $freshArgs `
                -RedirectStandardOutput (Join-Path $dir 'freshness.out') -RedirectStandardError (Join-Path $dir 'freshness.err')
        }
        if ($LatchBeforeTraffic) {
            # 실험 스크립트가 봇 부하를 정하기 전에 건다. 봇은 compose 기동과 함께 돌므로 호출 전 저널 행 수가 latch.json 에 남는다.
            $latchRc = Invoke-Logged 'python' @('harness/scale/band_cutover.py', 'latch', '--out', (Get-RelPath (Join-Path $dir 'latch.json'))) (Join-Path $dir 'latch.log')
            if ($latchRc -ne 0) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = "래치 거부 또는 실패(rc=$latchRc). latch.json 을 본다"; return $row }
        }
        # 연결 예산 사전 점검. max_connections 는 떠 있는 chaos-pg 에서 읽는다. 요청 풀 32 는 chaos compose 값이다.
        $budgetRc = Invoke-Logged 'python' @('harness/scale/pg_samplers.py', 'budget', '--live', '--api-nodes', '2',
            '--request-pool', '32', '--lease-pool', '2', '--append-pool', "$AppendPool", '--bots-request-pool', '10',
            '--exporter', '2', '--tooling', '4', '--out', (Get-RelPath (Join-Path $dir 'budget.json'))) (Join-Path $dir 'budget.log')
        if ($budgetRc -ne 0) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = "연결 예산 부족 또는 설정 읽기 실패(rc=$budgetRc)"; return $row }
        if ($SnapshotEvidence) {
            # 주입 전 증거. 활성 마켓마다 스냅샷이 생기기를 기다리되 못 채워도 실험은 돈다. 판정은 stage7_judge.py 가 한다.
            $preRc = Invoke-Logged 'python' @('harness/scale/snapshot_evidence.py', 'pre', '--dir', (Get-RelPath $dir),
                '--api', $snapshotApi, '--wait-sec', "$SnapshotWaitSec") (Join-Path $dir 'snapshot_pre.log')
            $row['snapshotEvidence'] = [ordered]@{ pre = $preRc; post = $null }
        }
        $row['clockBefore'] = Test-ClockSkew

        Remove-Item $stop -Force -ErrorAction SilentlyContinue
        $samplesPath = Join-Path $dir 'conn_samples.jsonl'
        $sampler = Start-Process -FilePath 'python' -WorkingDirectory $repo -NoNewWindow -PassThru `
            -ArgumentList @('harness/scale/pg_samplers.py', 'connections', '--out', (Get-RelPath $samplesPath),
                '--stop-file', (Get-RelPath $stop), '--interval', '5') `
            -RedirectStandardOutput (Join-Path $dir 'conn_sampler.out') -RedirectStandardError (Join-Path $dir 'conn_sampler.err')
        if ($Protocol -ge 2) {
            Remove-Item $stopBalance -Force -ErrorAction SilentlyContinue
            $balanceSampler = Start-Process -FilePath 'python' -WorkingDirectory $repo -NoNewWindow -PassThru `
                -ArgumentList @('harness/scale/s3_common_axes.py', 'sample', '--out', (Get-RelPath (Join-Path $dir 'balance_samples.jsonl')),
                    '--stop-file', (Get-RelPath $stopBalance), '--interval', '2') `
                -RedirectStandardOutput (Join-Path $dir 'balance_sampler.out') -RedirectStandardError (Join-Path $dir 'balance_sampler.err')
        }

        # 사건 스트림을 실험 스크립트보다 먼저 띄운다. 끝난 뒤 --since 로 되물으면 주입 사건이 데몬 버퍼에 남아 있지 않았다(5단계 E5·E7).
        $eventsPath = Join-Path $dir 'docker_events.jsonl'
        $eventsProc = Start-Process -FilePath 'docker' -NoNewWindow -PassThru `
            -ArgumentList @('events', '--since', "$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())", '--format', '"{{json .}}"',
                '--filter', 'type=container', '--filter', 'type=network') `
            -RedirectStandardOutput $eventsPath -RedirectStandardError (Join-Path $dir 'docker_events.err')

        if ($PartitionAxes) {
            $row['partitionAxes'] = [ordered]@{ pre = (Invoke-Logged 'python' @('harness/scale/s4_partition_axes.py', 'snapshot', '--out',
                (Get-RelPath (Join-Path $dir 'partition_pre.json'))) (Join-Path $dir 'partition_pre.log')) }
        }
        if ($P3) {
            # 희생 epoch 집합 표본기(S7 설계 5-2 공통 판정). 주입 직전 표본이 희생 노드와 그 epoch 를 정한다.
            Remove-Item $stopEpoch -Force -ErrorAction SilentlyContinue
            $epochSampler = Start-Process -FilePath 'python' -WorkingDirectory $repo -NoNewWindow -PassThru `
                -ArgumentList @('harness/scale/s7_axes.py', 'epoch-sampler', '--out', (Get-RelPath (Join-Path $dir 'epoch_samples.jsonl')),
                    '--stop-file', (Get-RelPath $stopEpoch), '--interval', '1') `
                -RedirectStandardOutput (Join-Path $dir 'epoch_sampler.out') -RedirectStandardError (Join-Path $dir 'epoch_sampler.err')
            if ($SurvivorLoadOps -gt 0) {
                # 생존 파티션 마켓만 치는 k6 개방 루프(하네스 검증설계 §3 P3 공통 판정의 부하). 계정은 그 마켓으로 다시 발급한다.
                $split = Get-PartitionMarkets
                $survivorMarkets = $(if ($split) { (@($split.Keys | Where-Object { $_ -ne $Resource } | ForEach-Object { $split[$_] }) | ForEach-Object { $_ }) -join ',' } else { '' })
                $keys = $(if ($survivorMarkets) { Initialize-LoadKeys -Markets $survivorMarkets } else { 0 })
                if ($keys -lt 1) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = '생존 파티션 부하 계정을 발급하지 못했다'; return $row }
                $k6Load = Start-SurvivorLoad -Name "pml-s7-k6-$Exp" -Markets $survivorMarkets -Ops $SurvivorLoadOps -DurationSec $WatchSec -RunId "s7surv$Exp"
                $row['survivorLoad'] = $k6Load
                Write-RunLog "K6 start $($k6Load.name) rc=$($k6Load.rc) markets=$survivorMarkets ops=$SurvivorLoadOps"
                if ($k6Load.rc -ne 0) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = "생존 파티션 부하 기동 실패(rc=$($k6Load.rc))"; return $row }
            }
            if ($WebObserveUrl -and $Exp -eq 'E4') {
                # W6 웹 관찰(S7 설계 4-13). 실험 스크립트 전에 띄우고, 스크립트가 끝나면 kill 시각을 표지 파일에 적는다.
                Remove-Item $markFile -Force -ErrorAction SilentlyContinue
                $webObs = Start-Process -FilePath 'node' -WorkingDirectory $repo -NoNewWindow -PassThru `
                    -ArgumentList @('harness/scripts/s6-web-resync.mjs', "--url=$WebObserveUrl", "--duration=$WebObserveSec",
                        "--out=$(Get-RelPath (Join-Path $dir 'web_resync.json'))", "--mark-file=$(Get-RelPath $markFile)") `
                    -RedirectStandardOutput (Join-Path $dir 'web_resync.out') -RedirectStandardError (Join-Path $dir 'web_resync.err')
                $row['webObserve'] = [ordered]@{ url = $WebObserveUrl; durationSec = $WebObserveSec; startedAt = (Now-Iso) }
                Write-RunLog "WEB observe start $WebObserveUrl"
            }
        }
        $t0 = [DateTimeOffset]::UtcNow
        $console = Join-Path $dir 'console.log'
        Write-RunLog "EXPERIMENT $Exp pwsh -NoProfile -File $($row.script)"
        $eap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        # S4 관문의 COLD 는 '기동은 늘 팔로워'를 판정에 더한다(S4 설계 7-2 X5).
        $expArgs = @()
        if ($PartitionAxes -and $Exp -eq 'COLD') { $expArgs += '-S4Boot' }
        # 중계 주행의 E4(W5·W6)는 kill 뒤 RTO + 5초 동안 모든 마켓에 부하가 있어야 마켓별 새 epoch 첫 메시지를 잰다(S6 설계 7-2 W5).
        # e4-leader-kill.ps1 이 드레인 전에 부하를 6초 더 둔다. 다른 주행과 다른 실험에는 주지 않는다.
        if ($Relay -and $Exp -eq 'E4') { $env:CHAOS_E4_HOLD_LOAD_SEC = '6' } else { Remove-Item Env:CHAOS_E4_HOLD_LOAD_SEC -ErrorAction SilentlyContinue }
        # 볼 리스 자원(S4 설계 4-18, S7 설계 4-13). P3 에서는 실험 스크립트가 이 자원 없이 시작하지 않는다.
        if ($Resource) { $expArgs += @('-Resource', $Resource) }
        try { & pwsh -NoProfile -File (Join-Path $PSScriptRoot $scripts[$Exp]) @expArgs *> $console; $expRc = $LASTEXITCODE }
        finally { $ErrorActionPreference = $eap; Remove-Item Env:CHAOS_E4_HOLD_LOAD_SEC -ErrorAction SilentlyContinue }
        $t1 = [DateTimeOffset]::UtcNow
        Write-RunLog "EXPERIMENT $Exp rc=$expRc"
        $row['exitCode'] = $expRc
        $row['window'] = [ordered]@{ from = $t0.ToString('o'); to = $t1.ToString('o') }
        New-Item -ItemType File -Force -Path $stop | Out-Null
        if (-not $sampler.WaitForExit(30000)) { Stop-Process -Id $sampler.Id -Force }
        if ($epochSampler) {
            New-Item -ItemType File -Force -Path $stopEpoch | Out-Null
            if (-not $epochSampler.WaitForExit(30000)) { Stop-Process -Id $epochSampler.Id -Force }
        }
        # 생존 파티션 부하는 공통 판정의 드레인 전에 멈춘다. 돌고 있으면 새 NEW 가 들어와 드레인이 끝나지 않는다.
        if ($k6Load) {
            Stop-SurvivorLoad -Name $k6Load.name -LogPath (Join-Path $LocalLogDir "$Exp-k6-survivor.log")
            Write-RunLog "K6 stop $($k6Load.name)"
            $k6Load = $null
        }
        if ($webObs) {
            $killHit = Select-String -Path $console -Pattern 'T0=(\S+) killed=' | Select-Object -First 1
            if ($killHit) { Save-Text $markFile $killHit.Matches[0].Groups[1].Value }
        }
        if ($balanceSampler) {
            New-Item -ItemType File -Force -Path $stopBalance | Out-Null
            if (-not $balanceSampler.WaitForExit(30000)) { Stop-Process -Id $balanceSampler.Id -Force }
        }
        # 마지막 복귀 사건이 스트림에 닿을 틈을 두고 멈춘다.
        Start-Sleep -Seconds 2
        Stop-Process -Id $eventsProc.Id -Force
        $eventsProc.WaitForExit(10000) | Out-Null

        # 판정 파일은 실험 스크립트가 harness/evidence/chaos-runs 에 쓴다. 판정 전에 예외로 끝나면 없다.
        $runId = $null
        $hit = Select-String -Path $console -Pattern 'runId=((?:E\d|COLD)-\d{8}-\d{6})' | Select-Object -First 1
        if ($hit) { $runId = $hit.Matches[0].Groups[1].Value }
        $row['runId'] = $runId
        $verdict = $null
        if ($runId) {
            $src = Join-Path $Global:CHAOS.EvidenceDir "$runId.json"
            if (Test-Path $src) {
                Copy-Item $src (Join-Path $dir 'verdict.json') -Force
                $verdict = Get-Content $src -Raw -Encoding utf8 | ConvertFrom-Json
            }
        }

        # 여기부터 down -v 전 증거. 카오스 볼륨과 컨테이너가 사라지면 다시 뜰 수 없다.
        if ($PartitionAxes -and $Exp -eq 'E4') {
            # 합류 단계(S4 설계 7-2 X2). 공통 판정 전에 돈다. 봇을 멈추기 전이라 새 리더가 부하를 받는 동안 합류한다.
            $row['rejoin'] = Invoke-RejoinStep $dir
        }
        if ($Protocol -ge 2) {
            # 공통 판정(S3 설계 7-3, S7 설계 4-13). 봇을 멈추고 드레인한 뒤 판정하므로 뒤의 증거 수집은 무부하 상태에서 돈다.
            $commonArgs = @('harness/scale/s3_common_axes.py', 'judge', '--window-from', $stackFrom.ToString('o'),
                '--fault-from', $t0.ToString('o'), '--fault-to', $t1.ToString('o'),
                '--samples', (Get-RelPath (Join-Path $dir 'balance_samples.jsonl')), '--protocol', "$Protocol",
                '--out', (Get-RelPath (Join-Path $dir 's3_common.json')))
            if ($Resource) { $commonArgs += @('--resource', $Resource) }
            # 옛 리더 강등 문구의 양성 대조(S4 설계 7-2). 문구가 코드와 어긋나면 리더 노드 축이 공허하게 통과한다.
            if ($PartitionAxes -and @('E5', 'E7') -contains $Exp) { $commonArgs += '--expect-demotion' }
            $commonRc = Invoke-Logged 'python' $commonArgs (Join-Path $dir 's3_common.log')
            $common = $null
            $commonPath = Join-Path $dir 's3_common.json'
            if (Test-Path $commonPath) { $common = Get-Content $commonPath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable }
            $row['s3Common'] = [ordered]@{ rc = $commonRc; ok = $(if ($common) { $common['ok'] } else { $false })
                failed = $(if ($common -and $common['axes']) { @($common['axes'].Keys | Where-Object { -not $common['axes'][$_] }) } else { @('판정 파일 없음') })
                reservations = $(if ($common -and $common['state']) { $common['state']['reservations'] } else { $null })
                reservedSettledByLaterEpoch = $(if ($common -and $common['state']) { $common['state']['reserved_settled_by_later_epoch'] } else { $null })
                file = 's3_common.json' }
        }
        if ($P3) {
            # P3 공통 판정의 S7 몫(구간 경계 증분, 희생 epoch 집합, 생존 파티션 ACK 간격). 위 공통 판정이 봇을 멈추고 비운 뒤다.
            $row['p3Axes'] = Invoke-P3CommonAxes -Dir $dir -Console $console
        }
        if ($PartitionAxes) {
            $postRc = Invoke-Logged 'python' @('harness/scale/s4_partition_axes.py', 'snapshot', '--out',
                (Get-RelPath (Join-Path $dir 'partition_post.json'))) (Join-Path $dir 'partition_post.log')
            $axesRc = Invoke-Logged 'python' @('harness/scale/s4_partition_axes.py', 'judge', '--window-from', $stackFrom.ToString('o'),
                '--fault-from', $t0.ToString('o'), '--fault-to', $t1.ToString('o'),
                '--pre', (Get-RelPath (Join-Path $dir 'partition_pre.json')), '--post', (Get-RelPath (Join-Path $dir 'partition_post.json')),
                '--out', (Get-RelPath (Join-Path $dir 'partition_axes.json'))) (Join-Path $dir 'partition_axes.log')
            $axes = $null
            $axesPath = Join-Path $dir 'partition_axes.json'
            if (Test-Path $axesPath) { $axes = Get-Content $axesPath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable }
            $row['partitionAxes']['post'] = $postRc
            $row['partitionAxes']['rc'] = $axesRc
            $row['partitionAxes']['ok'] = $(if ($axes) { $axes['ok'] } else { $false })
            $row['partitionAxes']['failed'] = $(if ($axes -and $axes['axes']) { @($axes['axes'].Keys | Where-Object { -not $axes['axes'][$_] }) } else { @('판정 파일 없음') })
            $row['partitionAxes']['file'] = 'partition_axes.json'
        }
        if ($WsGateAxes) {
            # WS 수용 판정의 스택 표본(S6 설계 7-2 W0·W5). 공통 판정이 봇을 멈춘 뒤라 LB 의 1차 노드가 봇 쓰기 503 으로 빠져 있지 않다.
            $gateMode = $(if ($Relay) { 'broker' } else { 'simple' })
            $gateArgs = @('harness/scale/s6_relay_axes.py', 'gate', '--mode', $gateMode, '--lb-since', [DateTimeOffset]::UtcNow.ToString('o'),
                '--out', (Get-RelPath (Join-Path $dir 'ws_gate.json')))
            if (-not $Relay -and @('E4', 'E7') -contains $Exp) { $gateArgs += @('--expect-follower', 'chaos-api-1') }
            $gateRc = Invoke-Logged 'python' $gateArgs (Join-Path $dir 'ws_gate.log')
            $gate = $null
            $gatePath = Join-Path $dir 'ws_gate.json'
            if (Test-Path $gatePath) { $gate = Get-Content $gatePath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable }
            $row['wsGate'] = [ordered]@{ rc = $gateRc; mode = $gateMode; ok = $(if ($gate) { $gate['ok'] } else { $false })
                failed = $(if ($gate -and $gate['axes']) { @($gate['axes'].Keys | Where-Object { -not $gate['axes'][$_] }) } else { @('판정 파일 없음') })
                file = 'ws_gate.json' }
        }
        if ($Relay) {
            # 관찰자와 표본기를 멈춰 마지막 상태를 받은 뒤 중계 축을 판정한다(S6 설계 7-2 W5).
            New-Item -ItemType File -Force -Path $stopFresh | Out-Null
            if ($freshness -and -not $freshness.WaitForExit(30000)) { Stop-Process -Id $freshness.Id -Force }
            if ($watch) {
                Stop-RelayWatch -Name $watch.name -LogPath (Join-Path $dir 'watch.log')
                $watch = $null
            }
            Invoke-Logged 'python' @('harness/scale/s6_relay_axes.py', 'watch', '--log', (Get-RelPath (Join-Path $dir 'watch.log')),
                '--out', (Get-RelPath (Join-Path $dir 'watch.json'))) (Join-Path $dir 'watch_merge.log') | Out-Null
            if ($Exp -eq 'E4') {
                $killHit = Select-String -Path $console -Pattern 'T0=(\S+) killed=(\S+)' | Select-Object -First 1
                $killAt = $(if ($killHit) { $killHit.Matches[0].Groups[1].Value } else { $t0.ToString('o') })
                # 창의 시작은 관찰자를 띄운 때다. 스택 기동과 P2·P3 전환의 재기동 구간은 판정 창에 넣지 않는다. 미러 신선도는
                # kill 한 노드가 합류한 뒤부터 본다(설계 7-2 W5, 아래 --rejoin-at). 두 시각은 2026-09-25 S6 관문 주행 전에 보정했고 W6 에도 같이 쓴다.
                if ($Protocol -eq 3) {
                    # W6(S6 설계 7-2, S7 설계 4-13). P2 모양의 W5 는 P3 에서 쓰지 않는다. 희생 노드는 콘솔의 killed=, 영향 마켓은 관찰 마켓
                    # 가운데 -Resource 파티션의 마켓이다.
                    $victim = $(if ($killHit) { $killHit.Matches[0].Groups[2].Value } else { '' })
                    $survivor = $(if ($victim -eq 'chaos-api-1') { 'chaos-api-2' } elseif ($victim -eq 'chaos-api-2') { 'chaos-api-1' } else { '' })
                    $split = Get-PartitionMarkets
                    $observed = @($watchMarkets.Split(','))
                    $affected = @($observed | Where-Object { $split -and @($split[$Resource]) -contains $_ })
                    $unaffected = @($observed | Where-Object { $affected -notcontains $_ })
                    if ($webObs) {
                        # 웹 관찰이 끝나야 판정 파일이 생긴다. 실험 스크립트 전에 띄웠으므로 남은 시간만 기다린다.
                        if (-not $webObs.WaitForExit(($WebObserveSec + 120) * 1000)) { Stop-Process -Id $webObs.Id -Force }
                        $row['webObserve']['exitCode'] = $(if ($webObs.HasExited) { $webObs.ExitCode } else { $null })
                        $row['webObserve']['file'] = Test-Path (Join-Path $dir 'web_resync.json')
                        $webObs = $null
                    }
                    $relayArgs = @('harness/scale/s6_relay_axes.py', 'judge', '--experiment', 'W6', '--evidence', (Get-RelPath $dir),
                        '--window-from', $row['relayWatch'].startedAt, '--kill-at', $killAt, '--victim', $victim, '--survivor', $survivor,
                        '--affected-markets', ($affected -join ','), '--unaffected-markets', ($unaffected -join ','),
                        '--out', (Get-RelPath (Join-Path $dir 'relay_axes.json')))
                } else {
                    $relayArgs = @('harness/scale/s6_relay_axes.py', 'judge', '--experiment', 'W5', '--evidence', (Get-RelPath $dir),
                        '--window-from', $row['relayWatch'].startedAt, '--kill-at', $killAt, '--follower', 'chaos-api-2', '--leader', 'chaos-api-1',
                        '--out', (Get-RelPath (Join-Path $dir 'relay_axes.json')))
                }
                if ($verdict -and $verdict.failover -and $verdict.failover.rtoSeconds) { $relayArgs += @('--rto-sec', "$($verdict.failover.rtoSeconds)") }
                if ($row['rejoin'] -and $row['rejoin']['joinedAt']) { $relayArgs += @('--rejoin-at', "$($row['rejoin']['joinedAt'])") }
                $relayRc = Invoke-Logged 'python' $relayArgs (Join-Path $dir 'relay_axes.log')
                $relayAxes = $null
                $relayPath = Join-Path $dir 'relay_axes.json'
                if (Test-Path $relayPath) { $relayAxes = Get-Content $relayPath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable }
                $row['relayAxes'] = [ordered]@{ rc = $relayRc; ok = $(if ($relayAxes) { $relayAxes['ok'] } else { $false })
                    experiment = $(if ($Protocol -eq 3) { 'W6' } else { 'W5' })
                    verdict = $(if ($relayAxes) { $relayAxes['verdict'] } else { $null })
                    failed = $(if ($relayAxes -and $relayAxes['axes']) { @($relayAxes['axes'].Keys | Where-Object { -not $relayAxes['axes'][$_] }) } else { @('판정 파일 없음') })
                    file = 'relay_axes.json' }
            }
        }
        if ($SnapshotEvidence) {
            # 판정은 재생 적용 줄의 구간 안 행 수로 세므로 실험 뒤에 쌓인 새 명령이 이 증거를 흐리지 않는다.
            $row['snapshotEvidence']['post'] = Invoke-Logged 'python' @('harness/scale/snapshot_evidence.py', 'post',
                '--dir', (Get-RelPath $dir), '--api', $snapshotApi) (Join-Path $dir 'snapshot_post.log')
        }
        Save-Text (Join-Path $dir 'failover_runs.json') (Invoke-Psql "SELECT coalesce(json_agg(f ORDER BY f.id), '[]'::json) FROM harness.failover_runs f")
        if ($runId) {
            Save-Text (Join-Path $dir 'invariant_checks.json') (Invoke-Psql ("SELECT coalesce(json_agg(i ORDER BY i.checked_at, i.check_name), '[]'::json) " +
                "FROM invariant_checks i WHERE i.details->>'run_id' = '$runId'"))
        }
        Save-Text (Join-Path $dir 'leader_lease.json') (Invoke-Psql "SELECT coalesce(json_agg(l), '[]'::json) FROM leader_lease l")
        if ($Protocol -ge 2) {
            # 주행 뒤 모드 상태(예약 행 수, 정산 전 예약, 퇴역 행). 위 공통 판정이 봇을 멈추고 드레인한 뒤의 값이다.
            Invoke-Logged 'python' @('harness/scale/protocol_latch.py', 'state', '--out',
                (Get-RelPath (Join-Path $dir 'protocol_state.json'))) (Join-Path $dir 'protocol_state.log') | Out-Null
        }
        $inversionsRaw = Invoke-Psql $inversionSql
        Save-Text (Join-Path $dir 'created_at_inversions.json') $inversionsRaw
        $inversions = $null
        if ($inversionsRaw) { try { $inversions = $inversionsRaw | ConvertFrom-Json } catch { $inversions = $null } }

        $eap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $sqlText = [System.IO.File]::ReadAllText((Join-Path $repo 'harness\sql\invariants.sql'))
            $psqlOut = ($sqlText | & docker exec -i $Global:CHAOS.Pg psql -U $Global:CHAOS.PgUser -d $Global:CHAOS.PgDb -X -q -t -A -F '|' 2>&1 | Out-String)
            $psqlRc = $LASTEXITCODE
        } finally { $ErrorActionPreference = $eap }
        Save-Text (Join-Path $dir 'invariants_psql.txt') $psqlOut
        $c22 = Get-C22Arms $psqlOut $psqlRc

        $events = Get-DockerEvents $eventsPath
        $states = [ordered]@{}
        foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-bots', 'chaos-pg') {
            $raw = (& docker inspect -f '{{json .State}}' $c 2>$null | Out-String).Trim()
            $states[$c] = $(if ($raw) { $raw | ConvertFrom-Json } else { $null })
        }
        Save-Text (Join-Path $dir 'containers.json') ($states | ConvertTo-Json -Depth 6)
        try {
            $proxies = Invoke-RestMethod -Uri "$($Global:CHAOS.ToxiUrl)/proxies" -UserAgent $Global:CHAOS_UA -TimeoutSec 10
            Save-Text (Join-Path $dir 'toxiproxy.json') ($proxies | ConvertTo-Json -Depth 6)
        } catch { Write-RunLog "toxiproxy 조회 실패: $($_.Exception.Message)" }

        New-Item -ItemType Directory -Force -Path $LocalLogDir | Out-Null
        $logs = @()
        $logContainers = @('chaos-api-1', 'chaos-api-2', 'chaos-bots', 'chaos-pg', 'chaos-toxiproxy')
        # S4 관문은 LB 로그의 no live upstreams·502 를 창 안팎으로 기록하므로 그 원문도 남긴다(S4 설계 7-2).
        if ($PartitionAxes -or $WsGateAxes -or $Relay) { $logContainers += 'chaos-lb' }
        if ($Relay) { $logContainers += 'chaos-broker' }
        foreach ($c in @($logContainers | Select-Object -Unique)) {
            $path = Join-Path $LocalLogDir "$Exp-$c.log"
            & cmd /c "docker logs $c > `"$path`" 2>&1"
            if (Test-Path $path) {
                $logs += [ordered]@{ container = $c; path = $path; bytes = (Get-Item $path).Length
                    sha256 = (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() }
            }
        }
        Save-Text (Join-Path $dir 'container_logs.json') (ConvertTo-Json @($logs) -Depth 4)

        # pg_samplers summarize 가 chaos-pg 로그의 연결 거절 문구를 함께 본다. down 전에 불러야 한다.
        $connPath = Join-Path $dir 'connections.json'
        Invoke-Logged 'python' @('harness/scale/pg_samplers.py', 'summarize', '--samples', (Get-RelPath $samplesPath),
            '--out', (Get-RelPath $connPath)) (Join-Path $dir 'connections.log') | Out-Null
        $connState = 'NOT RUN'
        if (Test-Path $connPath) { $connState = (Get-Content $connPath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable).'판정'.'상태' }

        $cutover = $null
        $statePath = Join-Path $dir 'band_state.json'
        # 스냅샷 채집 주행은 전역 모드에서도 래치 상태를 남긴다. 7단계 판정기의 모드 식별 증거다.
        if ($LatchBeforeTraffic -or $SnapshotEvidence) {
            Invoke-Logged 'python' @('harness/scale/band_cutover.py', 'state', '--out', (Get-RelPath $statePath)) (Join-Path $dir 'band_state.log') | Out-Null
        }
        if ($LatchBeforeTraffic) {
            $latch = Get-Content (Join-Path $dir 'latch.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
            $latchRows = @(); $marketRows = @(); $c28 = $null
            if (Test-Path $statePath) {
                $state = Get-Content $statePath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
                if ($state['래치']['래치 행']) { $latchRows = @($state['래치']['래치 행']) }
                if ($state['대역 행']['마켓']) { $marketRows = @($state['대역 행']['마켓']) }
                if ($state['C28']['ok']) { $c28 = [long]$state['C28']['위반 행'] }
            }
            $bandTotal = 0; $legacyTotal = 0; $bandMarkets = 0
            foreach ($m in $marketRows) {
                $bandTotal += [long]$m['band_rows']; $legacyTotal += [long]$m['legacy_rows']
                if ([long]$m['band_rows'] -gt 0) { $bandMarkets++ }
            }
            $authC28 = $null
            if ($verdict) {
                $authC28 = @($verdict.invariants | Where-Object { $_.window -eq 'post' -and "$($_.check)" -like 'C28*' } |
                    ForEach-Object { [ordered]@{ check = $_.check; ok = $_.ok; violatedRows = $_.violatedRows } })
            }
            $cutover = [ordered]@{
                '래치' = [ordered]@{ ok = $latch['ok']; '호출 전 저널 행' = $latch['사전']['journal_rows']; '래치 행' = $latchRows }
                '대역 모드 성립' = ($latchRows.Count -gt 0 -and $bandTotal -gt 0)
                '대역 행 합' = $bandTotal; '대역 행 > 0 마켓' = $bandMarkets; '레거시 행 합' = $legacyTotal
                'C28(하네스 SQL)' = $c28
                '사후 권위 run C28' = [ordered]@{ '포함' = $(if ($null -ne $authC28) { $authC28.Count -gt 0 } else { $null }); '행' = $authC28 }
                '주' = $(if ($c28 -gt 0 -and $inversions -and $inversions.inversions -gt 0) { 'C28 위반과 마켓 안 created_at 역행이 함께 있다. 시계 역행 뒤 거짓 양성 후보(ADR 0022 9-17)' } else { $null })
            }
            $row['cutover'] = $cutover
        }
        $row['clockAfter'] = Test-ClockSkew

        $expVerdict = $null
        if ($verdict) { $expVerdict = [string]$verdict.verdict }
        $row['실험 판정'] = $expVerdict
        $row['연결 판정'] = $connState
        # 연결 고갈은 장애 판정과 섞지 않고 떼어 적는다. 실험 판정 값은 그대로 둔다.
        $row['분리 판정'] = $(if ($connState -eq '증거 불완전(연결 고갈)') { '증거 불완전(연결 고갈)' }
            elseif (-not $expVerdict) { 'NOT RUN' }
            elseif ($expVerdict -eq 'SKIP') { 'NOT RUN(시계 스큐 SKIP)' }
            else { $expVerdict })
        if ($Protocol -ge 2 -and $row['분리 판정'] -eq 'PASS' -and -not $row['s3Common'].ok) { $row['분리 판정'] = 'FAIL(공통 판정)' }
        if ($P3 -and $row['분리 판정'] -eq 'PASS' -and $row['p3Axes'] -and -not $row['p3Axes'].ok) {
            $row['분리 판정'] = $(if ($row['p3Axes'].fail.Count -gt 0) { 'FAIL(P3 공통 판정)' } else { 'NOT RUN(P3 공통 판정)' })
        }
        if ($PartitionAxes -and $row['분리 판정'] -eq 'PASS' -and -not $row['partitionAxes'].ok) { $row['분리 판정'] = 'FAIL(파티션 축)' }
        if ($PartitionAxes -and $Exp -eq 'E4' -and $row['분리 판정'] -eq 'PASS' -and -not $row['rejoin'].ok) { $row['분리 판정'] = 'FAIL(합류)' }
        if ($WsGateAxes -and $row['분리 판정'] -eq 'PASS' -and -not $row['wsGate'].ok) { $row['분리 판정'] = 'FAIL(WS 게이트 축)' }
        if ($Relay -and $Exp -eq 'E4' -and $row['분리 판정'] -eq 'PASS' -and -not $row['relayAxes'].ok) {
            # W6 은 웹 밖 축이 모두 참이고 웹 관찰 파일만 없으면 NOT RUN(웹 관찰)이다(s6_relay_axes.verdict_with_web).
            $row['분리 판정'] = $(if ($row['relayAxes'].verdict -eq 'NOT RUN(웹 관찰)') { 'NOT RUN(웹 관찰)' } else { 'FAIL(중계 축)' })
        }
        # 설계 7-3 X2·X3 의 사전 규칙(rpo_records 0, 사후 권위 run C13A 0)을 분리 판정에 건다. 실험 스크립트의 판정은 C13A 를 게이팅에서
        # 빼고(lib.ps1 CHAOS_GATING_EXEMPT_CHECKS) E7 은 부하를 멈추지 않은 채 사후 run 을 뜬다. 이 축이 없으면 rpo_records 가 1 인
        # 주행이 PASS 로 남는다(S3 구현 검토, 관문 X3 반복 r1·r2). 물질화 전 NEW 를 센 것인지는 판정을 바꾸지 않고 증거에 적는다.
        if ($Protocol -ge 2 -and $row['분리 판정'] -eq 'PASS') {
            $rpoRecords = $(if ($verdict -and $verdict.failover) { $verdict.failover.rpoRecords } else { $null })
            $postC13a = @($verdict.invariants | Where-Object { $_.window -eq 'post' -and "$($_.check)" -eq 'C13A_ORDER_MATERIALIZED' -and -not $_.ok }).Count
            $row['rpoAxis'] = [ordered]@{ rpoRecords = $rpoRecords; postC13A = $postC13a }
            if ($null -eq $rpoRecords) { $row['분리 판정'] = 'NOT RUN(rpo_records 없음)' }
            elseif ([long]$rpoRecords -ne 0 -or $postC13a -gt 0) { $row['분리 판정'] = 'FAIL(rpo_records)' }
        }
        # 대역 모드에서 래치 행이나 대역 행이 없으면 대역 채번을 시험하지 않은 주행이다. C28 은 사후 권위 run 에 없을 수 있어 하네스 SQL 값으로 막는다.
        if ($cutover -and $row['분리 판정'] -eq 'PASS') {
            $row['분리 판정'] = $(if (-not $cutover['대역 모드 성립']) { 'NOT RUN(대역 모드 미성립)' }
                elseif ($null -eq $cutover['C28(하네스 SQL)']) { 'NOT RUN(C28 미확인)' }
                elseif ($cutover['C28(하네스 SQL)'] -gt 0) { 'FAIL(C28 섞임)' }
                else { 'PASS' })
        }

        $expected = $reachActions[$Exp]
        $apiEvents = @($events | Where-Object { $apiNodes -contains $_.container })
        $missing = @($expected | Where-Object { $a = $_; @($apiEvents | Where-Object { $_.action -eq $a }).Count -eq 0 })
        $fo = $null; $metrics = $null; $postNotOk = @()
        if ($verdict) {
            $fo = $verdict.failover
            $metrics = $verdict.metrics
            $postNotOk = @($verdict.invariants | Where-Object { $_.window -eq 'post' -and -not $_.ok } | ForEach-Object { '{0}={1}' -f $_.check, $_.violatedRows })
        }
        $c22Note = $null
        if ($c22.ok -and $c22.timeArm -gt 0 -and $inversions -and $inversions.inversions -gt 0) {
            $c22Note = 'C22 시간 축 팔 위반과 마켓 안 created_at 역행이 함께 있다. 시계 역행 뒤 거짓 양성 후보(ADR 0022 9-17). 판정은 바꾸지 않는다'
        }
        $selfRecovery = switch ($Exp) {
            'E2' { [ordered]@{ promotionStable = $metrics.promotionStable; standdownDelta = $metrics.standdownDelta } }
            'E4' { [ordered]@{ firstTradeTs = $fo.firstTradeTs; '주' = 'docker kill 은 재기동 정책을 건너뛰어 구 리더가 돌아오지 않는다. 자력 복구는 스탠바이 체결 재개로 본다' } }
            'E5' { [ordered]@{ firstTradeTs = $fo.firstTradeTs; rejoin = $fo.details.rejoin } }
            'E6' { [ordered]@{ firstTradeTs = $fo.firstTradeTs; spikeClean = $metrics.spikeClean } }
            'E7' { [ordered]@{ firstTradeTs = $fo.firstTradeTs; zombieShutdown = $metrics.zombieShutdown } }
            'COLD' { [ordered]@{ acceptResumedAt = $metrics.acceptResumedAt; firstTradeTs = $fo.firstTradeTs; nodes = $metrics.nodes
                stopOrder = $metrics.stopOrder; startOrder = $metrics.startOrder; epochAfterRejoin = $metrics.epochAfterRejoin
                '주' = '재기동 소요는 기록만 한다(검증설계 §2-4 7단계 규약)' } }
        }
        $row['criteria'] = [ordered]@{
            '1 목표 계층 도달' = [ordered]@{ '기대 사건' = $expected; '빠진 사건' = $missing; '사건' = $apiEvents
                '확인' = $(if ($expected.Count -eq 0) { '간접' } else { $missing.Count -eq 0 })
                '주' = $(if ($Exp -eq 'E2') { 'Toxiproxy 프록시 계층 주입이다. 커널 주입이 아니고 도커 사건이 없어 리스 상실 뒤 승격(epoch 증가)으로 간접 확인한다' } else { $null }) }
            '2 계약된 반응' = [ordered]@{ oldEpoch = $fo.oldEpoch; newEpoch = $fo.newEpoch; oldLeader = $fo.oldLeader
                newLeader = $fo.newLeader; metrics = $metrics }
            '3 복구 시간' = [ordered]@{ rtoSeconds = $fo.rtoSeconds; downtimeSeconds = $fo.downtimeSeconds
                '목표(초)' = $(if ($Exp -eq 'COLD') { $null } else { 30 }) }
            '4 무결성' = [ordered]@{ 'post 창 위반 체크' = $postNotOk; rpoRecords = $fo.rpoRecords; c22 = $c22
                createdAtInversions = $inversions; '주' = $c22Note }
            '5 자력 복구' = $selfRecovery
        }
        # 콜드 재기동 칸의 CRITICAL 위반은 게이팅 면제를 뺀 값이라 post 창 위반 체크와 따로 싣는다. 7단계 판정기가 읽는다.
        if ($Exp -eq 'COLD') { $row['criteria']['4 무결성']['사후 권위 run CRITICAL'] = $metrics.postCritical }
        return $row
    }
    catch {
        $row['분리 판정'] = 'BLOCKED'
        $row['사유'] = $_.Exception.Message
        return $row
    }
    finally {
        if ($sampler -and -not $sampler.HasExited) {
            New-Item -ItemType File -Force -Path $stop | Out-Null
            if (-not $sampler.WaitForExit(30000)) { Stop-Process -Id $sampler.Id -Force }
        }
        if ($balanceSampler -and -not $balanceSampler.HasExited) {
            New-Item -ItemType File -Force -Path $stopBalance | Out-Null
            if (-not $balanceSampler.WaitForExit(30000)) { Stop-Process -Id $balanceSampler.Id -Force }
        }
        if ($eventsProc -and -not $eventsProc.HasExited) { Stop-Process -Id $eventsProc.Id -Force }
        if ($freshness -and -not $freshness.HasExited) {
            New-Item -ItemType File -Force -Path $stopFresh | Out-Null
            if (-not $freshness.WaitForExit(30000)) { Stop-Process -Id $freshness.Id -Force }
        }
        if ($epochSampler -and -not $epochSampler.HasExited) {
            New-Item -ItemType File -Force -Path $stopEpoch | Out-Null
            if (-not $epochSampler.WaitForExit(30000)) { Stop-Process -Id $epochSampler.Id -Force }
        }
        if ($webObs -and -not $webObs.HasExited) { Stop-Process -Id $webObs.Id -Force }
        # 관찰자와 k6 부하가 실험 네트워크에 붙어 있으면 down 이 네트워크를 지우지 못한다. 판정 전에 예외로 나왔어도 여기서 멈춘다.
        if ($watch) { Stop-RelayWatch -Name $watch.name -LogPath (Join-Path $dir 'watch.log') }
        if ($k6Load) { Stop-SurvivorLoad -Name $k6Load.name -LogPath (Join-Path $LocalLogDir "$Exp-k6-survivor.log") }
        $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
        Write-RunLog "END $Exp down rc=$code"
    }
}

if ($PSVersionTable.PSVersion.Major -lt 7) { Write-Host 'pwsh 7 로 실행한다(CLAUDE.md 30절).'; exit 5 }
if (-not $Image) { Write-Host '-Image 가 필요하다. 5단계는 4단계 B4 태그, 대역 모드는 adr0022-stage6-b4b6.ps1 가 구운 6단계 태그, S3 관문(-Protocol 2)은 exchange-chaos-app:pml 이다.'; exit 5 }
# 보존 스택 가드(하네스 검증설계 §2-4). 실험마다 down -v 를 하므로 보존 스택에서는 아무것도 지우거나 세우지 않는다. lib.ps1 은
# CHAOS_PROJECT 가 없으면 exchange-chaos 를 쓰므로 미설정도 여기서 걸린다. 이미지 태그는 S3 경로에서만 묶는다(S3 설계 7-0).
if ($Global:CHAOS.Project -eq 'exchange-chaos') { Write-Host 'CHAOS_PROJECT 가 보존 스택 exchange-chaos 다(미설정 포함). 보존 볼륨을 지울 수 있어 시작하지 않는다. 폐기용 이름(예: exchange-pml)을 준다.'; exit 5 }
if ($Protocol -ge 2 -and ($Global:CHAOS.Project -ne 'exchange-pml' -or $Image -ne 'exchange-chaos-app:pml')) {
    Write-Host "S3~S7 경로(-Protocol 2·3)는 CHAOS_PROJECT=exchange-pml, -Image exchange-chaos-app:pml 에서만 돈다(지금 $($Global:CHAOS.Project), $Image)."; exit 5
}
if ($PartitionAxes -and $Protocol -lt 2) { Write-Host '-PartitionAxes 는 -Protocol 2 이상과 함께만 돈다(S4 설계 7-1).'; exit 5 }
if (($Relay -or $WsGateAxes) -and $Protocol -lt 2) { Write-Host '-Relay·-WsGateAxes 는 -Protocol 2 이상과 함께만 돈다(S6 설계 7-2).'; exit 5 }
# P3 모드와 P3 오버레이는 함께만 쓴다(S7 설계 4-13). 오버레이 없이 P3 에 올리면 선호 설정이 없어 배정을 볼 수 없고 LB 가 파티션
# 쓰기를 가르지 못한다. 오버레이만 겹치고 P2 로 돌리면 선호 설정은 뜻이 없고 판정이 P2 모양이다.
if ($Protocol -eq 3 -and -not $P3) { Write-Host '-Protocol 3 은 -P3(중계·P3 오버레이)와 함께만 돈다(S7 설계 4-13).'; exit 5 }
if ($P3 -and $Protocol -ne 3) { Write-Host '-P3 는 -Protocol 3 과 함께만 돈다(S7 설계 4-13).'; exit 5 }
if (-not $P3 -and (Test-P3OverlayListed)) { Write-Host 'CHAOS_COMPOSE_OVERLAYS 에 P3 오버레이가 있다. -Protocol 3 -P3 를 주거나 목록을 비운다.'; exit 5 }
if (-not $Relay -and (Test-RelayOverlayListed)) { Write-Host 'CHAOS_COMPOSE_OVERLAYS 에 중계 오버레이가 있다. -Relay 를 주거나 목록을 비운다(W0 는 기본 스택이다).'; exit 5 }
$wanted = @($Experiments.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$unknown = @($wanted | Where-Object { -not $scripts.Contains($_) })
if ($unknown.Count -gt 0) { Write-Host ('이 절차에 없는 실험: {0}' -f ($unknown -join ', ')); exit 5 }
# 볼 리스 자원(S4 설계 4-18, S7 설계 4-13). P3 에서는 한 사건이 파티션 하나만 옮기므로 자원 없이 시작하지 않는다.
if ($Protocol -eq 3) {
    if (@('matching-p0', 'matching-p1') -notcontains $Resource) {
        $needs = @($wanted | Where-Object { @('E2', 'E4', 'E5', 'E6', 'E7', 'COLD') -contains $_ })
        if ($needs.Count -gt 0 -or $Resource) { Write-Host ("-Protocol 3 의 {0} 은 -Resource matching-p0 또는 matching-p1 이 필요하다(지금 '{1}')." -f ($needs -join ','), $Resource); exit 5 }
    }
} elseif ($Resource -and $Resource -ne 'matching-engine') {
    Write-Host "P$Protocol 에는 파티션 자원이 없다(-Resource $Resource). P1·P2 의 자원은 matching-engine 하나다."; exit 5
}
if (($WebObserveUrl -or $SurvivorLoadOps -gt 0 -or $ConsolidateOn) -and $Protocol -ne 3) {
    Write-Host '-WebObserveUrl·-SurvivorLoadOps·-ConsolidateOn 은 -Protocol 3 에서만 쓴다(S7 설계 4-13, 5-2).'; exit 5
}
if ($ConsolidateOn -and @('chaos-api-1', 'chaos-api-2') -notcontains $ConsolidateOn) { Write-Host "-ConsolidateOn 은 앱 노드 이름이다: $ConsolidateOn"; exit 5 }
if (Test-Path (Join-Path $EvidenceDir 'summary.json')) { Write-Host '이미 summary.json 이 있다. 증거를 덮지 않는다.'; exit 5 }
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
Write-RunLog "RUN adr0022-stage5-chaos Image=$Image Experiments=$($wanted -join ',') LatchBeforeTraffic=$LatchBeforeTraffic SnapshotEvidence=$SnapshotEvidence Protocol=$Protocol PartitionAxes=$PartitionAxes Relay=$Relay WsGateAxes=$WsGateAxes P3=$P3 Resource=$Resource SurvivorLoadOps=$SurvivorLoadOps WebObserve=$([bool]$WebObserveUrl) ConsolidateOn=$ConsolidateOn Project=$($Global:CHAOS.Project)"
if (-not (Test-Path $Global:CHAOS.EnvFile)) { Write-RunLog 'BLOCKED harness/.env.chaos 가 없다'; exit 5 }
& docker info *> $null
if ($LASTEXITCODE -ne 0) { Write-RunLog 'BLOCKED 도커 데몬 미응답'; exit 5 }
$imageId = (& docker image inspect $Image --format '{{.Id}}' 2>$null | Out-String).Trim()
if (-not $imageId) { Write-RunLog "BLOCKED 이미지가 없다: $Image"; exit 5 }
if ($P3) {
    try { Set-P3Overlay } catch { Write-RunLog "BLOCKED $($_.Exception.Message)"; exit 5 }
    Write-RunLog "OVERLAYS $env:CHAOS_COMPOSE_OVERLAYS"
} elseif ($Relay) {
    try { Set-RelayOverlay } catch { Write-RunLog "BLOCKED $($_.Exception.Message)"; exit 5 }
    Write-RunLog "OVERLAYS $env:CHAOS_COMPOSE_OVERLAYS"
}
# 실험마다 down -v 를 한다. 남의 카오스 스택을 파기하지 않도록 시작 전에 비어 있어야 한다.
$existing = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
if ($existing.Count -gt 0) { Write-RunLog ('BLOCKED chaos- 컨테이너가 이미 있다({0})' -f ($existing -join ', ')); exit 5 }
$env:CHAOS_APP_IMAGE = $Image

$labels = (& docker image inspect $Image --format '{{json .Config.Labels}}' 2>$null | Out-String).Trim()
$summary = [ordered]@{
    image = $Image; imageId = $imageId
    imageRevision = $(if ($labels -and $labels -ne 'null') { ($labels | ConvertFrom-Json).'org.opencontainers.image.revision' } else { $null })
    harnessCommit = (& git -C $repo rev-parse HEAD | Out-String).Trim()
    latchBeforeTraffic = [bool]$LatchBeforeTraffic
    protocol = $Protocol
    partitionAxes = [bool]$PartitionAxes
    relay = [bool]$Relay
    wsGateAxes = [bool]$WsGateAxes
    p3 = [bool]$P3
    resource = $Resource
    webObserveUrl = $WebObserveUrl
    survivorLoadOps = $SurvivorLoadOps
    consolidateOn = $ConsolidateOn
    composeOverlays = $env:CHAOS_COMPOSE_OVERLAYS
    project = $Global:CHAOS.Project
    springProfiles = $env:CHAOS_SPRING_PROFILES
    snapshotEvidence = [bool]$SnapshotEvidence
    localLogDir = $LocalLogDir
    experiments = @()
}
foreach ($exp in $wanted) {
    $summary.experiments += (Invoke-Experiment $exp)
    Save-Text (Join-Path $EvidenceDir 'summary.json') ($summary | ConvertTo-Json -Depth 12)
}
$clean = @($summary.experiments | Where-Object { $_['분리 판정'] -ne 'PASS' -or $_['연결 판정'] -ne '고갈 없음' }).Count -eq 0
Write-RunLog ('DONE {0}' -f (($summary.experiments | ForEach-Object { '{0}={1}' -f $_.experiment, $_['분리 판정'] }) -join ' '))
if ($clean) { exit 0 }
exit 1
