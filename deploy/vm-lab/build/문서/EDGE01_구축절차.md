# EDGE01 구축 절차

EDGE01은 VM 운영환경의 웹 진입 노드다. Nginx 하나가 정적 웹을 내보내고 `/api/`와 `/ws/v1`을 APP01과 APP02로 넘긴다. 443에서 웹과 API와 WebSocket을 함께 받고 80은 HTTPS 전환에만 쓴다. 이 문서는 실행하기 전에 쓴 절차다. 아래 명령은 아직 하나도 실행하지 않았고 어떤 검증도 통과했다고 적지 않았다. 학교 AWS에서 얻은 결과를 이 환경의 결과로 옮겨 적지 않는다.

값의 기준은 `build/00-주소계획.md`다. 이 문서와 계획서가 어긋나면 계획서를 먼저 고치고 나머지를 맞춘다. 설계 정책의 출처는 `아키텍쳐/교수님_보고본/06_VM_운영환경`의 세 도면이다.

여섯 대에 공통인 부분은 `build/문서/공통_구축절차.md`와 `scripts/00-common-setup.sh`가 맡는다. 계정, SSH, 기본 차단 방화벽, node_exporter, 진단 타이머가 거기에 있다. 이 문서는 EDGE01 역할만 다룬다. 같은 일을 두 곳에서 하지 않는다.

## 역할과 정본 진입 주소

EDGE01의 주소는 lab-lan 쪽 192.168.10.11, host-only 쪽 192.168.56.11이다. 정본 진입 주소는 `https://192.168.56.11` 하나로 정했다. 인증서의 SAN, 웹 번들의 `NEXT_PUBLIC_API_BASE`와 `NEXT_PUBLIC_WS_BASE`, 앱의 `EXCHANGE_CORS_ALLOWED_ORIGINS`와 `APP_WS_ALLOWED_ORIGINS`, 그리고 Nginx가 앱으로 보내는 Host 헤더를 모두 이 값으로 맞춘다. 한 곳이라도 다르면 브라우저 요청이 CORS 검사나 WS 오리진 검사에서 막힌다. 도메인 이름을 쓰지 않은 이유는 주소계획에 호스트명이 없기 때문이다. 나중에 이름을 쓰려면 위 네 곳과 작업 PC의 hosts 항목을 함께 바꾼다.

업스트림은 APP01(192.168.10.21:8080)이 primary, APP02(192.168.10.22:8080)가 backup이다. 주소계획이 APP02를 대기로 정의했으므로 부하 분산이 아니라 primary와 backup으로 둔다. API와 WebSocket이 같은 upstream 블록을 써서 장애 표시를 공유한다.

관제 화면은 EDGE01에서 프록시하지 않는다. 주소계획이 Grafana 3000, Prometheus 9090, Alertmanager 9093을 OPS01 루프백과 SSH 터널로만 열기로 했다. 그래서 학교 배포본의 `nginx-grafana.conf`, `nginx-admin.conf`, `nginx-admin-upstream.conf`, `reporting/nginx-reports.conf`를 가져오지 않았다.

## 준비물

| 파일 | 쓰임 |
|---|---|
| `build/ks/edge01.ks` | Rocky Linux 10.2 무인 설치 kickstart |
| `build/scripts/00-common-setup.sh` | 여섯 대 공통 기반. EDGE01보다 먼저 적용한다 |
| `build/scripts/10-edge01-setup.sh` | EDGE01 안에서 실행하는 역할 구축 스크립트 |
| `build/build-files/edge01/nginx-vm.conf` | `/etc/nginx/nginx.conf` 정본 |
| `build/build-files/edge01/verify-public-vm.py` | 공개 경로 판정 스크립트 |
| `%USERPROFILE%\.ssh\vm-lab_ed25519` | SSH 개인키. 산출물 폴더 밖에 두고 VM에 복사하지 않는다 |
| `build/keys/vm-lab_ed25519.pub` | 같은 키쌍의 공개키. kickstart 치환에 쓴다 |

## 1단계. VM 생성과 무인 설치

`scripts/01-create-vms.ps1`이 VirtualBox VM을 만들고 kickstart로 무인 설치를 건다. EDGE01만 만들려면 역할을 지정한다.

```powershell
pwsh -NoProfile -File C:\Users\kopo26\Desktop\vm-lab\build\scripts\01-create-vms.ps1 -Role edge01
```

설치 경로는 이 스크립트 하나다. `inst.ks=http://...`로 답안 파일을 따로 내보내거나 `ks-edge01.cfg`, `edge01.generated.ks` 같은 중간 파일을 만들지 않는다. 공개키 자리표시자는 스크립트가 `build/keys/vm-lab_ed25519.pub`로 임시 사본에 치환해 넘기고, 치환되지 않은 자리표시자가 남으면 설치를 시작하지 않는다.

`edge01.ks`가 쓰는 설계는 `base.ks.template`과 같다. 템플릿은 설계 근거를 읽을 때만 보고 그 파일로 설치하지 않는다. 템플릿과 다른 곳은 헤더 주석과 `%post` 끝의 `/opt/exchange-web` 디렉터리 생성 두 군데뿐이다. 나머지는 여섯 대가 같다. 호스트명을 `edge01`로 두고 enp0s8을 192.168.10.11/24, enp0s9를 192.168.56.11/24로 고정하며, enp0s3(NAT)에서 DHCP로 기본 경로와 DNS를 받는다. root 로그인과 비밀번호 인증을 막고 `ops` 계정에 공개키만 넣는다. SSH는 `ListenAddress 192.168.56.11`로 관리 주소에만 붙는다. SELinux는 enforcing으로 두고 firewalld를 mask한다.

Nginx는 kickstart에서 설치하지 않는다. Rocky minimal ISO에는 BaseOS 일부만 들어 있고 nginx는 AppStream에 있다. 설치 매체에 없는 패키지를 `%packages`에 적으면 설치가 그 자리에서 멈춘다. Nginx는 VM이 뜬 뒤 NAT를 통해 dnf로 받는다.

설치가 끝나 VM이 재부팅하면 작업 PC에서 접속한다.

```powershell
ssh -i $env:USERPROFILE\.ssh\vm-lab_ed25519 ops@192.168.56.11
```

붙지 않으면 VirtualBox 콘솔에서 `ip -o -4 addr`와 `systemctl status sshd`를 먼저 본다. `ListenAddress`가 고정 주소라서 인터페이스가 올라오기 전에 sshd가 기동하면 실패한다. kickstart가 그 대비로 `network-online.target` 대기 드롭인을 넣어 두었다.

## 2단계. 공통 기반 적용

EDGE01 역할 작업 전에 공통 기반을 먼저 올린다. 이 단계가 계정 상태, 기본 차단 방화벽(`inet vmlab`, 정책 drop), node_exporter, 진단 타이머를 만든다. 자세한 절차는 `공통_구축절차.md`를 따른다.

```bash
sudo bash 00-common-setup.sh --plan
sudo bash 00-common-setup.sh --apply --node-exporter-tarball /tmp/node_exporter-<버전>.linux-amd64.tar.gz
```

이 시점에 열린 인바운드는 관리 SSH 22와 OPS01이 읽는 9100, 실습 대역 ICMP뿐이다. 443과 80은 아직 닫혀 있다. EDGE01 역할 스크립트가 그 두 포트를 역할 규칙으로 덧붙인다.

## 3단계. 파일 올리기

```powershell
$key = "$env:USERPROFILE\.ssh\vm-lab_ed25519"
scp -i $key C:\Users\kopo26\Desktop\vm-lab\build\scripts\10-edge01-setup.sh ops@192.168.56.11:/tmp/
scp -i $key -r C:\Users\kopo26\Desktop\vm-lab\build\build-files ops@192.168.56.11:/tmp/
```

EDGE01에서 실행 권한을 준다. 스크립트는 기본 탐색 경로로 자기 위치의 `../build-files/edge01`을 본다. 위처럼 따로 올렸으면 `EDGE01_FILES`로 경로를 알린다.

```bash
chmod +x /tmp/10-edge01-setup.sh
```

## 4단계. 패키지, 인증서, HTTP 미리보기, 역할 방화벽

`preview` 하위 명령이 패키지 설치, 자체 서명 인증서 생성, 정적 웹 디렉터리 준비, SELinux 문맥과 불리언, nginx 부팅 순서 드롭인, HTTP 미리보기 구성, 역할 방화벽 규칙을 한 번에 한다.

```bash
sudo EDGE01_FILES=/tmp/build-files/edge01 /tmp/10-edge01-setup.sh preview
```

설치하는 패키지는 nginx, openssl, policycoreutils-python-utils, nftables, tar, chrony, python3이다. curl은 설치하지 않는다. minimal 이미지의 curl-minimal과 충돌하므로 존재만 확인한다. 스크립트는 `nginx -V` 출력에서 `--with-http_ssl_module`을 찾고 없으면 멈춘다. 이 모듈이 없으면 443 구성이 기동에 실패한다.

인증서는 EDGE01 안에서 만든다. 개인키를 저장소나 증거본에 복사하지 않는다.

```bash
openssl req -x509 -newkey rsa:2048 -sha256 -days 825 -noenc \
  -keyout /etc/pki/tls/private/edge01.key -out /etc/pki/tls/certs/edge01.crt \
  -subj '/CN=edge01' \
  -addext 'subjectAltName=IP:192.168.56.11,IP:192.168.10.11' \
  -addext 'basicConstraints=critical,CA:TRUE'
```

키는 root:root 0600, 인증서는 0644로 둔다. `CA:TRUE`로 만드는 이유는 이 인증서 한 장을 작업 PC의 신뢰 저장소나 판정 스크립트의 신뢰 기준으로 그대로 넣기 위해서다.

이 단계가 끝나면 앱 없이 정적 화면만 확인한다. 웹 번들을 아직 올리지 않았으면 자리표시 페이지가 나온다.

```bash
curl -sI http://192.168.56.11/
```

미리보기 구성은 `deploy/linux/app/nginx-preview.conf`를 옮긴 것이다. GET과 HEAD만 받고 `/auth`, `/ws`, `/admin`, `/actuator`를 막는다. 구축 중간 점검에 그대로 맞는 성질이라 프록시 대상과 바인딩 주소만 바꿨다.

### 역할 방화벽 규칙

공통 기반이 이미 기본 차단을 걸었으므로 EDGE01이 더 열 것은 443과 80 둘뿐이다. 스크립트가 `/etc/vm-lab/firewall.d/20-edge01.nft`에 `add rule` 형태로만 덧붙이고 테이블과 체인을 다시 선언하지 않는다. 적용은 `systemctl reload vm-lab-firewall.service`다.

```
add rule inet vmlab input ip saddr 192.168.56.1/32 tcp dport 443 ct state new counter accept
add rule inet vmlab input ip saddr 192.168.56.1/32 tcp dport 80 ct state new counter accept
```

허용 출발지는 관리 대역 전체가 아니라 실습 단말 한 주소 `192.168.56.1`이다. `192.168.56.0/24`에는 나머지 다섯 VM과 pfSense의 host-only 인터페이스가 모두 들어 있어서, 대역으로 열면 DB01에서 EDGE01의 443으로 붙는 것까지 허용된다. 접근제어도의 "승인된 실습 단말만 접근"과 "사용자에서 앱·DB·관리 포트로 직접 접근 차단"을 만족하려면 주소 하나여야 한다.

8090 stub_status는 127.0.0.1에만 바인딩하므로 공통 규칙의 `iif lo accept`로 통과한다. 9100도 공통 규칙이 이미 OPS01 한 주소만 열어 두었다. 8081은 EDGE01에 열지도 않고 EDGE01에서 부르지도 않는다. 주소계획이 8081의 허용 출발지를 OPS01로만 정했다.

pfSense 포트 포워딩은 아직 쓰지 않는다. 포워딩을 거치면 출발지가 `192.168.10.1`이 되는데 그 주소는 주소계획의 포트 표에 없고 pfSense 자체가 아직 구축되지 않았다. 근거 없이 열어 두지 않으므로 `ALLOW_PFSENSE_FORWARD`는 0으로 둔다. 포워딩을 쓰기로 정하면 `00-주소계획.md`의 443·80 행에 `192.168.10.1`을 먼저 넣고 그다음에 1로 되돌린다.

```bash
sudo ALLOW_PFSENSE_FORWARD=0 /tmp/10-edge01-setup.sh preview
```

방화벽 계통은 하나다. `table inet vmlab`, `/etc/vm-lab/firewall.nft`, `vm-lab-firewall.service`를 쓰고 input 기본 정책은 drop이며 역할 규칙은 `/etc/vm-lab/firewall.d/20-edge01.nft`로만 더한다. `inet failstop` 테이블을 따로 세우거나 배포판 `nftables.service`를 두 번째 정책원으로 살려 두는 단독 경로는 쓰지 않는다. 그 경로는 정책이 accept라 적은 포트만 걸러 접근제어도의 "허용하지 않은 새 인바운드 연결은 차단"과 어긋나고, `nftables.service`는 중지할 때 ruleset 전체를 비울 수 있어 `vm-lab-firewall.service`가 세운 규칙까지 함께 내려간다. 그래서 공통 기반을 먼저 적용한 뒤에 EDGE01 역할 규칙을 얹는다.

이전에 단독 경로로 먼저 시험한 VM이 있으면 역할 규칙을 얹기 전에 `/etc/nftables/edge01.nft`, `/etc/sysconfig/nftables.conf`의 include 줄, enable된 `nftables.service`를 먼저 걷어낸다. `systemctl is-enabled nftables`가 masked나 disabled인지 확인하고 그 출력을 증거로 남긴다.

나가는 통신은 호스트 방화벽에서 제한하지 않는다. 발신 허용목록은 pfSense 경계가 맡는다. 그 전까지는 "모든 나가는 통신을 통제했다"고 적지 않는다.

## 5단계. 웹 번들 만들기와 올리기

작업 PC에서 저장소의 기존 스크립트로 정적 번들을 만든다. 이 스크립트는 빌드한 번들에 localhost 주소가 남았는지 검사하는 단계를 이미 갖고 있다.

```powershell
pwsh -File C:\Users\kopo26\Desktop\failstop\deploy\linux\app\build-web.ps1 -Origin https://192.168.56.11
tar -C C:\Users\kopo26\Desktop\failstop\web\out -czf $env:TEMP\web-out.tar.gz .
scp -i $key $env:TEMP\web-out.tar.gz ops@192.168.56.11:/tmp/
```

EDGE01에서 번들을 배치한다. 스크립트가 `/opt/exchange-web/releases/<날짜-시각>/`에 풀고 권한을 정리한 뒤 `current` 심볼릭 링크를 그쪽으로 건다. 디렉터리는 0755, 파일은 0644, 소유자는 root:root이고 nginx는 읽기만 한다. 배치 후 번들 안에 로컬 주소가 남았는지 한 번 더 본다.

```bash
sudo /tmp/10-edge01-setup.sh install-web /tmp/web-out.tar.gz
```

## 6단계부터 7단계. SELinux와 HTTPS 구성

SELinux는 끄지 않는다. 필요한 것은 세 가지다. 정적 파일에 `httpd_sys_content_t`를 붙이고, nginx가 8080으로 나가도록 `httpd_can_network_connect`를 켜고, stub_status용 8090을 `http_port_t`에 넣는다. 8090은 기본 `http_port_t` 목록에 없어서 추가하지 않으면 bind에 실패한다.

```bash
sudo semanage fcontext -a -t httpd_sys_content_t '/opt/exchange-web(/.*)?'
sudo restorecon -Rv /opt/exchange-web
sudo setsebool -P httpd_can_network_connect on
sudo semanage port -a -t http_port_t -p tcp 8090
```

이어서 업스트림과 HTTPS 구성을 적용한다. 교체 전에 기존 `nginx.conf`를 `.before-https`로 백업하고, `nginx -t`를 통과한 뒤에만 reload한다. 이 흐름은 `deploy/linux/app/activate-https.py`의 방식을 따랐다. Let's Encrypt 발급과 `www.sang.it.kr` 하드코딩은 뺐다.

```bash
sudo EDGE01_FILES=/tmp/build-files/edge01 /tmp/10-edge01-setup.sh https
```

업스트림은 `/etc/nginx/exchange-upstream.conf` 한 파일에 둔다. 장애 복구 때 바꿔야 할 대상이 이 파일 하나로 좁혀지기 때문이다.

```nginx
upstream exchange_app {
    zone exchange_app 64k;
    server 192.168.10.21:8080 max_fails=2 fail_timeout=10s;
    server 192.168.10.22:8080 backup;
}
```

`zone`을 이 빌드가 받지 않으면 스크립트가 그 줄을 빼고 다시 검사한다. 그래도 문제가 없는 이유는 `worker_processes`를 1로 고정했기 때문이다. 오픈소스 nginx에서 업스트림 실패 표시는 워커마다 따로 관리되는데, 워커가 하나면 표시가 갈리지 않는다. EDGE01은 vCPU가 1개라 이 고정이 성능 손해도 아니다.

`worker_shutdown_timeout`은 30초로 뒀다. reload 때 오래된 워커가 기존 WebSocket 연결을 무한정 붙잡으면 리더를 바꾼 뒤에도 재접속이 일어나지 않는다.

`proxy_next_upstream`에는 `error timeout http_502 http_503 http_504`만 넣고 `non_idempotent`는 넣지 않았다. 주문 POST를 프록시가 자동으로 재전송하면 저널에 기록된 뒤 응답만 끊긴 경우까지 다시 보내게 된다. 재조회로 수렴하라는 API 계약(503과 Retry-After 1)의 판단을 프록시가 가져가서는 안 된다.

## 8단계. APP01과 APP02 쪽 맞추기

EDGE01만으로는 API와 WS 확인이 끝나지 않는다. 앱 담당 작업에서 다음을 맞춘다. `SERVER_ADDRESS`를 127.0.0.1이 아니라 각자의 lab-lan 주소(192.168.10.21, 192.168.10.22)로 바꾸고, `EXCHANGE_CORS_ALLOWED_ORIGINS`와 `APP_WS_ALLOWED_ORIGINS`를 `https://192.168.56.11`로 둔다. 학교 배포본의 `app.env.school-private`는 `SERVER_ADDRESS=127.0.0.1`과 172.31 대역 오리진을 쓰고 있어 그대로 옮기면 EDGE01이 8080에 닿지 못한다.

## 9단계. 지표 수집 확인

node_exporter는 EDGE01 스크립트가 설치하지 않는다. 2단계의 공통 기반이 여섯 대에 같은 방식으로 올리고 각 VM의 lab-lan 주소에만 바인딩한다. 그 스크립트의 `--collector.systemd.unit-include` 목록에는 nginx가 이미 들어 있다. `https` 하위 명령이 EDGE01에서 실제로 떠 있는지와 바인딩 주소가 192.168.10.11인지만 확인한다. 설치 경로를 둘로 만들지 않는다.

```bash
curl -s http://192.168.10.11:9100/metrics | head -1     # OPS01 에서
```

## 10단계. 확인

`verify` 하위 명령이 아래 항목을 순서대로 실행하고 통과와 실패를 센다. 앱이 아직 없으면 앱 연동 항목을 건너뛴다. 앱과 DB가 올라온 뒤에는 `--with-app`을 붙여 그 항목까지 실패로 세게 한다.

```bash
sudo /tmp/10-edge01-setup.sh verify
sudo /tmp/10-edge01-setup.sh verify --with-app
```

확인하는 내용은 다음과 같다.

`nginx -t`가 `syntax is ok`와 `test is successful` 두 줄을 낸다. `ss -lntp`에 192.168.10.11과 192.168.56.11의 443과 80, 127.0.0.1:8090, 192.168.10.11:9100만 보인다. `0.0.0.0:443`이 보이면 바인딩을 잘못한 것이다.

```bash
curl -sI http://192.168.56.11/            # 308 과 Location: https://192.168.56.11/
curl -k -sI https://192.168.56.11/        # 200, text/html, nosniff, DENY
openssl s_client -connect 192.168.56.11:443 </dev/null 2>/dev/null \
  | openssl x509 -noout -subject -dates -ext subjectAltName
```

인증서 출력에 `CN=edge01`과 `IP:192.168.56.11`, `IP:192.168.10.11`, 그리고 `notAfter` 날짜가 나온다. 이 날짜를 운영 점검 항목으로 적는다.

```bash
for p in /actuator/health /api/v1/admin /api/admin/health /admin /admin.html; do
  curl -k -s -o /dev/null -w "$p %{http_code}\n" https://192.168.56.11$p
done                                                       # 모두 403
curl -k -s -o /dev/null -w '%{http_code}\n' https://192.168.56.11/.env            # 404
curl -k -s -o /dev/null -w '%{http_code}\n' https://192.168.56.11/api/v1/accounts # 401
curl -k -s https://192.168.56.11/api/v1/markets \
  | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["data"]))'        # 12
```

WebSocket 핸드셰이크는 101이 나와야 한다. 200이나 403이면 Upgrade 헤더 전달이나 앱의 오리진 설정을 다시 본다.

```bash
curl -k -i -N -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' -H "Sec-WebSocket-Key: $(openssl rand -base64 16)" \
  -H 'Origin: https://192.168.56.11' https://192.168.56.11/ws/v1
```

101은 핸드셰이크가 성립했다는 뜻이지 시세가 온다는 뜻이 아니다. 화면에서 값이 실제로 흐르는지 직접 본다.

SELinux 거부가 없어야 한다. 거부가 있으면 원인을 먼저 보고 문맥이나 불리언을 고친다. SELinux를 끄지 않는다.

```bash
sudo ausearch -m AVC -ts recent
```

방화벽은 규칙 자체와 실제 차단을 함께 본다. 기본 정책이 drop인지, 443과 80 허용 규칙이 붙었는지, OPS01이 아닌 VM에서 EDGE01의 9100이 막히는지를 각각 확인한다.

```bash
sudo nft list table inet vmlab
# APP01 등 다른 VM 에서: curl --max-time 3 http://192.168.10.11:9100/metrics
# OPS01 에서:            curl -s http://192.168.10.11:9100/metrics | head -1
```

마지막으로 공개 경로 판정 스크립트를 돌린다. 이 스크립트는 `-k`로 검증을 건너뛰지 않고 자체 서명 인증서를 신뢰 기준으로 넣어 SAN과 유효기간을 실제로 검사한다.

```bash
sudo python3 /tmp/build-files/edge01/verify-public-vm.py
```

## 11단계. 재부팅 확인

```bash
sudo reboot
# 다시 접속한 뒤
systemctl is-enabled nginx vm-lab-firewall node_exporter
systemctl is-active  nginx vm-lab-firewall node_exporter
ss -lntp
sudo /tmp/10-edge01-setup.sh verify --with-app
```

Nginx가 특정 IP에 바인딩하므로 인터페이스가 올라오기 전에 기동하면 실패한다. 그래서 `nginx.service`에 `network-online.target` 대기 드롭인을 넣고 `NetworkManager-wait-online.service`를 켜 뒀다. 그래도 실패하면 `journalctl -u nginx`에서 `Cannot assign requested address`를 찾아 확인한다.

## 장애 전환 시험

APP01을 멈추고 같은 호출을 다시 한다.

```bash
# APP01 에서
sudo systemctl stop exchange-app
# 작업 PC 나 EDGE01 에서
curl -k -s https://192.168.56.11/api/v1/markets | head -c 200
```

첫 요청이 502나 504로 한 번 실패할 수 있고 그 뒤 응답이 APP02에서 나온다. EDGE01의 `/var/log/nginx/error.log`에 업스트림 실패 기록이 남고, APP02의 `journalctl -u exchange-app`에 리더 획득 기록이 남는다. 이 두 기록의 시각 차이가 실제 전환 시간이다. 추정한 값이 아니라 이 두 줄을 증거로 남긴다.

여기서 중요한 함정이 있다. APP02가 리더를 가져가도 Nginx는 여전히 APP01을 primary로 본다. APP01을 다시 올리면 트래픽이 APP01로 돌아가는데, ADR 0017의 재승격 금지 규칙에 따라 리더는 APP02가 계속 갖는다. 그러면 WebSocket이 리더가 아닌 노드에 붙어 핸드셰이크는 101로 성공하면서 시세와 체결이 오지 않는다. 그래서 복구 절차에 업스트림 교체가 반드시 들어간다.

```bash
sudo /tmp/10-edge01-setup.sh swap-upstream
```

이 명령은 `exchange-upstream.conf`의 primary와 backup을 맞바꾸고 `nginx -t`를 통과한 뒤 reload한다. 실패하면 교체 전 파일로 되돌린다. 이 교체는 수동이며 자동화하지 않았다. 리더가 다시 바뀌면 같은 명령을 다시 실행한다.

reload 뒤 브라우저에서 시세가 다시 흐르는지 본다. reload 없이도 재접속이 되는지, `worker_shutdown_timeout`이 실제로 기존 연결을 끊는지는 각각 따로 확인한다.

## pfSense 반영

pfSense 구축이 끝나면 기본 경로를 192.168.10.1로 바꾸고 NAT 어댑터를 내린다. 그 전까지는 "모든 통신이 방화벽을 지난다"고 적지 않는다. 진입 주소가 pfSense 쪽으로 바뀌면 인증서 SAN, 웹 번들 오리진, 앱 오리진 설정, 그리고 역할 방화벽의 443 허용 출발지를 함께 다시 만든다. pfSense 자체 구축은 사용자가 직접 한다.

## 남은 위험과 확인하지 못한 것

자체 서명 인증서라 브라우저 경고가 뜨고 신뢰 체인 검증이 없다. 작업 PC에 인증서를 넣거나 예외를 등록해야 화면이 뜬다. 학교 AWS에서 확인한 Let's Encrypt 발급과 갱신, 체인 검증은 이 환경에서 검증되지 않는다. 갱신 자동화가 없으므로 825일 만료일을 운영 점검 항목으로 남긴다. HSTS 헤더는 넣지 않았다. 자체 서명 인증서에 HSTS를 걸면 브라우저가 정책을 기억해 되돌리기 번거롭다.

Python과 브라우저는 IP 주소로 접속할 때 SNI를 보내지 않는다. 그래서 TLS 핸드셰이크에서 nginx가 해당 소켓의 `default_server` 블록에서 인증서를 고른다. `default_server`에도 같은 인증서를 지정해 둔 이유가 이것이다. 요청 라우팅은 핸드셰이크 뒤 Host 헤더로 이뤄지므로 실제 응답은 진입 주소 server 블록이 낸다. 이 두 단계가 다르다는 점을 설정을 고칠 때 기억한다.

primary와 backup 구성은 APP01의 프로세스나 VM이 멈추는 경우만 자동으로 넘어간다. APP01이 살아서 응답하면서 리더 리스만 잃은 상태는 프록시가 알아채지 못한다. 그 상태의 쓰기 요청은 앱이 503과 Retry-After 1로 거절하고 클라이언트 재시도에 맡긴다. 이 구간의 동작은 아직 실측하지 않았다.

WebSocket 게시는 단일 노드 SimpleBroker이고 크로스노드 팬아웃이 아직 배선되지 않았다. 리더가 아닌 노드에 WS가 붙으면 핸드셰이크만 성공하고 데이터가 오지 않는다. 이 조용한 실패는 HTTP 상태코드로 드러나지 않으므로 화면에서 직접 확인해야 한다. WS를 분산하지 않고 API와 같은 upstream의 primary로 고정한 이유가 이것이다.

STOMP 하트비트가 계속 오가면 `proxy_read_timeout 75s`가 연결을 끊지 않는다. 구 리더가 하트비트만 보내고 게시를 멈춘 경우 클라이언트가 재접속하지 않을 수 있다. 실제로 하트비트가 유지되는지, 웹 클라이언트에 재접속 로직이 있는지는 확인이 필요하다.

`proxy_next_upstream`에 `non_idempotent`를 넣지 않았으므로, 팔로워가 낸 503이 `max_fails` 실패로 집계되는지 이 nginx 빌드에서 확인하지 않았다. 집계되지 않으면 수동 교체가 유일한 경로다. 시험으로 확인할 항목이다. `zone` 지시자가 이 빌드에서 동작하는지도 확인하지 않았다.

능동 헬스체크를 넣지 않았다. 오픈소스 nginx에 없고, 대신 쓸 수 있는 `/actuator/health/readiness`는 앱 8081에 있는데 주소계획이 그 포트의 허용 출발지를 OPS01로만 정했다. 도입하려면 `00-주소계획.md`를 먼저 고쳐 EDGE01을 8081 허용 출발지에 넣어야 한다. 이 문서에서 임의로 바꾸지 않았다.

Rocky 10 minimal의 AppStream에서 nginx를 받을 수 있는지, 그 nginx의 버전과 기본 모듈 구성이 어떤지 아직 확인하지 않았다. `http_ssl_module`이 없으면 443 구성이 기동에 실패한다. 스크립트가 패키지 단계에서 이 항목을 검사하고 없으면 멈춘다. NAT가 살아 있는 동안에만 설치가 가능하다는 점도 같이 본다.

방화벽 계통은 `table inet vmlab` 한 가지로 정했고 여섯 대가 같은 형식을 쓴다. 다만 이 통일을 아직 VM에서 적용해 확인하지 않았다. `10-edge01-setup.sh`에 남아 있는 단독 경로 분기와 `/etc/sysconfig/nftables.conf`를 건드리는 단계를 걷어내는 일도 아직 하지 않았다. 여섯 대를 올려 `nft list table inet vmlab`과 `systemctl is-enabled nftables` 출력을 직접 보기 전에는 "방화벽 정책을 통일했다"고 적지 않는다.
