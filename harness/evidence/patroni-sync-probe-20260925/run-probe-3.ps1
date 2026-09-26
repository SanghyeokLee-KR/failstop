# 3차 확인 실행기(설계 검토 지적 1). Patroni 엄격 모드가 동기 후보를 잃었을 때 거는 synchronous_standby_names = '*' 가
# 멤버가 아닌 복제 클라이언트(pg_receivewal, pg_basebackup 의 WAL 스트림)를 동기 대기 서버로 받아 커밋 대기를 푸는지 본다.
# 사례 P: '*' 와 pg_receivewal --synchronous(슬롯 없음). 사례 M: '*' 와 pg_basebackup -X stream(기본 임시 슬롯).
# 사례 N: '*' 와 pg_basebackup -X stream --no-slot. 사례 O: 멤버 이름('s1')을 건 상태의 pg_receivewal --synchronous.
# 사용: pwsh -NoProfile -File <이 폴더>\run-probe-3.ps1 *> <이 폴더>\probe-3-output.txt
# 첫 주행(probe-3a-output.txt)은 사례 M 의 pg_basebackup 이 timeout 뒤에도 WAL 수신 자식으로 남아 N·O 를 오염시켰다.
# 그래서 사례마다 bbkill 로 끝내고 복제 연결이 0 이 된 것을 확인한 뒤 다음 사례로 간다.
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
function NoWalSenders { (Q "select count(*) from pg_stat_replication") -eq '0' }
# 관찰 반복. 간격 초마다 repl 과 지정한 id 들의 visible 을 찍는다.
function Observe([int]$times, [int]$every, [string[]]$ids) {
    for ($i = 1; $i -le $times; $i++) {
        Start-Sleep -Seconds $every
        Step repl
        foreach ($id in $ids) { Step visible @($id) }
    }
}

try {
    Stamp '기동'
    docker compose -p exchange-patroni -f $compose up -d 2>&1 | ForEach-Object { "  $_" }
    WaitFor { (docker exec $pg1 pg_isready -U postgres 2>$null) -match 'accepting connections' } 'pg1 연결 수용' 90
    WaitFor { Streaming } 'pg2 streaming' 120
    Step version
    Step setup

    Stamp "기준: '*' 를 걸고 대기 서버 s1 이 동기로 잡히는지"
    Step star-on
    Step reload
    WaitFor { (Q "select count(*) from pg_stat_replication where application_name = 's1' and sync_state = 'sync'") -eq '1' } "'*' 아래 s1 동기" 30
    Step repl
    Step timed @('100')

    Stamp '대기 서버 정지(docker stop pg2)'
    docker stop $pg2 | ForEach-Object { "  $_" }
    WaitFor { NoWalSenders } '복제 연결 0' 30
    Step repl

    Stamp "사례 P: '*' 와 pg_receivewal --synchronous(슬롯 없음)"
    Background hold @('40')
    Start-Sleep -Seconds 2
    Step visible @('40')
    Background rw @('P', '12', '--synchronous')
    Start-Sleep -Seconds 4
    Step repl
    Step visible @('40')
    Step holdout @('40')
    Step timed @('41')
    Start-Sleep -Seconds 9
    Step log @('rw-P')
    WaitFor { NoWalSenders } '사례 P 뒤 복제 연결 0' 30
    Background hold @('42')
    Start-Sleep -Seconds 2
    Step visible @('42')

    Stamp "사례 M: '*' 와 pg_basebackup -X stream(기본 임시 슬롯), 32kB/s"
    Step switch
    Background hold @('50')
    Start-Sleep -Seconds 2
    Step visible @('50')
    Background bb @('M', '75')
    Observe 2 3 @('42', '50')
    Stamp '사례 M: 백업이 도는 중에 들어온 커밋'
    Background hold @('52')
    Observe 5 3 @('52')
    Stamp '사례 M: 백업이 도는 중에 WAL 세그먼트 전환'
    Step switch
    Observe 5 3 @('52')
    Step log @('bb-M')
    Step bbkill
    WaitFor { NoWalSenders } '사례 M 뒤 복제 연결 0' 30
    Step repl
    Step holdout @('50')
    Step holdout @('52')

    Stamp "사례 N: '*' 와 pg_basebackup -X stream --no-slot, 32kB/s"
    Step switch
    Background hold @('60')
    Start-Sleep -Seconds 2
    Step visible @('60')
    Background bb @('N', '75', '--no-slot')
    Observe 3 3 @('60')
    Background hold @('62')
    Observe 3 3 @('60', '62')
    Stamp '사례 N: 백업이 도는 중에 WAL 세그먼트 전환'
    Step switch
    Observe 5 3 @('60', '62')
    Step log @('bb-N')
    Step bbkill
    WaitFor { NoWalSenders } '사례 N 뒤 복제 연결 0' 30
    Step repl
    Step visible @('60')
    Step visible @('62')

    Stamp "사례 O: 멤버 이름('s1')을 건 상태의 pg_receivewal --synchronous"
    Step sync-on
    Step reload
    Start-Sleep -Seconds 1
    Background hold @('70')
    Start-Sleep -Seconds 2
    Step visible @('70')
    Background rw @('O', '10', '--synchronous')
    Start-Sleep -Seconds 4
    Step repl
    Step visible @('70')
    Step visible @('60')
    Start-Sleep -Seconds 7
    Step log @('rw-O')
    WaitFor { NoWalSenders } '사례 O 뒤 복제 연결 0' 30
    Step waiters

    Stamp '대기 서버 재기동(docker start pg2)'
    docker start $pg2 | ForEach-Object { "  $_" }
    WaitFor { Streaming } 'pg2 streaming 복귀' 120
    Start-Sleep -Seconds 2
    Step repl
    Step waiters
    Step rows
    Step holdout @('60')
    Step holdout @('62')
    Step holdout @('70')
    Stamp '대기 서버의 행'
    docker exec -u postgres $pg2 psql -X -U postgres -Atc "select 'standby-row', id, note from t order by id" 2>&1 | ForEach-Object { "  $_" }
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
