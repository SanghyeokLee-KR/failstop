# VM 실습망 여섯 대의 nftables 규칙 파일을 저장소 원본(rules/<vm>/) 하나로 관리한다. pwsh 7 에서 실행한다.
# 동작과 안전 장치는 같은 폴더의 README.md 에 있다.
#   -Pull  [-Vm 이름,...]        VM 의 지금 파일을 원본으로 가져온다(기준선).
#   -Audit [-Vm 이름,...]        파일 해시, 커널 규칙, 서비스, 마지막 적용을 표로 본다.
#   -Plan  [-Vm 이름,...]        적용하면 바뀔 파일과 커널 규칙을 보여 준다. 바꾸지 않는다.
#   -Apply -Vm 이름,... | -All   한 대씩 검사, 백업, 90초 되돌리기 예약, 적용, 확인 순서로 올린다.
param(
    [switch]$Pull,
    [switch]$Audit,
    [switch]$Plan,
    [switch]$Apply,
    [string[]]$Vm,
    [switch]$All
)
Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

$VmNames = @('edge01', 'app01', 'app02', 'db01', 'db02', 'ops01')
# 다른 VM 의 적용 뒤 확인이 OPS01 에서 출발하므로 OPS01 을 먼저 올린다. DB 는 대기(DB02)부터 한다.
$ApplyOrder = @('ops01', 'edge01', 'db02', 'db01', 'app01', 'app02')
$Address = @{
    edge01 = '192.168.10.11'; app01 = '192.168.10.21'; app02 = '192.168.10.22'
    db01 = '192.168.10.31'; db02 = '192.168.10.32'; ops01 = '192.168.10.41'
}
$EntryUrl = 'https://192.168.10.10/api/v1/markets'
$RulesRoot = Join-Path $PSScriptRoot 'rules'
$RemoteScript = Join-Path $PSScriptRoot 'nft-central-remote.sh'
$RollbackSeconds = 90
$SshOptions = @('-o', 'ConnectTimeout=8', '-o', 'ServerAliveInterval=5', '-o', 'ServerAliveCountMax=3')
$Operator = "$env:USERNAME@$env:COMPUTERNAME" -replace '[^A-Za-z0-9@._-]', '_'

# OPS01 에서 실행한다. 모든 수집 대상이 since(유닉스 초) 뒤에 한 번 이상 수집되기를 기다려 'up 수/전체' 를 낸다.
$PromProbe = @'
import json, re, sys, time, urllib.request
from datetime import datetime
since = float(sys.argv[1])
for _ in range(15):
    targets = json.load(urllib.request.urlopen('http://127.0.0.1:9090/api/v1/targets?state=active', timeout=5))['data']['activeTargets']
    last = [datetime.fromisoformat(re.sub(r'(\.\d{6})\d+', r'\1', t['lastScrape'])).timestamp() for t in targets]
    if targets and min(last) > since:
        print('%d/%d' % (sum(t['health'] == 'up' for t in targets), len(targets)))
        break
    time.sleep(2)
else:
    print('stale')
'@

function Write-Line([string]$Text, [string]$Color = 'Gray') { Write-Host $Text -ForegroundColor $Color }
function Write-Step([string]$Text, [string]$Color = 'White') {
    Write-Host ('[{0:HH:mm:ss}] {1}' -f (Get-Date), $Text) -ForegroundColor $Color
}

# 한글은 콘솔에서 두 칸을 차지하므로 표의 열을 맞추려고 글자 폭을 센다.
function Format-Cell([string]$Text, [int]$Width) {
    $cells = 0
    foreach ($ch in $Text.ToCharArray()) { $cells += if ($ch -ge [char]0x1100) { 2 } else { 1 } }
    $Text + (' ' * [Math]::Max(1, $Width - $cells))
}

function Get-Sha256([byte[]]$Bytes) {
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant()
}

function Read-Desired([string]$Name) {
    $dir = Join-Path $RulesRoot $Name
    $main = Join-Path $dir 'firewall.nft'
    if (-not (Test-Path -LiteralPath $main -PathType Leaf)) { throw "원본이 없다: $main. 먼저 -Pull 로 기준선을 만든다." }
    $files = [ordered]@{ 'firewall.nft' = [IO.File]::ReadAllBytes($main) }
    $sub = Join-Path $dir 'firewall.d'
    if (Test-Path -LiteralPath $sub) {
        foreach ($f in Get-ChildItem -LiteralPath $sub -File | Sort-Object Name) {
            if ($f.Name -notmatch '^[A-Za-z0-9._-]+\.nft$') { throw "원본 파일 이름이 규칙에 맞지 않는다: $($f.FullName)" }
            $files["firewall.d/$($f.Name)"] = [IO.File]::ReadAllBytes($f.FullName)
        }
    }
    foreach ($rel in $files.Keys) {
        $b = $files[$rel]
        # 편집기나 Git 설정이 CR·BOM 을 넣으면 VM 파일과 해시가 달라지고 nft 도 읽지 못한다.
        if ($b -contains 13 -or ($b.Length -ge 3 -and $b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF)) {
            throw "원본에 CR 또는 BOM 이 있다: rules/$Name/$rel. 줄바꿈은 LF 만 쓰는 UTF-8 로 저장한다."
        }
    }
    $files
}

# VM 의 파일 이름은 대소문자를 가리므로 목록은 순서 비교(Ordinal) 사전에 담는다. @{} 는 대소문자를 합친다.
function Get-WantManifest($Files) {
    $m = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    foreach ($rel in $Files.Keys) { $m[$rel] = Get-Sha256 $Files[$rel] }
    $m
}

# 원본 묶음을 짧게 가리키는 값. VM 의 적용 기록(set=)에 남겨 어느 원본을 올렸는지 찾는다.
function Get-SetId($Files) {
    $keys = [string[]]@($Files.Keys)
    [Array]::Sort($keys, [StringComparer]::Ordinal)
    $text = -join ($keys | ForEach-Object { "$(Get-Sha256 $Files[$_]) $_`n" })
    (Get-Sha256 ([Text.Encoding]::UTF8.GetBytes($text))).Substring(0, 12)
}

function Invoke-Remote([string]$Name, [string]$Action, $Files, [hashtable]$Extra) {
    $vars = [ordered]@{
        NC_ACTION = $Action; NC_VM = $Name; NC_ID = ''; NC_SET = ''; NC_BY = $Operator
        NC_ROLLBACK_SEC = "$RollbackSeconds"; NC_CHECKS = ''
    }
    if ($Extra) { foreach ($k in $Extra.Keys) { $vars[$k] = "$($Extra[$k])" } }
    $sb = [Text.StringBuilder]::new()
    foreach ($k in $vars.Keys) {
        if ($vars[$k] -notmatch '^[A-Za-z0-9@._/:-]*$') { throw "원격 변수 값에 허용하지 않는 문자가 있다: $k" }
        [void]$sb.Append("$k='$($vars[$k])'`n")
    }
    [void]$sb.Append([IO.File]::ReadAllText($RemoteScript)).Append("`n")
    if ($Files) {
        [void]$sb.Append("nc_begin`n")
        foreach ($rel in $Files.Keys) {
            $b64 = [Convert]::ToBase64String($Files[$rel], [Base64FormattingOptions]::InsertLineBreaks)
            [void]$sb.Append("nc_put '$rel' << 'NC_B64'`n").Append($b64).Append("`nNC_B64`n")
        }
    }
    [void]$sb.Append("nc_main`n")
    # 스크립트를 VM 의 임시 파일로 끝까지 받은 뒤 실행한다. 실행 중인 명령이 남은 입력을 읽어 가지 않게 한다.
    $wrapper = 'f=$(mktemp) && tr -d ''\r'' > "$f" && sudo bash "$f" 2>&1; rc=$?; rm -f "$f"; exit $rc'
    $lines = @($sb.ToString() | ssh @SshOptions $Name $wrapper 2>&1 | ForEach-Object { "$_" })
    # 값 줄은 '@이름 ...' 꼴이다. diff 의 '@@' 줄은 화면으로 보낸다.
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Text = @($lines | Where-Object { $_ -cnotmatch '^@[a-z]' })
        Data = @($lines | Where-Object { $_ -cmatch '^@[a-z]' })
    }
}

function Get-Datum($Result, [string]$Key) {
    $line = $Result.Data | Where-Object { $_.StartsWith("@$Key ") } | Select-Object -First 1
    if ($line) { $line.Substring($Key.Length + 2) } else { '' }
}

# 이름은 셋째 칸 전체다. VM 에 공백이 든 이름이 있어도 목록에서 빠지지 않게 한다.
function Get-Manifest($Result, [string]$Key = 'f') {
    $m = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    foreach ($line in $Result.Data) {
        $p = $line -split ' ', 3
        if ($p[0] -ne "@$Key") { continue }
        if ($p.Count -ne 3 -or $p[1] -cnotmatch '^[0-9a-f]{64}$') { throw "VM 이 보낸 파일 목록 줄의 형식이 틀리다: $line" }
        $m[$p[2]] = $p[1]
    }
    $m
}

# Want 는 원하는 상태(원본), Have 는 VM 의 파일이다. 결과 이름은 적용했을 때 일어날 일을 따른다.
function Compare-Manifest($Want, $Have) {
    $changed = @($Want.Keys | Where-Object { $Have.ContainsKey($_) -and $Have[$_] -ne $Want[$_] } | Sort-Object)
    $added = @($Want.Keys | Where-Object { -not $Have.ContainsKey($_) } | Sort-Object)
    $removed = @($Have.Keys | Where-Object { -not $Want.ContainsKey($_) } | Sort-Object)
    [pscustomobject]@{
        Changed = $changed; Added = $added; Removed = $removed
        Same = ($changed.Count + $added.Count + $removed.Count) -eq 0
    }
}

function Invoke-Ssh([string]$Name, [string]$Command, [string]$InputText) {
    if ($InputText) { $out = $InputText | ssh @SshOptions $Name $Command 2>&1 }
    else { $out = ssh -n @SshOptions $Name $Command 2>&1 }
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Text = (@($out | ForEach-Object { "$_" }) -join "`n").Trim() }
}

# 원본은 저장소에 올라가므로 비밀 값으로 보이는 줄이 있는 VM 은 가져오지 않는다.
# 줄 번호만 돌려준다. 내용을 화면에 찍으면 그 값이 터미널 기록과 캡처에 남는다.
function Find-SecretLike([byte[]]$Bytes) {
    $lines = [Text.Encoding]::UTF8.GetString($Bytes) -split "`n"
    @($lines | Select-String -Pattern 'password|passwd|secret|token|api[_-]?key|private key|webhook|powerplatform' |
        ForEach-Object { $_.LineNumber })
}

# Expect 가 same 인 확인은 값이 Pattern 형식이어야 하고, 적용 뒤에는 적용 전 값과도 같아야 한다.
# 형식을 보지 않으면 조회 오류 문구가 적용 전후에 똑같이 나와도 통과한다.
function New-Check([string]$Label, [string]$Kind, [string]$From, [string]$Command, [string]$Expect, [string]$Pattern) {
    [pscustomobject]@{ Label = $Label; Kind = $Kind; From = $From; Command = $Command; Expect = $Expect; Pattern = $Pattern }
}

function Test-CheckValue($Check, [string]$Value, $Before) {
    if ($Check.Expect -ne 'same') { return $Value -eq $Check.Expect }
    ($Value -cmatch $Check.Pattern) -and ($null -eq $Before -or $Value -ceq $Before)
}

# 역할별 확인. same 은 적용 전에 잰 값과 같아야 한다(앱 리더가 바뀌지 않았는지 등).
function Get-Checks([string]$Name) {
    $ip = $Address[$Name]
    $http = "curl -s -m 5 -o /dev/null -w '%{http_code}'"
    $open = { param($target, $port) "nc -z -w 3 $target $port && echo open" }
    $readiness = 'for ip in 192.168.10.21 192.168.10.22; do printf "%s " $(curl -s -m 5 http://$ip:8081/actuator/health/readiness); done'
    $status = '\{"status":"[A-Z_]+"\}'
    $list = [Collections.Generic.List[object]]::new()
    $list.Add((New-Check '새 SSH 연결(작업 PC)' ssh $Name '' '연결됨'))
    switch ($Name) {
        'edge01' {
            $list.Add((New-Check 'L7 입구 경유 웹 443' web '' '' '200'))
            $list.Add((New-Check '관제에서 웹 443' vm ops01 "curl -sk -m 5 -o /dev/null -w '%{http_code}' https://$ip/api/v1/markets" '200'))
            $list.Add((New-Check '관제에서 백업 SFTP 22' vm ops01 (& $open $ip 22) 'open'))
            $list.Add((New-Check '관제에서 node_exporter' vm ops01 "$http http://${ip}:9100/metrics" '200'))
        }
        { $_ -in 'app01', 'app02' } {
            $list.Add((New-Check '관제에서 readiness 8081' vm ops01 "curl -s -m 5 http://${ip}:8081/actuator/health/readiness" 'same' "^$status$"))
            $list.Add((New-Check '웹 진입에서 API 8080' vm edge01 (& $open $ip 8080) 'open'))
            $list.Add((New-Check '관제에서 node_exporter' vm ops01 "$http http://${ip}:9100/metrics" '200'))
            $list.Add((New-Check 'L7 입구 경유 웹 443' web '' '' '200'))
        }
        'db01' {
            $list.Add((New-Check '앱1에서 DB 5432' vm app01 (& $open $ip 5432) 'open'))
            $list.Add((New-Check '앱2에서 DB 5432' vm app02 (& $open $ip 5432) 'open'))
            $list.Add((New-Check 'DB02에서 복제 5432' vm db02 (& $open $ip 5432) 'open'))
            $list.Add((New-Check '관제에서 postgres_exporter' vm ops01 "$http http://${ip}:9187/metrics" '200'))
            $list.Add((New-Check '관제에서 node_exporter' vm ops01 "$http http://${ip}:9100/metrics" '200'))
            $list.Add((New-Check '앱 두 대 readiness' vm ops01 $readiness 'same' "^$status $status$"))
            $list.Add((New-Check 'L7 입구 경유 웹 443' web '' '' '200'))
        }
        'db02' {
            $list.Add((New-Check '복제 수신 상태' vm db02 'sudo -u postgres psql -XAtc "select status from pg_stat_wal_receiver"' 'streaming'))
            $list.Add((New-Check '관제에서 postgres_exporter' vm ops01 "$http http://${ip}:9187/metrics" '200'))
            $list.Add((New-Check '관제에서 node_exporter' vm ops01 "$http http://${ip}:9100/metrics" '200'))
        }
        'ops01' {
            $list.Add((New-Check 'Prometheus 수집 대상 up' prom ops01 '' 'same' '^[0-9]+/[1-9][0-9]*$'))
            $list.Add((New-Check '방화벽 원격 로그 수신' syslog ops01 '' '수신'))
        }
    }
    $list.Add((New-Check '파일과 커널 규칙' state $Name '' '원본과 같음'))
    $list
}

function Invoke-Check($Check, [hashtable]$Ctx) {
    switch ($Check.Kind) {
        'ssh' {
            $r = Invoke-Ssh $Check.From 'true'
            if ($r.ExitCode -eq 0) { '연결됨' } else { '연결 안 됨' }
        }
        'web' { "$(curl.exe -ks -m 8 -o NUL -w '%{http_code}' $EntryUrl)" }
        'vm' {
            $r = Invoke-Ssh $Check.From $Check.Command
            if ($r.ExitCode -eq 255) { 'SSH 실패' } elseif ($r.Text) { $r.Text } else { '응답 없음' }
        }
        'prom' {
            $r = Invoke-Ssh ops01 "python3 - $($Ctx.Since)" $PromProbe
            if ($r.ExitCode -eq 0) { $r.Text } else { "조회 실패(종료 코드 $($r.ExitCode))" }
        }
        'syslog' {
            # L7 입구에 표식을 단 요청을 보내고, 투명 방화벽이 OPS01 로 보낸 HAProxy 기록에서 그 표식을 찾는다.
            $marker = 'nc{0}' -f (Get-Random -Minimum 100000 -Maximum 999999)
            $null = curl.exe -ks -m 8 -o NUL ('{0}?probe={1}' -f $EntryUrl, $marker)
            $r = Invoke-Ssh ops01 "for i in 1 2 3 4 5 6 7 8 9 10; do sudo grep -qF $marker /var/log/pfsense/haproxy.log && { echo ok; exit; }; sleep 1; done; echo none"
            if ($r.Text -eq 'ok') { '수신' } else { '안 옴' }
        }
        'state' {
            $r = Invoke-Remote $Check.From audit
            if ($r.ExitCode -ne 0) { return '확인 불가' }
            # 응답 형식 오류는 도구를 멈추지 않고 확인 실패로 돌려, 걸어 둔 되돌리기 예약이 그대로 진행되게 한다.
            try { $have = Get-Manifest $r } catch { return '확인 불가(응답 형식 오류)' }
            $files = if ((Compare-Manifest $Ctx.Want $have).Same) { '' } else { '파일 다름 ' }
            $kernel = if ((Get-Datum $r 'kernel') -eq 'same') { '' } else { '커널 다름' }
            if ($files -or $kernel) { "$files$kernel".Trim() } else { '원본과 같음' }
        }
    }
}

function Write-CheckRow([bool]$Ok, [string]$Label, [string]$Value) {
    $mark = if ($Ok) { '통과' } else { '실패' }
    Write-Line ('   {0}  {1}{2}' -f $mark, (Format-Cell $Label 30), $Value) $(if ($Ok) { 'Green' } else { 'Red' })
}

function Write-Diff([string[]]$Lines) {
    foreach ($l in $Lines) {
        $color = 'Gray'
        if ($l.StartsWith('+++') -or $l.StartsWith('---')) { $color = 'White' }
        elseif ($l.StartsWith('+')) { $color = 'Green' }
        elseif ($l.StartsWith('-')) { $color = 'Red' }
        elseif ($l.StartsWith('@@')) { $color = 'Cyan' }
        elseif ($l -match '^(추가|삭제|변경): ') { $color = 'Yellow' }
        Write-Line $l $color
    }
}

function Invoke-PullOne([string]$Name) {
    $r = Invoke-Remote $Name pull
    if ($r.ExitCode -ne 0) {
        $r.Text | ForEach-Object { Write-Line $_ Red }
        Write-Line "== $Name 가져오기 실패(종료 코드 $($r.ExitCode))" Red
        return $false
    }
    # VM 의 이름은 대소문자를 가리므로 순서 비교 사전에 담는다.
    $got = [Collections.Generic.Dictionary[string, byte[]]]::new([StringComparer]::Ordinal)
    foreach ($line in $r.Data) {
        $p = $line -split ' '
        if ($p[0] -ne '@file') { continue }
        if ($p.Count -ne 3 -or $p[1] -cnotmatch '^(firewall\.nft|firewall\.d/[A-Za-z0-9._-]+\.nft)$') {
            throw "VM 이 보낸 파일 줄의 형식이 틀리다: $($line.Substring(0, [Math]::Min(80, $line.Length)))"
        }
        $got[$p[1]] = [Convert]::FromBase64String($p[2])
    }
    if (-not $got.ContainsKey('firewall.nft')) { throw "$Name 에서 firewall.nft 를 받지 못했다." }
    if (@($got.Keys | ForEach-Object { $_.ToLowerInvariant() } | Sort-Object -Unique).Count -ne $got.Count) {
        Write-Line "== $Name 가져오지 않음: 대소문자만 다른 규칙 파일 이름이 있어 Windows 원본 폴더에 함께 둘 수 없다. VM 에서 이름을 먼저 고친다." Red
        return $false
    }
    foreach ($rel in $got.Keys) {
        $suspect = Find-SecretLike $got[$rel]
        if ($suspect) {
            Write-Line "== $Name 가져오지 않음: $rel 의 $($suspect -join ', ')번째 줄이 비밀 값처럼 보인다. 내용은 출력하지 않는다." Red
            return $false
        }
    }
    $dir = Join-Path $RulesRoot $Name
    $sub = Join-Path $dir 'firewall.d'
    $null = New-Item -ItemType Directory -Force -Path $sub
    $new = 0; $updated = 0; $removed = 0
    # 이름이 대소문자까지 VM 과 같지 않은 원본 파일을 먼저 지운다. Windows 는 대소문자만 다른 이름을
    # 같은 파일로 보므로, 나중에 지우면 방금 쓴 내용까지 지운다.
    foreach ($f in Get-ChildItem -LiteralPath $sub -File) {
        if (-not $got.ContainsKey("firewall.d/$($f.Name)")) { Remove-Item -LiteralPath $f.FullName; $removed++ }
    }
    foreach ($rel in $got.Keys) {
        $path = Join-Path $dir $rel
        if (Test-Path -LiteralPath $path) {
            if ((Get-Sha256 ([IO.File]::ReadAllBytes($path))) -eq (Get-Sha256 $got[$rel])) { continue }
            $updated++
        } else { $new++ }
        [IO.File]::WriteAllBytes($path, $got[$rel])
    }
    Write-Line ('== {0}  VM 파일 {1}개를 원본으로: 새로 {2}, 바뀜 {3}, 지움 {4}  (묶음 {5})' -f $Name, $got.Count, $new, $updated, $removed, (Get-SetId $got)) Cyan
    foreach ($rel in $got.Keys) { Write-Line ('   {0}  {1}' -f (Get-Sha256 $got[$rel]).Substring(0, 12), $rel) }
    $r.Text | ForEach-Object { Write-Line "   $_" Yellow }
    $true
}

function Invoke-AuditAll([string[]]$Names) {
    Write-Line ('nftables 중앙 관리 점검  {0:yyyy-MM-dd HH:mm:ss}  원본 deploy/vm-lab/nft-central/rules' -f (Get-Date)) White
    $widths = 8, 12, 14, 10, 8, 22
    $header = 'VM', '파일', '커널 규칙', '규칙 수', '서비스', '마지막 적용', '판정'
    Write-Line (-join (0..5 | ForEach-Object { Format-Cell $header[$_] $widths[$_] }) + $header[6]) White
    $details = [Collections.Generic.List[string]]::new()
    $worst = 0
    foreach ($name in $Names) {
        $want = Get-WantManifest (Read-Desired $name)
        $r = Invoke-Remote $name audit
        $problem = if ($r.ExitCode -ne 0) { @($r.Text | Select-Object -Last 2) } else { @() }
        if (-not $problem) {
            try { $have = Get-Manifest $r } catch { $problem = @("$_") }
        }
        if ($problem) {
            Write-Line ((Format-Cell $name 8) + (Format-Cell '접속, VM 명령 또는 응답 형식 실패' 66) + '확인 불가') Yellow
            $problem | ForEach-Object { $details.Add("  $name  $_") }
            $worst = 2
            continue
        }
        $cmp = Compare-Manifest $want $have
        $kernel = Get-Datum $r 'kernel'
        $svc = Get-Datum $r 'svc'
        $rules = (Get-Datum $r 'rules') -split ' '
        $last = Get-Datum $r 'last'
        $lastCell = if ($last -match '^\d{4}-(\d\d-\d\d)T(\d\d:\d\d).* action=(\S+)') { "$($Matches[1]) $($Matches[2]) $($Matches[3])" } else { '기록 없음' }
        $fileCell = if ($cmp.Same) { "일치 $($want.Count)개" } else { "다름 $($cmp.Changed.Count + $cmp.Added.Count + $cmp.Removed.Count)개" }
        $kernelCell = switch ($kernel) { 'same' { '파일과 같음' } 'diff' { '파일과 다름' } default { '확인 실패' } }
        $ok = $cmp.Same -and $kernel -eq 'same' -and $svc -eq 'active'
        $cells = $name, $fileCell, $kernelCell, "$($rules[0]) / $($rules[1])", $svc, $lastCell
        $row = -join (0..5 | ForEach-Object { Format-Cell $cells[$_] $widths[$_] })
        Write-Line ($row + $(if ($ok) { '일치' } else { '어긋남' })) $(if ($ok) { 'Green' } else { 'Red' })
        if (-not $ok) {
            $worst = [Math]::Max($worst, 1)
            $cmp.Changed | ForEach-Object { $details.Add("  $name  내용 다름: $_") }
            $cmp.Removed | ForEach-Object { $details.Add("  $name  원본에 없는 파일이 VM 에 있음: $_") }
            $cmp.Added | ForEach-Object { $details.Add("  $name  원본 파일이 VM 에 없음: $_") }
            if ($kernel -ne 'same') { $details.Add("  $name  커널 규칙이 VM 의 파일로 만든 규칙과 다르다(파일을 거치지 않은 수동 변경이거나 적재 안 됨)") }
            if ($svc -ne 'active') { $details.Add("  $name  vm-lab-firewall.service 가 $svc 다") }
        }
    }
    Write-Line '규칙 수 = inet vmlab 테이블 / nft list ruleset 전체(OPS01 은 Docker 테이블 포함)' DarkGray
    if ($details.Count) {
        Write-Line '어긋난 곳' Yellow
        $details | ForEach-Object { Write-Line $_ Yellow }
        Write-Line '차이는 -Plan -Vm <이름> 으로 보고, 원본대로 되돌리려면 -Apply -Vm <이름> 을 쓴다.' Yellow
    }
    $worst
}

# 돌려주는 값은 종료 코드와 같다. 0 바꿀 것 없음, 1 차이 있음, 2 구문 검사 실패나 VM 명령 실패.
function Invoke-PlanOne([string]$Name) {
    $files = Read-Desired $Name
    Write-Line ('== {0} 계획  원본 {1}개 파일, 묶음 {2}' -f $Name, $files.Count, (Get-SetId $files)) Cyan
    $r = Invoke-Remote $Name plan $files
    if ($r.ExitCode -ne 0) {
        $r.Text | ForEach-Object { Write-Line $_ Red }
        Write-Line "VM 명령이 실패했다(종료 코드 $($r.ExitCode))." Red
        return 2
    }
    Write-Diff $r.Text
    $f = (Get-Datum $r 'files') -split ' '
    $rules = (Get-Datum $r 'rules') -split ' '
    $checkOk = (Get-Datum $r 'check') -eq 'ok'
    $kernelState = Get-Datum $r 'kernel'
    $kernel = switch ($kernelState) { 'same' { '바뀌지 않음' } 'diff' { "$($rules[0])개에서 $($rules[1])개로 바뀜" } default { '계산 못 함' } }
    Write-Line ('요약: 파일 변경 {0}, 추가 {1}, 삭제 {2} / 구문 검사(nft -c) {3} / 커널 규칙 {4}' -f $f[0], $f[1], $f[2], $(if ($checkOk) { '통과' } else { '실패' }), $kernel) White
    if (-not $checkOk) { return 2 }
    if ([int]$f[0] + [int]$f[1] + [int]$f[2] -eq 0 -and $kernelState -eq 'same') { return 0 }
    1
}

# 확인에 실패했거나 결과를 받지 못한 적용이 어떻게 끝났는지 VM 기록에서 찾는다.
# 취소 명령이 VM 에 닿은 뒤 응답만 잃었으면 rollback 대신 confirm 줄이 남는다.
function Wait-Rollback([string]$Name, [string]$Id, [datetime]$Deadline) {
    $sshWorks = $null
    $line = ''
    while ((Get-Date) -lt $Deadline) {
        $r = Invoke-Ssh $Name "sudo grep -E ' id=$Id action=(rollback|confirm) ' /var/log/vm-lab/nft-central.log"
        $now = $r.ExitCode -ne 255
        if ($now -ne $sshWorks) {
            Write-Step $(if ($now) { '새 SSH 연결 됨' } else { '새 SSH 연결 안 됨. 되돌리기를 기다린다.' }) Yellow
            $sshWorks = $now
        }
        if ($r.ExitCode -eq 0) { $line = $r.Text; break }
        Start-Sleep -Seconds 3
    }
    if (-not $line) {
        Write-Step "기다리는 시간 안에 되돌리기나 확인 기록을 찾지 못했다. -Audit -Vm $Name 과 VM 콘솔로 상태를 본다." Red
        return
    }
    Write-Step "VM 기록: $line" Yellow
    if ($line -match ' action=confirm ') {
        Write-Step '취소 명령은 VM 에 닿았고 응답만 받지 못했다. 새 규칙이 그대로이며 되돌리지 않는다.' Yellow
        return
    }
    $res = Invoke-Remote $Name result $null @{ NC_ID = $Id }
    $problem = if ($res.ExitCode -ne 0) { "종료 코드 $($res.ExitCode)" } else { '' }
    if (-not $problem) {
        try {
            $beforeFiles = Get-Manifest $res 'before'
            $afterFiles = Get-Manifest $res
            if ($beforeFiles.Count -eq 0) { $problem = '적용 전 목록이 비었다' }
        } catch { $problem = "$_" }
    }
    if ($problem) {
        $res.Text | ForEach-Object { Write-Line $_ Red }
        Write-Step "되돌린 뒤 상태를 확인하지 못했다($problem). -Audit -Vm $Name 으로 다시 본다." Red
        return
    }
    $cmp = Compare-Manifest $beforeFiles $afterFiles
    $kernel = Get-Datum $res 'kernel'
    $state = if ($cmp.Same) { '적용 전 파일과 같다' } else { '적용 전 파일과 다르다' }
    $kstate = if ($kernel -eq 'same') { '커널 규칙 = 파일' } else { '커널 규칙이 파일과 다르다' }
    Write-Step "되돌린 뒤: $state, $kstate" $(if ($cmp.Same -and $kernel -eq 'same') { 'Yellow' } else { 'Red' })
}

function Invoke-ApplyOne([string]$Name) {
    $files = Read-Desired $Name
    $setId = Get-SetId $files
    $want = Get-WantManifest $files
    Write-Line ''
    Write-Line ('== {0} 적용  원본 {1}개 파일, 묶음 {2}' -f $Name, $files.Count, $setId) Cyan
    $plan = Invoke-Remote $Name plan $files
    if ($plan.ExitCode -ne 0) {
        $plan.Text | ForEach-Object { Write-Line $_ Red }
        Write-Step '계획 단계의 VM 명령이 실패했다. 아무것도 바꾸지 않았다.' Red
        return $false
    }
    $f = (Get-Datum $plan 'files') -split ' '
    Write-Step ('파일 대조: 변경 {0}, 추가 {1}, 삭제 {2}' -f $f[0], $f[1], $f[2])
    $plan.Text | Where-Object { $_ -match '^(추가|삭제|변경): ' } | ForEach-Object { Write-Line "           $_" Yellow }
    if ((Get-Datum $plan 'check') -ne 'ok') {
        Write-Diff @($plan.Text | Where-Object { $_ -notmatch '^(추가|삭제|변경): ' })
        Write-Step '구문 검사(nft -c) 실패. 아무것도 바꾸지 않았다.' Red
        return $false
    }
    Write-Step '구문 검사(nft -c, firewall.nft 와 원본 firewall.d 전체): 통과' Green
    if ([int]$f[0] + [int]$f[1] + [int]$f[2] -eq 0 -and (Get-Datum $plan 'kernel') -eq 'same') {
        Write-Step '바꿀 것이 없다. 건너뛴다.'
        return $true
    }
    $rules = (Get-Datum $plan 'rules') -split ' '
    $only = if ([int]$f[0] + [int]$f[1] + [int]$f[2] -eq 0) { ' (파일은 같고 커널만 다르다. 파일을 거치지 않은 변경이다)' } else { '' }
    Write-Step "커널 규칙: $($rules[0])개에서 $($rules[1])개로$only"

    $checks = Get-Checks $Name
    Write-Step '적용 전 확인'
    $before = @{}
    $preOk = $true
    foreach ($c in $checks | Where-Object Kind -ne 'state') {
        $v = Invoke-Check $c @{ Since = 0 }
        $before[$c.Label] = $v
        $ok = Test-CheckValue $c $v $null
        Write-CheckRow $ok $c.Label $v
        if (-not $ok) { $preOk = $false }
    }
    if (-not $preOk) {
        Write-Step '적용 전 확인이 실패했다. 이미 문제가 있는 상태라 적용하지 않는다.' Red
        return $false
    }

    $id = Get-Date -Format 'yyyyMMdd-HHmmss'
    $res = Invoke-Remote $Name apply $files @{ NC_ID = $id; NC_SET = $setId }
    $result = Get-Datum $res 'result'
    $deadline = (Get-Date).AddSeconds($RollbackSeconds + 60)
    switch ($result) {
        'pending' { }
        'check-failed' {
            $res.Text | ForEach-Object { Write-Line $_ Red }
            Write-Step 'VM 에서 구문 검사에 실패했다. 아무것도 바꾸지 않았다.' Red
            return $false
        }
        'rolled-back-now' {
            $res.Text | ForEach-Object { Write-Line $_ Red }
            Write-Step '적용 직후 VM 안의 대조가 실패해 바로 되돌렸다.' Red
            return $false
        }
        default {
            $res.Text | ForEach-Object { Write-Line $_ Red }
            # @backup 줄은 되돌리기 예약 직후에 나온다. SSH 가 살아 있었는데 그 줄이 없으면 예약 전에 멈춘 것이다.
            if ($res.ExitCode -ne 255 -and -not (Get-Datum $res 'backup')) {
                Write-Step "VM 이 되돌리기 예약 전에 멈췄다(종료 코드 $($res.ExitCode)). 아무것도 바꾸지 않았다." Red
                return $false
            }
            Write-Step "적용 결과를 받지 못했다(종료 코드 $($res.ExitCode)). 예약이 섰다면 VM 이 스스로 되돌린다." Red
            Wait-Rollback $Name $id $deadline
            return $false
        }
    }
    Write-Step ('적용({0}): 백업 {1}' -f $id, (Get-Datum $res 'backup'))
    Write-Step ('되돌리기 예약: {0} ({1}초 뒤, 확인이 오지 않으면 VM 이 스스로 되돌린다)' -f (Get-Datum $res 'rollback-at'), $RollbackSeconds)

    Write-Step '적용 뒤 확인'
    $ctx = @{ Since = [long](Get-Datum $res 'applied'); Want = $want }
    $pass = 0
    foreach ($c in $checks) {
        $v = Invoke-Check $c $ctx
        $ok = Test-CheckValue $c $v $before[$c.Label]
        $shown = if ($ok -or $c.Expect -ne 'same') { $v } else { "$v (적용 전 $($before[$c.Label]))" }
        Write-CheckRow $ok $c.Label $shown
        if ($ok) { $pass++ }
    }
    if ($pass -eq $checks.Count) {
        $conf = Invoke-Remote $Name confirm $null @{ NC_ID = $id; NC_CHECKS = "$pass/$($checks.Count)" }
        $answer = Get-Datum $conf 'confirm'
        if ($answer -eq 'ok') {
            Write-Step "확인 $pass/$($checks.Count) 통과. 되돌리기 예약을 취소했다." Green
            return $true
        }
        $conf.Text | ForEach-Object { Write-Line $_ Red }
        if ($answer -eq 'too-late') {
            Write-Step '확인이 늦어 VM 이 이미 되돌렸다.' Red
            return $false
        }
        Write-Step "확인을 VM 에 전하지 못했다(종료 코드 $($conf.ExitCode)). 예약대로 VM 이 스스로 되돌린다." Red
        Wait-Rollback $Name $id $deadline
        return $false
    }
    Write-Step "확인 $pass/$($checks.Count) 통과. 예약을 취소하지 않는다. VM 이 $(Get-Datum $res 'rollback-at') 에 스스로 되돌린다." Red
    Wait-Rollback $Name $id $deadline
    $false
}

$savedEncoding = [Console]::OutputEncoding
$utf8 = [Text.UTF8Encoding]::new($false)
# 한국어 Windows 콘솔(CP949)에서도 VM 의 UTF-8 출력이 깨지지 않게 한다. 끝나면 되돌린다.
if ($savedEncoding.CodePage -ne 65001) { [Console]::OutputEncoding = $utf8 }
$OutputEncoding = $utf8
$code = 0
$summary = ''
$applying = ''
try {
    # 끝에 결과와 종료 코드를 한 줄로 알린다. 캡처만 남는 실행에서도 종료 코드를 볼 수 있다.
    # 인자·원본 오류와 예상하지 못한 오류는 2 로 끝낸다. 1 은 어긋남과 적용 실패에만 쓴다.
    try {
        $actions = @('Pull', 'Audit', 'Plan', 'Apply' | Where-Object { (Get-Variable -Name $_ -ValueOnly).IsPresent })
        if ($actions.Count -ne 1) { throw '-Pull, -Audit, -Plan, -Apply 가운데 하나만 준다.' }
        # pwsh -File 로 부르면 'ops01,db01' 이 문자열 하나로 들어온다. 나눈 뒤에 대상 조건을 본다.
        $Vm = @($Vm | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
        if ($Vm.Count -and $All) { throw '-Vm 과 -All 은 함께 쓰지 않는다.' }
        if ($Apply -and -not $Vm.Count -and -not $All) { throw '-Apply 는 -Vm <이름> 또는 -All 과 함께 쓴다.' }
        foreach ($n in $Vm) { if ($n -notin $VmNames) { throw "모르는 VM 이다: $n" } }
        $targets = if ($Vm.Count) { @($VmNames | Where-Object { $_ -in $Vm }) } else { $VmNames }
        switch ($actions[0]) {
            'Pull' {
                $failed = 0
                foreach ($n in $targets) { if (-not (Invoke-PullOne $n)) { $failed++ } }
                if ($failed) { $code = 1 }
                $summary = '가져온 VM {0}대, 실패 {1}대' -f ($targets.Count - $failed), $failed
            }
            'Audit' {
                $code = Invoke-AuditAll $targets
                $summary = @('모두 일치', '어긋난 VM 있음', '확인하지 못한 VM 있음')[$code]
            }
            'Plan' {
                foreach ($n in $targets) { $code = [Math]::Max($code, (Invoke-PlanOne $n)) }
                $summary = @('바꿀 것 없음', '바뀔 것 있음', '구문 검사 또는 VM 명령 실패')[$code]
            }
            'Apply' {
                $order = @($ApplyOrder | Where-Object { $_ -in $targets })
                # VM 을 건드리기 전에 대상 원본을 모두 읽어 이름·CR·BOM 오류를 먼저 찾는다.
                foreach ($n in $order) { $null = Read-Desired $n }
                Write-Line ('적용 순서: {0} (한 대씩, 확인 실패 시 {1}초 뒤 자동 되돌림)' -f ($order -join ', '), $RollbackSeconds) White
                $done = 0
                foreach ($n in $order) {
                    $applying = $n
                    if (Invoke-ApplyOne $n) { $done++; continue }
                    $rest = @($order | Select-Object -Skip ([array]::IndexOf($order, $n) + 1))
                    if ($rest) { Write-Line "여기서 멈춘다. 적용하지 않은 VM: $($rest -join ', ')" Red }
                    $code = 1
                    break
                }
                $summary = if ($code) { '{0}대 가운데 {1}대를 끝내고 멈췄다' -f $order.Count, $done } else { '{0}대 모두 끝났다(바꿀 것이 없어 건너뛴 VM 포함)' -f $order.Count }
            }
        }
    } catch {
        Write-Line "도구 오류: $($_.Exception.Message)" Red
        if ($applying) { Write-Line "$applying 에 되돌리기 예약이 섰다면 확인이 오지 않으므로 VM 이 예약 시각에 스스로 되돌린다. -Audit -Vm $applying 로 상태를 본다." Red }
        $code = 2
        $summary = '도구 오류로 멈췄다'
    }
    Write-Line ('결과: {0}. 종료 코드 {1}' -f $summary, $code) $(if ($code) { 'Yellow' } else { 'White' })
} finally {
    if ($savedEncoding.CodePage -ne 65001) { [Console]::OutputEncoding = $savedEncoding }
}
exit $code
