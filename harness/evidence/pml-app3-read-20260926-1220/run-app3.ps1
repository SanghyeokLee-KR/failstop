# 조회 노드(app-3) 분산 확인 실행기(README '실행 순서'). 폐기용 스택(compose 프로젝트 exchange-pml, 이미지 exchange-chaos-app:pml)에서만 돈다.
# 이미지는 굽지 않는다. HEAD 의 server/ 는 exchange-chaos-app:pml 을 구운 58469ef9 와 같다(README '조건').
# 사용: pwsh -NoProfile -File C:\dev\exchange-pml\harness\evidence\pml-app3-read-20260926-1220\run-app3.ps1 -RawDir <저장소 밖 폴더>
# 비밀 파일(harness/.env.chaos, harness/load/secrets/*)은 경로로만 넘기고 읽지 않는다. 조회 계정 비밀번호는 이 프로세스에서 만들어
# 환경 변수로만 넘기고 적지 않는다.
param([Parameter(Mandatory = $true)] [string] $RawDir)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
$repo = 'C:\dev\exchange-pml'
$ev = $PSScriptRoot
if ((Resolve-Path $ev).Path -notlike "$repo\harness\evidence\*") { throw "증거 폴더가 작업 트리 밖이다: $ev" }
$env:CHAOS_PROJECT = 'exchange-pml'
$env:COMPOSE_PROJECT_NAME = 'exchange-pml'
$env:CHAOS_APP_IMAGE = 'exchange-chaos-app:pml'
$env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
$env:CHAOS_EVIDENCE_DIR = Join-Path $ev 'chaos-runs'
Remove-Item Env:CHAOS_COMPOSE_OVERLAYS -ErrorAction SilentlyContinue
. (Join-Path $repo 'harness\scripts\lib.ps1')
Set-Location $repo
New-Item -ItemType Directory -Force -Path $RawDir | Out-Null
$cmdLog = Join-Path $ev 'commands.log'
$keep = @('exchange-chaos_chaos-pgdata', 'failstop-keep-chaos-pgdata-20260924')
$readUser = 'pml_read'

function Log { param([string] $Msg) $line = "[{0}] {1}" -f (Get-Date).ToString('yyyy-MM-ddTHH:mm:ss.fffK'), $Msg; Write-Host $line; Add-Content -LiteralPath $cmdLog -Value $line -Encoding utf8 }
function Save-Text { param([string] $Name, [string] $Text) [System.IO.File]::WriteAllText((Join-Path $ev $Name), $Text, [System.Text.UTF8Encoding]::new($false)) }
function Get-KeepVolumes { return (& docker volume inspect @keep --format '{{.Name}} {{.CreatedAt}}' 2>&1 | Out-String).Trim() }
function Invoke-Py {
    param([string[]] $ArgList, [string] $LogName)
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & python @ArgList *> (Join-Path $ev $LogName); return $LASTEXITCODE } finally { $ErrorActionPreference = $eap }
}
# 다섯 파일(기본, 중계, P3, 조회 노드, LB 사본) compose. cmd /c 로 감싸 출력 핸들 때문에 매달리지 않게 한다(lib.ps1 Invoke-ComposeQuiet 와 같은 이유).
function Invoke-ComposeRead {
    param([string] $ComposeArgs, [string] $LogName)
    $files = @('harness/docker-compose.chaos.yml', 'harness/docker-compose.relay.yml', 'harness/docker-compose.p3.yml',
        'harness/docker-compose.read-node.yml', (Join-Path $ev 'lb\compose.lb-copy.yml')) | ForEach-Object { "-f `"$_`"" }
    $cmd = "docker compose -p exchange-pml $($files -join ' ') --env-file `"harness/.env.chaos`" $ComposeArgs"
    Log "compose: $ComposeArgs"
    & cmd /c "$cmd > `"$(Join-Path $ev $LogName)`" 2>&1"
    return $LASTEXITCODE
}

$status = [ordered]@{ started = (Now-Iso) }
Log '시작'
# 가드
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'pwsh 7 로 실행한다' }
$existing = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
if ($existing.Count -gt 0) { throw ('chaos- 컨테이너가 이미 있다: {0}' -f ($existing -join ', ')) }
if (-not (Test-Path (Join-Path $repo 'harness\.env.chaos'))) { throw 'harness/.env.chaos 가 없다' }
$volStart = Get-KeepVolumes
Save-Text 'volumes-start.txt' $volStart
Save-Text 'host-start.txt' ((& docker ps -a --format '{{.Names}}|{{.Label "com.docker.compose.project"}}|{{.Status}}' | Out-String).Trim())
$image = [ordered]@{
    image = 'exchange-chaos-app:pml'
    id = (& docker image inspect exchange-chaos-app:pml --format '{{.Id}}').Trim()
    created = (& docker image inspect exchange-chaos-app:pml --format '{{.Created}}').Trim()
    head = (& git -C $repo rev-parse HEAD).Trim()
    serverDiffSince58469ef9 = ((& git -C $repo diff --stat 58469ef9 HEAD -- server/) | Out-String).Trim()
}
Save-Text 'images.json' ($image | ConvertTo-Json)
Log ("이미지 {0}, HEAD {1}, 58469ef9 이후 server/ 변경: '{2}'" -f $image.id, $image.head, $image.serverDiffSince58469ef9)

try {
    # 1. P3 스택(굽지 않음, obs 없음)
    Set-P3Overlay
    Log "오버레이 $env:CHAOS_COMPOSE_OVERLAYS, compose up -d --wait"
    $code = Invoke-ComposeQuiet 'up -d --wait'
    if ($code -ne 0) { throw "compose up 종료코드 $code" }

    # 2. P3 전환과 Q0 준비
    Log 'P3 전환(protocol_latch.py transition --target 3 --latch-band)'
    $rc = Invoke-Py @('harness/scale/protocol_latch.py', 'transition', '--target', '3', '--latch-band', '--out', (Join-Path $ev 'protocol.json')) 'protocol.log'
    if ($rc -ne 0) { throw "P3 전환 실패(rc=$rc)" }
    $pre = Invoke-P3Preflight -Dir $ev -TransitionFile (Join-Path $ev 'protocol.json') -RunId "APP3-Q0-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Log ("Q0 준비 ok={0} failed={1}" -f $pre['ok'], ($pre['failed'] -join ','))
    $status['q0'] = $pre['ok']

    # 3. 조회 계정(배포와 같은 프로비저닝 스크립트, 폐기용 DB). 비밀번호는 이 프로세스에서만 만든다.
    function New-Secret { $b = [byte[]]::new(24); [System.Security.Cryptography.RandomNumberGenerator]::Fill($b); return [Convert]::ToHexString($b).ToLower() }
    $env:DB_USER = 'pml_app'; $env:DB_PASSWORD = New-Secret
    $env:READ_DB_USER = $readUser; $env:READ_DB_PASSWORD = New-Secret
    $env:BOT_DB_USER = 'pml_bot'; $env:BOT_DB_PASSWORD = New-Secret
    Log 'deploy/provision-runtime-roles.sql 을 폐기용 DB 에 적용(pml_app, pml_read, pml_bot)'
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    Get-Content -Raw -LiteralPath (Join-Path $repo 'deploy\provision-runtime-roles.sql') |
        & docker exec -i -e DB_USER -e DB_PASSWORD -e READ_DB_USER -e READ_DB_PASSWORD -e BOT_DB_USER -e BOT_DB_PASSWORD chaos-pg psql -U exchange -d exchange -q *> (Join-Path $ev 'provision.log')
    $provRc = $LASTEXITCODE
    $ErrorActionPreference = $eap
    $env:CHAOS_READ_DB_USER = $readUser
    $env:CHAOS_READ_DB_PASSWORD = $env:READ_DB_PASSWORD
    foreach ($n in 'DB_PASSWORD', 'BOT_DB_PASSWORD', 'DB_USER', 'BOT_DB_USER', 'READ_DB_USER') { Remove-Item "Env:$n" -ErrorAction SilentlyContinue }
    if ($provRc -ne 0) { throw "프로비저닝 실패(rc=$provRc). provision.log" }
    Save-Text 'roles.json' (Invoke-Psql ("SELECT json_agg(json_build_object('role', r.rolname, 'login', r.rolcanlogin, 'super', r.rolsuper, " +
        "'read_only_default', (SELECT array_to_string(setconfig, ',') FROM pg_db_role_setting s WHERE s.setrole = r.oid AND s.setdatabase = 0), " +
        "'select_trades', has_table_privilege(r.rolname, 'trades', 'SELECT'), 'select_orders', has_table_privilege(r.rolname, 'orders', 'SELECT'), " +
        "'insert_trades', has_table_privilege(r.rolname, 'trades', 'INSERT'), 'insert_command_journal', has_table_privilege(r.rolname, 'command_journal', 'INSERT'))) " +
        "FROM pg_roles r WHERE r.rolname IN ('pml_read')"))
    # 금지된 조작을 실제로 시도해 거부 코드를 남긴다(개발·검증 기준 §18).
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $env:PGPASSWORD = $env:CHAOS_READ_DB_PASSWORD
    $neg = foreach ($sql in @('SELECT count(*) FROM trades', 'SELECT count(*) FROM orders', 'INSERT INTO trades DEFAULT VALUES', 'CREATE TABLE pml_read_probe(x int)')) {
        $out = & docker exec -e PGPASSWORD chaos-pg psql -h 127.0.0.1 -U $readUser -d exchange -v ON_ERROR_STOP=1 -v VERBOSITY=verbose -t -A -c $sql 2>&1 | Out-String
        "== $sql (rc=$LASTEXITCODE)`n" + (($out -split "`n" | Where-Object { $_ -match 'ERROR|^\s*\d+\s*$' }) -join "`n")
    }
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    $ErrorActionPreference = $eap
    Save-Text 'roles-negative.txt' ($neg -join "`n")

    # 4. LB 사본 마운트. 사본은 추적 파일과 같은 바이트로 시작하고(api-3 down), 실행기가 Balancer 로 down 만 바꾼다.
    New-Item -ItemType Directory -Force -Path (Join-Path $ev 'lb') | Out-Null
    $copy = Join-Path $ev 'lb\nginx.partition-relay.conf'
    Copy-Item -LiteralPath (Join-Path $repo 'harness\nginx\nginx.partition-relay.conf') -Destination $copy -Force
    $copyPosix = $copy.Replace('\', '/')
    Save-Text 'lb\compose.lb-copy.yml' ("# 이 주행 전용. chaos-lb 에 LB 설정 사본을 마운트한다(P3 오버레이의 같은 대상 마운트를 대신한다).`nservices:`n  chaos-lb:`n    volumes: [`"${copyPosix}:/etc/nginx/nginx.conf:ro`"]`n")
    $code = Invoke-ComposeRead 'up -d --no-deps --force-recreate chaos-lb' 'compose-lb.log'
    if ($code -ne 0) { throw "chaos-lb 재생성 실패($code)" }
    $code = Invoke-ComposeRead '--profile scale up --no-start --no-deps chaos-api-3' 'compose-api3.log'
    if ($code -ne 0) { throw "chaos-api-3 생성 실패($code)" }
    Start-Sleep -Seconds 3

    # 5. 단계 실행
    Log 'read_node_probe.py run'
    $rc = Invoke-Py @('harness/scale/read_node_probe.py', 'run', '--evidence-dir', $ev, '--raw-dir', $RawDir, '--lb-conf', $copy,
        '--preflight', (Join-Path $ev 'p3-preflight.json'), '--read-user', $readUser) 'run.log'
    Log "run 종료코드 $rc"
    $status['runRc'] = $rc

    # 6. 사후: 봇 정지, 드레인, 권위 run, summary.sql
    $post = [ordered]@{ at = (Now-Iso) }
    $post['bots_stopped'] = Stop-BotLoad
    $post['drained'] = [bool](Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 120 -RequiredStreak 2)
    $run = $null
    try {
        $port = Get-LiveNodePort -Resource 'matching-p0'
        $run = Invoke-InvariantRun -Port $port -RunId "APP3-post-$(Get-Date -Format 'yyyyMMdd-HHmmss')" -Window 'post'
    } catch { $post['run_error'] = $_.Exception.Message }
    if ($run) { Save-Text 'post_run.json' ($run | ConvertTo-Json -Depth 10) }
    $post['gated_clean'] = [bool]($run -and (Test-ExperimentIntegrity $run))
    $post['gated_violations'] = $(if ($run) { Get-GatedViolationSum $run } else { $null })
    $post['verdict'] = $(if ($run) { $run.verdict } else { $null })
    Invoke-Py @('-c', 'import sys, json; sys.path.insert(0, "harness/scale"); import s3_common_axes as s; print(json.dumps(s.run_summary()))') 'summary.json' | Out-Null
    try { $summary = Get-Content (Join-Path $ev 'summary.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable } catch { $summary = $null }
    $post['summary_ok'] = [bool]($summary -and $summary['ok'])
    Save-Text 'post.json' ($post | ConvertTo-Json -Depth 5)
    Log ("사후 드레인 {0}, 권위 run 게이팅 clean {1}, summary ok {2}" -f $post['drained'], $post['gated_clean'], $post['summary_ok'])

    # 7. 로그(저장소 밖)와 검사 결과
    $scan = [ordered]@{}
    foreach ($c in 'chaos-api-3', 'chaos-api-1', 'chaos-api-2', 'chaos-lb') {
        $f = Join-Path $RawDir "$c.log"
        & cmd /c "docker logs $c > `"$f`" 2>&1"
        $text = Get-Content -LiteralPath $f -Raw -Encoding utf8
        $scan[$c] = [ordered]@{ sha256 = (Get-FileHash -LiteralPath $f -Algorithm SHA256).Hash.ToLower(); lines = ($text -split "`n").Count
            error_lines = @($text -split "`n" | Where-Object { $_ -match '\bERROR\b' }).Count
            permission_denied = @($text -split "`n" | Where-Object { $_ -match 'permission denied|42501' }).Count
            chk_balance_or_23514 = @($text -split "`n" | Where-Object { $_ -match 'chk_balance_nonneg|23514' }).Count }
    }
    Save-Text 'log-scan.json' ($scan | ConvertTo-Json -Depth 4)
    $diff = & git -C $repo diff --no-index --stat -- (Join-Path $repo 'harness\nginx\nginx.partition-relay.conf') $copy 2>&1 | Out-String
    $diffBody = & git -C $repo diff --no-index -- (Join-Path $repo 'harness\nginx\nginx.partition-relay.conf') $copy 2>&1 | Out-String
    Save-Text 'lb\final.diff' ($diff + "`n" + $diffBody)

    # 8. 판정
    Invoke-Py @('harness/scale/read_node_probe.py', 'judge', '--facts', (Join-Path $ev 'facts.json'), '--post', (Join-Path $ev 'post.json'),
        '--out', (Join-Path $ev 'verdict.json')) 'judge.log' | Out-Null
    Log ('판정: ' + (Get-Content (Join-Path $ev 'judge.log') -Raw -Encoding utf8).Trim())
}
finally {
    # 9. 정리. down -v 는 -p exchange-pml 이 붙은 호출로만 한다.
    Log 'down -v(-p exchange-pml, obs·scale 프로파일 포함)'
    if (-not $env:CHAOS_COMPOSE_OVERLAYS) { $env:CHAOS_COMPOSE_OVERLAYS = $Global:CHAOS_P3_OVERLAYS }
    $code = Invoke-ComposeQuiet '--profile obs --profile scale down -v --remove-orphans'
    Log "down 종료코드 $code"
    Remove-Item Env:CHAOS_READ_DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:READ_DB_PASSWORD -ErrorAction SilentlyContinue
    $left = [ordered]@{
        chaos_containers = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
        pml_volumes = @(& docker volume ls --filter 'label=com.docker.compose.project=exchange-pml' --format '{{.Name}}')
        pml_networks = @(& docker network ls --filter 'label=com.docker.compose.project=exchange-pml' --format '{{.Name}}')
    }
    Save-Text 'cleanup.json' ($left | ConvertTo-Json)
    $volEnd = Get-KeepVolumes
    Save-Text 'volumes-end.txt' $volEnd
    Save-Text 'host-end.txt' ((& docker ps -a --format '{{.Names}}|{{.Label "com.docker.compose.project"}}|{{.Status}}' | Out-String).Trim())
    Log ("보존 볼륨 시작과 같음: {0}" -f ($volStart -eq $volEnd))
    Log ("남은 chaos 컨테이너 {0}, exchange-pml 볼륨 {1}, 네트워크 {2}" -f $left.chaos_containers.Count, $left.pml_volumes.Count, $left.pml_networks.Count)
}
