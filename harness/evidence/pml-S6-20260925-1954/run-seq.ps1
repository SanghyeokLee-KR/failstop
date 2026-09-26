# 단계 순서를 잇는다. 한 단계가 실패해도 다음 단계로 간다. 사용: pwsh -NoProfile -File run-seq.ps1 W1 W3 W4 W5 W0:r2
foreach ($s in $args) {
    $p = $s.Split(':')
    $a = @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'run-s6-gate.ps1'), '-Phase', $p[0])
    if ($p.Count -gt 1) { $a += @('-Tag', $p[1]) }
    & pwsh @a *> $null
    Add-Content -Path (Join-Path $PSScriptRoot 'run-seq.log') -Value ('{0} {1} rc={2}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $s, $LASTEXITCODE) -Encoding utf8
}
Add-Content -Path (Join-Path $PSScriptRoot 'run-seq.log') -Value 'SEQ DONE' -Encoding utf8
