# 마켓당 경계 문장 측정 드라이버. pwsh 7. 로컬 postgres:17-alpine 만 쓴다.
# usage: pwsh -NoProfile -File run_followup2.ps1 [-Phases setup,M,collect,cleanup] [-Name explain-wp6-fu2]
param(
    [string]$Phases = 'setup,M,collect,cleanup',
    [string]$Name = 'explain-wp6-fu2',
    [string]$Migrations = ''
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$base = Join-Path (Split-Path $here -Parent) 'round1'
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
    Invoke-Pg 'mkdir -p /bench/followup2 /out/followup2'
    Get-ChildItem $base -File | Where-Object { $_.Extension -in '.sh', '.sql' } |
        ForEach-Object { docker cp $_.FullName "${Name}:/bench/$($_.Name)" }
    Get-ChildItem $here -File | Where-Object { $_.Extension -in '.sh', '.sql', '.awk' } |
        ForEach-Object { docker cp $_.FullName "${Name}:/bench/followup2/$($_.Name)" }
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
        Invoke-Pg "$psql -f /bench/cursors.sql > /out/followup2/cursors.txt"
        Invoke-Pg "$psql -c 'CREATE INDEX event_journal_market_id_source_cmd_seq_event_ordinal_idx ON event_journal (market_id, source_cmd_seq, event_ordinal)'"
    } else {
        Copy-Scripts
    }
    if ('M' -in $ph) {
        Invoke-Pg 'sh /bench/followup2/measure.sh'
        Invoke-Pg 'sh /bench/followup2/seq.sh > /out/followup2/seq_summary.txt'
    }
    if ('collect' -in $ph) {
        Invoke-Pg 'cd /out/followup2/runs && for x in *.txt; do awk -v f="${x%.txt}" -f /bench/followup2/plan_pm.awk "$x"; done > /out/followup2/summary.txt'
        Invoke-Pg 'awk -f /bench/followup2/sum.awk /out/followup2/summary.txt | sort > /out/followup2/totals.txt'
        New-Item -ItemType Directory -Force (Join-Path $here 'out') | Out-Null
        docker cp "${Name}:/out/followup2/." (Join-Path $here 'out')
    }
}
finally {
    if ('cleanup' -in $ph) {
        docker stop $Name | Out-Null
        docker rm $Name | Out-Null
    }
}
