# 학교 AWS 발표 자료 게시 (2026-09-27 ~ 09-28)

발표 자료(HTML 발표 화면·시연 영상)를 학교 계정에 올린 기록이다. 시각은 KST, 대상은 학교 계정 서울 리전이다. 화면 캡처의 오른쪽 위 계정 ID와 상세 화면의 '소유자' 칸은 계정 번호라 가렸다. 로그인·MFA 화면과 키 내용은 찍지 않았다.

## 발표 전용 서버 준비와 중단

사용자는 발표 전용 t3.small을 학교 규칙대로 새로 만들기로 했다. 보안 그룹은 규칙대로 빈 인바운드로 먼저 만들고(01·02), Lambda가 붙인 `username=2026-ai-kopo-04`·`group=2026-ai-kopo` 태그를 확인한 뒤(03) 사용자가 승인한 세 규칙을 넣었다(04). HTTP 80과 HTTPS 443은 0.0.0.0/0, SSH 22는 이 PC의 공인 IP 하나(/32)다. 저장 뒤에는 규칙을 고칠 수 없다.

| 자원 | 값 |
|---|---|
| 보안 그룹 | `2026-ai-kopo-04-deck-sg` / `sg-0ccf2f39fa40ef49c`, 기본 VPC |
| 키 페어 | `2026-ai-kopo-04-deck-ssh-01`, RSA 2048, PEM(05·06) |

키는 `C:\Users\DEV-PC\.ssh\school-exchange`에 두고 현재 사용자와 SYSTEM만 접근하게 잠갔다. PPK는 PuTTY 공식 `puttygen` 0.84(임시 컨테이너)로 PEM에서 만들었고, 두 파일에서 뽑은 공개키가 일치했다.

인스턴스 시작(07: Amazon Linux 2023, t3.small, 기존 보안 그룹, 기본 VPC·공인 IP, gp3 8GiB, `SafeInstanceProfile-2026-ai-kopo-04`)은 실패했다. 콘솔 오류는 계정의 표준 인스턴스 vCPU 한도 16개를 넘는다는 내용이었다. 그때 계정 전체에서 2 vCPU 인스턴스 8대가 실행 중이었고 그중 4대가 이 프로젝트(앱·주 DB·예비 DB·봇/관측)였다. 실패 화면은 캡처하지 못했다. 인스턴스는 만들어지지 않았으므로 위 보안 그룹과 키 페어는 쓰이지 않은 채 남아 있다.

## 앱 서버 Nginx에 게시

사용자 결정(2026-09-28 00:0x)으로 발표 자료를 기존 앱 서버(`2026-ai-kopo-04-linux-lab-01`)의 공개 Nginx에 `/deck/` 경로로 올렸다. 역할을 합친 이유는 위 vCPU 한도이고, 발표가 끝나면 되돌린다. 접속은 기존과 같이 SSM을 경유한 SSH(`2026-ai-kopo-04-ssh-01` 키)다. PC의 AWS CLI 로그인이 만료돼 사용자가 `aws login`으로 다시 로그인했다.

실행한 명령과 결과(서버 쪽은 한 번의 SSH 세션에서 차례로 실행):

```text
scp deck-20260928.tgz nginx-deck.conf ec2-user@i-0465dd41a0390f262:/tmp/     # 16,895,868바이트, 36초
sha256sum /tmp/deck-20260928.tgz          → 2a0eed71…6fe02 (PC와 같음)
sudo install -d -o root -g root -m 0755 /opt/exchange-deck/releases/20260928
sudo tar -xzf /tmp/deck-20260928.tgz -C /opt/exchange-deck/releases/20260928 --no-same-owner --no-same-permissions
(파일 root:root 0644, 디렉터리 0755) → 703개
sudo ln -sfn /opt/exchange-deck/releases/20260928 /opt/exchange-deck/current
sudo install -o root -g root -m 0644 /tmp/nginx-deck.conf /etc/nginx/exchange-deck.conf
sudo cp -p /etc/nginx/nginx.conf /etc/nginx/nginx.conf.before-deck
sudo sed -i '/include \/etc\/nginx\/exchange-reports.conf;/a\        include /etc/nginx/exchange-deck.conf;' /etc/nginx/nginx.conf
sudo nginx -t                             → syntax is ok / test is successful
sudo systemctl reload nginx               → active
```

reload 직후의 첫 요청 두 개는 404였다. 옛 작업 프로세스가 응답한 것으로 보고 다시 요청했다. 그 뒤 `/deck` 308, `/deck/` 200(9,004바이트), `/deck/slide-demo.html` 200, CSS·이미지 200이었고 PC에서도 `/deck/` 200을 받았다. SELinux는 Permissive, Nginx 오류 로그에는 이 경로의 오류가 없었다.

시연 영상은 13회차 편집본(7분, 39,199,086바이트)을 따로 올렸다. PC와 서버의 SHA-256이 `8e46e150…b14eaab`로 같고, 공개 주소에서 `video/mp4` 200과 부분 요청 206을 확인했다. 07 시연 슬라이드의 구간 시각과 승격 시간, 보고 전달 슬라이드의 경보 시각·PDF 두 쪽·Teams 카드도 13회차 경보(00:03 발생, 00:05 해제, 00:06 보고)로 맞춰 다시 올렸다. Teams 카드는 사용자가 캡처한 해제 카드에서 '참고 분석' 문단 위까지만 썼다. 그 문단의 시각이 UTC인데 KST로 표기되는 결함이 있기 때문이다(보고 서비스 쪽 수정 대상).

08·09는 공개 주소(`https://www.sang.it.kr/deck/`)를 헤드리스 브라우저로 연 페이지 화면이다.

## 되돌리기

발표가 끝나면 `/etc/nginx/exchange-deck.conf`와 `nginx.conf`의 include 한 줄을 지우고(`nginx.conf.before-deck` 백업과 비교) `nginx -t`·reload 뒤 `/opt/exchange-deck`를 지운다. 쓰지 않은 보안 그룹과 키 페어의 삭제는 사용자가 정한다.
