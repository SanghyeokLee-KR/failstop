param([string]$Origin = 'https://www.sang.it.kr')
$ErrorActionPreference = 'Stop'
$uri = [Uri]$Origin
if ($uri.Scheme -ne 'https' -or $uri.IsLoopback -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment) {
    throw '외부 HTTPS 주소만 지정하세요. 경로와 쿼리는 넣지 않습니다.'
}
$root = (Resolve-Path "$PSScriptRoot/../../..").Path
$values = @{
    NEXT_PUBLIC_DATASOURCE = 'http'
    NEXT_PUBLIC_ADMIN_DATASOURCE = 'http'
    NEXT_PUBLIC_ADMIN_AUTH = 'password'
    NEXT_PUBLIC_API_BASE = $Origin.TrimEnd('/')
    NEXT_PUBLIC_WS_BASE = $Origin.TrimEnd('/').Replace('https://', 'wss://')
    NEXT_PUBLIC_ADMIN_BASE = $Origin.TrimEnd('/')
}
$before = @{}
try {
    # 빈 환경 변수는 PowerShell 버전에 따라 제거되어 .env.local 값이 살아난다.
    foreach ($name in $values.Keys) {
        $before[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process')
    }
    & npm --prefix "$root/web" run build
    if ($LASTEXITCODE -ne 0) { throw '웹 빌드 실패' }
    $bad = Get-ChildItem "$root/web/out/_next/static" -Recurse -Filter '*.js' |
        Select-String -Pattern '(https?|wss?)://(localhost|127\.0\.0\.1)(:|/)' -List
    if ($bad) { throw '배포 번들에 로컬 서버 주소가 남아 있습니다.' }
    Write-Output "PASS: API=$($values.NEXT_PUBLIC_API_BASE), WS=$($values.NEXT_PUBLIC_WS_BASE)"
} finally {
    foreach ($name in $before.Keys) {
        [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process')
    }
}
