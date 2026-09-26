# 2026-09-26 VM 실습망 Databasus 백업·서버 밖 보관·복원 검증

2026-09-22 예행([예행 기록](../../deploy/vm-lab/databasus/캡처/예행-2026-09-22/README.md))이 웹 화면의 관리자 가입에서 멈춘 뒤를 이어, 로컬 VM 실습망에서 Databasus v3.59.0으로 주 DB(DB01)의 논리 백업을 만들고, 웹 진입 서버(EDGE01)의 SFTP 전용 계정으로 서버 밖에 보관하고, 복원 검증 에이전트로 되살아나는지 확인한 기록이다. 시간대는 KST다. 이 기록은 한 PC의 가상망에서 얻은 결과이며 학교·개인 AWS 검증 결과가 아니다.

pfSense 경계형 전환(같은 날, [pfSense 증거본](../2026-09-26_VM_pfSense/README.md)) 뒤라 모든 VM은 lab-lan 주소만 쓴다. Databasus 화면은 OPS01 루프백(127.0.0.1:4005)에만 열려 있어 작업 PC에서 SSH 터널(14005 → 4005)로 접속했다.

관리자 가입, DB 계정 비밀번호, SFTP 개인키 붙여 넣기, 복원 검증 에이전트 토큰은 사용자가 직접 입력했고 그 화면은 찍지 않았다. 브라우저 캡처는 페이지 영역만 남겼다. 가입 계정의 이메일이 보이는 설정·감사 로그 화면은 증거에 넣지 않았다.

| 순서 | 파일 | 내용 | 결과 |
|---|---|---|---|
| 01 | `01-시작전-상태.png` | OPS01 `docker ps`(Databasus 정상), `ss -lntp`(4005는 127.0.0.1만), DB01 `\du databasus_ro`, EDGE01 보관 폴더 비어 있음 | 기준선 |
| 02 | `02-웹UI-첫화면-가입전.png` | "This is the first account on this instance" 가입 화면 | 가입 전 확인 |
| 03 | `03-로그인후-첫화면.png` | 가입 뒤 첫 화면(워크스페이스 없음) | 확인 |
| 04 | `04-외부보관소-연결성공.png` | 저장소 "외부 백업 보관소 (웹 진입 서버 SFTP)": 192.168.10.11:22, `dbbackup`, 경로 `databasus`. 화면의 Auth method가 Password로 보이지만 EDGE01은 이 계정에 공개키 인증만 허용한다 | 연결 시험 성공 |
| 05 | `05-DB등록-입력(비밀번호-제외).png` | 거래 DB (주 DB): 192.168.10.31:5432, `databasus_ro`, `exchange`, SSL Require. 비밀번호 칸은 빈 상태 | 입력 |
| 06 | `06-읽기전용-권한경고.png` | Databasus가 읽기 전용 계정에 `TEMP`, `EXECUTE (SECURITY DEFINER)` 쓰기 가능 권한이 남았다고 경고. 사용자 결정으로 "위험 감수"를 골라 진행 | 발견(아래 참고) |
| 07 | `07-백업일정-암호화-보존7일.png` | 매일 09:00 논리 백업, 저장소 외부 백업 보관소, 백업 파일 암호화, 보존 7일, 실패 알림 켬(알림 대상은 아직 없음) | 설정 |
| 08 | `08-첫백업-성공.png` | 22:58 첫 백업 Successful, 0.45 MB(원본 3.05 MB) | 성공 |
| 09 | `09-서버밖-파일도착.png` | EDGE01 `/srv/db-backup/databasus`에 474,053바이트 백업과 메타데이터, sshd 로그에 22:58:13 OPS01(192.168.10.41)의 `internal-sftp` 접속 | 서버 밖 보관 확인 |
| 10 | `10-복원검증-성공.png` | Verifications: 23:12 수동 복원 검증 Successful | 성공 |
| 11 | `11-복원검증-상세-행수.png` | 복원 DB 12.9 MB, 스키마 2, 테이블 109, `pg_restore` 종료 코드 0, 테이블별 행 수(flyway 47, recovery_runs 22, journal_counters 3 등) | 성공 |
| 12 | `12-원본DB-대조-임시컨테이너정리.png` | DB01에서 `\dt public.*` 104개 + `\dt harness.*` 5개 = 109개, 같은 세 표의 행 수 47·22·3 일치. OPS01에는 Databasus 컨테이너만 남음 | 원본과 일치 |

## 구성하면서 바꾼 것

경계형 전환으로 예행 때의 SFTP 경로가 끊겨 있었다. EDGE01의 sshd는 `AllowUsers dbbackup@192.168.56.41`(OPS01의 host-only 주소)만 받았고, 방화벽도 host-only 관리 대역의 22만 열었다. OPS01에는 이제 host-only 주소가 없다. `AllowUsers`를 `dbbackup@192.168.10.41`로 바꾸고 EDGE01 nftables에 `iifname enp0s8 ip saddr 192.168.10.41 tcp dport 22`를 더했다(`deploy/vm-lab/build/scripts/55-boundary-db-backup-sftp.sh`). 네트워크는 OPS01의 22를 열고, 계정 제한은 sshd가 맡는다.

복원 검증 에이전트는 OPS01에 두었다. 바이너리는 Databasus 자신(`http://127.0.0.1:4005/api/v1/system/verification-agent?arch=amd64`, SHA-256 `5e790778…42d406`)에서 받았다. 에이전트가 쓰는 임시 PostgreSQL 이미지(`databasus/verification-postgres:17-v3.59.0`, 다이제스트 `sha256:da1e6f09…840b6`)는 Docker Hub에 있어, pfSense LAN 규칙 "패키지 저장소 443 (점검 때만 켬)"을 23:03:28에 켜고 받은 뒤 23:04:54에 다시 껐다. 끈 뒤 OPS01에서 외부 443이 막힌 것을 확인했다. 에이전트 토큰은 사용자가 OPS01의 프롬프트(`read -s`)에 직접 넣었다. 에이전트는 설정 파일(`databasus-verification.json`, 0600)과 로그에 토큰 앞 8자만 남기고 나머지를 가린다. 로그도 0600으로 좁혔다.

## 발견과 남은 일

- 06의 경고 원인: DB01에서 `databasus_ro`가 실행할 수 있는 SECURITY DEFINER 함수는 `create_balance_for_account`, `register_trade_seq`, `register_event_emission`, `seed_market_journal_counters` 네 개다. 저장소의 V47.1이 PUBLIC 실행 권한을 회수하는 바로 그 함수들로, VM의 DB01에는 V47.1이 적용돼 있지 않다. `TEMP`는 PostgreSQL 기본값으로 PUBLIC에 열려 있다. 사용자가 "기록하고 진행"을 골랐고 V47.1 적용과 TEMP 정리는 하지 않았다.
- VM의 거래 DB에는 거래 데이터가 없다(마켓·주문·원장 0건). 이번 복원 검증은 스키마 109개 표와 기존 행이 되살아나는 것까지 보였고, 거래 데이터가 많은 상태의 복원 시간과 대조는 하지 않았다.
- 에이전트는 `verification-agent start`의 데몬으로 떠 있고 systemd 유닛이 없어 OPS01을 재부팅하면 다시 켜야 한다. 에이전트는 Docker의 user namespace 재매핑이 꺼져 있다고 경고했다.
- 백업 성공·실패 알림 대상(Teams 등)은 연결하지 않았다. 물리 백업과 PITR은 켜지 않았다.
- 개인키 보관: 저장소 등록 때 SFTP 개인키가 작업 PC의 클립보드와 브라우저를 한 번 지났다. 예행 기록의 "개인키는 OPS01 밖으로 나가지 않았다"는 등록 전까지의 사실이다.
