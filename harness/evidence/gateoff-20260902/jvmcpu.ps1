param([int]$TargetPid, [int]$IntervalSec = 15, [int]$DurationSec = 840, [string]$Out)
't,jvm_cpu_pct,jvm_threads_os,ws_mb' | Out-File -FilePath $Out -Encoding utf8
$p = Get-Process -Id $TargetPid
$prev = $p.TotalProcessorTime.TotalSeconds; $prevT = Get-Date
$end = (Get-Date).AddSeconds($DurationSec)
while ((Get-Date) -lt $end) {
  Start-Sleep -Seconds $IntervalSec
  $p.Refresh(); $now = Get-Date
  $cpu = $p.TotalProcessorTime.TotalSeconds; $dt = ($now - $prevT).TotalSeconds
  $pct = [math]::Round(($cpu - $prev) / $dt * 100, 1)
  $prev = $cpu; $prevT = $now
  "$($now.ToString('HH:mm:ss')),$pct,$($p.Threads.Count),$([math]::Round($p.WorkingSet64/1MB))" | Out-File -FilePath $Out -Append -Encoding utf8
}
