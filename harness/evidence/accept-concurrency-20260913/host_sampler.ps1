# 공유 호스트의 스택 밖 프로세스 CPU 표본. 15초 간격, 상위 8개. 값은 논리 CPU 16개 대비 퍼센트다.
# 사용: pwsh -NoProfile -File host_sampler.ps1 <label>   멈추기: stop-host-<label> 파일 생성
$label = $args[0]
$dir = $PSScriptRoot
$out = Join-Path $dir "host_samples_$label.csv"
$stop = Join-Path $dir "stop-host-$label"
'ts,process,cpu_pct_of_16' | Set-Content -Encoding utf8 $out
$deadline = (Get-Date).AddHours(1)
while (-not (Test-Path $stop) -and (Get-Date) -lt $deadline) {
    $ts = Get-Date -Format 'HH:mm:ss'
    try {
        $s = Get-Counter '\Process(*)\% Processor Time' -SampleInterval 5 -MaxSamples 1 -ErrorAction SilentlyContinue
        $s.CounterSamples | Where-Object { $_.InstanceName -notin @('_total', 'idle') } |
            Sort-Object CookedValue -Descending | Select-Object -First 8 |
            ForEach-Object { '{0},{1},{2:F1}' -f $ts, $_.InstanceName, ($_.CookedValue / 16) } |
            Add-Content -Encoding utf8 $out
    } catch { "$ts,sample_failed," | Add-Content -Encoding utf8 $out }
    Start-Sleep -Seconds 10
}
