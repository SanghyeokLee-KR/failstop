# 재현 드라이버. pwsh 7. 로컬 postgres:17-alpine 만 쓰고 새 이미지를 받지 않는다.
# usage: pwsh -NoProfile -File run_all.ps1 [-OutDir out] [-Name explain-wp6-pg] [-Migrations <dir>]
param(
    [string]$Migrations = '',
    [string]$Name = 'explain-wp6-pg',
    [string]$OutDir = 'out'
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
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

docker image inspect postgres:17-alpine *> $null
if ($LASTEXITCODE -ne 0) { throw 'postgres:17-alpine 로컬 이미지 없음. 받지 않고 멈춘다.' }

docker run -d --name $Name -e POSTGRES_PASSWORD=explain_local_only -e POSTGRES_DB=exchange postgres:17-alpine | Out-Null
try {
    # 엔트리포인트가 initdb 용 임시 서버를 띄웠다 내리므로 init 완료 로그 뒤에 준비를 본다.
    do { Start-Sleep -Milliseconds 500 } until ((docker logs $Name 2>&1 | Out-String) -match 'init process complete')
    do { Start-Sleep -Milliseconds 500; docker exec $Name pg_isready -U postgres -h 127.0.0.1 *> $null } while ($LASTEXITCODE -ne 0)

    Invoke-Pg 'mkdir -p /mig /bench /out'
    docker cp "$Migrations\." "${Name}:/mig/"
    Get-ChildItem $here -File | Where-Object { $_.Extension -in '.sh', '.sql' } |
        ForEach-Object { docker cp $_.FullName "${Name}:/bench/$($_.Name)" }

    $psql = 'psql -X -U postgres -d exchange -v ON_ERROR_STOP=1'
    Invoke-Pg 'sh /bench/apply.sh 0 35'
    Invoke-Pg "$psql -f /bench/seed_markets.sql"
    Invoke-Pg "$psql -v n=100000 -f /bench/seed_commands.sql"
    Invoke-Pg "$psql -f /bench/seed_legacy_events.sql"
    Invoke-Pg 'sh /bench/apply.sh 36 41'
    Invoke-Pg "$psql -v n=300000 -f /bench/seed_commands.sql"
    Invoke-Pg "$psql -f /bench/seed_modern_events.sql"
    Invoke-Pg "$psql -c 'VACUUM (ANALYZE)'"
    Invoke-Pg "$psql -f /bench/cursors.sql > /out/cursors.txt"
    Invoke-Pg "$psql -f /bench/sizes.sql > /out/sizes_noidx.txt"
    Invoke-Pg 'sh /bench/measure.sh noidx'
    Invoke-Pg "$psql -c '\timing on' -c 'CREATE INDEX ON event_journal (market_id, source_cmd_seq, event_ordinal)' > /out/create_index.txt"
    Invoke-Pg "$psql -f /bench/sizes.sql > /out/sizes_idx.txt"
    Invoke-Pg 'sh /bench/measure.sh idx'
    Invoke-Pg 'sh /bench/jitoff.sh idx'
    Invoke-Pg 'sh /bench/auto.sh > /out/auto_summary.txt'
    Invoke-Pg 'sh /bench/summarize.sh > /out/summary.csv'
    New-Item -ItemType Directory -Force (Join-Path $here $OutDir) | Out-Null
    docker cp "${Name}:/out/." (Join-Path $here $OutDir)
}
finally {
    docker stop $Name | Out-Null
    docker rm $Name | Out-Null
}
