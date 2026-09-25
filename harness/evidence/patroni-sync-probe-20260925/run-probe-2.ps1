# 2차 확인 실행기. WAL 을 쓰는 쓰기 가능 탐지 후보(pg_logical_emit_message)의 동기 대기와 권한, 그리고
# client_connection_check_interval 이 0 일 때 죽은 클라이언트의 행 잠금 대기가 남는지(1차 I-lock 의 대조군)를 본다.
# 사용: pwsh -NoProfile -File <이 폴더>\run-probe-2.ps1 *> <이 폴더>\probe-2-output.txt
# compose 프로젝트 exchange-patroni 만 다룬다.

$ErrorActionPreference = 'Stop'
$compose = Join-Path $PSScriptRoot 'compose.yml'
$pg1 = 'patroni-probe-pg1'
$pg2 = 'patroni-probe-pg2'

function Stamp([string]$what) { Write-Output ("[{0}] {1}" -f (Get-Date).ToUniversalTime().ToString('HH:mm:ss.fff'), $what) }
function Step([string]$name) { Stamp "단계 $name"; docker exec -u postgres $pg1 sh /probe/steps.sh $name 2>&1 | ForEach-Object { "  $_" } }
function Background([string]$name) { Stamp "백그라운드 $name"; docker exec -d -u postgres $pg1 sh /probe/steps.sh $name }
function WaitFor([scriptblock]$ok, [string]$what, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (& $ok) { Stamp "확인: $what"; return }
        Start-Sleep -Milliseconds 500
    }
    throw "시간 안에 확인하지 못함: $what"
}
function Streaming { (docker exec -u postgres $pg1 psql -X -U postgres -Atc "select count(*) from pg_stat_replication where application_name = 's1' and state = 'streaming'" 2>$null) -eq '1' }

try {
    Stamp '기동'
    docker compose -p exchange-patroni -f $compose up -d 2>&1 | ForEach-Object { "  $_" }
    WaitFor { (docker exec $pg1 pg_isready -U postgres 2>$null) -match 'accepting connections' } 'pg1 연결 수용' 90
    WaitFor { Streaming } 'pg2 streaming' 120
    Step version
    Step setup
    Step J-role
    Step sync-on
    Step reload
    WaitFor { (docker exec -u postgres $pg1 psql -X -U postgres -Atc "select count(*) from pg_stat_replication where application_name = 's1' and sync_state = 'sync'" 2>$null) -eq '1' } 's1 동기 대기 서버' 30
    Step status
    Stamp '대기 서버가 있을 때의 탐지 후보'
    Step J-super
    Step J-app

    Stamp '대기 서버 정지(docker stop pg2)'
    docker stop $pg2 | ForEach-Object { "  $_" }
    Start-Sleep -Seconds 2
    Step status
    Step J-super
    Step J-app
    Step waiters

    Background D-hold
    Start-Sleep -Seconds 1
    Step K-control
    Start-Sleep -Seconds 4
    Step waiters

    Stamp '대기 서버 재기동(docker start pg2)'
    docker start $pg2 | ForEach-Object { "  $_" }
    WaitFor { Streaming } 'pg2 streaming 복귀' 120
    Start-Sleep -Seconds 2
    Step status
    Step waiters
    Step rows
}
finally {
    Stamp '정리(down -v --remove-orphans)'
    docker compose -p exchange-patroni -f $compose down -v --remove-orphans 2>&1 | ForEach-Object { "  $_" }
    Stamp '남은 컨테이너(이름 patroni-)'
    docker ps -a --filter 'name=patroni-' --format '{{.Names}}' | ForEach-Object { "  $_" }
    Stamp '남은 볼륨(compose 프로젝트 라벨 exchange-patroni)'
    docker volume ls --filter 'label=com.docker.compose.project=exchange-patroni' --format '{{.Name}}' | ForEach-Object { "  $_" }
    Stamp '남은 망(exchange-patroni)'
    docker network ls --filter 'name=exchange-patroni' --format '{{.Name}}' | ForEach-Object { "  $_" }
}
