# 2026-09-26 VM 실습망 pfSense 구축

작업 PC(DEV-PC, Windows, VirtualBox 7.2.8)에서 VM 실습망의 경계 방화벽 pfSense CE 2.7.2를 설치하고 설정한 기록이다. 시간대는 KST다. 구조는 2026-09-26 사용자 결정인 경계형(lab-lan 앞에 pfSense, 작업 PC → 웹 진입 서버 443은 포트포워딩으로만 연다)을 따른다. 이 기록은 로컬 VM 실습 결과이며 학교·개인 AWS의 검증 결과가 아니다.

관리자 비밀번호 입력 구간은 사용자가 직접 입력했고 캡처하지 않았다. Teams 웹훅 주소는 기록하지 않는다.

| 순서 | 파일 | 내용 | 결과 |
|---|---|---|---|
| 01 | `01-pfsense-vm-생성.log` | ISO SHA-256 대조(Netgate 게시값과 일치), `create-pfsense-vm.ps1`로 VM 생성, 부팅, 설치 뒤 ISO 분리 | 생성·설치 완료. 설치 후 셸이 살아 있는 상태에서 ISO를 강제 분리해 `vm_fault: pager read error`가 난 실수와 강제 재시작 기록 포함 |
| 02 | `02-설치화면.png` | 설치 프로그램 첫 화면(VBoxManage 콘솔 캡처) | 표시 |
| 03 | `03-설치완료-셸.png` | 설치 뒤 수동 수정용 셸 | 표시 |
| 04 | `04-iso분리후-오류.png` | ISO 강제 분리 뒤 오류 화면 | 실수 기록 |
| 05 | `05-디스크부팅.png` | 디스크 부팅, 서비스 기동 | 성공 |
| 06 | `06-인터페이스-할당.png` | 콘솔: WAN em0 DHCP 10.0.2.15, LAN em1 192.168.10.1/24, OPT1 em2 192.168.56.10/24 | 성공 |
| 07 | `07-관리주소-접속확인.log` | 작업 PC → 192.168.56.10: 규칙 전 443·ping 차단, `easyrule pass opt1 tcp 192.168.56.1 192.168.56.10 443` 뒤 443 `200 OK`, 22 차단 | 허용·차단 확인 |
| 08 | `08-별칭.png` | 별칭 ADMIN_PC, WEB_SERVER, APP_SERVERS, DB_SERVERS, OPS, LAB_VMS | 적용 |
| 09 | `09-관리화면-8443.png` | 관리 화면 포트 8443, 80번 리다이렉트 끔 | 적용 |
| 10 | `10-포트포워딩.png` | OPT1: ADMIN_PC → OPT1 주소 443·80 → WEB_SERVER 443·80, 연결 규칙 자동 생성 | 적용 |
| 11 | `11-관리망-규칙.png` | OPT1: 관리 화면 8443, 각 VM SSH 22, 포워딩 연결 규칙 2개. 임시 EasyRule 443 규칙 삭제 | 적용 |
| 12 | `12-LAN-규칙.png` | LAN: 기본 전체 허용 2개 비활성, VM → pfSense DNS 53·NTP 123 허용, 패키지 저장소 443 규칙은 꺼진 채 대기 | 적용 |
| 13 | `13-원격로그.png` | 방화벽 이벤트를 LAN 주소에서 192.168.10.41:514로 전송 | 저장 |
| 14 | `14-DNS-Resolver.png` | DNS Resolver 켜짐(설치 기본값) | 확인 |
| 15 | `15-NTP.png` | NTP 서비스 화면 | 확인 |
| 16 | `16-경계형전환-A.log` | 여섯 대에 `boundary-phase-a.sh` 실행: nftables에 pfSense 경유 관리 SSH 22(출발지 192.168.56.1), OPS01에 pfSense 원격 로그 514 허용, 기본 경로·DNS를 192.168.10.1로, chrony를 pfSense로, OPS01 rsyslog 수신 설정 | 설정 적용. 이때 pfSense가 꺼져 있어 로그의 `DNS 실패`·NTP `^?`는 그 상태의 결과 |
| 16 | `16-EDGE01-경계형A.png` | pfSense 기동 직후 EDGE01: 기본 경로 192.168.10.1, DNS 응답 성공, NTP는 아직 동기 전(`^?`) | 전환 중 화면 |
| 16 | `16-경계형A-여섯대.png` | 여섯 대 모두 기본 경로·네임서버 192.168.10.1, NTP `^* 192.168.10.1`, nftables pfSense 경유 규칙 | 성공 |
| 17 | `17-OPS01-syslog수신.png` | OPS01 rsyslog가 192.168.10.41:514 UDP 대기, nftables 514 허용(당시 카운터 0) | 대기 확인 |
| 18 | `18-경계형B-NIC제거.log` | VM 정상 종료 뒤 NAT(NIC1)·host-only(NIC3) 제거. 1차 제거 뒤 pfSense 경유 SSH가 실패했다. 원인은 sshd `ListenAddress`와 Nginx `listen`이 host-only 주소 192.168.56.x에 묶여 있던 것이다. host-only를 임시로 다시 붙여 sshd를 lab-lan 주소로, EDGE01 Nginx·Host 헤더·인증서 SAN을 새 진입 주소 192.168.56.10으로, 앱 CORS·WS 오리진을 `https://192.168.56.10`으로 바꾸고 OPS01 blackbox 인증서를 갱신한 뒤 NIC를 다시 뗐다. DB01 실패는 새 주소의 호스트 키 확인 질문 때문이었다 | 여섯 대 모두 lab-lan NIC 하나, pfSense 경유 SSH 성공 |
| 19 | `19-시험-작업PC에서.png` | 작업 PC: `curl.exe -k -I https://192.168.56.10` → `200 OK`(pfSense 포워딩 → 웹 진입 서버). `Test-NetConnection` DB 192.168.10.31:5432·앱 192.168.10.21:8080 `False`, 웹 진입 서버 22 `True` | 허용·차단 확인 |
| 20 | `20-시험-서버사이.png` | `ssh edge01 "nc -zv -w 3 ..."` 등: 웹→앱 8080 연결, 웹→DB 5432 `TIMEOUT`, 앱→DB 5432 연결, 앱1→앱2 8080 `TIMEOUT`. 같은 lab-lan 안이라 pfSense를 지나지 않고 각 VM의 nftables가 판정한다 | 허용·차단 확인 |
| 21 | `21-시험-외부발신.png` | DB01: `nslookup`은 pfSense(192.168.10.1)를 거쳐 응답, `curl -m 5 -I https://dl.rockylinux.org`는 시간 초과(pfSense LAN 기본 거절), `chronyc sources`는 `^* pfsense-fw.lab` | 허용·차단 확인. 첫 `nslookup`의 `communications error ... timed out` 한 줄은 아래 관찰 참고 |
| 22 | `22-pfSense-차단로그.png` | Status › System Logs › Firewall, 시험 시각(21:23~21:39)과 목적지 포트 5432·8080·443으로 거른 화면: OPT1에서 작업 PC → DB 5432·앱 8080, LAN에서 DB01 → 146.75.94.132:443(21:30:22) 거절 | 차단 기록 확인 |
| 23 | `23-OPS01-원격로그-수신.png` | OPS01 `/var/log/pfsense/firewall.log`에서 같은 세 종류의 차단 기록 확인. rsyslog 192.168.10.41:514 대기 | 수신 확인(21:34 기준 424줄, nftables 514 카운터 331패킷) |
| 24 | `24-LAN-규칙-Teams443.png` | LAN 규칙에 `OPS → TEAMS_WEBHOOK TCP 443` 추가(22:1x). `TEAMS_WEBHOOK`은 Teams Workflows 웹훅의 호스트 이름 하나를 넣은 FQDN 별칭이다 | 적용. 시험 연결 1건이 규칙 상태에 잡힘 |
| 25 | `25-TEAMS_WEBHOOK-해석주소.png` | Diagnostics › Tables: pfSense가 호스트 이름을 해석해 넣은 주소(IPv4 1개, IPv6 1개) | 확인 |
| 26 | `26-OPS01-외부발신-정리.png` | OPS01: Teams 주소 443 `Connected`, grafana.com 443 `TIMEOUT`, Databasus `anonymous telemetry is disabled`(로그 시각은 UTC, 22:04:30 KST), Grafana 외부 확인 설정 | 허용·차단 확인 |

관찰:

- pfSense DNS Resolver는 전달(forwarding) 없이 루트부터 재귀 조회한다. 캐시에 없는 이름은 0.6~1.6초가 걸렸고(`dig` 측정, 21:3x), CNAME이 긴 `dl.rockylinux.org`는 `nslookup`의 짧은 첫 시도 제한을 넘겨 한 번 시간 초과한 뒤 재시도에서 응답했다. 캐시된 이름은 즉시 응답한다. 경계 차단 때문이 아니다.
- 차단 기록에 OPS01(192.168.10.41)의 외부 443 시도가 계속 보였다. 하나는 Grafana의 grafana.com 플러그인·업데이트 확인(journal의 `plugin.backgroundinstaller` 오류), 다른 하나는 Databasus의 사용 통계 전송(`metrics.databasus.com`)이었고 알림 웹훅은 아니었다. 21:48에 Grafana 설정을, 22:04에 Databasus의 `IS_DISABLE_ANONYMOUS_TELEMETRY`를 바꿨다. 이후 두 발신의 차단 기록은 나오지 않았다(22:10 확인).
- Teams 웹훅 호스트는 사용자가 작업 PC에 둔 링크 파일에서 호스트 부분만 뽑아 pfSense 별칭에 넣었다. 전체 주소와 호스트 이름은 이 기록과 저장소에 남기지 않는다. 이 호스트는 Azure Traffic Manager를 거치는 CNAME이라 가리키는 주소가 바뀔 수 있다. pfSense가 별칭을 다시 해석하기 전에 바뀌면 그 사이 전송이 막힐 수 있다. VM 실습망의 Alertmanager는 아직 Teams로 보내지 않으며(수신자 `hold`), 이번에는 443 연결만 확인하고 웹훅 요청은 보내지 않았다.
- 웹 진입 주소는 `https://192.168.56.11`에서 `https://192.168.56.10`(pfSense 포워딩)으로 바뀌었다. 저장소의 VM 실습 문서는 아직 이전 주소를 적고 있다.

남은 일: 저장소 VM 실습 문서 반영과 커밋은 같은 날 했다. VM 실습망 Alertmanager를 Teams로 연결할지, 규칙을 끈 직후와 pfSense 재부팅 뒤 재시험은 아직 하지 않았다.
