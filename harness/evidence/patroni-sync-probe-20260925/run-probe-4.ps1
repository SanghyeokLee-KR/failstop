# 4차 확인 실행기(설계 검토 지적 4). 동기 대기 서버가 없는 동안 커밋 대기 중인 트랜잭션이 postmaster 크래시 재시작(백엔드
# 하나가 SIGKILL 로 죽은 경우) 뒤 복제 없이 보이는지 본다. 3차의 사례 N 끝에서 대기 중이던 행이 보이게 된 원인(PID 1 인
# postmaster 의 자식이 된 고아 프로세스가 신호로 끝남)도 같이 확인하고, 고아가 정상 종료하면 재시작이 없는지를 대조한다.
# 사용: pwsh -NoProfile -File <이 폴더>\run-probe-4.ps1 *> <이 폴더>\probe-4-output.txt
# compose 프로젝트 exchange-patroni 만 다룬다. 다른 프로젝트·보존 볼륨에는 명령을 보내지 않는다.

$ErrorActionPreference = 'Stop'
$compose = Join-Path $PSScriptRoot 'compose.yml'
$pg1 = 'patroni-probe-pg1'
$pg2 = 'patroni-probe-pg2'

function Stamp([string]$what) { Write-Output ("[{0}] {1}" -f (Get-Date).ToUniversalTime().ToString('HH:mm:ss.fff'), $what) }
function Step([string]$name, [string[]]$rest = @()) {
    Stamp ("단계 $name " + ($rest -join ' ')).TrimEnd()
    docker exec -u postgres $pg1 sh /probe/steps.sh $name @rest 2>&1 | ForEach-Object { "  $_" }
}
function Background([string]$name, [string[]]$rest = @()) {
    Stamp ("백그라운드 $name " + ($rest -join ' ')).TrimEnd()
    docker exec -d -u postgres $pg1 sh /probe/steps.sh $name @rest
}
function WaitFor([scriptblock]$ok, [string]$what, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (& $ok) { Stamp "확인: $what"; return }
        Start-Sleep -Milliseconds 500
    }
    throw "시간 안에 확인하지 못함: $what"
}
function Q([string]$sql) { docker exec -u postgres $pg1 psql -X -U postgres -Atc $sql 2>$null }
function Streaming { (Q "select count(*) from pg_stat_replication where application_name = 's1' and state = 'streaming'") -eq '1' }
# postmaster 로그 가운데 크래시 재시작과 복구를 알리는 줄만 시각과 함께 옮긴다.
function CrashLog([string]$since) {
    Stamp "pg1 로그(크래시·복구 관련 줄, $since 이후)"
    docker logs --since $since $pg1 2>&1 |
        Select-String -Pattern 'terminated by signal|terminating any other|all server processes terminated|interrupted|redo |database system is ready|automatic recovery|crash' |
        ForEach-Object { "  $_" }
}

try {
    Stamp '기동'
    docker compose -p exchange-patroni -f $compose up -d 2>&1 | ForEach-Object { "  $_" }
    WaitFor { (docker exec $pg1 pg_isready -U postgres 2>$null) -match 'accepting connections' } 'pg1 연결 수용' 90
    WaitFor { Streaming } 'pg2 streaming' 120
    Step version
    Step pid1
    Step setup
    Step star-on
    Step reload
    WaitFor { (Q "select count(*) from pg_stat_replication where application_name = 's1' and sync_state = 'sync'") -eq '1' } "'*' 아래 s1 동기" 30
    Step repl

    Stamp '대기 서버 정지(docker stop pg2)'
    docker stop $pg2 | ForEach-Object { "  $_" }
    Start-Sleep -Seconds 2
    Step repl

    Stamp '사례 Q1: 커밋 대기 중에 다른 백엔드 하나가 SIGKILL 로 죽음'
    Background hold @('80')
    Background sleeper
    Start-Sleep -Seconds 2
    Step visible @('80')
    $t1 = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    Step kill-sleeper
    Start-Sleep -Seconds 2
    Step ready
    Step visible @('80')
    Step holdout @('80')
    Step repl
    CrashLog $t1

    Stamp '사례 Q3(대조): 고아 프로세스가 정상 종료'
    Background hold @('82')
    Start-Sleep -Seconds 2
    Step visible @('82')
    $t3 = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    Step orphan @('3')
    Start-Sleep -Seconds 5
    Step ready
    Step visible @('82')
    CrashLog $t3

    Stamp '사례 Q2: PID 1(postmaster)의 자식이 된 고아 프로세스가 SIGTERM 으로 끝남'
    Step visible @('82')
    $t2 = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    Step orphan @('300')
    Step orphan-kill @('300')
    Start-Sleep -Seconds 2
    Step ready
    Step visible @('82')
    Step holdout @('82')
    CrashLog $t2

    Stamp '행(대기 서버는 멈춘 채다)'
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
