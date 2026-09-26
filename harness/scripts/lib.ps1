# 카오스 실험 공용 유틸. 각 실험 스크립트가 dot-source 한다.
# 포트·컨테이너명은 docker-compose.chaos.yml과 1:1. 판정은 전부 SQL 0행/RTO 임계로 기계화한다.

$ErrorActionPreference = 'Stop'

# compose 프로젝트 이름. 기본은 compose 파일의 name(exchange-chaos)과 같다. 폐기용 실험 스택은
# CHAOS_PROJECT=exchange-pml 처럼 다른 이름을 주어 볼륨과 네트워크를 보존 스택과 가른다. 컨테이너
# 이름(chaos-*)은 compose 파일에 고정돼 있어 두 프로젝트를 동시에 띄울 수는 없다.
$chaosProject = $(if ($env:CHAOS_PROJECT) { $env:CHAOS_PROJECT } else { 'exchange-chaos' })
if ($chaosProject -notmatch '^[a-z0-9][a-z0-9_-]*$') { throw "CHAOS_PROJECT 형식 위반: $chaosProject" }

# 대상 스택(Patroni 설계 8-1, 검토 R7). 값을 주지 않으면 카오스 스택(chaos-*) 그대로라 카오스 실험과 S5·S6 관문 스크립트의
# 동작이 바뀌지 않는다. Patroni 실험 스택은 psql 을 patroni-toolbox 에서 돌려 patroni-haproxy-b 를 거쳐 현재 Primary 에 붙고
# (Primary 가 두 DB 컨테이너 사이를 옮겨 다니므로 고정 컨테이너에 docker exec 하면 Standby 에 붙는다), API 후보·포트·봇
# 컨테이너를 바꾼다. 기본값은 harness/scripts/tests/lib-target.Tests.ps1 이 고정한다.
function Get-TargetSetting {
    param([string] $Name, $Default)
    $v = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($v)) { return $Default }
    return $v.Trim()
}

# 컨테이너 이름은 cmd 문자열에 들어간다(Invoke-DockerQuiet). 형식을 먼저 막는다.
function Assert-ContainerName {
    param([string] $Name, [string] $Setting)
    if ($Name -notmatch '^[a-z0-9][a-z0-9_.-]*$') { throw "$Setting 형식 위반: $Name" }
    return $Name
}

$targetApiContainers = @((Get-TargetSetting 'CHAOS_API_CONTAINERS' 'chaos-api-1,chaos-api-2').Split(',') |
    ForEach-Object { $_.Trim() } | Where-Object { $_ } |
    ForEach-Object { Assert-ContainerName $_ 'CHAOS_API_CONTAINERS' })
if ($targetApiContainers.Count -lt 1) { throw 'CHAOS_API_CONTAINERS 가 비었다' }

$Global:CHAOS = @{
    ComposeFile = Get-TargetSetting 'CHAOS_COMPOSE_FILE' (Join-Path $PSScriptRoot '..\docker-compose.chaos.yml')
    EnvFile     = Get-TargetSetting 'CHAOS_ENV_FILE' (Join-Path $PSScriptRoot '..\.env.chaos')
    Project     = $chaosProject
    # psql 을 docker exec 할 컨테이너. 카오스 스택은 DB 컨테이너 자신, Patroni 스택은 patroni-toolbox 다.
    Pg          = Assert-ContainerName (Get-TargetSetting 'CHAOS_PSQL_CONTAINER' 'chaos-pg') 'CHAOS_PSQL_CONTAINER'
    # 컴포즈가 프로젝트 접두를 붙인 실명이다. E5 분할과 캘리브레이션이 이 값을 쓴다.
    Network     = "${chaosProject}_chaos-net"
    Api1Port    = [int](Get-TargetSetting 'CHAOS_API1_PORT' 18080)
    Api2Port    = [int](Get-TargetSetting 'CHAOS_API2_PORT' 28080)
    LbPort      = [int](Get-TargetSetting 'CHAOS_LB_PORT' 58080)
    # 리더 판별(Get-LeaderContainer)의 후보. 쉼표로 가른다.
    ApiContainers = $targetApiContainers
    BotsContainer = Assert-ContainerName (Get-TargetSetting 'CHAOS_BOTS_CONTAINER' 'chaos-bots') 'CHAOS_BOTS_CONTAINER'
    # 기본 58474. 호스트가 이 포트를 WinNAT 동적 예외로 예약해 게시 불가일 때(Windows) CHAOS_TOXI_URL로
    # 재지정(예: 포트 리맵 오버라이드). env 미설정 시 기존 동작 불변.
    ToxiUrl     = $(if ($env:CHAOS_TOXI_URL) { $env:CHAOS_TOXI_URL } else { 'http://localhost:58474' })
    PgUser      = 'exchange'
    PgDb        = 'exchange'
    RepoRoot    = Join-Path $PSScriptRoot '..\..'
    # 실험 판정 파일이 떨어지는 곳. 컨테이너 볼륨이 아니라 저장소라 down -v 에 살아남는다.
    # CHAOS_EVIDENCE_DIR 를 주면 그 폴더에 쓴다(폐기용 스택 회차의 증거를 한 폴더에 모을 때).
    EvidenceDir = $(if ($env:CHAOS_EVIDENCE_DIR) { $env:CHAOS_EVIDENCE_DIR } else { Join-Path $PSScriptRoot '..\evidence\chaos-runs' })
}

# param 블록을 두지 않는 이유: [Parameter()]가 붙는 순간 고급 함수가 되고, 그러면 PowerShell이
# 인자를 먼저 파라미터로 바인딩한다. 단문자 compose 플래그는 공용 파라미터에 접두 일치해
# 남은 인자 목록에 들어오지 못한다(-v는 -Verbose, -d는 -Debug로 먹힌다). 실측: 'down -v'가
# 인자 3개만 남겨 볼륨이 지워지지 않았고, down.ps1이 전체 파기를 약속한 채 원장을 존치시켜
# 이후 실험이 이전 상태를 물려받았다. 자동 변수 $args를 쓰는 단순 함수는 바인딩을 하지 않는다.
function Invoke-Compose {
    $overlay = @()
    foreach ($f in @(Get-ComposeOverlayFiles)) { $overlay += @('-f', $f) }
    & docker compose -p $Global:CHAOS.Project -f $Global:CHAOS.ComposeFile @overlay --env-file $Global:CHAOS.EnvFile @args
}

# 기본 compose 파일 뒤에 겹칠 오버레이 목록(ADR 0034 S6, 마켓분할_리더_S6_설계 4-19). 환경 변수 CHAOS_COMPOSE_OVERLAYS 에
# 저장소 루트 기준 harness/ 아래 경로를 세미콜론으로 나열한다(예: harness/docker-compose.relay.yml). 스택을 다시 세우는 모든
# 호출(실험 실행기의 down -v·up, Set-BotOps 의 봇 재생성)이 Invoke-Compose·Invoke-ComposeQuiet 를 거치므로 오버레이가 빠지지
# 않는다. 빠지면 중계 브로커가 고아로 지워지고 앱 노드가 simple 모드로, LB 가 nginx.conf 로 뜬다.
# 목록의 파일이 없거나 harness/ 밖이면 멈춘다. 호출할 때마다 읽으므로 실행기가 dot-source 뒤에 변수를 채워도 반영된다.
function Get-ComposeOverlayFiles {
    $raw = $env:CHAOS_COMPOSE_OVERLAYS
    if ([string]::IsNullOrWhiteSpace($raw)) { return @() }
    $harness = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    $root = [System.IO.Path]::GetFullPath($Global:CHAOS.RepoRoot)
    $files = @()
    foreach ($item in $raw.Split(';')) {
        $p = $item.Trim()
        if (-not $p) { continue }
        $full = $(if ([System.IO.Path]::IsPathRooted($p)) { [System.IO.Path]::GetFullPath($p) } else { [System.IO.Path]::GetFullPath((Join-Path $root $p)) })
        if (-not $full.StartsWith($harness + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "CHAOS_COMPOSE_OVERLAYS 의 파일이 harness/ 밖이다: $p"
        }
        if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw "CHAOS_COMPOSE_OVERLAYS 의 파일이 없다: $p" }
        $files += $full
    }
    return $files
}

# 네이티브 호출 공용 래퍼. cmd /c로 감싸 스트림을 nul에 버린다. PowerShell에서 compose를 직접
# 호출하면 재기동한 컨테이너가 상속한 출력 핸들 때문에 파이프가 EOF에 도달하지 않아 -d(detach)
# 인데도 반환하지 않고 매달린다(실측). Get-LeaderContainer의 docker logs와 동일한 공용 우회다.
# 반환은 종료코드다. 호출부는 반드시 변수에 받는다. 미포획이면 상위 함수의 반환값에 섞여 배열이 된다.
function Invoke-ComposeQuiet {
    param([string] $ComposeArgs)
    $overlay = (@(Get-ComposeOverlayFiles) | ForEach-Object { "-f `"$_`"" }) -join ' '
    $cmd = "docker compose -p $($Global:CHAOS.Project) -f `"$($Global:CHAOS.ComposeFile)`" $overlay --env-file `"$($Global:CHAOS.EnvFile)`" $ComposeArgs"
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
    $bots = $Global:CHAOS.BotsContainer
    $code = Invoke-DockerQuiet "stop $bots"
    if ($code -ne 0) {
        Write-Host "경고: $bots 정지 실패(종료코드 $code). 부하가 계속되는 상태로 드레인한다" -ForegroundColor Yellow
        return $false
    }
    Write-Host "봇 정지(docker stop $bots). 드레인 창 동안 신규 쓰기 없음"
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
        $bots = $Global:CHAOS.BotsContainer
        $code = Invoke-DockerQuiet "start $bots"
        if ($code -eq 0) { Write-Host "봇 재개(docker start $bots, 정지 전 ops 그대로)" }
        else { Write-Host "경고: $bots 재개 실패(종료코드 $code). 다음 실험 전 수동 확인" -ForegroundColor Yellow }
    } catch {
        Write-Host "경고: $($Global:CHAOS.BotsContainer) 재개 실패($($_.Exception.Message)). 다음 실험 전 수동 확인" -ForegroundColor Yellow
    }
}

# WebSocket 중계 오버레이(ADR 0034 S6, 마켓분할_리더_S6_설계 4-19). up.ps1 -Relay, adr0022-stage5-chaos.ps1 -Relay,
# s6-relay-outage.ps1 이 쓴다.
$Global:CHAOS_RELAY_OVERLAY = 'harness/docker-compose.relay.yml'

# 오버레이 목록을 중계 오버레이로 채운다. 다른 값이 이미 있으면 멈춘다(무엇이 겹칠지 모르는 채 스택을 세우지 않는다).
# 브로커 비밀 값(CHAOS_RELAY_PASSCODE)이 env 파일에 있는지는 compose 의 필수 변수 검사(config --quiet)로 본다.
# 이 함수는 env 파일을 읽지 않고, 값이 풀린 설정을 출력하지 않는다.
function Set-RelayOverlay {
    $cur = $env:CHAOS_COMPOSE_OVERLAYS
    if (-not [string]::IsNullOrWhiteSpace($cur) -and $cur.Trim() -ne $Global:CHAOS_RELAY_OVERLAY) {
        throw "CHAOS_COMPOSE_OVERLAYS 가 이미 다른 값이다($cur). 중계 오버레이만 겹친다"
    }
    $env:CHAOS_COMPOSE_OVERLAYS = $Global:CHAOS_RELAY_OVERLAY
    $code = Invoke-ComposeQuiet 'config --quiet'
    if ($code -ne 0) {
        throw "중계 오버레이 compose 해석 실패(종료코드 $code). harness/.env.chaos 에 CHAOS_RELAY_PASSCODE 가 있는지 본다"
    }
}

# 오버레이 목록에 중계 오버레이가 들어 있는가. 기본 모드 실험(W0)이 남은 목록으로 중계 스택을 세우지 않게 막는 데 쓴다.
function Test-RelayOverlayListed {
    return @(Get-ComposeOverlayFiles | Where-Object { $_ -like '*docker-compose.relay.yml' }).Count -gt 0
}

# 스택을 세운 뒤 오버레이가 실제로 걸렸는지 본다(설계 4-19). 셋 가운데 하나라도 아니면 그 실험은 BLOCKED 다.
#   1) chaos-broker 가 떠 있고 헬스체크가 healthy
#   2) chaos-api-1·chaos-api-2 의 환경에 EXCHANGE_WS_RELAY_MODE=broker
#   3) chaos-lb 가 nginx.relay.conf 를 /etc/nginx/nginx.conf 로 마운트
# 환경은 모드 한 줄만 고르는 템플릿으로 읽는다. 비밀 값 줄은 이 프로세스로 오지 않는다.
function Test-RelayOverlay {
    param([int] $TimeoutSec = 120)
    $res = [ordered]@{ at = (Get-Date).ToUniversalTime().ToString('o') }
    $healthy = Wait-For { (& docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' chaos-broker 2>$null | Out-String).Trim() -eq 'healthy' } -TimeoutSec $TimeoutSec -PollSec 2
    $res['brokerHealthy'] = [bool]$healthy
    $modeTemplate = '{{range .Config.Env}}{{if eq (index (split . `=`) 0) `EXCHANGE_WS_RELAY_MODE`}}{{println .}}{{end}}{{end}}'
    $modes = [ordered]@{}
    foreach ($c in 'chaos-api-1', 'chaos-api-2') {
        $lines = @(& docker inspect -f $modeTemplate $c 2>$null | Where-Object { $_ })
        $modes[$c] = $(if ($lines.Count -eq 1) { "$($lines[0])".Substring('EXCHANGE_WS_RELAY_MODE='.Length).Trim() } else { $null })
    }
    $res['appModes'] = $modes
    $mounts = @(& docker inspect -f '{{range .Mounts}}{{.Source}}|{{.Destination}}{{println}}{{end}}' chaos-lb 2>$null |
        Where-Object { $_ -like '*|/etc/nginx/nginx.conf' })
    $res['lbConfSource'] = $(if ($mounts.Count -eq 1) { "$($mounts[0])".Split('|')[0] } else { $null })
    $res['lbRelayConf'] = $mounts.Count -eq 1 -and "$($mounts[0])" -match 'nginx\.relay\.conf\|'
    $res['ok'] = $res['brokerHealthy'] -and @($modes.Values | Where-Object { $_ -ne 'broker' }).Count -eq 0 -and $res['lbRelayConf']
    return $res
}

# P3(파티션 리더 모드) 오버레이(ADR 0034 S7, 마켓분할_리더_S7_설계 4-9). 중계 오버레이 뒤에 P3 오버레이를 겹친다. compose 는 같은
# 컨테이너 경로의 마운트를 뒤 파일 것으로 바꾸므로 순서가 바뀌면 chaos-lb 가 nginx.relay.conf 로 뜬다. P3 에서는 두 노드가 모두 마켓
# 절반만 쥐어 WS 가 중계에만 기대므로 P3 오버레이는 중계 오버레이 없이 쓰지 않는다. up.ps1 -P3, adr0022-stage5-chaos.ps1 -P3,
# s7-p3-gate.ps1, s7-p3-demo.ps1 이 쓴다.
$Global:CHAOS_P3_OVERLAYS = 'harness/docker-compose.relay.yml;harness/docker-compose.p3.yml'
# 두 앱의 선호 파티션(docker-compose.p3.yml)과 JDBC 주소의 Toxiproxy 앞부분(docker-compose.chaos.yml). Test-P3Overlay 가 뜬 컨테이너의
# 환경과 대조한다. 주소를 바꾸면 E2(Q6)의 절단이 그 노드에 닿지 않고, ApplicationName 이 없으면 Q8 의 잠금 표본이 노드를 가르지 못한다.
$Global:CHAOS_P3_NODES = [ordered]@{
    'chaos-api-1' = @{ preferred = 'matching-p0'; jdbcPrefix = 'jdbc:postgresql://chaos-toxiproxy:15432/exchange?' }
    'chaos-api-2' = @{ preferred = 'matching-p1'; jdbcPrefix = 'jdbc:postgresql://chaos-toxiproxy:25432/exchange?' }
}

# 오버레이 목록을 중계;P3 순서로 채운다. 다른 값이 이미 있으면 멈춘다(중계 오버레이 하나만 있어도 멈춘다. 무엇이 겹칠지 모르는 채
# 스택을 세우지 않는다). 브로커 비밀 값이 env 파일에 있는지는 compose 의 필수 변수 검사(config --quiet)로 본다.
function Set-P3Overlay {
    $cur = $env:CHAOS_COMPOSE_OVERLAYS
    $normalized = ''
    if (-not [string]::IsNullOrWhiteSpace($cur)) {
        $normalized = (@($cur.Split(';') | ForEach-Object { $_.Trim() } | Where-Object { $_ }) -join ';')
    }
    if ($normalized -and $normalized -ne $Global:CHAOS_P3_OVERLAYS) {
        throw "CHAOS_COMPOSE_OVERLAYS 가 이미 다른 값이다($cur). P3 는 중계 오버레이와 P3 오버레이를 이 순서로만 겹친다"
    }
    $env:CHAOS_COMPOSE_OVERLAYS = $Global:CHAOS_P3_OVERLAYS
    $code = Invoke-ComposeQuiet 'config --quiet'
    if ($code -ne 0) {
        throw "P3 오버레이 compose 해석 실패(종료코드 $code). harness/.env.chaos 에 CHAOS_RELAY_PASSCODE 가 있는지 본다"
    }
}

# 오버레이 목록에 P3 오버레이가 들어 있는가. P3 가 아닌 실험이 남은 목록으로 P3 스택을 세우지 않게 막는 데 쓴다.
function Test-P3OverlayListed {
    return @(Get-ComposeOverlayFiles | Where-Object { $_ -like '*docker-compose.p3.yml' }).Count -gt 0
}

# 컨테이너 환경 변수 한 줄을 이름으로 골라 읽는다(Test-RelayOverlay 와 같은 템플릿). 다른 줄은 이 프로세스로 오지 않는다. 비밀 값이
# 든 이름은 읽지 않는다. 값이 없거나 두 줄 이상이면 $null 이다.
function Get-ContainerEnvValue {
    param([string] $Container, [string] $Name)
    if ($Name -notmatch '^[A-Z][A-Z0-9_]*$') { throw "환경 변수 이름 형식 위반: $Name" }
    if ($Name -match 'PASSWORD|PASSCODE|SECRET|KEY|TOKEN') { throw "비밀 값이 든 환경 변수는 읽지 않는다: $Name" }
    $template = '{{range .Config.Env}}{{if eq (index (split . `=`) 0) `' + $Name + '`}}{{println .}}{{end}}{{end}}'
    $lines = @(& docker inspect -f $template $Container 2>$null | Where-Object { $_ })
    if ($lines.Count -ne 1) { return $null }
    return "$($lines[0])".Substring($Name.Length + 1).Trim()
}

# JDBC 주소가 기대한 Toxiproxy 앞부분으로 시작하고 질의 인자에 ApplicationName=<컨테이너 이름> 이 있는가.
function Test-P3JdbcUrl {
    param([string] $Url, [string] $Prefix, [string] $Container)
    if ([string]::IsNullOrWhiteSpace($Url) -or -not $Url.StartsWith($Prefix, [System.StringComparison]::Ordinal)) { return $false }
    $query = $Url.Substring($Prefix.Length)
    return @($query.Split('&') | Where-Object { $_ -ceq "ApplicationName=$Container" }).Count -eq 1
}

# 스택을 세운 뒤 P3 오버레이가 실제로 걸렸는지 본다(설계 4-9). 하나라도 아니면 그 실험은 BLOCKED 다.
#   1) chaos-broker 가 healthy, 2) 두 앱의 EXCHANGE_WS_RELAY_MODE=broker(Test-RelayOverlay 의 두 확인)
#   3) chaos-lb 가 nginx.partition-relay.conf 를 /etc/nginx/nginx.conf 로 마운트(Test-RelayOverlay 의 nginx.relay.conf 확인 대신)
#   4) 두 앱의 EXCHANGE_LEADER_PARTITION_PREFERRED 가 matching-p0·matching-p1
#   5) 두 앱의 SPRING_DATASOURCE_URL 이 Toxiproxy 주소를 지키고 ApplicationName=<컨테이너 이름> 을 싣는다
# 환경은 변수 한 줄만 고르는 템플릿으로 읽는다. 비밀 값 줄은 이 프로세스로 오지 않는다.
function Test-P3Overlay {
    param([int] $TimeoutSec = 120)
    $relay = Test-RelayOverlay -TimeoutSec $TimeoutSec
    $res = [ordered]@{ at = $relay['at']; brokerHealthy = $relay['brokerHealthy']; appModes = $relay['appModes'] }
    $res['lbConfSource'] = $relay['lbConfSource']
    $res['lbPartitionRelayConf'] = -not [string]::IsNullOrWhiteSpace($relay['lbConfSource']) -and
        "$($relay['lbConfSource'])|" -match 'nginx\.partition-relay\.conf\|$'
    $preferred = [ordered]@{}; $jdbc = [ordered]@{}; $jdbcOk = [ordered]@{}
    foreach ($c in $Global:CHAOS_P3_NODES.Keys) {
        $want = $Global:CHAOS_P3_NODES[$c]
        $preferred[$c] = Get-ContainerEnvValue -Container $c -Name 'EXCHANGE_LEADER_PARTITION_PREFERRED'
        $jdbc[$c] = Get-ContainerEnvValue -Container $c -Name 'SPRING_DATASOURCE_URL'
        $jdbcOk[$c] = Test-P3JdbcUrl -Url $jdbc[$c] -Prefix $want.jdbcPrefix -Container $c
    }
    $res['preferred'] = $preferred
    $res['preferredOk'] = @($Global:CHAOS_P3_NODES.Keys | Where-Object { $preferred[$_] -ne $Global:CHAOS_P3_NODES[$_].preferred }).Count -eq 0
    $res['jdbcUrls'] = $jdbc
    $res['jdbcOk'] = $jdbcOk
    $res['ok'] = [bool]$res['brokerHealthy'] -and @($relay['appModes'].Values | Where-Object { $_ -ne 'broker' }).Count -eq 0 -and
        $res['lbPartitionRelayConf'] -and $res['preferredOk'] -and @($jdbcOk.Values | Where-Object { -not $_ }).Count -eq 0
    return $res
}

# P3 의 E4 epoch 규칙(하네스 검증설계 §5-1). 옛 epoch 뒤 그 자원의 lease_acquisitions ACQUIRE 행이 T5 까지 정확히 하나이고, 그 행이
# 모드 3 의 획득이며 holder 가 옛 holder 와 다르고, 생존 노드가 /actuator/partitions 에서 그 자원을 그 epoch 로 ACCEPTING 이라 알린다
# (holder 가 생존 노드). epoch 는 모든 자원을 통틀어 한 번씩 배정되므로 다른 파티션의 epoch 가 더 크면 P1·P2 의 '정확히 +1' 이 맞지
# 않는다. 읽지 못한 값은 참으로 세지 않는다.
function Test-P3AcquireOnce {
    param([string] $Resource, [long] $OldEpoch, [string] $OldHolder, [datetime] $Until, [int] $SurvivorPort)
    $res = [ordered]@{ resource = $Resource; oldEpoch = $OldEpoch; until = $null; acquires = $null; survivorEpoch = $null; ok = $false }
    if (@('matching-p0', 'matching-p1') -notcontains $Resource) { $res['사유'] = "P3 자원이 아니다: $Resource"; return $res }
    $untilIso = $Until.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fff') + 'Z'
    $res['until'] = $untilIso
    $sql = "SELECT coalesce(json_agg(json_build_object('epoch', epoch, 'holder', holder, 'protocol', protocol, " +
        "'acquired_at', acquired_at) ORDER BY epoch), '[]'::json) FROM lease_acquisitions WHERE resource = '$Resource' " +
        "AND origin = 'ACQUIRE' AND epoch > $OldEpoch AND acquired_at <= '$untilIso'"
    $raw = Invoke-Psql $sql
    if ([string]::IsNullOrWhiteSpace($raw)) { $res['사유'] = 'lease_acquisitions 를 읽지 못했다'; return $res }
    $rows = @($raw | ConvertFrom-Json)
    $res['acquires'] = $rows
    try {
        $parts = Invoke-RestMethod -Uri "http://localhost:$SurvivorPort/actuator/partitions" -TimeoutSec 5
        $mine = @($parts.partitions | Where-Object { $_.resource -eq $Resource -and $_.state -eq 'ACCEPTING' })
        if ($mine.Count -eq 1) { $res['survivorEpoch'] = [long]$mine[0].epoch }
    } catch { $res['survivorEpoch'] = $null }
    $one = $rows.Count -eq 1
    $res['exactlyOne'] = $one
    $res['acquiredInP3'] = $one -and [int]$rows[0].protocol -eq 3
    $res['holderChanged'] = $one -and "$($rows[0].holder)" -ne $OldHolder
    $res['holderIsSurvivor'] = $one -and $null -ne $res['survivorEpoch'] -and [long]$rows[0].epoch -eq [long]$res['survivorEpoch']
    $res['ok'] = $res['exactlyOne'] -and $res['acquiredInP3'] -and $res['holderChanged'] -and $res['holderIsSurvivor']
    return $res
}

# 파티션마다 마켓 심볼 목록(market_lease_partition, V48 시드). 읽지 못하면 $null 이다. 판정이 마켓을 가를 때 시드를 복제하지 않고
# 스택의 표를 읽는다.
function Get-PartitionMarkets {
    $raw = Invoke-Psql ("SELECT coalesce(json_object_agg(resource, symbols), '{}'::json) FROM (SELECT p.resource, " +
        "json_agg(m.symbol ORDER BY m.id) AS symbols FROM market_lease_partition p JOIN markets m ON m.id = p.market_id " +
        "GROUP BY p.resource) x")
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    try { return ($raw | ConvertFrom-Json -AsHashtable) } catch { return $null }
}

# 두 노드의 /actuator/partitions 응답. 답하지 않는 노드는 $null 이다.
function Get-NodePartitions {
    $out = [ordered]@{}
    foreach ($n in @('chaos-api-1', 'chaos-api-2')) {
        $port = $(if ($n -eq 'chaos-api-1') { $Global:CHAOS.Api1Port } else { $Global:CHAOS.Api2Port })
        try { $out[$n] = Invoke-RestMethod -Uri "http://localhost:$port/actuator/partitions" -TimeoutSec 5 } catch { $out[$n] = $null }
    }
    return $out
}

# 그 자원을 ACCEPTING 으로 쥔 노드 하나. 없거나 둘이면 $null 이다(s7_axes.owner_of 와 같은 규칙).
function Get-PartitionOwner {
    param($Partitions, [string] $Resource)
    $found = @()
    foreach ($n in $Partitions.Keys) {
        $p = $Partitions[$n]
        if ($p -and @($p.partitions | Where-Object { $_.resource -eq $Resource -and $_.state -eq 'ACCEPTING' }).Count -gt 0) { $found += $n }
    }
    if ($found.Count -eq 1) { return $found[0] }
    return $null
}

# 노드 안에서 파티션 참여를 켜거나 끈다(POST /actuator/partitions 는 loopback 전용, S5 설계 4-9). 반환은 HTTP 상태 문자열이다.
function Set-PartitionParticipation {
    param([string] $Container, [string] $Resource, [bool] $Participate)
    if (@('chaos-api-1', 'chaos-api-2') -notcontains $Container) { throw "앱 노드가 아니다: $Container" }
    if (@('matching-p0', 'matching-p1') -notcontains $Resource) { throw "P3 자원이 아니다: $Resource" }
    $body = '{"resource":"' + $Resource + '","participate":' + $(if ($Participate) { 'true' } else { 'false' }) + '}'
    $code = (& docker exec $Container curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' `
        -d $body 'http://localhost:8080/actuator/partitions' 2>$null | Out-String).Trim()
    return $code
}

# 두 파티션을 한 노드로 모은다(S7 설계 5-2 Q3b). 다른 노드가 쥔 파티션은 그 노드의 참여를 꺼서 넘기고, 목표 노드가 얻으면 다시
# 켠다. 인계는 TTL 만료와 비선호 유예로만 일어나고, 참여를 다시 켜도 되찾지 않는다(결정 4). 반환은 기록이고 ok 가 거짓이면 멈춘다.
function Move-PartitionsTo {
    param([string] $Node, [int] $TimeoutSec = 90)
    $res = [ordered]@{ node = $Node; moves = @(); ok = $false }
    foreach ($r in @('matching-p0', 'matching-p1')) {
        $owner = Get-PartitionOwner -Partitions (Get-NodePartitions) -Resource $r
        if ($owner -eq $Node) { continue }
        if (-not $owner) { $res['사유'] = "$r 소유 노드가 없다"; return $res }
        $off = Set-PartitionParticipation -Container $owner -Resource $r -Participate $false
        $got = Wait-For { (Get-PartitionOwner -Partitions (Get-NodePartitions) -Resource $r) -eq $Node } -TimeoutSec $TimeoutSec -PollSec 1
        $on = Set-PartitionParticipation -Container $owner -Resource $r -Participate $true
        $res['moves'] += [ordered]@{ resource = $r; from = $owner; participateOff = $off; participateOn = $on
            movedAt = $(if ($got) { $got.ToString('o') } else { $null }) }
        if (-not $got) { $res['사유'] = "$r 가 $TimeoutSec 초 안에 $Node 로 옮지 않았다"; return $res }
    }
    $final = Get-NodePartitions
    $res['ok'] = (Get-PartitionOwner -Partitions $final -Resource 'matching-p0') -eq $Node -and
        (Get-PartitionOwner -Partitions $final -Resource 'matching-p1') -eq $Node
    return $res
}

# Q0 준비 판정(S7 설계 5-2 Q0, 하네스 검증설계 §3 P3 실험). 전환 도구 출력(protocol.json)을 읽고 스택의 현재 상태를 본 뒤 Dir 에
# p3-preflight.json 을 남긴다. 축은 모드 3, 선호 배정(api-1 matching-p0, api-2 matching-p1. 재배치 여부는 전환 도구 기록으로 남긴다),
# P3 오버레이(Test-P3Overlay: 브로커, broker 모드, LB 합본 마운트, 선호 설정, JDBC 주소), FEE_INCOME 샤드 1 계정 수 = 샤드 0 계정 수,
# 두 노드 exchange_leader_protocol_max 3, 사전 권위 run, 전환 직후 구간 경계 표본이다. 하나라도 거짓이면 그 실험은 BLOCKED 다.
# 사전 권위 run 은 봇 부하 중이라 실험 판정과 같은 게이팅 술어(Test-ExperimentIntegrity)로 보고 원 판정(verdict)을 함께 적는다.
function Invoke-P3Preflight {
    param([string] $Dir, [string] $TransitionFile, [string] $RunId)
    $res = [ordered]@{ at = (Now-Iso); transitionFile = $TransitionFile }
    $axes = [ordered]@{}
    $transition = $null
    if (Test-Path -LiteralPath $TransitionFile) {
        try { $transition = Get-Content -LiteralPath $TransitionFile -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable } catch { $transition = $null }
    }
    $axes['transition_ok'] = [bool]($transition -and $transition['ok'])
    $axes['segment_sampled_after_cutover'] = [bool]($transition -and $transition['segment_after_cutover'] -and $transition['segment_after_cutover']['ok'])
    $res['assignmentAtTransition'] = $(if ($transition) { [ordered]@{
        first = $transition['assignment_first']; relocations = $transition['relocations']; final = $transition['assignment'] } } else { $null })
    $res['relocated'] = [bool]($transition -and $transition['assignment_first'] -and $transition['assignment_first']['state'] -eq 'relocate')

    $protocol = Get-Scalar "SELECT protocol FROM leader_protocol"
    $axes['leader_protocol_3'] = $null -ne $protocol -and [int]$protocol -eq 3
    $parts = Get-NodePartitions
    $res['partitions'] = $parts
    $axes['api1_holds_p0'] = (Get-PartitionOwner -Partitions $parts -Resource 'matching-p0') -eq 'chaos-api-1'
    $axes['api2_holds_p1'] = (Get-PartitionOwner -Partitions $parts -Resource 'matching-p1') -eq 'chaos-api-2'

    $overlay = Test-P3Overlay
    $res['overlay'] = $overlay
    $axes['broker_healthy'] = [bool]$overlay['brokerHealthy']
    $axes['apps_broker_mode'] = @($overlay['appModes'].Values | Where-Object { $_ -ne 'broker' }).Count -eq 0
    $axes['lb_partition_relay_conf'] = [bool]$overlay['lbPartitionRelayConf']
    $axes['preferred_settings'] = [bool]$overlay['preferredOk']
    $axes['jdbc_toxiproxy_and_application_name'] = @($overlay['jdbcOk'].Values | Where-Object { -not $_ }).Count -eq 0

    $feeRaw = Invoke-Psql ("SELECT json_build_object('shard0', count(*) FILTER (WHERE shard = 0), 'shard1', count(*) FILTER (WHERE shard = 1)) " +
        "FROM accounts WHERE owner_type = 'SYSTEM' AND purpose = 'FEE_INCOME'")
    $fee = $null
    if (-not [string]::IsNullOrWhiteSpace($feeRaw)) { try { $fee = $feeRaw | ConvertFrom-Json } catch { $fee = $null } }
    $res['feeIncomeAccounts'] = $fee
    $axes['fee_income_shard1_equals_shard0'] = $null -ne $fee -and [long]$fee.shard0 -gt 0 -and [long]$fee.shard1 -eq [long]$fee.shard0

    $gauges = [ordered]@{}
    foreach ($n in @('chaos-api-1', 'chaos-api-2')) {
        $port = $(if ($n -eq 'chaos-api-1') { $Global:CHAOS.Api1Port } else { $Global:CHAOS.Api2Port })
        $gauges[$n] = Get-AppMetric -Port $port -Pattern 'exchange_leader_protocol_max(?:\{[^}]*\})?\s+([-0-9.eE]+)'
        $axes["protocol_max_3_$n"] = $null -ne $gauges[$n] -and [double]$gauges[$n] -eq 3
    }
    $res['protocolMax'] = $gauges

    $run = $null
    try { $run = Invoke-InvariantRun -Port $Global:CHAOS.Api1Port -RunId $RunId -Window 'q0' } catch { $run = $null }
    $res['preRun'] = $(if ($run) { [ordered]@{ runId = $RunId; verdict = $run.verdict; gatedViolations = (Get-GatedViolationSum $run)
        clean = (Test-ExperimentIntegrity $run) } } else { [ordered]@{ runId = $RunId; error = '권위 run 호출 실패' } })
    $axes['pre_authoritative_run_clean'] = [bool]($run -and (Test-ExperimentIntegrity $run))

    $res['axes'] = $axes
    $res['failed'] = @($axes.Keys | Where-Object { -not $axes[$_] })
    $res['ok'] = $res['failed'].Count -eq 0
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $Dir 'p3-preflight.json'), ($res | ConvertTo-Json -Depth 10), [System.Text.UTF8Encoding]::new($false))
    return $res
}

# 부하 계정을 그 마켓들로 다시 발급한다(harness/scale/clean_suite.py reissue_keys). 키 파일(harness/load/secrets/k6_keys.json)은
# 파이썬이 쓰고 이 함수는 개수만 받는다. 볼륨을 새로 세운 스택마다 부른다. 반환은 발급한 계정 수(실패면 0)다.
function Initialize-LoadKeys {
    param([string] $Markets, [int] $Count = 4)
    if ($Markets -notmatch '^[A-Z0-9]+-[A-Z0-9]+(,[A-Z0-9]+-[A-Z0-9]+)*$') { throw "마켓 목록 형식 위반: $Markets" }
    $prevMarkets = $env:SCALE_MARKETS; $prevKeys = $env:SCALE_LOAD_KEYS
    $env:SCALE_MARKETS = $Markets; $env:SCALE_LOAD_KEYS = "$Count"
    try {
        $scale = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\scale'))
        $code = "import sys; sys.path.insert(0, r'$scale'); import clean_suite as c; print(c.reissue_keys())"
        $out = (& python -c $code 2>$null | Out-String).Trim()
        if ($out -match '^\d+$') { return [int]$out }
        return 0
    } finally {
        $env:SCALE_MARKETS = $prevMarkets; $env:SCALE_LOAD_KEYS = $prevKeys
    }
}

# 생존 파티션 마켓만 치는 k6 개방 루프(하네스 검증설계 §3 P3 실험 공통 판정의 ACK 간격 부하, S7 설계 5-2). calibrate.js(order_flow.js 의
# placeOrder·readMarket)를 MARKET_HINT=1 로 LB 에 돌린다. 부하 계정은 Initialize-LoadKeys 로 먼저 발급한다. 컨테이너 이름은
# pml-s7-k6-<실험>이라 chaos- 접두가 아니다. 네트워크에 붙어 있으면 down 이 그 네트워크를 지우지 못하므로 다음 down -v 전에 멈춘다.
# -Hint 0 은 힌트 헤더를 싣지 않는 부하다(Q15, 설계 4-9 '힌트 없는 쓰기').
function Start-SurvivorLoad {
    param([string] $Name, [string] $Markets, [int] $Ops, [int] $DurationSec, [string] $RunId, [ValidateSet(0, 1)][int] $Hint = 1)
    if ($RunId -notmatch '^[A-Za-z0-9_-]{1,16}$') { throw "k6 주행 이름 형식 위반(1~16자): $RunId" }
    $load = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\load'))
    & docker rm -f $Name *> $null
    $dockerArgs = @('run', '-d', '--name', $Name, '--network', $Global:CHAOS.Network, '-v', "${load}:/load:ro",
        '-e', "BASE_OPS=$Ops", '-e', "MARKETS=$Markets", '-e', "MARKET_HINT=$Hint", '-e', "CAL_DURATION=${DurationSec}s",
        '-e', 'BASE_URL=http://chaos-lb:8080', '-e', 'K6_KEYS=/load/secrets/k6_keys.json', '-e', 'CAL_OUT=/tmp/calibration.json',
        '-e', "RUN_ID=$RunId", 'grafana/k6:1.4.0', 'run', '--quiet', '--no-color', '/load/calibrate.js')
    & docker @dockerArgs *> $null
    return [ordered]@{ name = $Name; rc = $LASTEXITCODE; markets = $Markets; ops = $Ops; hint = $Hint; durationSec = $DurationSec; startedAt = (Now-Iso) }
}

# k6 개방 루프를 멈추고 로그를 저장한 뒤 지운다. 로그는 요청 식별자를 담을 수 있어 저장소 밖 경로를 준다.
function Stop-SurvivorLoad {
    param([string] $Name, [string] $LogPath)
    & docker stop -t 10 $Name *> $null
    if ($LogPath) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LogPath) | Out-Null
        & cmd /c "docker logs $Name > `"$LogPath`" 2>&1"
    }
    & docker rm -f $Name *> $null
}

# k6 관찰자(harness/load/ws_relay_watch.js) 컨테이너. 실험 네트워크(<프로젝트>_chaos-net)에 붙어 노드 주소로 직접 붙는다.
# 이름은 pml-s6-watch-<실험>이라 chaos- 접두가 아니다(실행기의 시작 전 검사와 겹치지 않는다). 관찰자는 다음 down -v 전에 반드시
# 멈춘다. 네트워크에 붙어 있으면 down 이 그 네트워크를 지우지 못한다. 상태 줄은 --log-format json 콘솔 로그로 나온다.
function Start-RelayWatch {
    param([string] $Name, [string] $OutDir, [int] $DurationSec,
          [string] $Urls = 'ws://chaos-api-1:8080/ws/v1,ws://chaos-api-2:8080/ws/v1,ws://chaos-lb:8080/ws/v1',
          [string] $Markets = 'KRW-BTC,KRW-ETH,KRW-XRP')
    $load = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\load'))
    $out = [System.IO.Path]::GetFullPath($OutDir)
    & docker rm -f $Name *> $null
    $dockerArgs = @('run', '-d', '--name', $Name, '--network', $Global:CHAOS.Network,
        '-v', "${load}:/scripts:ro", '-v', "${out}:/out", 'grafana/k6:1.4.0', 'run', '--log-format', 'json', '--quiet',
        '-e', "WS_URLS=$Urls", '-e', "MARKETS=$Markets", '-e', "DURATION=${DurationSec}s", '-e', 'OUT=/out/watch-summary.json',
        '/scripts/ws_relay_watch.js')
    $id = (& docker @dockerArgs 2>&1 | Out-String).Trim()
    return [ordered]@{ name = $Name; rc = $LASTEXITCODE; id = $id; startedAt = (Get-Date).ToUniversalTime().ToString('o') }
}

# 관찰자를 SIGINT 로 멈춰 마지막 상태 줄과 끝 요약을 쓰게 한 뒤 로그를 저장하고 지운다. 기다려도 안 끝나면 지운다.
function Stop-RelayWatch {
    param([string] $Name, [string] $LogPath, [int] $GraceSec = 30)
    & docker kill --signal INT $Name *> $null
    $deadline = (Get-Date).AddSeconds($GraceSec)
    while ((Get-Date) -lt $deadline) {
        if ((& docker inspect -f '{{.State.Running}}' $Name 2>$null | Out-String).Trim() -ne 'true') { break }
        Start-Sleep -Seconds 1
    }
    & cmd /c "docker logs $Name > `"$LogPath`" 2>&1"
    & docker rm -f $Name *> $null
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
            $map = Read-EnvFile $Global:CHAOS.EnvFile
            # Patroni 스택의 env 파일은 PATRONI_LEASE_TTL 로 같은 값을 준다(harness/patroni/.env.patroni.example).
            $raw = $map['CHAOS_LEASE_TTL']
            if (-not $raw) { $raw = $map['PATRONI_LEASE_TTL'] }
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

# DB 직조회(권위 판정).
function Invoke-Psql {
    param([string] $Sql)
    # -q가 없으면 psql이 명령 완료 태그를 함께 낸다. -t -A는 질의 결과의 헤더·푸터만 죽이고
    # UPDATE·INSERT의 "UPDATE 0" 같은 상태 줄은 남긴다. 그 줄이 빈 결과 판정과 필드 분해를
    # 통째로 망가뜨린다(빈 큐 폴이 "UPDATE 0"을 반환해 [long] 캐스팅에서 종료 오류).
    # 현 호출부는 전부 SELECT라 -q가 튜플 출력을 바꾸지 않는다.
    # CHAOS_PSQL_TIMEOUT_SEC 를 주면 연결 5초, 질의 전체를 그 초로 자른다(컨테이너 안 timeout). Patroni 스택의 psql 은
    # HAProxy 를 거치는데, Primary 를 강제 종료한 순간 연결이 끝없이 매달린 적이 있다(2026-09-25 E-P1 첫 회차). 주지 않으면 종전과 같다.
    $timeout = [Environment]::GetEnvironmentVariable('CHAOS_PSQL_TIMEOUT_SEC')
    if ($timeout) {
        $out = & docker exec -e PGCONNECT_TIMEOUT=5 $Global:CHAOS.Pg timeout ([int]$timeout) psql -U $Global:CHAOS.PgUser -d $Global:CHAOS.PgDb -q -t -A -c $Sql 2>$null
    } else {
        $out = & docker exec $Global:CHAOS.Pg psql -U $Global:CHAOS.PgUser -d $Global:CHAOS.PgDb -q -t -A -c $Sql 2>$null
    }
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
    param([string] $Resource)
    $query = if ([string]::IsNullOrWhiteSpace($Resource)) { '' } else { "?resource=$Resource" }
    foreach ($p in @($Global:CHAOS.Api1Port, $Global:CHAOS.Api2Port)) {
        try {
            Invoke-RestMethod -Uri "http://localhost:$p/harness/v1/status$query" -TimeoutSec 3 | Out-Null
            return $p
        } catch { }
    }
    throw '살아있는 api 노드 없음(양 노드 응답 실패)'
}

# 파티션 리더 모드(P3)에서는 resource 가 필수다(서버가 400). P1·P2 에서는 비우면 종전 모양이다.
function Get-HarnessStatus {
    param([int] $Port, [string] $Resource)
    $query = if ([string]::IsNullOrWhiteSpace($Resource)) { '' } else { "?resource=$Resource" }
    return Invoke-RestMethod -Uri "http://localhost:$Port/harness/v1/status$query" -TimeoutSec 5
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
    param([int] $Port, [hashtable] $Body, [string] $Resource)
    # 측정한 리스 자원을 싣는다(V53, S4 설계 4-18). 없으면 서버가 P1·P2 에서 matching-engine 으로 적고 P3 에서 400 이다.
    # 호출부가 바디에 이미 넣었으면 그대로 둔다.
    if (-not $Body.ContainsKey('resource')) { $Body['resource'] = Get-LeaseResource -Resource $Resource }
    $json = $Body | ConvertTo-Json -Depth 6
    $resp = Invoke-RestMethod -Uri "http://localhost:$Port/harness/v1/failover" -Method Post `
        -ContentType 'application/json' -Body $json -TimeoutSec 10
    # 판정 파일이 이 적재 본문을 함께 싣는다. failover_runs 행은 down -v 로 파기된다.
    $Global:CHAOS_LAST_FAILOVER = @{ body = $Body; response = $resp }
    return $resp
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
# event_journal.fencing_epoch 축은 승격 재생 산출물을 재개로 집어 RTO를 과소 보고한다. 폐기 술어
# 둘과 실측 대조, 등가(=)가 아니라 초과(>)를 쓰는 이유는 모두 그 절에 있다.
# 조인 전환으로 생긴 결합: 이 측정은 C12(고아 이벤트 0행)에 의존한다. 대응 command 행이 없는
# TRADE가 생기면 inner join이 그것을 버려 RTO가 n/a가 되고 판정이 FAIL로 떨어진다(fail-closed).
#
# 파티션 자원(S4 설계 4-18, 하네스 검증설계 §5-1)이면 그 파티션 마켓의 체결만 본다. 한 사건이 파티션 하나만 옮기므로 다른
# 파티션의 체결을 재개로 집으면 RTO 를 과소 보고한다. matching-engine 이면 문장이 종전과 바이트 단위로 같다.
function Get-FirstTradeSql {
    param([long] $OldEpoch, [string] $T0Iso, [string] $Resource = 'matching-engine')
    $sql = "SELECT min(e.created_at) AT TIME ZONE 'UTC' FROM event_journal e " +
        "JOIN command_journal c ON c.seq = e.source_cmd_seq " +
        "WHERE e.event_type='TRADE' AND c.fencing_token > $OldEpoch AND e.created_at > '$T0Iso'"
    if ($Resource -ne 'matching-engine') {
        $sql += " AND e.market_id IN (SELECT market_id FROM market_lease_partition WHERE resource = '$Resource')"
    }
    return $sql
}

# 모드가 쓰는 리스 자원 목록(S4 설계 4-18). P1·P2 는 matching-engine 하나, P3 은 matching-p0·matching-p1 이다. 모드를
# 읽지 못하면 멈춘다.
function Get-ModeLeaseResources {
    $protocol = [int](Get-Scalar "SELECT protocol FROM leader_protocol" -Required)
    if ($protocol -ge 3) { return @('matching-p0', 'matching-p1') }
    return @('matching-engine')
}

# 실험이 볼 리스 자원(S4 설계 4-18). leader_protocol 이 1·2 면 matching-engine 이고, 3(파티션 리더 모드)이면 -Resource 가
# 필수다. 한 사건이 파티션 하나만 옮기므로 어느 자원을 보는지 모른 채 판정하면 다른 파티션의 리더를 본다. 모드를 읽지
# 못하면 멈춘다. 1 로 가정하면 P3 스택에서 matching-engine 행을 보며 공허하게 통과한다.
function Get-LeaseResource {
    param([string] $Resource)
    $protocol = [int](Get-Scalar "SELECT protocol FROM leader_protocol" -Required)
    if ($protocol -ge 3) {
        if ([string]::IsNullOrWhiteSpace($Resource)) {
            throw "leader_protocol 이 3(파티션 리더 모드)이라 -Resource(matching-p0 또는 matching-p1)가 필수다"
        }
        if (@('matching-p0', 'matching-p1') -notcontains $Resource) { throw "P3 에 없는 리스 자원: $Resource" }
        return $Resource
    }
    if (-not [string]::IsNullOrWhiteSpace($Resource) -and $Resource -ne 'matching-engine') {
        throw "P$protocol 에 없는 리스 자원: $Resource"
    }
    return 'matching-engine'
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
        [int] $StandbyPort = 0, [int] $RestoreOps = 0, [string] $Resource
    )
    # 이 사건이 옮기는 리스 자원(S4 설계 4-18). P1·P2 는 matching-engine 이고 P3 에서는 -Resource 가 필수다.
    $leaseResource = Get-LeaseResource -Resource $Resource
    # 스탠바이 포트를 api-2로 하드코딩하면 리더가 api-2일 때(앞선 실험이 그렇게 남긴 경우)
    # 방금 죽인 노드를 폴링하게 되어 승격을 영영 못 보고 RTO가 n/a로 남는다. 호출부가 실제
    # 스탠바이를 알고 있으면 그것을 쓰고, 없으면 기본 api-2 포트로 떨어진다.
    $standbyPort = if ($StandbyPort -gt 0) { $StandbyPort } else { $Global:CHAOS.Api2Port }

    Write-Section 'ⓒ 관찰(개입 금지)'
    $t2 = Wait-For {
        try { $s = Get-HarnessStatus -Port $standbyPort -Resource $leaseResource; ($s.epoch -gt $OldEpoch) -and $s.leaderAlive }
        catch { $false }
    } -TimeoutSec $ObserveSec -PollSec 0.5
    $statusAfter = Get-HarnessStatus -Port (Get-LiveNodePort -Resource $leaseResource) -Resource $leaseResource
    $newEpoch = [long]$statusAfter.epoch
    $newHolder = $statusAfter.holder

    # T5 = 페일오버 후 최초 신규 TRADE의 engine_ts(§5-1 정의). created_at은 DB now()가 아니라
    # 엔진 처리 시각이라 커밋 가시화 지연과 무관한 재개 시점을 가리킨다.
    $t0Iso = $T0.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fff') + 'Z'
    $firstTradeSql = Get-FirstTradeSql -OldEpoch $OldEpoch -T0Iso $t0Iso -Resource $leaseResource
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
        # 자원을 넘긴다. P3 에서는 자원 없는 /harness/v1/status 가 400 이라 두 노드가 모두 죽은 것으로 보인다(S7 설계 4-13).
        $postPort = Get-LiveNodePort -Resource $leaseResource
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

    # epoch는 단조 상승만 본다. 승격 재생 반납으로 한 사건에서 epoch가 +2 이상 오를 수 있어
    # (DB 설계 §5.4) 정확히 +1을 요구하면 정상 경로가 FAIL로 뒤집힌다.
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
    $rec = Record-Failover -Port $postPort -Body $body -Resource $leaseResource

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

# ── 확장 유틸(E1·E3·E5·E7·E8·E9 전용). E2·E4·E6은 위 함수만 쓴다. ──

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

# 정렬은 배열 강제(@)로 단일 표본이 스칼라가 되는 것을 막는다.
function Get-Percentile {
    param([double[]] $Values, [double] $P = 99)
    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $rank = [int][math]::Ceiling(($P / 100.0) * $sorted.Count)
    if ($rank -lt 1) { $rank = 1 }
    if ($rank -gt $sorted.Count) { $rank = $sorted.Count }
    return [double] $sorted[$rank - 1]
}

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

# command_journal에 명령 축 fencing 컬럼(fencing_token)이 있는지(실스키마 대조). 실DDL 정본:
# command_journal.fencing_token(= epoch 값), event_journal.fencing_epoch. 정본 §4-1 C22 주석은
# 이를 command_journal.fencing_epoch로 오기(§5-1 T5는 fencing_token으로 정확). 실스키마가 권위다.
#
# -Required 필수. 종전에는 Invoke-Psql을 직접 썼고 그 함수는 stderr를 버리므로 psql 무응답이
# 빈 문자열로 왔다. PowerShell에서 [decimal]'' -gt 0은 예외가 아니라 False라, 관측 실패가
# 그대로 "명령 축 없음"이 되어 아래 판정이 축 하나를 통째로 건너뛰었다.
function Test-CommandFencingColumn {
    $v = [long](Get-Scalar "SELECT count(*) FROM information_schema.columns WHERE table_name='command_journal' AND column_name='fencing_token'" -Required)
    return ($v -gt 0)
}

# 구 epoch가 쓴 저널 행 수. 주입 직후와 관찰 후에 각각 재고 그 차가 스테일 쓰기다.
#
# 왜 위치 워터마크를 쓰지 않는가. 종전에는 주입 직후 max(seq)와 max(event_seq)를 잡고 그
# 이후 행만 셌다. 명령 축은 seq가 전역 단조라 성립하지만 이벤트 축은 V36 이후 성립하지 않는다.
# event_seq = market_id * band_span + market_seq라 max는 가장 큰 마켓 대역의 값이고, 그보다 낮은
# 대역의 마켓이 구 epoch로 append하면 그 행은 워터마크 아래라 세어지지 않는다(ADR 0022 §6-5).
# 게다가 둘 다 이미 물질화된 명령을 구 리더가 다시 적재하는 경우를 못 잡는다. 그 행의 seq는
# 워터마크 아래이기 때문이다.
#
# epoch 기준 증분은 축과 무관하다. 어느 대역이든, 새 행이든 재적재든, 구 epoch를 달고 들어온
# 것은 전부 차에 잡힌다.
function Get-EpochWriteCounts {
    param([long] $Epoch, [bool] $CmdHasFencing)
    # -Required 필수. 이 값들이 E5·E7의 지배 증거(split-brain 반증)라, 빈 결과를 0으로 읽으면
    # psql 무응답이 그대로 "이중 쓰기 0"이 되어 확정 위양성 PASS를 만든다.
    $cmd = $null
    if ($CmdHasFencing) {
        $cmd = [long](Get-Scalar "SELECT count(*) FROM command_journal WHERE fencing_token = $Epoch" -Required)
    }
    $evt = [long](Get-Scalar "SELECT count(*) FROM event_journal WHERE fencing_epoch = $Epoch" -Required)
    return [pscustomobject]@{ Cmd = $cmd; Evt = $evt }
}

# 기준선과 사후를 받아 스테일 쓰기 수를 낸다. 음수면 행이 사라진 것이고 그것도 이상이다.
function Get-StaleWriteDelta {
    param($Baseline, $After)
    $n = 0
    if ($null -ne $Baseline.Cmd -and $null -ne $After.Cmd) { $n += ($After.Cmd - $Baseline.Cmd) }
    $n += ($After.Evt - $Baseline.Evt)
    return $n
}

# 기준선이 판정 대상의 존재를 증명하는가. 구 리더가 주입 전에 쓴 것이 한 건도 없으면 술어가
# 틀렸거나(컬럼명·epoch 값) 그 회차에 부하가 실리지 않은 것이다. 둘 다 이후 델타 0을 공허하게
# 만든다. §22 양성 증거 축이다.
function Test-StaleWriteBaseline {
    param($Baseline, [bool] $CmdHasFencing)
    if ($CmdHasFencing -and ($null -eq $Baseline.Cmd -or $Baseline.Cmd -le 0)) { return $false }
    return ($Baseline.Evt -gt 0)
}

# 승격 후 최초 신규 TRADE의 engine_ts(§5-1 T5). 커밋 가시화 지연과 무관한 체결 재개 시점.
# 반환: UtcDateTime 또는 $null. 술어는 Get-FirstTradeSql 공용(재생 산출물 배제 근거는 그 주석).
function Wait-FirstTradeAfter {
    param([long] $OldEpoch, [datetime] $T0, [int] $TimeoutSec, [string] $Resource = 'matching-engine')
    $t0Iso = $T0.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fff') + 'Z'
    $sql = Get-FirstTradeSql -OldEpoch $OldEpoch -T0Iso $t0Iso -Resource $Resource
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
# 파티션 리더 모드(P3)에서는 -Resource 로 파티션을 고른다(Get-LeaseResource). holder 는 노드마다 하나라 그 노드의 모든
# 파티션이 같은 UUID 를 로그에 남긴다.
function Get-LeaderContainer {
    param([string[]] $Candidates, [string] $Resource)
    # 기본 후보는 대상 스택의 API 컨테이너다(카오스 스택은 chaos-api-1·chaos-api-2).
    if (-not $Candidates) { $Candidates = $Global:CHAOS.ApiContainers }
    $leaseResource = Get-LeaseResource -Resource $Resource
    $holder = Invoke-Psql "SELECT holder FROM leader_lease WHERE resource='$leaseResource' AND lease_until > now()"
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
# 한 세트다(정본 §4-3 각주).
#
# C22·C23은 면제가 아니라 게이팅 대상이다. 빼면 fencing 역행·채번 재사용이라는 CRITICAL 위반이
# 실험 사후 권위 run을 그대로 통과한다. 권위 run에서 오탐이 없는 근거는 §4-2 ④·§4-3 각주.
# 서버 Scope.AUTHORITATIVE_ONLY와 이 목록은 축이 다르다(어느 run에서 평가하는가 대 평가된 결과를
# 게이팅에 쓰는가). 1:1로 맞추면 C22·C23이 게이팅에서 빠진다.
#
# 목록을 전역 상수로 두는 이유는 인라인 사본이 반쪽 교정을 만들기 때문이다.
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
    # 공허 통과 차단. 체크가 하나도 안 돈 run을 통과로 보고하지 않는다(서버
    # AuthoritativeRun.pass와 동일 술어).
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

# 실험 판정을 저장소 파일로 남긴다. failover_runs·invariant_checks 는 카오스 볼륨 안이라 down -v 로
# 파기되므로 판정 시점에 함께 뽑는다(기획안 12절 A5). verdict 에는 종료코드를 정하는 값을 넘긴다.
# 저장 실패가 판정과 종료코드를 바꾸지 않게 하되, 조용히 삼키지 않고 붉게 알린다.
function Save-VerdictFile {
    param([string] $ExperimentId, [string] $RunId, [string] $Verdict, [hashtable] $Metrics)
    try {
        if ([string]::IsNullOrWhiteSpace($RunId)) {
            $RunId = "$ExperimentId-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
        }
        # 아래 SQL 에 그대로 들어가므로 형식을 먼저 막는다.
        if ($RunId -notmatch '^[A-Za-z0-9-]+$') { throw "runId 형식 위반: $RunId" }

        $repo = $Global:CHAOS.RepoRoot
        $commit = (& git -C $repo rev-parse HEAD 2>$null | Out-String).Trim()
        $dirty = $null
        if ($commit) {
            # 판정에 닿는 경로만 센다. 문서는 판정을 바꾸지 않고, 판정 파일 자신이 쌓이는 경로를
            # 세면 두 번째 실험부터 항상 dirty 다.
            $dirty = @(& git -C $repo status --porcelain -- server harness ':!harness/evidence' 2>$null).Count
        }
        # compose 는 셸 환경변수를 --env-file 값보다 앞세운다. 이미지를 환경변수로 고정한 회차가 파일 값으로 적히지 않게 한다.
        $imageName = $env:CHAOS_APP_IMAGE
        if ([string]::IsNullOrWhiteSpace($imageName)) { $imageName = (Read-EnvFile $Global:CHAOS.EnvFile)['CHAOS_APP_IMAGE'] }
        $imageId = (& docker image inspect $imageName --format '{{.Id}}' 2>$null | Out-String).Trim()

        $invSql = "SELECT coalesce(json_agg(json_build_object('window', details->>'window', " +
            "'check', check_name, 'ok', ok, 'violatedRows', violated_rows, 'checkedAt', checked_at) " +
            "ORDER BY checked_at, check_name), '[]'::json) FROM invariant_checks " +
            "WHERE details->>'run_id' = '$RunId'"
        $invRaw = Invoke-Psql $invSql
        $invariants = $null
        if (-not [string]::IsNullOrWhiteSpace($invRaw)) { $invariants = @($invRaw | ConvertFrom-Json) }

        $obj = [ordered]@{
            experimentId  = $ExperimentId
            runId         = $RunId
            verdict       = $Verdict
            writtenAt     = (Now-Iso)
            code          = [ordered]@{ commit = $commit; uncommittedPaths = $dirty; image = $imageName; imageId = $imageId }
            metrics       = $Metrics
            invariantRows = $(if ($null -ne $invariants) { $invariants.Count } else { $null })
            invariants    = $invariants
        }
        $last = $Global:CHAOS_LAST_FAILOVER
        if ($last -and $last.body.details.runId -eq $RunId) {
            $obj['failover'] = $last.body
            $obj['failoverRunId'] = $last.response.failover_run_id
        }

        $dir = $Global:CHAOS.EvidenceDir
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $path = Join-Path $dir "$RunId.json"
        $json = ([pscustomobject]$obj) | ConvertTo-Json -Depth 10
        [System.IO.File]::WriteAllText($path, $json, [System.Text.UTF8Encoding]::new($false))

        if ($null -eq $invariants) {
            Write-Host "경고: 판정 파일에 불변식 행을 싣지 못했다(psql 무응답). $path" -ForegroundColor Yellow
        } elseif ($invariants.Count -eq 0) {
            Write-Host "경고: run_id=$RunId 의 불변식 행이 0건이다. 판정 대상이 없는 파일이다. $path" -ForegroundColor Yellow
        } else {
            Write-Host "판정 파일: $path (불변식 행 $($invariants.Count))"
        }
    }
    catch {
        Write-Host "판정 파일 저장 실패: $($_.Exception.Message). 이 회차는 기획안 12절 A5 를 채우지 못한다" -ForegroundColor Red
    }
}

# 비페일오버·SKIP 실험의 verdict JSON을 표준 형식으로 stdout에 내고 판정 파일로 남긴다. failover_runs
# 적재는 안 한다(epoch 변화 없는 실험을 페일오버 행으로 남기면 증거가 오염된다). E1·E3·E8·E9가 쓴다.
# 이 실험들은 여기서 넘긴 verdict 가 곧 종료코드를 정한다.
function Write-Verdict {
    param([string] $ExperimentId, [string] $RunId, [string] $Verdict, [hashtable] $Metrics)
    $obj = [ordered]@{ experimentId = $ExperimentId; verdict = $Verdict; ts = (Now-Iso) }
    foreach ($k in $Metrics.Keys) { $obj[$k] = $Metrics[$k] }
    Write-Host ''
    Write-Host '── verdict(JSON) ───────────────────────' -ForegroundColor Green
    ([pscustomobject]$obj) | ConvertTo-Json -Depth 6 -Compress | Write-Host
    Write-Host '────────────────────────────────────────'
    Save-VerdictFile -ExperimentId $ExperimentId -RunId $RunId -Verdict $Verdict -Metrics $Metrics
}

# ── 시계 동기 전제조건 가드(조사 #58). 페일오버 승격 게이트 실험 전용. ──

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
    param([Parameter(Mandatory = $true)][string] $ExperimentId, [string] $RunId)
    $clock = Test-ClockSkew
    if (-not $clock.Reachable) {
        # 도달불가는 시계가드 소관 밖. 정상 진행시켜 기존 권위 run·주입 경로가 실패를 드러내게 둔다.
        Write-Host "시계가드: $($Global:CHAOS.Pg) 의 clock_timestamp 미응답, 스큐 판정 생략(왕복 $($clock.RoundTripMs)ms)." -ForegroundColor Yellow
        return $false
    }
    if (-not $clock.Skewed) {
        Write-Host ("시계가드 통과: 스큐 {0}ms, 임계 {1}ms, 왕복 {2}ms." -f $clock.SkewMs, $clock.ToleranceMs, $clock.RoundTripMs)
        return $false
    }
    Write-Verdict -ExperimentId $ExperimentId -RunId $RunId -Verdict 'SKIP' -Metrics @{
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
