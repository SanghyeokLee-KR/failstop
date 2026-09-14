# 후속 측정 드라이버. pwsh 7. 로컬 postgres:17-alpine 만 쓴다.
# usage: pwsh -NoProfile -File run_followup.ps1 [-Phases setup,A,B,C,collect,cleanup] [-Name explain-wp6-fu]
# 옆 round1 디렉터리의 적재·커서 스크립트를 그대로 쓰고 후보 인덱스까지 만든 뒤 A, B, C 를 돈다.
param(
    [string]$Phases = 'setup,A,B,C,collect,cleanup',
    [string]$Name = 'explain-wp6-fu',
    [string]$Migrations = ''
)
$ErrorActionPreference = 'Stop'
$fu = $PSScriptRoot
$base = Join-Path (Split-Path $fu -Parent) 'round1'
$ph = $Phases -split ','
# 마이그레이션 위치는 이 스크립트가 든 저장소에서 찾는다. 저장소 밖에서 돌리면 -Migrations 로 준다.
if (-not $Migrations) {
    $root = git -C $PSScriptRoot rev-parse --show-toplevel 2>$null
    if (-not $root) { throw '-Migrations 로 마이그레이션 디렉터리를 지정한다(저장소 밖 실행).' }
    $Migrations = Join-Path $root 'server/app/src/main/resources/db/migration'
}

function Invoke-Pg([string]$cmd) {
    docker exec $Name sh -c $cmd
    if ($LASTEXITCODE -ne 0) { throw "failed: $cmd" }
}

function Copy-Scripts {
    Invoke-Pg 'mkdir -p /bench/followup /out/followup'
    Get-ChildItem $base -File | Where-Object { $_.Extension -in '.sh', '.sql' } |
        ForEach-Object { docker cp $_.FullName "${Name}:/bench/$($_.Name)" }
    Get-ChildItem $fu -File | Where-Object { $_.Extension -in '.sh', '.sql', '.awk' } |
        ForEach-Object { docker cp $_.FullName "${Name}:/bench/followup/$($_.Name)" }
}

try {
    if ('setup' -in $ph) {
        docker image inspect postgres:17-alpine *> $null
        if ($LASTEXITCODE -ne 0) { throw 'postgres:17-alpine 로컬 이미지 없음. 받지 않고 멈춘다.' }
        docker run -d --name $Name -e POSTGRES_PASSWORD=explain_local_only -e POSTGRES_DB=exchange postgres:17-alpine | Out-Null
        do { Start-Sleep -Milliseconds 500 } until ((docker logs $Name 2>&1 | Out-String) -match 'init process complete')
        do { Start-Sleep -Milliseconds 500; docker exec $Name pg_isready -U postgres -h 127.0.0.1 *> $null } while ($LASTEXITCODE -ne 0)
        Invoke-Pg 'mkdir -p /mig'
        docker cp "$Migrations\." "${Name}:/mig/"
        Copy-Scripts
        $psql = 'psql -X -U postgres -d exchange -v ON_ERROR_STOP=1'
        Invoke-Pg 'sh /bench/apply.sh 0 35'
        Invoke-Pg "$psql -f /bench/seed_markets.sql"
        Invoke-Pg "$psql -v n=100000 -f /bench/seed_commands.sql"
        Invoke-Pg "$psql -f /bench/seed_legacy_events.sql"
        Invoke-Pg 'sh /bench/apply.sh 36 41'
        Invoke-Pg "$psql -v n=300000 -f /bench/seed_commands.sql"
        Invoke-Pg "$psql -f /bench/seed_modern_events.sql"
        Invoke-Pg "$psql -c 'VACUUM (ANALYZE)'"
        Invoke-Pg "$psql -f /bench/cursors.sql > /out/followup/cursors.txt"
        Invoke-Pg "$psql -c 'CREATE INDEX event_journal_market_id_source_cmd_seq_event_ordinal_idx ON event_journal (market_id, source_cmd_seq, event_ordinal)'"
    } else {
        Copy-Scripts
    }
    if ('A' -in $ph) { Invoke-Pg 'sh /bench/followup/measure_a.sh' }
    if ('A' -in $ph -or 'Adiag' -in $ph) { Invoke-Pg 'sh /bench/followup/diag_a_jitoff.sh' }
    if ('B' -in $ph) { Invoke-Pg 'sh /bench/followup/measure_b.sh' }
    if ('C' -in $ph) { Invoke-Pg 'sh /bench/followup/measure_c.sh' }
    if ('collect' -in $ph) {
        Invoke-Pg 'cd /out/followup/A 2>/dev/null && for x in *.txt; do case $x in summary*) continue;; esac; awk -v f="${x%.txt}" -f /bench/followup/plan_a.awk "$x"; done > /out/followup/A_summary.txt || true'
        Invoke-Pg 'cd /out/followup/B 2>/dev/null && for x in v*.txt; do awk -v f="${x%.txt}" -f /bench/followup/plan_b.awk "$x"; done > /out/followup/B_summary.txt || true'
        New-Item -ItemType Directory -Force (Join-Path $fu 'out') | Out-Null
        docker cp "${Name}:/out/followup/." (Join-Path $fu 'out')
    }
}
finally {
    if ('cleanup' -in $ph) {
        docker stop $Name | Out-Null
        docker rm $Name | Out-Null
    }
}
