# 격리 카오스 스택 파기. 전체 볼륨 삭제(append-only 원장의 선별 리셋 금지, §3-6).
param([switch] $KeepData)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

if ($KeepData) {
    Invoke-Compose --profile obs down
} else {
    Invoke-Compose --profile obs down -v
}
