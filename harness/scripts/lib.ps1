# 카오스 실험 공용 유틸. 각 실험 스크립트가 dot-source 한다.
# 포트·컨테이너명은 docker-compose.chaos.yml과 1:1. 판정은 전부 SQL 0행/RTO 임계로 기계화한다.

$ErrorActionPreference = 'Stop'

$Global:CHAOS = @{
    ComposeFile = Join-Path $PSScriptRoot '..\docker-compose.chaos.yml'
    EnvFile     = Join-Path $PSScriptRoot '..\.env.chaos'
    Pg          = 'chaos-pg'
    Api1Port    = 18080
    Api2Port    = 28080
    LbPort      = 58080
    # 기본 58474. 호스트가 이 포트를 WinNAT 동적 예외로 예약해 게시 불가일 때(Windows) CHAOS_TOXI_URL로
    # 재지정(예: 포트 리맵 오버라이드). env 미설정 시 기존 동작 불변.
    ToxiUrl     = $(if ($env:CHAOS_TOXI_URL) { $env:CHAOS_TOXI_URL } else { 'http://localhost:58474' })
    PgUser      = 'exchange'
    PgDb        = 'exchange'
}

# param 블록을 두지 않는 이유: [Parameter()]가 붙는 순간 고급 함수가 되고, 그러면 PowerShell이
# 인자를 먼저 파라미터로 바인딩한다. 단문자 compose 플래그는 공용 파라미터에 접두 일치해
# 남은 인자 목록에 들어오지 못한다(-v는 -Verbose, -d는 -Debug로 먹힌다). 실측: 'down -v'가
# 인자 3개만 남겨 볼륨이 지워지지 않았고, down.ps1이 전체 파기를 약속한 채 원장을 존치시켜
# 이후 실험이 이전 상태를 물려받았다. 자동 변수 $args를 쓰는 단순 함수는 바인딩을 하지 않는다.
function Invoke-Compose {
    & docker compose -f $Global:CHAOS.ComposeFile --env-file $Global:CHAOS.EnvFile @args
}

# 네이티브 호출 공용 래퍼. cmd /c로 감싸 스트림을 nul에 버린다. PowerShell에서 compose를 직접
# 호출하면 재기동한 컨테이너가 상속한 출력 핸들 때문에 파이프가 EOF에 도달하지 않아 -d(detach)
# 인데도 반환하지 않고 매달린다(실측). Get-LeaderContainer의 docker logs와 동일한 공용 우회다.
# 반환은 종료코드다. 호출부는 반드시 변수에 받는다. 미포획이면 상위 함수의 반환값에 섞여 배열이 된다.
function Invoke-ComposeQuiet {
    param([string] $ComposeArgs)
    $cmd = "docker compose -f `"$($Global:CHAOS.ComposeFile)`" --env-file `"$($Global:CHAOS.EnvFile)`" $ComposeArgs"
    & cmd /c "$cmd >nul 2>nul"
    return $LASTEXITCODE
}

# compose가 아니라 docker CLI 직접 호출. 의존 그래프를 타지 않는 것이 목적이다(Stop/Restore-BotLoad 참조).
function Invoke-DockerQuiet {
    param([string] $DockerArgs)
    & cmd /c "docker $DockerArgs >nul 2>nul"
    return $LASTEXITCODE
}

# 실험별 봇 부하 제어. 봇 플릿 target-ops를 지정값으로 재기동한다. 런타임 rate API(PUT /admin/v1/sim/rate)는
# governor가 봇 노드(bots 프로파일) 전용이라 서빙 노드 호출이 no-op으로 무효다(SimAdminController 주석).
# 그래서 CHAOS_BOTS_OPS를 바꿔 chaos-bots만 force-recreate하고 새 부하가 실릴 시간을 짧게 준다. E6
# 스파이크(SPIKE_OPS)도 같은 기제다. 실험은 자기 BASE_OPS로 이 함수를 호출해 부하를 정한다.
function Set-BotOps {
    param([int] $Ops, [int] $SettleSec = 5)
    # 환경변수는 자식에 상속된다. force-recreate라야 새 값이 컨테이너 env에 박힌다.
    $env:CHAOS_BOTS_OPS = "$Ops"
    # --no-deps라 의존 게이트를 건너뛴다. Restore-BotLoad가 compose를 못 쓰는 것과 대비되는 지점이다.
    $code = Invoke-ComposeQuiet 'up -d --no-deps --force-recreate chaos-bots'
    if ($code -ne 0) { throw "봇 OPS 적용 실패(chaos-bots 재기동 종료코드 $code)" }
    Write-Host "봇 OPS=$Ops 적용(chaos-bots 재기동, settle ${SettleSec}s)"
    if ($SettleSec -gt 0) { Start-Sleep -Seconds $SettleSec }
}

# 드레인 전제 만들기. 봇 쓰기를 끊는다. Set-BotOps 1로 낮추는 것으로는 부족하다. 1 ops도 쓰기라
# 새 NEW 명령이 계속 들어와 C13a가 in-flight를 유실로 세고, 재기동한 봇이 그 부하로 남아 다음
# 실험이 무부하를 이월받는다. 반환은 정지 성공 여부(호출부가 판정 유효성 판단에 쓴다).
function Stop-BotLoad {
    $code = Invoke-DockerQuiet 'stop chaos-bots'
    if ($code -ne 0) {
        Write-Host "경고: chaos-bots 정지 실패(종료코드 $code). 부하가 계속되는 상태로 드레인한다" -ForegroundColor Yellow
        return $false
    }
    Write-Host '봇 정지(docker stop chaos-bots). 드레인 창 동안 신규 쓰기 없음'
    return $true
}

# 정지 전 ops로 복원. compose가 아니라 docker start인 이유: chaos-bots는 chaos-api-1의
# service_healthy에 종속되는데 그 헬스체크가 readiness이고 팔로워 readiness는 설계상 503이다.
# 그래서 페일오버 직후 compose start는 의존 게이트에서 무한 대기하거나 dependency unhealthy로
# exit 1을 낸다(실측). 그러면 봇이 정지된 채 남아 다음 실험이 0 ops를 이월받는다. docker start는
# 의존 그래프를 타지 않고 컨테이너 env를 보존하므로 정지 전 ops가 그대로 돌아온다.
# 복원 실패는 경고만 남긴다. finally에서 throw하면 앞선 return이 취소되고 판정이 예외로 뒤집힌다.
function Restore-BotLoad {
    param([bool] $WasStopped, [int] $Ops = 0)
    if (-not $WasStopped) { return }
    # Ops가 주어지면 그 값으로 되돌린다. docker start는 정지 시점 env를 복원하는데, 스파이크
    # 실험은 정지 시점 ops가 SPIKE_OPS(400)라 그대로 되살리면 폭주 부하가 다음 실험으로 이월된다.
    # force-recreate 경로는 --no-deps라 의존이 unhealthy여도 통과한다(실측).
    try {
        if ($Ops -gt 0) {
            Set-BotOps $Ops -SettleSec 0
            return
        }
        $code = Invoke-DockerQuiet 'start chaos-bots'
        if ($code -eq 0) { Write-Host '봇 재개(docker start chaos-bots, 정지 전 ops 그대로)' }
        else { Write-Host "경고: chaos-bots 재개 실패(종료코드 $code). 다음 실험 전 수동 확인" -ForegroundColor Yellow }
    } catch {
        Write-Host "경고: chaos-bots 재개 실패($($_.Exception.Message)). 다음 실험 전 수동 확인" -ForegroundColor Yellow
    }
}

# 드레인 대상 술어(C13A_ORDER_MATERIALIZED와 동일 anti-join). 물질화되지 않은 NEW 명령 수.
$Global:CHAOS_DRAIN_SQL = "SELECT count(*) FROM command_journal c WHERE c.command_type='NEW' " +
    "AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = c.assigned_order_id)"

# 드레인 0 확인. RequiredStreak 회 연속으로 0이어야 완료로 본다. 쓰기를 끊지 못한 경로에서는
# 단발 0이 드레인이 아니라 다음 명령이 도착하기 직전의 우연일 수 있어 연속 표본을 요구한다.
# psql 무응답 표본은 0으로 세지 않는다. 무응답을 0으로 읽으면 관측 실패가 드레인 완료로
# 둔갑하고, 그 위에서 도는 사후 권위 run이 "정지 상태 판정"이라는 전제를 잃는다. 여기서
# throw하지 않는 이유는 단발 무응답으로 실험 전체를 죽이지 않기 위해서다. 연속 표본을 끊고
# 다시 재며, 창 내내 무응답이면 자연히 타임아웃해 드레인 미완으로 떨어진다(fail-closed).
function Wait-DrainZero {
    param([string] $Sql, [int] $TimeoutSec, [int] $RequiredStreak = 1, [double] $PollSec = 2)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $streak = 0
    $unreachable = 0
    while ((Get-Date) -lt $deadline) {
        $v = Get-Scalar $Sql
        if ($null -eq $v) {
            $unreachable++
            $streak = 0
        } elseif ($v -eq 0) {
            $streak++
            if ($streak -ge $RequiredStreak) {
                # 성립한 경우에도 표본 품질을 남긴다. 무응답이 섞인 드레인은 관측 공백 위에서
                # 성립한 것이라 사후 run의 "정지 상태 판정" 전제가 그만큼 약하다.
                if ($unreachable -gt 0) {
                    Write-Host "경고: 드레인 폴링 중 psql 무응답 표본 $unreachable 건(드레인 자체는 성립). 표본 품질 기록" -ForegroundColor Yellow
                }
                return $true
            }
        } else {
            $streak = 0
        }
        Start-Sleep -Milliseconds ([int]($PollSec * 1000))
    }
    if ($unreachable -gt 0) {
        Write-Host "경고: 드레인 폴링 중 psql 무응답 표본 $unreachable 건. 드레인 미완으로 처리한다" -ForegroundColor Yellow
    }
    return $false
}

# 리스 TTL(초). §5-1 T1 = 구 lease_until − 리스 TTL(마지막 heartbeat 역산). 카오스 스택은
# CHAOS_LEASE_TTL로 가속(6s), 값 부재 시 DB§5.4 기본 10s. downtime=T2-T1 산정에 쓴다.
function Get-LeaseTtlSec {
    try {
        if (Test-Path $Global:CHAOS.EnvFile) {
            $raw = (Read-EnvFile $Global:CHAOS.EnvFile)['CHAOS_LEASE_TTL']
            if ($raw -and ($raw -match '([0-9]+(\.[0-9]+)?)')) { return [double] $Matches[1] }
        }
    } catch { }
    return 10.0
}

function Read-EnvFile {
    param([string] $Path)
    $map = @{}
    # -Encoding UTF8 명시: config는 BOM 없는 UTF-8이다. 미지정 시 Windows PowerShell 5.1이 ANSI(cp949)로
    # 읽어 한글 인라인 주석의 더블바이트가 줄 경계를 삼켜 특정 키가 통째로 누락된다(pwsh는 무관).
    Get-Content $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
            $i = $line.IndexOf('=')
            $key = $line.Substring(0, $i).Trim()
            $val = $line.Substring($i + 1)
            # 인라인 주석 제거(값에 # 미포함 전제, config/*.env 스타일).
            $h = $val.IndexOf('#')
            if ($h -ge 0) { $val = $val.Substring(0, $h) }
            $map[$key] = $val.Trim()
        }
    }
    return $map
}

# DB 직조회(권위 판정). -t -A로 헤더·정렬 제거한 raw 값만 반환한다.
function Invoke-Psql {
    param([string] $Sql)
    $out = & docker exec $Global:CHAOS.Pg psql -U $Global:CHAOS.PgUser -d $Global:CHAOS.PgDb -t -A -c $Sql 2>$null
    return ($out | Out-String).Trim()
}

# 스칼라 조회. 빈 결과를 0으로 폴백하지 않는다. Invoke-Psql은 stderr를 버리므로 psql 무응답·
# 연결 실패·구문 오류가 전부 빈 문자열로 돌아오는데, 그것을 0으로 읽으면 "드레인 0건",
# "스테일 쓰기 0건" 같은 통과 신호가 관측 실패로부터 만들어진다. 무응답과 진짜 0을 가른다.
#   -Required : 빈 결과를 throw(집계·count 질의처럼 행이 반드시 나오는 축). 기본 선택지다.
#   기본      : 빈 결과를 $null로 반환. 행 부재가 정상 상태인 질의(leader_lease 행 부재)에서만
#               쓰고, 호출부가 $null을 명시로 판정해야 한다. [long]$null은 0이 되므로 캐스팅
#               전에 반드시 null 검사를 둔다.
# Get-AppMetric의 -Required와 같은 어휘다(도달 실패는 throw, 부재는 $null).
function Get-Scalar {
    param([string] $Sql, [switch] $Required)
    $v = Invoke-Psql $Sql
    if ([string]::IsNullOrWhiteSpace($v)) {
        if ($Required) { throw "psql 스칼라 조회가 빈 결과를 냈다(무응답 또는 질의 실패). SQL: $Sql" }
        return $null
    }
    return [decimal] $v
}

function Get-LiveNodePort {
    foreach ($p in @($Global:CHAOS.Api1Port, $Global:CHAOS.Api2Port)) {
        try {
            Invoke-RestMethod -Uri "http://localhost:$p/harness/v1/status" -TimeoutSec 3 | Out-Null
            return $p
        } catch { }
    }
    throw '살아있는 api 노드 없음(양 노드 응답 실패)'
}

function Get-HarnessStatus {
    param([int] $Port)
    return Invoke-RestMethod -Uri "http://localhost:$Port/harness/v1/status" -TimeoutSec 5
}

function Invoke-InvariantRun {
    param([int] $Port, [string] $RunId, [string] $Window)
    $uri = "http://localhost:$Port/harness/v1/invariants/run?runId=$RunId&window=$Window"
    return Invoke-RestMethod -Uri $uri -Method Post -TimeoutSec 60
}

function Reset-LoadtestAcks {
    param([int] $Port)
    Invoke-RestMethod -Uri "http://localhost:$Port/harness/v1/loadtest-acks/reset" -Method Post -TimeoutSec 10 | Out-Null
}

function Record-Failover {
    param([int] $Port, [hashtable] $Body)
    $json = $Body | ConvertTo-Json -Depth 6
    return Invoke-RestMethod -Uri "http://localhost:$Port/harness/v1/failover" -Method Post `
        -ContentType 'application/json' -Body $json -TimeoutSec 10
}

# Toxiproxy 2.12.0은 DNS 리바인딩 방어로 User-Agent에 'Mozilla'가 있으면 거부(User agent not allowed).
# PowerShell Invoke-RestMethod 기본 UA가 Mozilla라 전부 비브라우저 UA로 지정한다(5.1/7 공통).
$Global:CHAOS_UA = 'chaos-harness'

function Add-Toxic {
    param([string] $Proxy, [string] $Name, [string] $Type, [hashtable] $Attributes)
    $body = @{ name = $Name; type = $Type; stream = 'downstream'; attributes = $Attributes } | ConvertTo-Json
    Invoke-RestMethod -Uri "$($Global:CHAOS.ToxiUrl)/proxies/$Proxy/toxics" -Method Post `
        -ContentType 'application/json' -Body $body -UserAgent $Global:CHAOS_UA -TimeoutSec 10 | Out-Null
}

function Remove-AllToxics {
    param([string] $Proxy)
    try {
        $toxics = Invoke-RestMethod -Uri "$($Global:CHAOS.ToxiUrl)/proxies/$Proxy/toxics" -UserAgent $Global:CHAOS_UA -TimeoutSec 10
        foreach ($t in $toxics) {
            Invoke-RestMethod -Uri "$($Global:CHAOS.ToxiUrl)/proxies/$Proxy/toxics/$($t.name)" -Method Delete -UserAgent $Global:CHAOS_UA | Out-Null
        }
    } catch { }
}

# T5(체결 재개) 술어. 정본 §5-1 T5의 검산식 그대로 command_journal.fencing_token 축을 쓴다.
# event_journal.fencing_epoch를 쓰면 안 된다. 두 컬럼의 의미가 다르다.
#   command_journal.fencing_token : 그 명령을 "수용한" 리더의 epoch
#   event_journal.fencing_epoch   : 그 이벤트를 "저널한" 리더의 epoch
# 승격 재생은 구 리더가 수용한 미처리 명령(token=구 epoch)을 신 리더가 다시 적용해 이벤트를
# 신 epoch로 append하면서 created_at은 원래 엔진 처리 시각(주입 직후)을 보존한다. 그래서
# fencing_epoch 축으로 재면 그 재생 산출물이 "재개된 첫 체결"로 잡히고 RTO가 실제보다 훨씬
# 작게 나온다. 실측(E6 400 ops): 승격은 13:48:08.571인데 fencing_epoch=2 최초 TRADE는
# 13:48:03.196으로 승격보다 5.4초 앞섰고 RTO가 0.086s로 보고됐다(실제 약 5.5s). 부하가 클수록
# 재생 백로그가 커져 오차가 커지므로, RTO<30s 게이트가 부하 상황에서 무력화된다.
# token 축은 구 리더가 수용한 명령을 구조적으로 배제하므로 재생 산출물이 섞이지 않는다.
# 정본 주석대로 fencing_token은 command_journal에만 있어 반드시 조인 경유다.
# 등가(=)가 아니라 초과(>)로 두는 이유: 승격 재생 반납으로 epoch가 +2 이상 오를 수 있고(DB §5.4)
# 그때 등가 비교는 재개를 영영 놓쳐 RTO가 n/a로 남는다.
# 조인 전환으로 생긴 결합: 이 측정은 C12(고아 이벤트 0행)에 의존한다. source_cmd_seq는 NOT NULL이라
# null 탈락은 없지만, 대응 command 행이 없는 TRADE가 생기면 inner join이 그것을 버려 RTO가 n/a가
# 되고 판정이 FAIL로 떨어진다. fail-closed 방향이라 수용하되, 스냅샷 채택 후 재생 경로가 바뀌어
# 고아 이벤트가 생길 여지가 나오면 이 측정축이 먼저 무너진다는 것을 알고 있어야 한다.
function Get-FirstTradeSql {
    param([long] $OldEpoch, [string] $T0Iso)
    return "SELECT min(e.created_at) AT TIME ZONE 'UTC' FROM event_journal e " +
        "JOIN command_journal c ON c.seq = e.source_cmd_seq " +
        "WHERE e.event_type='TRADE' AND c.fencing_token > $OldEpoch AND e.created_at > '$T0Iso'"
}

function Write-Section {
    param([string] $Text)
    Write-Host ''
    Write-Host "== $Text ==" -ForegroundColor Cyan
}

function Now-Iso { return (Get-Date).ToUniversalTime().ToString('o') }

# 조건 충족까지 폴링. 반환: 충족 시각(UTC) 또는 $null(타임아웃).
# 조건식 예외는 미충족으로 흡수한다. 폴링 술어에는 psql·HTTP 조회가 들어가는데(Get-TradeCount는
# -Required라 무응답에 throw한다) 단발 실패로 실험 전체를 죽이면 Wait-DrainZero가 무응답을
# 관용하는 것과 규약이 어긋난다. 다만 조용히 삼키지는 않는다. 타임아웃으로 끝났고 예외가 있었으면
# 마지막 예외를 함께 낸다. 그러지 않으면 호출부의 "봇 부하가 실리지 않았다" 메시지가 실제 원인
# (관측 경로 단절)을 가린다.
function Wait-For {
    param([scriptblock] $Condition, [int] $TimeoutSec, [double] $PollSec = 0.5)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $errCount = 0; $lastErr = $null
    while ((Get-Date) -lt $deadline) {
        $met = $false
        try { $met = [bool](& $Condition) } catch { $errCount++; $lastErr = $_.Exception.Message }
        # UTC로 반환한다. DB engine_ts(UTC)와 뺄셈할 때 로컬 시각이면 오프셋만큼 오차가 난다.
        if ($met) { return (Get-Date).ToUniversalTime() }
        Start-Sleep -Milliseconds ([int]($PollSec * 1000))
    }
    if ($errCount -gt 0) {
        Write-Host "경고: Wait-For 폴링 중 조건식 예외 $errCount 회(마지막: $lastErr). 타임아웃 원인이 조건 미충족이 아니라 관측 경로 단절일 수 있다" -ForegroundColor Yellow
    }
    return $null
}

# 주입(T0) 이후 승격·체결 재개 관찰 → 사후 권위 run → RTO/RPO 판정 → failover_runs 기록.
# E2·E6이 공용으로 쓴다(E4는 상세 참조본이라 인라인 유지). 개입 금지 구간이므로 관찰만 한다.
function Complete-FailoverExperiment {
    param(
        [string] $ExperimentId, [string] $RunId,
        [long] $OldEpoch, [string] $OldHolder, [datetime] $LeaseUntilBefore,
        [long] $TradesAtInject, [datetime] $T0,
        [int] $ObserveSec, [int] $RtoTarget,
        [int] $StandbyPort = 0, [int] $RestoreOps = 0
    )
    # 스탠바이 포트를 api-2로 하드코딩하면 리더가 api-2일 때(앞선 실험이 그렇게 남긴 경우)
    # 방금 죽인 노드를 폴링하게 되어 승격을 영영 못 보고 RTO가 n/a로 남는다. 호출부가 실제
    # 스탠바이를 알고 있으면 그것을 쓰고, 없으면 종전 기본값으로 떨어진다.
    $standbyPort = if ($StandbyPort -gt 0) { $StandbyPort } else { $Global:CHAOS.Api2Port }

    Write-Section 'ⓒ 관찰(개입 금지)'
    $t2 = Wait-For {
        try { $s = Get-HarnessStatus -Port $standbyPort; ($s.epoch -gt $OldEpoch) -and $s.leaderAlive }
        catch { $false }
    } -TimeoutSec $ObserveSec -PollSec 0.5
    $statusAfter = Get-HarnessStatus -Port (Get-LiveNodePort)
    $newEpoch = [long]$statusAfter.epoch
    $newHolder = $statusAfter.holder

    # T5 = 페일오버 후 최초 신규 TRADE의 engine_ts(§5-1 정의). created_at은 DB now()가 아니라
    # 엔진 처리 시각이라 커밋 가시화 지연과 무관한 재개 시점을 가리킨다.
    $t0Iso = $T0.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fff') + 'Z'
    $firstTradeSql = Get-FirstTradeSql -OldEpoch $OldEpoch -T0Iso $t0Iso
    $obsResume = Wait-For {
        -not [string]::IsNullOrWhiteSpace((Invoke-Psql $firstTradeSql))
    } -TimeoutSec $ObserveSec -PollSec 0.5
    $firstTradeRaw = Invoke-Psql $firstTradeSql
    $t5 = $null; $firstTradeTs = $null
    if ($firstTradeRaw) {
        $firstTradeTs = $firstTradeRaw.Replace(' ', 'T') + 'Z'
        $t5 = ([datetimeoffset]$firstTradeTs).UtcDateTime
    }

    Write-Section 'ⓓ 드레인 + 사후 권위 run'
    # C13a(NEW 명령 -> orders 물질화)는 append와 정산 커밋 사이 비동기 창 때문에 부하 중에는
    # 처리 중인 주문을 그대로 유실로 센다. sql/invariants.sql 주석이 "큐 드레인된 권위 run/정지
    # 상태에서만 유효"라고 규정한 이유다. 실측(E6 400 ops)에서 10~11건이 잡혔고 부하를 낮추자
    # 0이 됐다. 체크를 게이트에서 빼는 대신 유효 조건을 만들어 RPO 증거를 보존한다.
    #
    # try 범위를 [정지 -> 드레인 -> 사후 run]으로 좁힌 이유: 이후 구간(기록·출력)은 봇이 돌아도
    # 무해하고, 복원을 함수 말미로 미루면 마지막 네이티브 명령이 docker start가 되어 그 종료코드가
    # 실험 프로세스 종료코드로 샌다(E5·E7이 같은 사고를 낸 전례).
    $botsStopped = Stop-BotLoad
    $drained = $false
    try {
        # 정지 실패 시 최소 방어: 연속 2회 0을 요구해 단발 0을 드레인으로 오인하지 않는다.
        $requiredStreak = if ($botsStopped) { 1 } else { 2 }
        $drained = Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 90 -RequiredStreak $requiredStreak
        if (-not $drained) {
            $residual = Get-Scalar $Global:CHAOS_DRAIN_SQL
            $residualDisp = 'n/a(psql 무응답)'; if ($null -ne $residual) { $residualDisp = $residual }
            Write-Host "경고: 드레인 미완(잔여 $residualDisp 건). C13a가 in-flight를 포함할 수 있다" -ForegroundColor Yellow
        }
        $postPort = Get-LiveNodePort
        $post = Invoke-InvariantRun -Port $postPort -RunId $RunId -Window 'post'
    }
    finally {
        Restore-BotLoad -WasStopped $botsStopped -Ops $RestoreOps
    }

    $rows = @{}
    foreach ($c in $post.checks) { $rows[$c.checkName] = [long]$c.violatedRows }
    $assetSum = $rows['C01_ASSET_CONSERVATION']
    $cmdGap = $rows['C05_CMD_GAPLESS']; $evtGap = $rows['C06_EVT_GAPLESS']
    $dupOrder = $rows['C07_DUP_ORDER'] + $rows['C07P_DUP_ORDER_JOURNAL']
    $dupFill = $rows['C08_DUP_FILL']; $ordersLost = $rows['C13A_ORDER_MATERIALIZED']
    $rpo = $cmdGap + $evtGap + $ordersLost

    # 드레인 성립 여부는 증거 품질 표기이지 판정 완화 사유가 아니다. 판정은 드레인 성패와 무관하게
    # criticalClean(C13a 포함)을 쓴다. 드레인 실패 시 C13a를 빼면 자기무력화가 된다. Wait-DrainZero의
    # 술어가 C13a와 같은 anti-join이라 "드레인 실패"는 곧 "C13a 위반"이고, 그때 C13a를 빼는 것은
    # 진짜 주문 유실을 PASS로 뒤집는 짓이다. 정본 PASS 식은 CRITICAL 전량 0행 ∧ rpo_records=0이다
    # (CICD §6 R7·검증설계 §5-3). 봇 정지 실패는 아래 재측정 경고로만 드러낸다.
    $drainValid = $botsStopped -and $drained
    if (-not $drainValid) {
        Write-Host "경고: 드레인 미성립(정지=$botsStopped, 드레인=$drained). C13a=$ordersLost가 실유실인지 in-flight인지 아래 재측정으로 가른다" -ForegroundColor Yellow
    }
    # 정지 실패 경로 최소 방어: run 직후 같은 술어를 다시 재서 표본이 in-flight를 물었는지 본다.
    # 값이 줄었으면 run 시점 표본에 처리 중 주문이 섞여 있었다는 뜻이라 그 run은 정지 상태 판정이
    # 아니다. 판정은 건드리지 않고 원인 귀속 근거로만 남긴다(재실행이 해소 경로).
    if (-not $botsStopped) {
        # 여기서 throw하면 판정·증거 출력이 통째로 사라진다. 재측정은 원인 귀속 보조라 무응답을
        # 별도로 알리고 넘어간다. 무응답을 0으로 읽어 "불일치 없음"으로 보고하지는 않는다.
        $c13aRecheckRaw = Get-Scalar $Global:CHAOS_DRAIN_SQL
        if ($null -eq $c13aRecheckRaw) {
            Write-Host '경고: C13a 재측정 실패(psql 무응답). run 표본이 in-flight를 물었는지 가르지 못했다' -ForegroundColor Yellow
        } elseif ([long]$c13aRecheckRaw -ne $ordersLost) {
            Write-Host "경고: C13a 재측정 불일치(run $ordersLost -> 직후 $c13aRecheckRaw). 부하 중 표본이라 RPO 증거로 쓸 수 없다. 봇 정지 원인 해소 후 재실행" -ForegroundColor Yellow
        }
    }

    $rto = $null; if ($t5) { $rto = ($t5 - $T0).TotalSeconds }
    # §5-1 정의: downtime(exchange_failover_downtime_seconds)=T2-T1. T1은 마지막 heartbeat이며
    # 구 lease_until − 리스 TTL로 역산한다. 사전 스냅샷 lease_until을 T1로 직접 쓰면 TTL만큼 편향된다.
    $leaseTtlSec = Get-LeaseTtlSec
    $downtime = $null; if ($t2) { $downtime = ($t2 - $LeaseUntilBefore).TotalSeconds + $leaseTtlSec }
    $rtoRounded = $null; if ($null -ne $rto) { $rtoRounded = [math]::Round($rto, 3) }
    $downtimeRounded = $null; if ($null -ne $downtime) { $downtimeRounded = [math]::Round($downtime, 3) }
    $rtoDisplay = 'n/a'; if ($null -ne $rtoRounded) { $rtoDisplay = $rtoRounded }
    $firstTradeVal = $null; if ($firstTradeTs) { $firstTradeVal = $firstTradeTs }

    # epoch는 단조 상승만 본다. 승격 재생 반납이 도입되면서 한 페일오버 사건에서
    # [반납 -> 만료 -> 재승격]이 반복될 수 있고 그때 epoch가 +2 이상 오른다(DB 설계 §5.4).
    # 정확히 +1을 요구하면 그 정상 경로가 FAIL로 뒤집힌다. E5·E7은 이미 -ge로 고쳤는데
    # 공용 함수만 교정을 못 받아 E2·E6에 남아 있었다. 서비스 재개는 아래 t5·rto가 요구한다.
    $verdict = 'FAIL'
    if ($post.criticalClean -and $t5 -and ($newEpoch -ge $OldEpoch + 1) -and ($null -ne $rto) -and ($rto -lt $RtoTarget)) {
        $verdict = 'PASS'
    }

    $tradesAfter = [long](Get-Scalar "SELECT count(*) FROM trades" -Required)
    $body = @{
        experimentId = $ExperimentId; oldLeader = $OldHolder; newLeader = $newHolder
        oldEpoch = $OldEpoch; newEpoch = $newEpoch; firstTradeTs = $firstTradeVal
        rtoSeconds = $rtoRounded; downtimeSeconds = $downtimeRounded
        rpoRecords = $rpo; verdict = $verdict
        details = @{ runId = $RunId; assetSum = "$assetSum"; dupOrder = "$dupOrder";
                     dupFill = "$dupFill"; cmdGap = "$cmdGap"; evtGap = "$evtGap";
                     tradesBefore = "$TradesAtInject"; tradesAfter = "$tradesAfter";
                     drainValid = "$drainValid" }
    }
    $rec = Record-Failover -Port $postPort -Body $body

    Write-Host ''
    Write-Host "── $ExperimentId 증거 ─────────────────────" -ForegroundColor Green
    Write-Host ("verdict          : {0}" -f $verdict)
    Write-Host ("RTO(체결 재개)   : {0} s (목표 < {1})" -f $rtoDisplay, $RtoTarget)
    Write-Host ("epoch            : {0} -> {1}" -f $OldEpoch, $newEpoch)
    Write-Host ("자산합(C01)      : {0}" -f $assetSum)
    Write-Host ("중복 접수/체결   : {0} / {1}" -f $dupOrder, $dupFill)
    Write-Host ("저널 gap C05/C06 : {0} / {1}" -f $cmdGap, $evtGap)
    Write-Host ("주문 유실(C13a)  : {0} (드레인 성립={1})" -f $ordersLost, $drainValid)
    Write-Host ("failover_run_id  : {0}" -f $rec.failover_run_id)
    Write-Host '────────────────────────────────────────'
    # Write-Host로 감싸지 않으면 Invoke-Psql 반환 문자열이 파이프라인에 섞여 함수가 배열을
    # 돌려준다. 그러면 호출부의 `$verdict -ne 'PASS'`가 배열 비교가 되어 판정이 PASS여도
    # 항상 참이 되고 E2·E6가 exit 0을 낼 수 없다(실측).
    Write-Host (Invoke-Psql "SELECT id, experiment_id, old_epoch, new_epoch, rto_seconds, rpo_records, verdict FROM harness.failover_runs ORDER BY id DESC LIMIT 3")
    return $verdict
}

# ── 확장 유틸(E1·E3·E5·E7·E8·E9 전용, 추가만). E2·E4·E6(구 E1~E3) 경로는 위 함수만 쓴다. ──

# 엔드포인트 지연 표본(ms) + 실패 카운트. DB 경유 read를 반복 GET한다. E1은 리더 pg 프록시
# 지연 주입의 p99 상승·복귀를, 실패 표본은 오류율을 준다. status는 leader_lease를 읽어 DB를 탄다.
function Measure-Latency {
    param([int] $Port, [string] $Path = '/harness/v1/status', [int] $Count = 100, [int] $GapMs = 50)
    $samples = New-Object System.Collections.Generic.List[double]
    $errors = 0
    for ($i = 0; $i -lt $Count; $i++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            Invoke-RestMethod -Uri "http://localhost:$Port$Path" -TimeoutSec 10 | Out-Null
            $sw.Stop(); $samples.Add($sw.Elapsed.TotalMilliseconds)
        } catch {
            $sw.Stop(); $errors++
        }
        if ($GapMs -gt 0) { Start-Sleep -Milliseconds $GapMs }
    }
    return [pscustomobject]@{ Samples = $samples.ToArray(); Errors = $errors; Total = $Count }
}

# nearest-rank 백분위수(ms). 표본 부재 시 $null. 정렬은 배열 강제(@)로 단일 표본 스칼라화 방지.
function Get-Percentile {
    param([double[]] $Values, [double] $P = 99)
    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $rank = [int][math]::Ceiling(($P / 100.0) * $sorted.Count)
    if ($rank -lt 1) { $rank = 1 }
    if ($rank -gt $sorted.Count) { $rank = $sorted.Count }
    return [double] $sorted[$rank - 1]
}

# 컨테이너 실행/존재 여부. E3 redis 전제 검사, E5/E7 대상 확인용. 이름 정확 일치만 참.
function Test-ContainerRunning {
    param([string] $Name)
    $names = & docker ps --format '{{.Names}}' 2>$null
    return ($names -contains $Name)
}
function Test-ContainerExists {
    param([string] $Name)
    $names = & docker ps -a --format '{{.Names}}' 2>$null
    return ($names -contains $Name)
}

# 컨테이너 헬스 상태. E1 1차 게이트(주입 중·해제 후 unhealthy 표본 0, 정본 §3 E1)의 표본원이다.
# --format으로 뽑지 않는 이유: healthcheck 미정의 컨테이너에서 .State.Health가 nil이라 포맷이
# 깨진다. 전체 JSON을 파싱해 헬스 부재('none')와 조회 실패('unknown')를 상태값과 구분한다.
# 'unknown'은 도커 조회 자체가 실패한 것이라 호출부가 unhealthy와 같은 급으로 다뤄야 한다.
function Get-ContainerHealth {
    param([string] $Name)
    $raw = & cmd /c "docker inspect $Name 2>nul"
    $json = ($raw | Out-String).Trim()
    if (-not $json) { return 'unknown' }
    try {
        $arr = @($json | ConvertFrom-Json)
        # 없는 컨테이너는 docker inspect가 빈 배열을 낸다. 이것을 'none'으로 돌려주면 "헬스체크
        # 미정의"와 구분되지 않아, 죽어서 사라진 노드가 정상 표본으로 계상된다.
        if ($arr.Count -lt 1 -or $null -eq $arr[0] -or $null -eq $arr[0].State) { return 'unknown' }
        if ($null -eq $arr[0].State.Health) { return 'none' }
        return [string]$arr[0].State.Health.Status
    } catch { return 'unknown' }
}

# toxiproxy에 특정 프록시가 배선돼 있는지. E3 redis 프록시 전제 검사용.
function Test-ToxiproxyProxy {
    param([string] $Name)
    try {
        $proxies = Invoke-RestMethod -Uri "$($Global:CHAOS.ToxiUrl)/proxies" -UserAgent $Global:CHAOS_UA -TimeoutSec 10
        return ($null -ne $proxies.$Name)
    } catch { return $false }
}

# 저널 워터마크. 주입 직후 max(seq)를 잡아 이후 구 epoch append(스테일 쓰기)를 시간 축으로 격리한다.
# COALESCE로 빈 테이블에서도 행이 나오므로 빈 결과는 곧 psql 무응답이다. 그것을 0으로 읽으면
# 워터마크가 0이 되어 이후 스테일 쓰기 질의가 저널 전량을 대상으로 삼는다(판정 무효).
function Get-CommandWatermark { [long](Get-Scalar "SELECT COALESCE(max(seq),0) FROM command_journal" -Required) }
function Get-EventWatermark   { [long](Get-Scalar "SELECT COALESCE(max(event_seq),0) FROM event_journal" -Required) }

# command_journal에 명령 축 fencing 컬럼(fencing_token)이 있는지(실스키마 대조). 실DDL 정본:
# command_journal.fencing_token(= epoch 값), event_journal.fencing_epoch. 정본 §4-1 C22 주석은
# 이를 command_journal.fencing_epoch로 오기(§5-1 T5는 fencing_token으로 정확). 실스키마가 권위다.
function Test-CommandFencingColumn {
    $v = Invoke-Psql "SELECT count(*) FROM information_schema.columns WHERE table_name='command_journal' AND column_name='fencing_token'"
    return ([decimal]$v -gt 0)
}

# 스테일 쓰기 수: 워터마크 이후 구 epoch로 append된 저널 행. split-brain 반증(E5)·좀비 리더(E7)의
# 기계 판정. 명령 축(command_journal.fencing_token)이 1차 쓰기 경로라 존재 시 합산, event 축
# (event_journal.fencing_epoch)은 항상. fencing_token/fencing_epoch 값 = 리더 epoch(실데이터 확인).
function Get-StaleWriteCount {
    param([long] $CmdWatermark, [long] $EvtWatermark, [long] $OldEpoch, [bool] $CmdHasFencing)
    # -Required 필수. 이 두 count가 E5·E7의 지배 증거(split-brain 반증)라, 빈 결과를 0으로 읽으면
    # psql 무응답이 그대로 "이중 쓰기 0"이 되어 확정 위양성 PASS를 만든다.
    $n = 0
    if ($CmdHasFencing) {
        $n += [long](Get-Scalar "SELECT count(*) FROM command_journal WHERE seq > $CmdWatermark AND fencing_token = $OldEpoch" -Required)
    }
    $n += [long](Get-Scalar "SELECT count(*) FROM event_journal WHERE event_seq > $EvtWatermark AND fencing_epoch = $OldEpoch" -Required)
    return $n
}

# 승격 후 최초 신규 TRADE의 engine_ts(§5-1 T5). 커밋 가시화 지연과 무관한 체결 재개 시점.
# 반환: UtcDateTime 또는 $null. 술어는 Get-FirstTradeSql 공용(재생 산출물 배제 근거는 그 주석).
function Wait-FirstTradeAfter {
    param([long] $OldEpoch, [datetime] $T0, [int] $TimeoutSec)
    $t0Iso = $T0.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fff') + 'Z'
    $sql = Get-FirstTradeSql -OldEpoch $OldEpoch -T0Iso $t0Iso
    Wait-For { -not [string]::IsNullOrWhiteSpace((Invoke-Psql $sql)) } -TimeoutSec $TimeoutSec -PollSec 0.5 | Out-Null
    $raw = Invoke-Psql $sql
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    return ([datetimeoffset]($raw.Replace(' ', 'T') + 'Z')).UtcDateTime
}

# 현 리더 컨테이너 판별. 자기 인스턴스 id 노출 엔드포인트가 없어(status는 lease.holder만 반환)
# 활성(lease alive) holder UUID를 각 노드 로그에서 찾는다. holder UUID는 소유 노드 로그에만
# 찍히므로 매칭되는 컨테이너가 현 리더다. ASCII UUID만 대조해 콘솔 인코딩 무관(승격/상실 한글
# 매칭은 5.1 OEM 코드페이지에서 깨져 신뢰 불가). 실패·리더 부재 시 $null.
# 용도: E5/E7이 팔로워를 잘못 대상 삼아 페일오버 없이 공허 FAIL 나는 것을 막는다(README 리허설 규약).
function Get-LeaderContainer {
    param([string[]] $Candidates = @('chaos-api-1', 'chaos-api-2'))
    $holder = Invoke-Psql "SELECT holder FROM leader_lease WHERE resource='matching-engine' AND lease_until > now()"
    if ([string]::IsNullOrWhiteSpace($holder)) { return $null }
    $matched = @()
    foreach ($c in $Candidates) {
        # cmd /c로 stderr를 nul에 버려 PowerShell이 네이티브 stderr(JAVA_TOOL_OPTIONS)를
        # NativeCommandError로 승격시키는 것을 원천 차단한다(2>$null도 Stop에서 터짐, 공용 규약).
        $lines = & cmd /c "docker logs $c 2>nul" | Select-String -SimpleMatch $holder
        if ($lines) { $matched += $c }
    }
    # 첫 매치를 반환하면 안 된다. holder UUID는 소유 노드에만 찍히지 않는다. fencing 거부
    # 사유 문자열이 현재 리더의 holder를 담고 있어(V1 assert_current_leader) 강등된 구 리더의
    # 로그에도 신 리더 UUID가 남는다. 그러면 후보 순서상 앞선 팔로워가 리더로 뽑히고, 그
    # 팔로워를 주입 대상 삼은 실험은 승격이 없어 확정 위양성 FAIL이 난다. 모호하면 멈춘다.
    if ($matched.Count -eq 1) { return $matched[0] }
    if ($matched.Count -gt 1) {
        Write-Host ("리더 판별 모호: holder={0}가 컨테이너 {1}에서 동시 매치. 로그 오염(fencing 거부 사유에 신 리더 holder 포함)일 수 있다." -f $holder, ($matched -join ', ')) -ForegroundColor Yellow
    }
    return $null
}

# /actuator/prometheus 텍스트에서 메트릭 1개(첫 매치의 캡처그룹 1)를 뽑는다. 부재 시 $null(옵션 지표).
# -Required는 스크레이프 도달 실패만 throw한다. 시계열 부재는 그대로 $null이다. 두 사건을 같은
# $null로 뭉개면 관측 경로 단절이 "메트릭 미노출이라 판정에서 제외"로 조용히 흡수된다(E2 반납
# 게이트가 그랬다). Invoke-RestMethod는 비2xx에서도 throw하므로 응답 코드 확인을 겸한다.
# 주의: 서버가 등록하지 않은 지표(E9 exchange_idempotent_replay_total)는 스크레이프가 성공해도
# 항상 시계열 부재다. 그 축에 -Required를 붙여도 승격되지 않으며, 붙여서도 안 된다.
function Get-AppMetric {
    param([int] $Port, [string] $Pattern, [switch] $Required)
    try {
        $text = Invoke-RestMethod -Uri "http://localhost:$Port/actuator/prometheus" -TimeoutSec 5
    } catch {
        if ($Required) { throw "actuator/prometheus 스크레이프 실패(port $Port): $($_.Exception.Message)" }
        return $null
    }
    $m = [regex]::Match(($text | Out-String), $Pattern)
    if ($m.Success -and $m.Groups.Count -gt 1) { return $m.Groups[1].Value }
    return $null
}

# 실험 무결성 판정. 게이팅에서 빼는 것은 아래 3건과 WARN 등급뿐이고, 나머지가 전부 0행이면 clean.
# 면제 사유는 체크마다 다르다. C13a는 append와 정산 커밋 사이의 비동기 물질화 창, C19는 정지 시점
# 오더북 스냅샷이라 둘 다 큐 드레인을 전제한다(실측: 400 ops에서 C13a 10~11건, 드레인하면 0).
# C16은 드레인과 무관하다. 스테일 loadtest_acks 오탐이 배제 사유이고 실험 시작 훅의 TRUNCATE와
# 한 세트다(정본 §4-3 각주). 스테일 ACK 행은 아무리 드레인해도 0이 되지 않는다.
#
# C22·C23은 여기 들어가지 않는다. 정본 §4-2 ④·§4-3 각주가 두 체크를 권위 run(REPEATABLE READ
# 스냅샷) 평가 대상으로 지정하면서 오탐 없음을 명시한다. C23은 채번 UPDATE와 저널 INSERT가 한
# 트랜잭션이라 스냅샷에서 중간 상태가 보이지 않고, C22는 커밋된 append-only 행만 보므로 역행이
# 관측되면 그 자체가 split-brain 참양성이다. 두 체크를 빼면 fencing 역행·채번 재사용이라는
# CRITICAL 위반이 실험 사후 권위 run을 그대로 통과한다.
#
# 서버 Scope.AUTHORITATIVE_ONLY(7건: C13A·C16·C19·C22·C23·C24·C25)와 이 목록(3건)은 서로 다른
# 축이다. C24·C25가 늘면서 5건에서 7건이 됐다(V13 원가 프로젝션). 전자는 "어느 run에서
# 평가하는가"이고 후자는 "평가된 결과를 게이팅에 쓰는가"다. 두 목록을 1:1로 맞추면 C22·C23이
# 게이팅에서 빠진다.
#
# 목록을 전역 상수로 두는 이유: 실험 스크립트가 같은 배열을 인라인으로 복제하면 여기를 고쳐도
# 사본이 남아 반쪽 교정이 된다(실측: e6가 인라인 사본을 써서 WARN 제외까지 어긋났다).
$Global:CHAOS_GATING_EXEMPT_CHECKS = @(
    'C13A_ORDER_MATERIALIZED', 'C16_ACK_JOURNALED', 'C19_ORDERBOOK_NONCROSS'
)

# 게이팅 대상 체크 하나의 판정 참여 여부. 제외 목록과 WARN 등급이 유일한 면제 사유다.
# WARN은 관측용 등급이라 실험 실패 사유가 아니다(CRITICAL·HIGH만 게이팅).
function Test-GatingExempt {
    param([string] $CheckName, [string] $Severity)
    if ($Global:CHAOS_GATING_EXEMPT_CHECKS -contains $CheckName) { return $true }
    if ($Severity -eq 'WARN') { return $true }
    return $false
}

# 게이팅 대상 위반 행 합. 판정에 쓰지 말고 보고에만 쓴다(판정은 Test-ExperimentIntegrity 단독).
# 같은 면제 술어를 소비하므로 정상 페이로드에서는 [합 0] 과 [판정 clean] 이 일치한다. 빈 응답은
# 예외다. 여기서는 0이 나오고 판정은 거짓이 되는데, 그 비대칭이 의도다(아래 공허 가드).
# 0 클램프는 서버 AuthoritativeRun과 대칭을 맞춘 것이다. 부호 상쇄로 위반을 가리지 않는다.
function Get-GatedViolationSum {
    param($Run)
    $sum = [long]0
    foreach ($c in $Run.checks) {
        if (Test-GatingExempt -CheckName $c.checkName -Severity $c.severity) { continue }
        $sum += [math]::Max([long]0, [long]$c.violatedRows)
    }
    return $sum
}

function Test-ExperimentIntegrity {
    param($Run)
    # 공허 통과 차단. 체크가 하나도 안 돈 run을 통과로 보고하지 않는다(서버 AuthoritativeRun.pass와
    # 동일 술어). 종전에는 호출부의 위반합 조건이 빈 응답에서 $null을 내는 바람에 이 구멍을 우연히
    # 막고 있었고, 그 조건을 걷어내면서 가드가 필요해졌다.
    if (-not $Run.checks -or @($Run.checks).Count -lt 1) { return $false }
    foreach ($c in $Run.checks) {
        # 형상 가드. checkName이 비면 응답 DTO 필드가 개명된 것이고, 그때는 violatedRows도 못 읽어
        # 전 행이 0으로 보인다. 위반이 있어도 조용한 PASS가 되므로 판정 불능으로 떨어뜨린다.
        if ([string]::IsNullOrWhiteSpace($c.checkName)) { return $false }
        if (Test-GatingExempt -CheckName $c.checkName -Severity $c.severity) { continue }
        if ([long]$c.violatedRows -ne 0) { return $false }
    }
    return $true
}

# 비페일오버·SKIP 실험의 verdict JSON을 표준 형식으로 stdout에 낸다. failover_runs 적재는 안 한다
# (epoch 변화 없는 실험을 페일오버 행으로 남기면 증거가 오염된다). E1·E3·E8·E9가 쓴다.
function Write-Verdict {
    param([string] $ExperimentId, [string] $Verdict, [hashtable] $Metrics)
    $obj = [ordered]@{ experimentId = $ExperimentId; verdict = $Verdict; ts = (Now-Iso) }
    foreach ($k in $Metrics.Keys) { $obj[$k] = $Metrics[$k] }
    Write-Host ''
    Write-Host '── verdict(JSON) ───────────────────────' -ForegroundColor Green
    ([pscustomobject]$obj) | ConvertTo-Json -Depth 6 -Compress | Write-Host
    Write-Host '────────────────────────────────────────'
}

# ── 시계 동기 전제조건 가드(조사 #58). 페일오버 승격 게이트 실험 전용. 추가만. ──

# Docker Desktop VM 시계가 호스트와 비단조로 어긋나면(호스트 슬립발 간헐 스큐) 리스 만료 구동
# 승격(try_acquire: lease_until < clock_timestamp)이 지연돼 페일오버 실험이 거짓 FAIL 난다.
# 코드·HA(CP 안전)는 정상. chaos-pg clock_timestamp와 호스트 UTC 차의 절대값(ms)이 임계 초과면 스큐.
# 임계: CHAOS_CLOCK_SKEW_TOLERANCE_MS(기본 2000). psql 왕복지연을 midpoint로 상쇄해 잔차만 남긴다.
function Test-ClockSkew {
    param([int] $ToleranceMs = $(if ($env:CHAOS_CLOCK_SKEW_TOLERANCE_MS) { [int]$env:CHAOS_CLOCK_SKEW_TOLERANCE_MS } else { 2000 }))
    # 호스트 시각으로 psql 호출을 감싸 before·after 중앙값을 기준점으로 쓴다(NTP식 왕복 상쇄).
    $before = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $raw = Invoke-Psql "SELECT (extract(epoch from clock_timestamp()) * 1000)::bigint"
    $after = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $roundTrip = [long]($after - $before)
    if ([string]::IsNullOrWhiteSpace($raw)) {
        # PG 미도달은 스큐가 아니라 별개 실패다. 스큐 아님으로 반환하되 도달불가를 표기(호출부 판단).
        return [pscustomobject]@{ Skewed = $false; SkewMs = $null; ToleranceMs = $ToleranceMs; Reachable = $false; RoundTripMs = $roundTrip }
    }
    $pgMs = [long]$raw
    $hostMid = [long](($before + $after) / 2)
    $skewMs = [long][math]::Abs($pgMs - $hostMid)
    return [pscustomobject]@{
        Skewed = ($skewMs -gt $ToleranceMs); SkewMs = $skewMs; ToleranceMs = $ToleranceMs
        Reachable = $true; RoundTripMs = $roundTrip; PgEpochMs = $pgMs; HostEpochMs = $hostMid
    }
}

# 페일오버 실험 주입 직전 호출. 스큐면 verdict SKIP(스큐값·임계 기록)을 방출하고 $true 반환
# (호출부가 주입 없이 exit 0으로 조기 종료). 동기·PG미도달이면 $false(정상 진행). 주입이 없어
# failover_runs 미적재(SKIP은 페일오버 사건 아님). SKIP은 E3 redis·E8 chaosmonkey 부재와 동일 어휘.
function Test-FailoverClockGuard {
    param([Parameter(Mandatory = $true)][string] $ExperimentId)
    $clock = Test-ClockSkew
    if (-not $clock.Reachable) {
        # 도달불가는 시계가드 소관 밖. 정상 진행시켜 기존 권위 run·주입 경로가 실패를 드러내게 둔다.
        Write-Host "시계가드: chaos-pg clock_timestamp 미응답, 스큐 판정 생략(왕복 $($clock.RoundTripMs)ms)." -ForegroundColor Yellow
        return $false
    }
    if (-not $clock.Skewed) {
        Write-Host ("시계가드 통과: 스큐 {0}ms, 임계 {1}ms, 왕복 {2}ms." -f $clock.SkewMs, $clock.ToleranceMs, $clock.RoundTripMs)
        return $false
    }
    Write-Verdict -ExperimentId $ExperimentId -Verdict 'SKIP' -Metrics @{
        reason      = 'clock_skew'
        clockSkewMs = $clock.SkewMs
        toleranceMs = $clock.ToleranceMs
        roundTripMs = $clock.RoundTripMs
        pgEpochMs   = $clock.PgEpochMs
        hostEpochMs = $clock.HostEpochMs
        note        = 'Docker VM 시계와 호스트 UTC 스큐가 임계 초과. 장애 미주입(거짓 FAIL 방지, 조사 #58). WSL2/Docker Desktop 시계 재동기(wsl --shutdown 후 재기동) 뒤 재실행.'
    }
    Write-Host ''
    Write-Host ("{0} SKIP: 시계 스큐 {1}ms, 임계 {2}ms 초과. 주입 생략(#58 거짓 FAIL 방지)." -f $ExperimentId, $clock.SkewMs, $clock.ToleranceMs) -ForegroundColor Yellow
    return $true
}
