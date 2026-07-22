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
    ToxiUrl     = 'http://localhost:58474'
    PgUser      = 'exchange'
    PgDb        = 'exchange'
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)] $Args)
    & docker compose -f $Global:CHAOS.ComposeFile --env-file $Global:CHAOS.EnvFile @Args
}

function Read-EnvFile {
    param([string] $Path)
    $map = @{}
    Get-Content $Path | ForEach-Object {
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

function Get-Scalar {
    param([string] $Sql)
    $v = Invoke-Psql $Sql
    if ([string]::IsNullOrWhiteSpace($v)) { return 0 }
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

function Add-Toxic {
    param([string] $Proxy, [string] $Name, [string] $Type, [hashtable] $Attributes)
    $body = @{ name = $Name; type = $Type; stream = 'downstream'; attributes = $Attributes } | ConvertTo-Json
    Invoke-RestMethod -Uri "$($Global:CHAOS.ToxiUrl)/proxies/$Proxy/toxics" -Method Post `
        -ContentType 'application/json' -Body $body -TimeoutSec 10 | Out-Null
}

function Remove-AllToxics {
    param([string] $Proxy)
    try {
        $toxics = Invoke-RestMethod -Uri "$($Global:CHAOS.ToxiUrl)/proxies/$Proxy/toxics" -TimeoutSec 10
        foreach ($t in $toxics) {
            Invoke-RestMethod -Uri "$($Global:CHAOS.ToxiUrl)/proxies/$Proxy/toxics/$($t.name)" -Method Delete | Out-Null
        }
    } catch { }
}

function Write-Section {
    param([string] $Text)
    Write-Host ''
    Write-Host "== $Text ==" -ForegroundColor Cyan
}

function Now-Iso { return (Get-Date).ToUniversalTime().ToString('o') }

# 조건 충족까지 폴링. 반환: 충족 시각(UTC) 또는 $null(타임아웃).
function Wait-For {
    param([scriptblock] $Condition, [int] $TimeoutSec, [double] $PollSec = 0.5)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        # UTC로 반환한다. DB engine_ts(UTC)와 뺄셈할 때 로컬 시각이면 오프셋만큼 오차가 난다.
        if (& $Condition) { return (Get-Date).ToUniversalTime() }
        Start-Sleep -Milliseconds ([int]($PollSec * 1000))
    }
    return $null
}

# 주입(T0) 이후 승격·체결 재개 관찰 → 사후 권위 run → RTO/RPO 판정 → failover_runs 기록.
# E2·E3가 공용으로 쓴다(E1은 상세 참조본이라 인라인 유지). 개입 금지 구간이므로 관찰만 한다.
function Complete-FailoverExperiment {
    param(
        [string] $ExperimentId, [string] $RunId,
        [long] $OldEpoch, [string] $OldHolder, [datetime] $LeaseUntilBefore,
        [long] $TradesAtInject, [datetime] $T0,
        [int] $ObserveSec, [int] $RtoTarget
    )
    $standbyPort = $Global:CHAOS.Api2Port

    Write-Section 'ⓒ 관찰(개입 금지)'
    $t2 = Wait-For {
        try { $s = Get-HarnessStatus -Port $standbyPort; ($s.epoch -gt $OldEpoch) -and $s.leaderAlive }
        catch { $false }
    } -TimeoutSec $ObserveSec -PollSec 0.5
    $statusAfter = Get-HarnessStatus -Port (Get-LiveNodePort)
    $newEpoch = [long]$statusAfter.epoch
    $newHolder = $statusAfter.holder

    # T5 = 페일오버 후 최초 신규 TRADE의 engine_ts(§5-1 정의). created_at은 DB now()가 아니라
    # 엔진 처리 시각이라 커밋 가시화 지연과 무관한 재개 시점을 가리킨다. T0 이후 신 epoch 필터.
    $t0Iso = $T0.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fff') + 'Z'
    $firstTradeSql = "SELECT min(created_at) AT TIME ZONE 'UTC' " +
        "FROM event_journal WHERE event_type='TRADE' AND fencing_epoch=$newEpoch AND created_at > '$t0Iso'"
    $obsResume = Wait-For {
        -not [string]::IsNullOrWhiteSpace((Invoke-Psql $firstTradeSql))
    } -TimeoutSec $ObserveSec -PollSec 0.5
    $firstTradeRaw = Invoke-Psql $firstTradeSql
    $t5 = $null; $firstTradeTs = $null
    if ($firstTradeRaw) {
        $firstTradeTs = $firstTradeRaw.Replace(' ', 'T') + 'Z'
        $t5 = ([datetimeoffset]$firstTradeTs).UtcDateTime
    }

    Write-Section 'ⓓ 사후 권위 run'
    Start-Sleep -Seconds 3
    $postPort = Get-LiveNodePort
    $post = Invoke-InvariantRun -Port $postPort -RunId $RunId -Window 'post'

    $rows = @{}
    foreach ($c in $post.checks) { $rows[$c.checkName] = [long]$c.violatedRows }
    $assetSum = $rows['C01_ASSET_CONSERVATION']
    $cmdGap = $rows['C05_CMD_GAPLESS']; $evtGap = $rows['C06_EVT_GAPLESS']
    $dupOrder = $rows['C07_DUP_ORDER'] + $rows['C07P_DUP_ORDER_JOURNAL']
    $dupFill = $rows['C08_DUP_FILL']; $ordersLost = $rows['C13A_ORDER_MATERIALIZED']
    $rpo = $cmdGap + $evtGap + $ordersLost

    $rto = $null; if ($t5) { $rto = ($t5 - $T0).TotalSeconds }
    $downtime = $null; if ($t2) { $downtime = ($t2 - $LeaseUntilBefore).TotalSeconds }
    $rtoRounded = $null; if ($null -ne $rto) { $rtoRounded = [math]::Round($rto, 3) }
    $downtimeRounded = $null; if ($null -ne $downtime) { $downtimeRounded = [math]::Round($downtime, 3) }
    $rtoDisplay = 'n/a'; if ($null -ne $rtoRounded) { $rtoDisplay = $rtoRounded }
    $firstTradeVal = $null; if ($firstTradeTs) { $firstTradeVal = $firstTradeTs }

    $verdict = 'FAIL'
    if ($post.criticalClean -and $t5 -and ($newEpoch -eq $OldEpoch + 1) -and ($null -ne $rto) -and ($rto -lt $RtoTarget)) {
        $verdict = 'PASS'
    }

    $tradesAfter = [long](Get-Scalar "SELECT count(*) FROM trades")
    $body = @{
        experimentId = $ExperimentId; oldLeader = $OldHolder; newLeader = $newHolder
        oldEpoch = $OldEpoch; newEpoch = $newEpoch; firstTradeTs = $firstTradeVal
        rtoSeconds = $rtoRounded; downtimeSeconds = $downtimeRounded
        rpoRecords = $rpo; verdict = $verdict
        details = @{ runId = $RunId; assetSum = "$assetSum"; dupOrder = "$dupOrder";
                     dupFill = "$dupFill"; cmdGap = "$cmdGap"; evtGap = "$evtGap";
                     tradesBefore = "$TradesAtInject"; tradesAfter = "$tradesAfter" }
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
    Write-Host ("주문 유실(C13a)  : {0}" -f $ordersLost)
    Write-Host ("failover_run_id  : {0}" -f $rec.failover_run_id)
    Write-Host '────────────────────────────────────────'
    Invoke-Psql "SELECT id, experiment_id, old_epoch, new_epoch, rto_seconds, rpo_records, verdict FROM harness.failover_runs ORDER BY id DESC LIMIT 3"
    return $verdict
}
