# 2 vCPU 실측(run 2) 계측기. 3초 간격. CPU는 1코어=100% 단위로 재고 vcpu_pct 열에 2 vCPU 인스턴스 환산값을 함께 남긴다.
param([int]$TargetPid, [int]$IntervalSec = 3, [int]$DurationSec = 900, [string]$Out, [int]$Port = 8090, [int]$VCpu = 2)
$ErrorActionPreference = 'Continue'
't,gate,jvm_cpu_1core_pct,vcpu_pct,os_threads,ws_mb,pending,active,timeout_total,queue,adm_rejected,completion_ratio,standdown,epoch,is_leader,pg_cpu_pct,tcp_estab,tcp_close_wait,http_ms,http_code' | Out-File -FilePath $Out -Encoding utf8
$p = Get-Process -Id $TargetPid
$prev = $p.TotalProcessorTime.TotalSeconds; $prevT = Get-Date
$end = (Get-Date).AddSeconds($DurationSec)
function M($txt, $name, $filter) {
  $re = if ($filter) { "^$name\{[^}]*$filter[^}]*\}\s+([0-9.eE+-]+)" } else { "^$name(\{[^}]*\})?\s+([0-9.eE+-]+)" }
  $m = [regex]::Match($txt, $re, 'Multiline'); if ($m.Success) { $m.Groups[$m.Groups.Count-1].Value } else { '' }
}
while ((Get-Date) -lt $end) {
  $p.Refresh(); $now = Get-Date
  $cpu = $p.TotalProcessorTime.TotalSeconds; $dt = ($now - $prevT).TotalSeconds
  $pct = if ($dt -gt 0) { [math]::Round(($cpu - $prev) / $dt * 100, 1) } else { '' }
  $prev = $cpu; $prevT = $now
  $vcpu = if ($pct -ne '') { [math]::Round($pct / $VCpu, 1) } else { '' }
  $sw = [Diagnostics.Stopwatch]::StartNew(); $code = 0
  try { $r = Invoke-WebRequest -Uri "http://localhost:$Port/actuator/prometheus" -TimeoutSec 3 -UseBasicParsing; $txt = $r.Content; $code = $r.StatusCode } catch { $txt = ''; $code = 0 }
  $sw.Stop()
  $g = ''
  if ($txt) { try { $g = (Invoke-RestMethod -Uri "http://localhost:$Port/actuator/admission" -TimeoutSec 3).enabled } catch { $g = 'ERR' } } else { $g = 'ERR' }
  $pg = ''; try { $pg = (docker stats exchange-pg-gateoff --no-stream --format '{{.CPUPerc}}') -replace '%','' } catch {}
  $ns = @(netstat -an | Select-String ":$Port\s")
  $estab = ($ns | Where-Object { $_ -match 'ESTABLISHED' }).Count
  $cw = ($ns | Where-Object { $_ -match 'CLOSE_WAIT' }).Count
  $row = @(
    $now.ToString('HH:mm:ss'), $g, $pct, $vcpu, $p.Threads.Count, [math]::Round($p.WorkingSet64/1MB),
    (M $txt 'hikaricp_connections_pending' 'pool="HikariPool-1"'),
    (M $txt 'hikaricp_connections_active' 'pool="HikariPool-1"'),
    (M $txt 'hikaricp_connections_timeout_total' 'pool="HikariPool-1"'),
    (M $txt 'exchange_sequencer_queue_size' $null),
    (M $txt 'exchange_admission_rejected_total' 'scope="order"'),
    (M $txt 'exchange_node_write_completion_ratio' $null),
    (M $txt 'exchange_leader_standdown_total' $null),
    (M $txt 'exchange_leader_epoch' $null),
    (M $txt 'exchange_leader_is_leader' $null),
    $pg, $estab, $cw, [int]$sw.ElapsedMilliseconds, $code
  ) -join ','
  $row | Out-File -FilePath $Out -Append -Encoding utf8
  Start-Sleep -Seconds $IntervalSec
}
