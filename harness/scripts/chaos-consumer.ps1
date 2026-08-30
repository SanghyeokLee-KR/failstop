# 카오스 명령 소비기(하네스 CICD §6-2, API §6-B).
#
# 관리 콘솔에서 2인 승인을 거친 주입 명령이 chaos_commands(V21)에 PENDING으로 적재된다.
# 이 스크립트가 그 행을 점유해 실제 주입을 수행하고 결과를 되쓴다. 데모 대본 0-3절이
# 요구하는 "승인 명령 소비기와 Docker event 상관관계"가 이 경로로 성립한다.
#
# 왜 서버가 직접 하지 않는가.
#   1. 거래 API 프로세스에 docker.sock을 주면 그 프로세스의 RCE 하나가 호스트 장악이 된다.
#   2. E4는 리더를 죽이는 실험이다. 명령을 받은 프로세스가 그 명령으로 사라지면 결과를
#      기록할 주체가 없다. 소비기는 별도 프로세스라 대상이 죽어도 살아서 증빙을 남긴다.
#
# at-most-once는 이 스크립트가 아니라 DB가 보장한다. FOR UPDATE SKIP LOCKED로 한 건만
# 점유하므로 소비기를 둘 띄워도 같은 지시를 두 번 집지 못한다.
#
# 이 스크립트는 주입 원시연산만 수행한다. 실험 전체 프로토콜(사전 권위 run → 주입 → 무개입
# 관찰 → 사후 판정)은 e1~e9 스크립트가 계속 담당한다. 둘은 역할이 다르다.
param(
    [string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e4.env'),
    # 한 번만 훑고 끝낸다. CI·스모크용
    [switch] $Once,
    [int] $PollMs = 1000,
    [int] $TimeoutSec = 0
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath

$consumerId = "consumer-$env:COMPUTERNAME-$PID"

# SQL 리터럴로 들어가는 값은 전부 이 함수를 지난다. 작은따옴표 이스케이프만으로 충분한
# 이유는 대상이 서버가 CHECK·정규식으로 좁힌 값(컨테이너명·toxic명·상태 어휘)이기 때문이다.
function Sql-Lit {
    param([string] $Value)
    if ($null -eq $Value) { return 'NULL' }
    return "'" + ($Value -replace "'", "''") + "'"
}

# 한 건 점유. 소비기를 둘 띄워도 같은 지시를 두 번 집지 않는다.
function Claim-Command {
    $sql = @"
UPDATE chaos_commands SET state='CLAIMED', claimed_by=$(Sql-Lit $consumerId), claimed_at=now()
WHERE id = (SELECT id FROM chaos_commands WHERE state='PENDING'
            ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1)
RETURNING id, experiment_id::text, action, target, coalesce(experiment,''), params::text
"@
    $row = Invoke-Psql ($sql -replace "`r?`n", ' ')
    if ([string]::IsNullOrWhiteSpace($row)) { return $null }
    $f = $row -split '\|'
    # psql이 예기치 못한 줄(상태 태그·경고)을 흘리면 캐스팅에서 스크립트가 죽는다. 빈 큐를
    # 오류로 만들지 않고 이번 바퀴를 건너뛴다.
    if ($f.Count -lt 6 -or $f[0] -notmatch '^\d+$') { return $null }
    return [pscustomobject]@{
        Id = [long] $f[0]; ExperimentId = $f[1]; Action = $f[2]
        Target = $f[3]; Experiment = $f[4]; Params = $f[5]
    }
}

function Complete-Command {
    param([long] $Id, [string] $State, [string] $Result)
    $clipped = if ($Result.Length -gt 900) { $Result.Substring(0, 900) } else { $Result }
    Invoke-Psql ("UPDATE chaos_commands SET state=$(Sql-Lit $State), finished_at=now(), " +
                 "result=$(Sql-Lit $clipped) WHERE id = $Id") | Out-Null
}

# 셸 메타문자가 든 대상은 거부한다. Invoke-DockerQuiet가 cmd /c로 문자열을 실행하므로
# 대상 이름에 & | > 가 들어가면 임의 명령이 소비기 호스트에서 돈다. 서버 검증과 같은 형태다.
function Assert-SafeTarget {
    param([string] $Value)
    if ($Value -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$') {
        throw "대상 이름이 허용 형식이 아니다: $Value"
    }
}

# 컨테이너 id와 실제 상태를 증빙으로 남긴다. 대본이 요구하는 Docker event 상관관계의 근거다.
function Get-ContainerEvidence {
    param([string] $Name)
    $out = & docker inspect --format '{{.Id}} {{.State.Status}} {{.State.ExitCode}}' $Name 2>$null
    if ($LASTEXITCODE -ne 0) { return "inspect 실패: $Name" }
    return ($out | Out-String).Trim()
}

# 설정 키가 실험마다 갈려 있어 없는 값은 폴백을 둔다. 소비기는 어느 실험의 env로도 뜬다.
function Cfg {
    param([string] $Key, [string] $Fallback)
    $v = $cfg[$Key]
    if ([string]::IsNullOrWhiteSpace($v)) { return $Fallback }
    return ($v -split '\s+#')[0].Trim()
}

# target(toxic 이름)에서 프록시를 고른다. e1·e2·e9는 저널 DB, e3는 세션 캐시 경로다.
function Resolve-Proxy {
    param([string] $Experiment, [string] $Target)
    $key = "$Experiment $Target".ToLower()
    if ($key -match 'e3|redis') { return (Cfg 'REDIS_PROXY' 'redis-api1') }
    return (Cfg 'LEADER_PROXY' 'pg-api1')
}

function Invoke-Injection {
    param($Cmd)
    switch ($Cmd.Action) {
        'chaos.kill_node' {
            # 이 값은 cmd /c 문자열에 보간된다. 서버가 이미 좁히지만 소비기는 운영자
            # 워크스테이션에서 돌므로 여기서 한 번 더 막는다(심층 방어).
            Assert-SafeTarget $Cmd.Target
            $method = if ($Cmd.Params -match '"method"\s*:\s*"docker_pause"') { 'pause' } else { 'kill' }
            $before = Get-ContainerEvidence $Cmd.Target
            $rc = Invoke-DockerQuiet "$method $($Cmd.Target)"
            if ($rc -ne 0) { throw "docker $method 실패(rc=$rc): $($Cmd.Target)" }
            Start-Sleep -Milliseconds 400
            return "docker $method $($Cmd.Target) | before: $before | after: $(Get-ContainerEvidence $Cmd.Target)"
        }
        'chaos.network' {
            $proxy = Resolve-Proxy $Cmd.Experiment $Cmd.Target
            # 지연이 기본이다. 서버 ChaosBody.type이 비면 대본의 e1 프로파일을 따른다.
            $type = if ($Cmd.Params -match '"type"\s*:\s*"([a-z_]+)"') { $Matches[1] } else { 'latency' }
            $attrs = switch ($type) {
                'latency'    { @{ latency = [int] (Cfg 'TOXIC_LATENCY_MS' '200'); jitter = [int] (Cfg 'TOXIC_JITTER_MS' '100') } }
                'timeout'    { @{ timeout = 0 } }
                'reset_peer' { @{ timeout = 0 } }
                default      { @{ latency = 200; jitter = 50 } }
            }
            Add-Toxic -Proxy $proxy -Name $Cmd.Target -Type $type -Attributes $attrs
            return "toxic $type on $proxy as $($Cmd.Target) | attrs: $($attrs | ConvertTo-Json -Compress)"
        }
        'chaos.app' {
            # Chaos Monkey는 앱 자신의 actuator다. 소비기는 릴레이만 한다.
            $port = Get-LiveNodePort
            $uri = "http://localhost:$port/actuator/chaosmonkey/assaults"
            $body = '{"level":3,"latencyActive":true,"latencyRangeStart":800,"latencyRangeEnd":2000}'
            Invoke-RestMethod -Uri $uri -Method Post -ContentType 'application/json' `
                -Body $body -TimeoutSec 10 | Out-Null
            return "chaosmonkey assault on :$port | $($Cmd.Target)"
        }
        'chaos.network.clear' {
            $proxy = Resolve-Proxy $Cmd.Experiment $Cmd.Target
            Remove-AllToxics -Proxy $proxy
            # pause로 멈춘 노드는 toxic 해제로 살아나지 않는다. 함께 푼다(검증설계 §3 자동 종료 훅).
            foreach ($n in @((Cfg 'TARGET_NODE' 'chaos-api-1'), (Cfg 'STANDBY_NODE' 'chaos-api-2'))) {
                if ($n) { Invoke-DockerQuiet "unpause $n" | Out-Null }
            }
            return "toxics cleared on $proxy | unpause 시도 완료"
        }
        default { throw "알 수 없는 액션: $($Cmd.Action)" }
    }
}

Write-Section "카오스 명령 소비기 시작 ($consumerId)"
$deadline = if ($TimeoutSec -gt 0) { (Get-Date).AddSeconds($TimeoutSec) } else { [datetime]::MaxValue }
$handled = 0

while ((Get-Date) -lt $deadline) {
    $cmd = Claim-Command
    if ($null -eq $cmd) {
        if ($Once) { break }
        Start-Sleep -Milliseconds $PollMs
        continue
    }

    Write-Host "점유: #$($cmd.Id) $($cmd.Action) -> $($cmd.Target) [$($cmd.Experiment)]"
    # E3는 Redisson 미배선으로 SKIP 확정이다(검증설계 §3). 존재하지 않는 프록시에 주입을
    # 시도하면 실패로 남는데, 그것은 실행 실패가 아니라 범위 밖이다. 상태를 갈라 적는다.
    if ($cmd.Experiment -eq 'E3') {
        Complete-Command -Id $cmd.Id -State 'SKIPPED' -Result 'E3는 Redisson 미배선으로 범위 제외(검증설계 §3)'
        Write-Host "  건너뜀: E3 범위 제외"
        $handled++
        continue
    }

    try {
        $evidence = Invoke-Injection $cmd
        Complete-Command -Id $cmd.Id -State 'EXECUTED' -Result $evidence
        Write-Host "  실행: $evidence"
    } catch {
        # 실패를 삼키면 콘솔이 PENDING에 영원히 머문 것과 구분되지 않는다.
        Complete-Command -Id $cmd.Id -State 'FAILED' -Result $_.Exception.Message
        Write-Host "  실패: $($_.Exception.Message)"
    }
    $handled++
}

Write-Host "소비 종료. 처리 $handled 건"
