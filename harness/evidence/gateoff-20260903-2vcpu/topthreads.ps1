# 어떤 OS 스레드가 CPU를 태우는지. jcmd Thread.print 의 nid(16진 OS 스레드 id)와 대조한다.
param([int]$TargetPid, [int]$WindowSec = 10, [int]$Rounds = 12, [string]$Out)
"round,t,os_tid,delta_cpu_ms,pct_of_1core,java_thread" | Out-File -FilePath $Out -Encoding utf8
for ($r = 1; $r -le $Rounds; $r++) {
  $p = Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
  if (-not $p) { "round $r,,,,,PROCESS_GONE" | Out-File -FilePath $Out -Append -Encoding utf8; break }
  $a = @{}; foreach ($t in $p.Threads) { try { $a[$t.Id] = $t.TotalProcessorTime.TotalMilliseconds } catch {} }
  Start-Sleep -Seconds $WindowSec
  $p.Refresh()
  $b = @{}; foreach ($t in $p.Threads) { try { $b[$t.Id] = $t.TotalProcessorTime.TotalMilliseconds } catch {} }
  # jcmd 로 java 스레드 이름 대조표를 만든다(플랫폼 스레드만 nid 를 가진다)
  $names = @{}
  try {
    $dump = & jcmd $TargetPid Thread.print 2>$null
    foreach ($line in $dump) {
      if ($line -match '^"([^"]+)".*nid=(0x[0-9a-fA-F]+)') {
        $names[[Convert]::ToInt32($Matches[2], 16)] = $Matches[1]
      }
    }
  } catch {}
  $now = Get-Date -Format 'HH:mm:ss'
  $b.Keys | ForEach-Object {
    $d = $b[$_] - ($(if ($a.ContainsKey($_)) { $a[$_] } else { 0 }))
    [pscustomobject]@{ tid = $_; d = $d }
  } | Sort-Object d -Descending | Select-Object -First 8 | ForEach-Object {
    $nm = if ($names.ContainsKey($_.tid)) { $names[$_.tid] } else { '(unmapped)' }
    $pct = [math]::Round($_.d / ($WindowSec * 1000) * 100, 1)
    "$r,$now,$($_.tid),$([math]::Round($_.d)),$pct,`"$nm`"" | Out-File -FilePath $Out -Append -Encoding utf8
  }
}
