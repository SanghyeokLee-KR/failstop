# 둘째 주행의 단계 순서(설계 7-1 명령 목록 + 설계 밖 기준선 Z1base). 한 단계가 실패해도 다음 단계로 간다. 각 단계는 스택을
# -p exchange-pml 로 지우고 새로 세운다. 단계별 출력은 <단계>-runner.log, 종료 코드는 run-all.log 에 남긴다.
$ev = $PSScriptRoot
$r = Join-Path $ev 'run-s5-gate.ps1'
$steps = @(
    @{ n = 'Z0'; a = @('-Phase', 'Chaos', '-LbConf', 'default', '-Experiments', 'E4', '-SubDir', 'Z0') },
    @{ n = 'Z1'; a = @('-Phase', 'Z1', '-LbConf', 'partition') },
    @{ n = 'Z1base'; a = @('-Phase', 'Z1base', '-LbConf', 'default') },
    @{ n = 'Z1d'; a = @('-Phase', 'Z1d', '-LbConf', 'partition') },
    @{ n = 'Z2'; a = @('-Phase', 'Z2', '-LbConf', 'partition') },
    @{ n = 'Z3'; a = @('-Phase', 'Z3', '-LbConf', 'partition') },
    @{ n = 'Z4'; a = @('-Phase', 'Z4', '-LbConf', 'partition') },
    @{ n = 'Z5'; a = @('-Phase', 'Chaos', '-LbConf', 'partition', '-Experiments', 'E4,E7,E2', '-SubDir', 'Z5') },
    @{ n = 'Z6'; a = @('-Phase', 'B1', '-LbConf', 'partition') },
    @{ n = 'Final'; a = @('-Phase', 'Final') }
)
if ($args.Count -gt 0) { $steps = @($steps | Where-Object { $_.n -in $args }) }
foreach ($s in $steps) {
    $start = Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'
    & pwsh -NoProfile -File $r @($s.a) *> (Join-Path $ev "$($s.n)-runner.log")
    $rc = $LASTEXITCODE
    Add-Content -Path (Join-Path $ev 'run-all.log') -Value ('{0} {1} rc={2} end={3}' -f $start, $s.n, $rc, (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')) -Encoding utf8
}
Add-Content -Path (Join-Path $ev 'run-all.log') -Value 'DONE' -Encoding utf8
