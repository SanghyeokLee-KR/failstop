# 옛 주 DB 재합류 감시(harness/scripts/demo-rejoin.ps1)의 판단을 본다. Docker·Patroni 에 명령을 보내지 않는다.
# 사용: pwsh -NoProfile -File harness/scripts/tests/demo-rejoin.Tests.ps1
#
# 멤버 상태, 컨테이너 로그, reinit, 시계를 가짜로 바꿔 끼운다. 보는 것은 넷이다.
#   1. 곧바로 streaming 이면 되감기로 붙은 것이고 reinit 하지 않는다.
#   2. 'requested WAL segment ... has already been removed' 가 보이면 한 번만 reinit 하고, 그 뒤 붙으면 방법을 되감기→재초기화로 적는다.
#   3. 로그가 없어도 StuckSec 동안 붙지 않으면 reinit 한다. 그 전에는 하지 않는다.
#   4. 로그는 2초에 한 번만 읽는다.

$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
$passed = 0

function Assert-Equal($expected, $actual, [string] $what) {
    if ("$expected" -ne "$actual") { throw "${what}: '$expected' 를 기대했으나 '$actual'" }
}

function Pass([string] $name) {
    $script:passed++
    Write-Host "PASS $name"
}

$Global:Demo = @{ Members = [ordered]@{ 'db-1' = 'chaos-db-1'; 'db-2' = 'chaos-db-2' } }
function Get-DemoNow { return $script:now }
function ConvertTo-IsoUtc([double] $Epoch) { return "iso-$Epoch" }
. (Join-Path $scripts 'demo-rejoin.ps1')

# 가짜(demo-rejoin.ps1 을 읽은 뒤 같은 범위에 다시 정의해 덮는다)
function Get-MemberState([string] $Member) { return $script:state }
function Get-ContainerLogSince([string] $Container, [string] $SinceIso) { $script:logReads++; return $script:log }
function Invoke-Reinit([string] $Member) { $script:reinits += @($Member) }
function Write-Host { }

function Reset-Fakes {
    $script:now = 1000.0; $script:state = 'starting'; $script:log = ''; $script:reinits = @(); $script:logReads = 0
}

# 1. 곧바로 붙음
Reset-Fakes
$w = New-RejoinWatch 'db-1' 1000.0
$script:state = 'streaming'
Step-RejoinWatch $w
Assert-Equal 'rewind' $w.Method '곧바로 붙으면 되감기'
Assert-Equal 0 $script:reinits.Count 'reinit 없음'
Assert-Equal 1000 $w.StreamingAt '붙은 시각'
Assert-Equal 'chaos-db-1' $w.Container '컨테이너 이름'
Pass 'streaming-at-once'

# 2. WAL 세그먼트가 지워졌다는 로그 → reinit 한 번, 그 뒤 붙음
Reset-Fakes
$w = New-RejoinWatch 'db-1' 1000.0
$script:now = 1003.0
$script:log = "LOG:  started streaming WAL from primary at 1/BB000000 on timeline 1`nFATAL:  could not receive data from WAL stream: ERROR:  requested WAL segment 0000000100000001000000BB has already been removed"
Step-RejoinWatch $w
Assert-Equal 1 $script:reinits.Count '지워진 WAL 로그를 보면 reinit'
Assert-Equal 'db-1' $script:reinits[0] 'reinit 대상'
Assert-Equal 'wal_segment_removed' $w.Reason '이유'
Assert-Equal 'rewind_then_reinit' $w.Method '방법'
$script:now = 1010.0
Step-RejoinWatch $w
Step-RejoinWatch $w
Assert-Equal 1 $script:reinits.Count 'reinit 은 한 번만'
$script:state = 'streaming'; $script:now = 1020.0
Step-RejoinWatch $w
Assert-Equal 1020 $w.StreamingAt '재초기화 뒤 붙은 시각'
Assert-Equal 'rewind_then_reinit' $w.Method '붙은 뒤에도 방법은 재초기화'
Pass 'wal-removed-triggers-single-reinit'

# 3. 로그 없이 오래 붙지 못함 → StuckSec 에서 reinit
Reset-Fakes
$w = New-RejoinWatch 'db-2' 1000.0 60
foreach ($t in 1005.0, 1020.0, 1040.0, 1059.0) { $script:now = $t; Step-RejoinWatch $w }
Assert-Equal 0 $script:reinits.Count 'StuckSec 전에는 reinit 없음'
$script:now = 1061.0
Step-RejoinWatch $w
Assert-Equal 1 $script:reinits.Count 'StuckSec 뒤 reinit'
Assert-Equal 'not_streaming_after_60s' $w.Reason '시간 초과 이유'
Pass 'stuck-timeout-triggers-reinit'

# 4. 로그는 2초에 한 번
Reset-Fakes
$w = New-RejoinWatch 'db-1' 1000.0
foreach ($t in 1000.5, 1001.0, 1001.5, 1002.0, 1002.6) { $script:now = $t; Step-RejoinWatch $w }
Assert-Equal 2 $script:logReads '2초 간격으로만 로그를 읽는다'
Pass 'log-read-throttle'

# 5. 이미 붙어 있던 노드는 그 표시를 지킨다(Invoke-Return 이 켜지 않은 노드)
Reset-Fakes
$w = New-RejoinWatch 'db-2' 1000.0
$w.Method = 'already_streaming'
$script:state = 'streaming'
Step-RejoinWatch $w
Assert-Equal 'already_streaming' $w.Method '이미 붙어 있던 표시'
Pass 'already-streaming-kept'

Write-Output "demo-rejoin: $passed 통과"
