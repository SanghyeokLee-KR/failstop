# 경보 식 확인 실행기(설계 검토 지적 12). 로컬에 있던 prom/prometheus:v3.13.0 의 promtool 로 첫 판 식과 고친 초안을 본다.
# 사용: pwsh -NoProfile -File <이 폴더>\run-promtool.ps1 *> <이 폴더>\promtool-output.txt
# 컨테이너는 이름 patroni-promtool 로 한 번씩 띄우고 --rm 으로 지운다. 이 폴더만 읽기 전용으로 붙이고 망·볼륨을 만들지 않는다.

$ErrorActionPreference = 'Continue'
$image = 'prom/prometheus:v3.13.0'

function Promtool([string[]]$arguments) {
    Write-Output ("[{0}] promtool {1}" -f (Get-Date).ToUniversalTime().ToString('HH:mm:ss.fff'), ($arguments -join ' '))
    docker run --rm --name patroni-promtool --network none -v "${PSScriptRoot}:/w:ro" -w /w --entrypoint /bin/promtool $image @arguments 2>&1 |
        ForEach-Object { "  $_" }
    Write-Output "  rc=$LASTEXITCODE"
}

Promtool @('--version')
Promtool @('check', 'rules', 'original-failover.yml')
Promtool @('check', 'rules', 'original-quorum.yml')
Promtool @('test', 'rules', 'original-quorum.test.yml')
Promtool @('check', 'rules', 'patroni-alerts-draft.yml')
Promtool @('test', 'rules', 'patroni-alerts-draft.test.yml')
Write-Output '남은 컨테이너(이름 patroni-)'
docker ps -a --filter 'name=patroni-' --format '{{.Names}}' | ForEach-Object { "  $_" }
