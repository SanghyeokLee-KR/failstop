# 게이트 OFF 실험 계측기. 15초마다 앱 지표·JVM CPU·PG CPU를 CSV로 남긴다.
param([int]$IntervalSec = 15, [int]$DurationSec = 720, [string]$Out = "C:\Users\kopo26\AppData\Local\Temp\claude\D--dev-----\838ae1ec-3fb0-44d0-acb4-dc81e68f64db\scratchpad\gateoff_samples.csv")
$ErrorActionPreference = 'Continue'
$hdr = 't,gate,pending,active,max,timeout_total,queue,adm_inflight_order,adm_inflight_query,adm_rejected,completion_ratio,standdown_total,epoch,is_leader,jvm_cpu_pct,pg_cpu_pct,jvm_threads'
$hdr | Out-File -FilePath $Out -Encoding utf8
$jpid = (Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'server.port=8090' } | Select-Object -First 1).ProcessId; $java = if ($jpid) { Get-Process -Id $jpid } else { $null }
if (-not $java) { $java = Get-Process java | Sort-Object WorkingSet64 -Descending | Select-Object -First 1 }
$prevCpu = $java.TotalProcessorTime.TotalSeconds; $prevT = Get-Date
$cores = [Environment]::ProcessorCount
$end = (Get-Date).AddSeconds($DurationSec)
function M($txt, $name, $filter) {
  $re = if ($filter) { "^$name\{[^}]*$filter[^}]*\}\s+([0-9.eE+-]+)" } else { "^$name(\{[^}]*\})?\s+([0-9.eE+-]+)" }
  $m = [regex]::Match($txt, $re, 'Multiline'); if ($m.Success) { $m.Groups[$m.Groups.Count-1].Value } else { '' }
}
while ((Get-Date) -lt $end) {
  try {
    $txt = (Invoke-WebRequest -Uri 'http://localhost:8090/actuator/prometheus' -TimeoutSec 5 -UseBasicParsing).Content
  } catch { $txt = '' }
  try { $g = (Invoke-RestMethod -Uri 'http://localhost:8090/actuator/admission' -TimeoutSec 5).enabled } catch { $g = 'ERR' }
  $java.Refresh(); $now = Get-Date
  $cpu = $java.TotalProcessorTime.TotalSeconds; $dt = ($now - $prevT).TotalSeconds
  $jvmPct = if ($dt -gt 0) { [math]::Round(($cpu - $prevCpu) / $dt * 100, 1) } else { '' }
  $prevCpu = $cpu; $prevT = $now
  $pg = ''; try { $pg = (docker stats exchange-pg-gateoff --no-stream --format '{{.CPUPerc}}') -replace '%','' } catch {}
  $row = @(
    $now.ToString('HH:mm:ss'), $g,
    (M $txt 'hikaricp_connections_pending' 'pool="HikariPool-1"'),
    (M $txt 'hikaricp_connections_active' 'pool="HikariPool-1"'),
    (M $txt 'hikaricp_connections_max' 'pool="HikariPool-1"'),
    (M $txt 'hikaricp_connections_timeout_total' 'pool="HikariPool-1"'),
    (M $txt 'exchange_sequencer_queue_size' $null),
    (M $txt 'exchange_admission_in_flight' 'scope="order"'),
    (M $txt 'exchange_admission_in_flight' 'scope="query"'),
    (M $txt 'exchange_admission_rejected_total' 'scope="order"'),
    (M $txt 'exchange_node_write_completion_ratio' $null),
    (M $txt 'exchange_leader_standdown_total' $null),
    (M $txt 'exchange_leader_epoch' $null),
    (M $txt 'exchange_leader_is_leader' $null),
    $jvmPct, $pg,
    (M $txt 'jvm_threads_live_threads' $null)
  ) -join ','
  $row | Out-File -FilePath $Out -Append -Encoding utf8
  Write-Host $row
  Start-Sleep -Seconds $IntervalSec
}
