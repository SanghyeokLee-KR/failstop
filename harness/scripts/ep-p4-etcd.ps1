# E-P4 etcd 장애(Patroni 설계 8-3). -Variant a: bot-1 역할 멤버(etcd-3) 하나 정지. b: 두 멤버 정지(failsafe 켬).
# c: b 와 같되 failsafe_mode 를 끈 대조군(기록). 60초 뒤 되살린다. c 는 주입 전에 patronictl 로 failsafe 를 끄고 끝에 되돌리지 않는다
# (스택은 회차 끝에 지운다).
param([ValidateSet('a', 'b', 'c')][string] $Variant = 'a', [switch] $Build, [switch] $KeepStack, [switch] $NoK6)
. (Join-Path $PSScriptRoot 'ep-lib.ps1')
$scenario = "E-P4$Variant"
$members = if ($Variant -eq 'a') { @('patroni-etcd-3') } else { @('patroni-etcd-1', 'patroni-etcd-2') }
$inject = {
    param($tl)
    if ($Variant -eq 'c') {
        $primary = Get-EpPrimaryContainer
        & docker exec $primary patronictl -c /patroni/patroni.yml edit-config -s failsafe_mode=false --force | Out-Null
        Start-Sleep -Seconds 6
    }
    foreach ($m in $members) { Assert-EpContainer $m; & docker stop $m | Out-Null }
    return @{ injected = ($members -join ','); role_injected = 'etcd'; sync_member = $null }
}
$recover = { param($tl) foreach ($m in $members) { & docker start $m | Out-Null } }
$expect = @{}
if ($Variant -eq 'c') { $expect['ExpectSuspension'] = $true }
$code = @(Invoke-EpExperiment -Scenario $scenario -Inject $inject -Recover $recover -HoldSec 60 @expect `
    -K6:(-not $NoK6) -Build:$Build -KeepStack:$KeepStack)
exit ([int]($code[-1]))
