# VM 운영환경 여섯 대를 만들고 Rocky Linux 10.2 무인 설치를 시작한다.
# pfSense는 이 스크립트의 대상이 아니다. 사용자가 직접 구축한다.
#
# 설치 경로는 이 스크립트 하나다(02-정본결정.md 2절). inst.ks HTTP 안내와
# ks-<이름>.cfg 두 번째 세트는 쓰지 않는다.
#
# 값의 기준은 build\00-주소계획.md 다. 여기와 다르면 계획서를 먼저 고친다.
# 실행: pwsh -NoProfile -File 01-create-vms.ps1 -Role all
#       pwsh -NoProfile -File 01-create-vms.ps1 -Role db01 -WhatIfOnly
#
# 이 스크립트는 ks 원본을 고치지 않는다. 임시 폴더에 사본을 만들어 자리표시자를
# 채우고 그 사본을 --script-template 으로 넘긴다. 치환하는 자리표시자는 아래 다섯 개다.
#   @@OPS_SSH_PUBKEY@@ -> 관리 공개키 한 줄(-PubKey 파일의 내용)
#   __OPSKEY__         -> 같은 값
#   __HOSTNAME__       -> 정본 호스트 이름 <이름>.lab
#   __LANIP__          -> lab-lan 주소
#   __MGMTIP__         -> host-only 주소
# 치환 후 @@ 또는 __[A-Z_]+__ 가 남으면 설치하지 않고 멈춘다. 주석 안의 자리표시자도
# 같이 채워지지만 사본에서만 그렇고 원본 ks 파일은 손대지 않는다.
param(
    [string]$Role = "all",
    [string]$BaseFolder = "D:\VMs",
    [string]$Iso = "C:\Users\kopo26\Desktop\vm-lab\iso\Rocky-10.2-x86_64-minimal.iso",
    [string]$KsDir = "C:\Users\kopo26\Desktop\vm-lab\build\ks",
    [string]$PubKey = "C:\Users\kopo26\Desktop\vm-lab\build\keys\vm-lab_ed25519.pub",
    [string]$RenderDir = "",
    [string]$IntNet = "lab-lan",
    [string]$HostOnly = "VirtualBox Host-Only Ethernet Adapter",
    [string]$HostOnlyHostIp = "192.168.56.1",
    [switch]$WhatIfOnly
)

$ErrorActionPreference = "Stop"
$vbm = "C:\Program Files\Oracle\VirtualBox\VBoxManage.exe"
if (-not (Test-Path $vbm)) { throw "VBoxManage를 찾을 수 없다: $vbm" }
if (-not (Test-Path $Iso)) { throw "Rocky ISO를 찾을 수 없다: $Iso" }

# 관리 공개키를 한 줄로 읽는다. 개인키는 %USERPROFILE%\.ssh\vm-lab_ed25519 에 있고
# 이 폴더에 두지 않는다(02-정본결정.md 1절). 공개키는 비밀 값이 아니다.
function Read-OpsPublicKey {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        throw @"
관리 공개키가 없다: $Path
먼저 키를 만든다. 개인키는 build 폴더에 두지 않는다.
  ssh-keygen -t ed25519 -f `$env:USERPROFILE\.ssh\vm-lab_ed25519 -C vm-lab-ops
  icacls `$env:USERPROFILE\.ssh\vm-lab_ed25519 /inheritance:r /grant:r "`$env:USERNAME:(R,W)"
  Copy-Item `$env:USERPROFILE\.ssh\vm-lab_ed25519.pub $Path
"@
    }
    $text = (Get-Content -Path $Path -Raw).Trim()
    if ($text -match "[`r`n]") { throw "관리 공개키 파일에 줄이 두 개 이상이다: $Path" }
    if ($text -notmatch '^(ssh-ed25519|ssh-rsa|ecdsa-sha2-[a-z0-9-]+) [A-Za-z0-9+/=]{20,}( .*)?$') {
        throw "관리 공개키 형식이 아니다: $Path"
    }
    if ($text -match 'BEGIN OPENSSH PRIVATE KEY') { throw "개인키를 공개키 자리에 넣었다: $Path" }
    return $text
}

# ks 원본을 읽어 자리표시자를 채운 사본을 만든다. 원본은 고치지 않는다.
function New-RenderedKickstart {
    param(
        [string]$SourcePath,
        [hashtable]$Vm,
        [string]$PubKeyLine,
        [string]$OutDir
    )

    $short = $Vm.Name.ToLower()
    $map = [ordered]@{
        '@@OPS_SSH_PUBKEY@@' = $PubKeyLine
        '__OPSKEY__'         = $PubKeyLine
        '__HOSTNAME__'       = "$short.lab"
        '__LANIP__'          = $Vm.LanIp
        '__MGMTIP__'         = $Vm.MgmtIp
    }

    $text = Get-Content -Path $SourcePath -Raw
    foreach ($token in $map.Keys) { $text = $text.Replace($token, $map[$token]) }

    # anaconda는 CRLF가 섞인 ks 파일과 BOM이 붙은 첫 줄에서 구문 오류를 낸다.
    $text = $text -replace "`r`n", "`n"

    # @@VBOX_INSERT_...@@ 는 VBoxManage가 직접 치환하는 토큰이라 검사에서 뺀다.
    $scan = [regex]::Replace($text, '@@VBOX_INSERT_[A-Z_.]*@@', '')
    $left = @()
    $lines = $scan -split "`n"
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '@@' -or $lines[$i] -match '__[A-Z_]+__') {
            $left += ("    {0}행: {1}" -f ($i + 1), $lines[$i].Trim())
        }
    }
    if ($left.Count -gt 0) {
        throw ("치환되지 않은 자리표시자가 남았다: {0}`n{1}`n이 상태로 설치하면 그 자리가 값 없이 들어간다. ks 파일이나 이 스크립트의 치환 목록을 고치고 다시 실행한다." -f $SourcePath, ($left -join "`n"))
    }

    # 정본 공개키가 아닌 키가 박혀 있으면 멈춘다. 폐기한 키로 설치하면 그 VM에 들어갈 수 없다.
    $body = ($PubKeyLine -split '\s+')[1]
    foreach ($m in [regex]::Matches($text, '(ssh-ed25519|ssh-rsa|ecdsa-sha2-[a-z0-9-]+)\s+([A-Za-z0-9+/=]{20,})')) {
        if ($m.Groups[2].Value -ne $body) {
            $head = $m.Groups[2].Value.Substring(0, [Math]::Min(44, $m.Groups[2].Value.Length))
            throw ("{0} 에 정본이 아닌 공개키가 들어 있다. 02-정본결정.md 1절의 키 한 줄로 맞추거나 자리표시자로 바꾼다.`n    파일의 키 {1} {2}...`n    정본 키   {3}..." -f $SourcePath, $m.Groups[1].Value, $head, $PubKeyLine.Substring(0, [Math]::Min(56, $PubKeyLine.Length)))
        }
    }

    $out = Join-Path $OutDir "$short.ks"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($out, $text, $utf8NoBom)
    return $out
}

# 여섯 대의 유일한 관리 진입 경로가 host-only 어댑터다. 없거나 주소가 다르면 설치해도 들어갈 수 없다.
function Assert-HostOnlyAdapter {
    param([string]$Vbm, [string]$Name, [string]$ExpectIp)

    $cur = ""
    $seen = @()
    $foundIp = ""
    foreach ($line in (& $Vbm list hostonlyifs)) {
        if ($line -match '^Name:\s+(.+?)\s*$') { $cur = $Matches[1]; $seen += $cur }
        elseif ($line -match '^IPAddress:\s+([0-9.]+)\s*$' -and $cur -eq $Name) { $foundIp = $Matches[1] }
    }
    if (-not ($seen -contains $Name)) {
        throw ("host-only 어댑터가 없다: {0}`n  있는 어댑터: {1}`n  VirtualBox 관리자에서 만들고 호스트 주소를 {2}/24 로 둔다." -f $Name, ($seen -join ", "), $ExpectIp)
    }
    if ($foundIp -ne $ExpectIp) {
        throw ("host-only 어댑터 주소가 계획과 다르다: {0} 는 {1} 인데 00-주소계획.md 는 {2} 다." -f $Name, $foundIp, $ExpectIp)
    }
    Write-Output "host-only 어댑터 확인: $Name $foundIp"
}

# DHCP 범위가 정적 주소 .11~.41 과 겹치면 주소가 흔들린다. 멈추지는 않고 경고만 낸다.
function Show-HostOnlyDhcpWarning {
    param([string]$Vbm)

    $lower = ""
    foreach ($line in (& $Vbm list dhcpservers)) {
        if ($line -match '^lowerIPAddress:\s+([0-9.]+)') { $lower = $Matches[1] }
        elseif ($line -match '^upperIPAddress:\s+([0-9.]+)') {
            $upper = $Matches[1]
            if ($lower -match '^192\.168\.56\.(\d+)$') {
                $lo = [int]$Matches[1]
                if ($upper -match '^192\.168\.56\.(\d+)$') {
                    $hi = [int]$Matches[1]
                    if ($lo -le 41 -and $hi -ge 11) {
                        Write-Warning "host-only DHCP 범위 $lower~$upper 가 정적 주소 192.168.56.11~.41 과 겹친다. DHCP를 끄거나 범위를 옮긴다."
                    }
                }
            }
            $lower = ""
        }
    }
}

# C 드라이브 여유가 30GB뿐이라 VM은 D에 만든다. 기본 경로를 쓰지 않는다.
if (-not (Test-Path $BaseFolder)) { New-Item -ItemType Directory -Path $BaseFolder -Force | Out-Null }

# 00-주소계획.md 의 표를 그대로 옮긴 값이다. DataDiskGB가 0이 아니면 별도 데이터 디스크를 붙인다.
$VMS = @(
    @{ Name = "EDGE01"; Cpus = 1; MemMB = 1536; DiskGB = 20; DataDiskGB = 0;  LanIp = "192.168.10.11"; MgmtIp = "192.168.56.11" }
    @{ Name = "APP01";  Cpus = 2; MemMB = 3072; DiskGB = 25; DataDiskGB = 0;  LanIp = "192.168.10.21"; MgmtIp = "192.168.56.21" }
    @{ Name = "APP02";  Cpus = 2; MemMB = 3072; DiskGB = 25; DataDiskGB = 0;  LanIp = "192.168.10.22"; MgmtIp = "192.168.56.22" }
    @{ Name = "DB01";   Cpus = 2; MemMB = 3072; DiskGB = 20; DataDiskGB = 20; LanIp = "192.168.10.31"; MgmtIp = "192.168.56.31" }
    @{ Name = "DB02";   Cpus = 2; MemMB = 3072; DiskGB = 20; DataDiskGB = 20; LanIp = "192.168.10.32"; MgmtIp = "192.168.56.32" }
    @{ Name = "OPS01";  Cpus = 2; MemMB = 6144; DiskGB = 30; DataDiskGB = 0;  LanIp = "192.168.10.41"; MgmtIp = "192.168.56.41" }
)

if ($Role -ne "all") {
    # @()로 감싸지 않으면 한 대만 고를 때 해시테이블 하나가 되어 Count가 키 개수로 나온다.
    $VMS = @($VMS | Where-Object { $_.Name -ieq $Role })
    if (-not $VMS) { throw "알 수 없는 역할: $Role" }
}

Assert-HostOnlyAdapter -Vbm $vbm -Name $HostOnly -ExpectIp $HostOnlyHostIp
Show-HostOnlyDhcpWarning -Vbm $vbm

$opsKey = Read-OpsPublicKey -Path $PubKey

if (-not $RenderDir) {
    $RenderDir = Join-Path ([System.IO.Path]::GetTempPath()) ("vm-lab-ks-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
}
if (-not (Test-Path $RenderDir)) { New-Item -ItemType Directory -Path $RenderDir -Force | Out-Null }

$existing = & $vbm list vms
$totalMem = ($VMS | Measure-Object -Property MemMB -Sum).Sum
Write-Output "대상 $($VMS.Count)대 / 메모리 합계 $([math]::Round($totalMem/1024,1))GB / 저장 경로 $BaseFolder"
Write-Output "관리 공개키 $PubKey / 치환 사본 폴더 $RenderDir"

foreach ($vm in $VMS) {
    $n = $vm.Name
    Write-Output ""
    Write-Output "===== $n ($($vm.LanIp))"

    if ($existing -match [regex]::Escape("`"$n`"")) {
        Write-Output "  이미 존재한다. 건너뛴다. 다시 만들려면 먼저 지운다."
        continue
    }
    $ks = Join-Path $KsDir "$($n.ToLower()).ks"
    if (-not (Test-Path $ks)) { throw "kickstart가 없다: $ks" }

    # 원본은 그대로 두고 자리표시자를 채운 사본을 만든다. 남은 자리표시자가 있으면 여기서 멈춘다.
    $ksRendered = New-RenderedKickstart -SourcePath $ks -Vm $vm -PubKeyLine $opsKey -OutDir $RenderDir
    Write-Output "  kickstart 치환 완료: $ksRendered"

    if ($WhatIfOnly) {
        $extra = if ($vm.DataDiskGB -gt 0) { " + 데이터 $($vm.DataDiskGB)GB" } else { "" }
        Write-Output "  [계획] vCPU $($vm.Cpus) / 메모리 $($vm.MemMB)MB / 디스크 $($vm.DiskGB)GB$extra"
        Write-Output "  [계획] kickstart 원본 $ks"
        continue
    }

    $dir = Join-Path $BaseFolder $n
    & $vbm createvm --name $n --ostype RedHat_64 --basefolder $BaseFolder --register
    # VirtualBox 무인 설치가 Rocky 10 ISO 의 EFI 부팅 경로만 패치한다. BIOS 로 두면 kickstart 가 주입되지 않는다.
    & $vbm modifyvm $n --memory $vm.MemMB --cpus $vm.Cpus --vram 16 --firmware efi --rtcuseutc on
    & $vbm modifyvm $n --graphicscontroller vmsvga --audio-driver none --usb off
    # 2026-09-22 확인: paravirtprovider 기본값(Linux 게스트는 KVM)에서 게스트 커널이 부팅 1초 안팎에
    # 멈추는 일이 반복됐다. 화면에 'sched_clock: Marking stable (...)->(..., 음수)' 가 남고 그 뒤로
    # 진행이 없다. APP01 은 재시작으로도 풀리지 않았고 준가상 시계를 끄고 HPET 을 켜자 바로 올라왔다.
    # 게스트의 clocksource 도 kvm-clock 에서 hpet 으로 바뀐다. 실습 규모에서 성능 차이는 문제되지 않는다.
    & $vbm modifyvm $n --paravirt-provider none --hpet on

    # 설치가 끝난 뒤에는 디스크를 먼저 고른다. 설치 매체가 남아 있어도 설치 메뉴로 다시 들어가지 않는다.
    & $vbm modifyvm $n --boot1 disk --boot2 dvd --boot3 none --boot4 none

    # NIC 순서를 모든 VM에서 같게 둔다. Rocky에서 enp0s3, enp0s8, enp0s9로 잡힌다.
    # NIC1 NAT는 패키지 설치용 임시 경로다. pfSense 완성 후 내리고 기본 경로를 192.168.10.1로 바꾼다.
    & $vbm modifyvm $n --nic1 nat      --nictype1 82540EM
    & $vbm modifyvm $n --nic2 intnet   --intnet2 $IntNet --nictype2 82540EM
    & $vbm modifyvm $n --nic3 hostonly --host-only-adapter3 $HostOnly --nictype3 82540EM

    & $vbm storagectl $n --name SATA --add sata --controller IntelAhci --portcount 4
    $osDisk = Join-Path $dir "$n-os.vdi"
    & $vbm createmedium disk --filename $osDisk --size ($vm.DiskGB * 1024) --format VDI
    & $vbm storageattach $n --storagectl SATA --port 0 --device 0 --type hdd --medium $osDisk

    # DB 두 대는 도면대로 PGDATA를 별도 디스크에 둔다. 마운트와 권한을 실제 운영과 같은 모양으로 만든다.
    if ($vm.DataDiskGB -gt 0) {
        $dataDisk = Join-Path $dir "$n-data.vdi"
        & $vbm createmedium disk --filename $dataDisk --size ($vm.DataDiskGB * 1024) --format VDI
        & $vbm storageattach $n --storagectl SATA --port 1 --device 0 --type hdd --medium $dataDisk
        Write-Output "  데이터 디스크 $($vm.DataDiskGB)GB 부착"
    }

    # VBoxManage unattended install 은 쓰지 않는다. Rocky 10.2 에서 두 군데가 깨진다.
    # (1) 생성되는 EFI grub.cfg 가 default="1" 이라 'Test this media' 항목을 골라 미디어 검사에서 멈춘다.
    #     재조립한 VISO 에는 체크섬이 없어 이 검사는 반드시 실패한다.
    # (2) 어느 메뉴 항목에도 inst.ks= 를 넣지 않아 kickstart 가 적용되지 않는다.
    # 그래서 같은 VISO 방식을 쓰되 grub.cfg 를 직접 만들어 넣는다. 형식은 VirtualBox 가 만든 것과 같다.
    $grubPath = Join-Path $dir "boot-grub.cfg"
    $visoPath = Join-Path $dir "install.viso"

    # 설치 ISO 의 볼륨 레이블. VISO 가 --import-iso 로 가져올 때 유지된다.
    $isoLabel = "Rocky-10-2-x86_64-dvd"
    $grub = @"
set default="0"
set timeout=1
insmod all_video
insmod gzio
insmod part_gpt
insmod ext2
search --no-floppy --set=root -l '$isoLabel'
# 2026-09-22 확인: Rocky 10.2 설치 커널이 VirtualBox 에서 sched_clock 보정 직후 멈추는 일이
# 반복됐다. 화면에 남은 마지막 줄은 늘 'sched_clock: Marking stable (...,-9xxxxxxxx)' 이었고
# paravirt-provider 를 none 으로 두든 기본값(KVM)으로 두든 재현됐다. TSC 를 신뢰하지 않게 하고
# 시계 소스를 HPET 으로 고정하자 통과했다. 같은 이유로 kickstart 의 bootloader --append 에도
# 같은 인자를 넣어 설치된 시스템의 첫 부팅까지 같은 조건으로 맞춘다.
menuentry 'Kickstart install' {
	linuxefi /images/pxeboot/vmlinuz inst.stage2=hd:LABEL=$isoLabel inst.ks=cdrom:/ks.cfg inst.text clocksource=hpet tsc=unstable
	initrdefi /images/pxeboot/initrd.img
}
"@
    # anaconda 는 LF 를 기대한다. CRLF 로 쓰면 커널 인자가 깨진다.
    [System.IO.File]::WriteAllText($grubPath, ($grub -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))

    $marker = [System.Guid]::NewGuid().ToString()
    $viso = "--iprt-iso-maker-file-marker-bourne-sh $marker --file-mode=0444 --dir-mode=0555 --import-iso '$Iso' /EFI/BOOT/grub.cfg=:must-remove: '/EFI/BOOT/grub.cfg=$grubPath' '/ks.cfg=$ksRendered'"
    [System.IO.File]::WriteAllText($visoPath, $viso + "`n", (New-Object System.Text.UTF8Encoding($false)))

    & $vbm storageattach $n --storagectl SATA --port 3 --device 0 --type dvddrive --medium $visoPath
    & $vbm modifyvm $n --boot1 dvd --boot2 disk --boot3 none --boot4 none
    & $vbm startvm $n --type headless

    Write-Output "  설치 시작. kickstart 는 inst.ks=cdrom:/ks.cfg 로 들어간다."
    Write-Output "    & '$vbm' controlvm $n screenshotpng $dir\progress.png"
}

Write-Output ""
Write-Output "치환 사본은 $RenderDir 에 남는다. 공개키만 들어 있고 비밀 값은 없다. 증거를 남긴 뒤 지운다."
Write-Output "설치가 끝나면 각 VM이 스스로 재부팅한다. 다음으로 확인한다:"
Write-Output "  & '$vbm' list runningvms"
Write-Output '  ssh -i $env:USERPROFILE\.ssh\vm-lab_ed25519 ops@192.168.56.11   # EDGE01'
