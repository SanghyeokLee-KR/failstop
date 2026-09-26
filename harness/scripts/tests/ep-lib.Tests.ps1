# Patroni 실험 실행기 공용(ep-lib.ps1)의 순수 함수를 본다. Docker 에 명령을 보내지 않는다.
# 사용: pwsh -NoProfile -File harness/scripts/tests/ep-lib.Tests.ps1

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
$passed = 0

function Assert-Equal($expected, $actual, [string] $what) {
    if ("$expected" -ne "$actual") { throw "${what}: '$expected' 를 기대했으나 '$actual'" }
}

function Pass([string] $name) {
    $script:passed++
    Write-Host "PASS $name"
}

# 새 pwsh 에서 ep-lib.ps1 을 읽는다. 이 프로세스의 환경 변수(CHAOS_*)를 바꾸지 않기 위해서다.
$text = @'
# HELP exchange_write_probe_unconfirmed_total 확인 안 된 느림
exchange_write_probe_unconfirmed_total 3.0
exchange_write_gate_rejected_total{application="exchange",response="423",} 7.0
exchange_write_gate_rejected_total{application="exchange",response="503",} 2.0
exchange_partition_demotions_total{partition="matching-engine",reason="LEASE_LOST",} 1.0
exchange_leader_standdown_total 1.0
exchange_leader_lease_renew_total{outcome="ok",partition="matching-engine",} 40.0
exchange_leader_lease_renew_total{outcome="rejected",partition="matching-engine",} 2.0
http_server_requests_seconds_count{error="none",method="POST",outcome="SERVER_ERROR",status="500",uri="/api/v1/orders",} 4.0
http_server_requests_seconds_count{error="none",method="GET",outcome="SERVER_ERROR",status="500",uri="/api/v1/markets",} 9.0
exchange_order_intake_commit_recovered_total 5.0
'@
$file = Join-Path ([System.IO.Path]::GetTempPath()) ("ep-metrics-" + [guid]::NewGuid().ToString('N') + '.txt')
[System.IO.File]::WriteAllText($file, $text, [System.Text.UTF8Encoding]::new($false))
try {
    $lib = Join-Path $scripts 'ep-lib.ps1'
    $json = & pwsh -NoProfile -Command ". '$lib'; ConvertFrom-EpMetricsText ([System.IO.File]::ReadAllText('$file')) | ConvertTo-Json -Compress"
    if ($LASTEXITCODE -ne 0) { throw "ep-lib.ps1 읽기 실패: $json" }
    $m = ($json | Select-Object -Last 1) | ConvertFrom-Json
} finally {
    Remove-Item $file -ErrorAction SilentlyContinue
}

Assert-Equal 3 $m.probe_unconfirmed '태그 없는 계수'
Assert-Equal 7 $m.gate_423 '423 거절'
Assert-Equal 2 $m.gate_503 '503 거절'
Assert-Equal 2 $m.demotions '파티션 강등 + 자발적 강등'
Assert-Equal 2 $m.renew_rejected '갱신 거부만'
Assert-Equal 4 $m.orders_500 '주문 경로의 500 만'
Assert-Equal 5 $m.commit_recovered 'V47 커밋 회수'
Assert-Equal 0 $m.lease_acquire_expired '없는 계수는 0'
Pass 'metrics-text-parsing'

Write-Host "ep-lib: $passed 통과"
