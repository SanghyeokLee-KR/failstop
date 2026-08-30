<#
.SYNOPSIS
k6 ACK 라인을 harness.loadtest_acks로 적재한다(검증설계 §2-5, C16 원천).

.DESCRIPTION
C16(BOT_ACK_SUBSET)은 "클라이언트가 접수 응답을 받은 주문이 전부 command_journal에
있는가"를 본다. RPO=0의 외부 증거다. 그런데 판정 SQL이 loadtest_acks를 좌변에 두는
LEFT JOIN이라 그 표가 비면 0행이 나오고 위반 0으로 통과한다. 판정이 통과했는데 판정
대상이 없는 상태다. 실측(2026-08-30): 두 표 모두 0행이었다.

파이프라인이 종전에는 README의 셸 예시로만 있었다. 발표 중에 grep·sed·csv 변환을
손으로 하는 절차라 실제로 쓰이지 않았다. 도구로 만든다.

ACK 라인 형식은 order_flow.js가 낸다.
  ACK|{"ak":"<access_key>","coid":"<client_order_id>","oid":"<uuid>","t":<epoch_ms>}

access_key를 user_id로 바꾸는 표가 harness.loadtest_accounts다. LoadKeySeeder가 키를
발급하면서 함께 적재한다. oid는 주문 외부 UUID인데 loadtest_acks.order_id는 bigint라
형이 다르다. C16 조인은 (user_id, client_order_id)만 쓰므로 order_id는 비운다.

.PARAMETER LogPath
k6 stdout이 담긴 파일. ACK 라인만 골라 쓴다.

.PARAMETER Reset
적재 전에 loadtest_acks를 비운다. 실험 시작 훅과 같은 역할이다. 이전 주행의 ACK가
남아 있으면 이번 실험 창의 판정에 스테일 오탐이 섞인다.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$LogPath,
    [switch]$Reset,
    [string]$Source = 'k6',
    [string]$Pg = 'chaos-pg'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $LogPath)) { throw "로그 파일 없음: $LogPath" }

# 매핑 표가 비면 적재가 통째로 무의미하다. 조용히 0행을 넣고 성공으로 끝내면 C16이
# 다시 공허 통과한다. 먼저 막는다.
$mapped = & docker exec $Pg psql -U exchange -d exchange -q -t -A `
    -c 'SELECT count(*) FROM harness.loadtest_accounts' 2>$null
if ($LASTEXITCODE -ne 0) { throw 'loadtest_accounts 조회 실패' }
if ([int]$mapped -eq 0) {
    throw 'harness.loadtest_accounts가 비었다. POST /harness/v1/loadtest-keys로 키를 발급하면 함께 적재된다. 그 표 없이는 access_key를 user_id로 바꿀 수 없다.'
}

$acks = @()
# k6는 console.log를 구조화 로그로 감싼다. 형태가 둘이다.
#   원시:   ACK|{"ak":...}
#   구조화: time="..." level=info msg="ACK|{\"ak\":...}" source=console
# 후자는 JSON이 이스케이프돼 있어 그대로 파싱하면 실패한다. 실측(2026-08-30): 6,065건이
# 있는 로그에서 0건 추출로 나왔다. 두 형태를 다 받는다.
$rx = [regex]'ACK\|(\{.*?\})(?="\s|$)'
foreach ($line in [System.IO.File]::ReadLines((Resolve-Path $LogPath))) {
    $i = $line.IndexOf('ACK|')
    if ($i -lt 0) { continue }
    $json = $null
    $m = $rx.Match($line)
    if ($m.Success) {
        $json = $m.Groups[1].Value
        # 구조화 로그면 msg 값 안이라 따옴표가 이스케이프돼 있다. 되돌린다.
        if ($line -match 'msg="') { $json = $json.Replace('\"', '"') }
    } else {
        $json = $line.Substring($i + 4)
    }
    try { $o = $json | ConvertFrom-Json } catch { continue }
    if (-not $o.ak -or -not $o.coid) { continue }
    $acks += [pscustomobject]@{ ak = $o.ak; coid = $o.coid; t = [long]$o.t }
}

Write-Host ("ACK 라인 {0}건 추출" -f $acks.Count)
if ($acks.Count -eq 0) {
    # 주행이 있었는데 ACK가 0이면 접수가 없었다는 뜻이다. 그대로 적재하면 C16이 통과하지만
    # 그 통과는 무의미하다. 호출부가 알도록 비영으로 끝낸다.
    throw 'ACK 라인이 0건이다. 주행에서 신규 접수(201)가 없었거나 로그가 잘렸다. 이 상태로 적재하면 C16이 공허 통과한다.'
}

# 적재는 psql stdin으로 넘긴다. docker cp + \copy 조합을 쓰지 않는 이유는 실측 때문이다.
# Git Bash 경로 변환이 /tmp를 Windows 경로로 바꿔 cp가 조용히 실패했고, 그 뒤 \copy가
# 파일을 못 찾아 0행이 적재됐는데 스크립트는 exit 0으로 끝났다. 관측 실패가 통과 신호가
# 된 경우다. stdin은 중간 파일이 없어 그 실패 지점 자체가 사라진다.
$sb = New-Object System.Text.StringBuilder
if ($Reset) { [void]$sb.AppendLine('TRUNCATE harness.loadtest_acks;') }
[void]$sb.AppendLine('CREATE TEMP TABLE ack_raw (access_key text, client_order_id text, t_ms bigint, source text);')
[void]$sb.AppendLine('COPY ack_raw FROM STDIN WITH (FORMAT csv);')
foreach ($a in $acks) {
    [void]$sb.AppendLine(('{0},{1},{2},{3}' -f $a.ak, $a.coid, $a.t, $Source))
}
[void]$sb.AppendLine('\.')
[void]$sb.AppendLine(@'
INSERT INTO harness.loadtest_acks (user_id, client_order_id, order_id, acked_at, source)
SELECT m.user_id, r.client_order_id, NULL, to_timestamp(r.t_ms / 1000.0), r.source
FROM ack_raw r JOIN harness.loadtest_accounts m ON m.access_key = r.access_key
ON CONFLICT (user_id, client_order_id) DO NOTHING;
SELECT count(*) FROM harness.loadtest_acks;
'@)

$sqlFile = Join-Path ([System.IO.Path]::GetTempPath()) ("ack-" + [guid]::NewGuid().ToString('N') + '.sql')
[System.IO.File]::WriteAllText($sqlFile, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
$out = Get-Content $sqlFile -Raw | & docker exec -i $Pg psql -U exchange -d exchange -q -t -A -v ON_ERROR_STOP=1
$code = $LASTEXITCODE
Remove-Item $sqlFile -ErrorAction SilentlyContinue
if ($code -ne 0) { throw "적재 실패(psql exit=$code): $out" }

$total = ($out | Where-Object { $_ -match '^\d+$' } | Select-Object -Last 1)
Write-Host ("loadtest_acks 총 {0}행" -f $total)

# 매핑 실패로 버려진 행을 드러낸다. 조용히 줄면 C16이 그만큼 덜 검사한다.
if ([int]$total -lt $acks.Count -and -not $Reset) {
    Write-Host '주의: 누적 표라 이번 주행분만으로 비교하려면 -Reset을 쓴다' -ForegroundColor DarkGray
} elseif ($Reset -and [int]$total -lt $acks.Count) {
    Write-Host ("경고: ACK {0}건 중 {1}행만 적재됐다. access_key가 loadtest_accounts에 없는 건이 있다" -f `
        $acks.Count, $total) -ForegroundColor Yellow
}
exit 0
