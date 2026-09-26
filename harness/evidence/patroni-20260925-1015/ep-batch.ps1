param([string] $RunList)
$Runs = $RunList.Split(',')
# 실행기를 차례로 돈다. 각 회차 앞에서 S5 스택(chaos-* 컨테이너)이 돌면 2분 간격으로 기다린다(누적 3시간 상한).
$scripts = 'C:\dev\exchange-patroni\harness\scripts'
$logDir = Join-Path $env:TEMP 'exchange-patroni-ep'
$waitBudget = $(if ($env:EP_WAIT_BUDGET) { [int]$env:EP_WAIT_BUDGET } else { 3 * 3600 })
$summary = Join-Path $logDir 'session-batch.txt'
foreach ($r in $Runs) {
    $name, $arg = $r.Split(':')
    while ($true) {
        $chaos = @(docker ps --format '{{.Names}}' | Where-Object { $_ -like 'chaos-*' -or $_ -like 'testcontainers-ryuk-*' -or $_ -like 'pml-*' -or ($_ -match 'k6' -and $_ -notlike 'patroni-*') })
        $chaos += @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'GradleWorkerMain' -and $_.CommandLine -notmatch 'exchange-patroni' } | ForEach-Object { "gradle-worker:$($_.ProcessId)" })
        if (-not $chaos -or $waitBudget -le 0) { break }
        Add-Content $summary ("{0} WAIT {1} chaos 컨테이너 {2}" -f (Get-Date).ToUniversalTime().ToString('HH:mm:ss'), $name, ($chaos -join ','))
        Start-Sleep -Seconds 120; $waitBudget -= 120
    }
    $chaosNow = @(docker ps --format '{{.Names}}' | Where-Object { $_ -like 'chaos-*' }) -join ','
    $log = Join-Path $logDir "session-$name$arg.log"
    $start = (Get-Date).ToUniversalTime().ToString('HH:mm:ss')
    $argList = @('-NoProfile', '-File', (Join-Path $scripts "$name.ps1"))
    if ($arg) { $argList += @('-Variant', $arg) }
    & pwsh @argList *> $log
    $code = $LASTEXITCODE
    $verdict = (Select-String -Path $log -Pattern '^판정 (\S+) \((.+)\)$' | Select-Object -Last 1)
    Add-Content $summary ("{0}-{1} {2}{3} exit={4} chaos=[{5}] {6}" -f $start, (Get-Date).ToUniversalTime().ToString('HH:mm:ss'), $name, $arg, $code, $chaosNow, $(if ($verdict) { $verdict.Line } else { 'NO-VERDICT' }))
}
Add-Content $summary 'BATCH-END'
