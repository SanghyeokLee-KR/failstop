# Patroni 실험 스택 정리. 이 프로젝트(exchange-patroni)의 컨테이너·볼륨·망만 지우고 남은 것이 0 인지 출력으로 보인다.
# 보존 볼륨(exchange-chaos_chaos-pgdata, failstop-keep-*)과 다른 작업의 프로젝트에는 명령을 보내지 않는다.
# 사용: pwsh -NoProfile -File harness/patroni/down.ps1
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$envFile = Join-Path $here '.env.patroni'
if (-not (Test-Path $envFile)) { $envFile = Join-Path $here '.env.patroni.example' }
$project = 'exchange-patroni'

# 프로파일 서비스(obs, ep)는 켜야 지운다. --remove-orphans 는 정의에 없는 서비스만 지운다.
docker compose -p $project -f (Join-Path $here 'docker-compose.patroni.yml') --env-file $envFile --profile obs --profile ep down -v --remove-orphans
if ($LASTEXITCODE -ne 0) { throw 'compose down 실패' }

$left = @(
    docker ps -a --filter "label=com.docker.compose.project=$project" --format '{{.Names}}'
    docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}'
    docker network ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}'
) | Where-Object { $_ }
if ($left) { throw "정리 뒤 남은 자원: $($left -join ', ')" }
Write-Host "$project 컨테이너·볼륨·망 0"
Write-Host '보존 볼륨(건드리지 않음):'
docker volume ls --format '{{.Name}}' | Where-Object { $_ -match '^(exchange-chaos_chaos-pgdata|failstop-keep-)' }
