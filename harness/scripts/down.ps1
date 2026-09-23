# 격리 카오스 스택 파기. 전체 볼륨 삭제(append-only 원장의 선별 리셋 금지, §3-6).
# 대상 프로젝트는 lib.ps1 의 CHAOS_PROJECT(기본 exchange-chaos)다. 폐기용 스택은 CHAOS_PROJECT 를 주고 부른다.
param([switch] $KeepData)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

if ($KeepData) {
    Invoke-Compose --profile obs down
} else {
    Invoke-Compose --profile obs down -v
}
