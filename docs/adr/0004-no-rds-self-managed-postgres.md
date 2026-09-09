# ADR 0004. 관리형 RDS를 쓰지 않고 EC2에서 PostgreSQL을 자체 운영한다

- 상태: 채택
- 날짜: 2026-08-26
- 결정자: 아키텍처 담당(정본 문서 관리자)

## 배경

카오스 실험 E2에서 클러스터가 리더 없이 정지한 사건이 이 결정의 출발점이다.
`harness/docker-compose.chaos.yml`의 `chaos-pg` 주석이 사건을 가리키고, 전말은 여기 적는다. 백엔드가 idle in transaction
상태로 2분 넘게 RowShareLock(FOR SHARE)을 쥐었고, 스탠바이의 `try_acquire_leader` UPDATE가
그 뒤에서 대기했다. `docker pause`와 network disconnect는 RST를 남기지 않아 PostgreSQL이
클라이언트 사망을 인지하지 못했다. 기본값이 `idle_in_transaction_session_timeout=0`이고
리눅스 keepalive 기본이 2시간이라 회수 경로 자체가 없었다.

해소 수단은 전부 PG 서버 파라미터다. `idle_in_transaction_session_timeout=10s`,
`tcp_keepalives_idle=5`, `tcp_keepalives_interval=2`, `tcp_keepalives_count=3`
(`harness/docker-compose.chaos.yml`의 `chaos-pg` command 블록). 같은 서비스의 주석이
이 둘을 회수 시간 유계화의 두 축으로 규정한다. 현재 E2는 RTO 13.882초, E7은 9.983초, E5는 11.99초로
라이브 PASS다. 셋 다 로컬 카오스 스택 회차이고 verdict 파일이 저장소에 남지 않았다. 근거는
`docs/contracts/하네스_검증설계.md`의 2026-07-29 패치 노트(E2)와 ADR 0012(E5·E7, 커밋 923ee6d)다.

AWS 이관 설계에서 DB 계층을 관리형으로 올릴지 판단해야 했다. 이 축의 요구를 정본 4.2
선택표의 데이터베이스 행이 적는다. 커널 계층 장애 주입(tc netem, nftables, SIGSTOP)과
노드 간 실제 네트워크 분단 관측이 그 요구다. 둘 다 OS 셸 접근을 전제한다.

## 결정

DB 계층을 EC2 자체 운영 PostgreSQL 17로 둔다. RDS를 채택하지 않는다.

비채택 판정을 정본 4.2 선택표의 데이터베이스 행과 4.3 비채택 표의 관리형 관계형 데이터베이스
행에 기록했다. 커밋 615c256(2026-08-26).

## 근거

- 증명 범위가 결정 축이다. 다섯 축(OS 셸 접근, DB 경로 tc netem 지연·손실·재정렬,
  nftables 5432 블랙홀(RST 없는 DROP), 커널 파라미터 keepalive·somaxconn·conntrack 조작,
  PG 프로세스 SIGSTOP·kill -9)이 전부 RDS에서 불가다. 정본 4.2 선택표의 데이터베이스 행이
  같은 사유를 "관리형 데이터베이스는 운영체제 접근이 없어 커널 파라미터 조작과 프로세스 정지를
  못 합니다"로 적는다.
- 증명 명제가 장애 아래에서의 무결과 자동 복구와 병목 이동·안정화 셋이다(정본 1.3). 정본 4.2
  선택표의 데이터베이스 행이 결론을 적는다. RDS를 채택하면 DB 계층 전체가 증명 범위 밖으로
  나가고 남는 명제는 앱 계층 페일오버로 축소된다.
- 인용 가능한 실측이 그 축 위에 있다. 정본 10.3 장애 주입 카탈로그의 E2·E5·E7은 각각
  연결 절단·네트워크 분단·컨테이너 일시 정지 실험이고,
  `harness/docker-compose.chaos.yml`의 `chaos-pg` 주석이 disconnect·pause 둘 다 RST를 남기지
  않아 기본값 아래에서는 회수되지 않는다고 적는다. 주입 권한이 없으면 이 세 수치를 AWS에서 재현할 수 없다.
- 비용이 반대 방향으로 걸리지 않는다. 2026-08-26 판정 시점 산정이 RDS Multi-AZ 최소 등급 약
  USD 85 대 EC2 2대 + 80GiB 약 USD 53으로 자체 운영이 저렴했다. 그 뒤 정족수 채택으로
  데이터베이스 노드가 3대가 됐고, 현재 단가는 정본 9.2가 768시간 47.9232달러로 적는다.
- 로컬과 클라우드의 동형화 계약과 맞는다. 정본 5.3이 두 평면을 잇는 것을 같은 컨테이너 이미지
  지문으로 규정하고, `deploy/compose.node-data.yml:16`과 `harness/docker-compose.chaos.yml:11`이
  둘 다 `postgres:17-alpine`으로 고정돼 있다. 관리형은 이 계약을 만족시키지 못한다.

## 대안

- RDS PostgreSQL Multi-AZ 채택. 기각. 위 다섯 축 전량 불가이고 비용도 더 크다.
  이 안만 정본 4.3 비채택 표에 정식 판정으로 기록돼 있다.
- 혼합안. 상시면은 RDS, 증명면은 EC2 자체 운영. 기각. 정본 5.3이 두 면에 같은 이미지
  지문을 요구하고, `deploy/compose.node-data.yml:10-11`이 "상시면과 증명면이 같은 값을 써야
  실험 결과가 이관된다"를 keepalive 튜닝 승계의 사유로 명시한다. 두 면의 DB 구현이 갈리면
  증명면 결과를 상시면 주장으로 옮길 수 없다. 이 안은 정본 4.3에 별도 행으로 없다.
  기각 근거는 동형화 계약에서 유도한 것이다.
- 자체 운영하되 RDS가 주던 것의 대체를 만들지 않는다. 기각. 정본 4.1이 "대가는 정직하게
  계상합니다"로 원칙을 적고, 7.9 백업과 복구 표가 스냅샷·기저 백업·WAL 아카이빙·복제를 등재했다.
  다만 백업 3종은 전부 "목표. 미배선"이고 복제는 "설계 확정. 클라우드 미배포"다.
  자동 패치는 7.9 밖이고 "dnf-automatic" 문자열은 정본에 0건이다
  (`grep -c "dnf-automatic" "제출_20260901/기획안_원본.md"` -> 0).
  대체 없이 자체 운영하면 `docs/architecture/아키텍처_개관.md` 3.3이 적은 RPO=0 범위 한계 위에
  백업 부재가 더해진다.

## 영향

- 대체 구현 4종이 부채로 남았다(정본 7.9 백업과 복구 표). 자동 스냅샷은 EBS 스냅샷 일 1회 +
  `pg_basebackup` 주 1회, PITR은 WAL 아카이빙을 S3로, Multi-AZ 페일오버는 스트리밍 복제
  standby + 수동 승격 스크립트, 자동 패치는 dnf-automatic security only다. 자동 승격(Patroni)은
  스트레치로 분리했다.
- 배선 실물은 `deploy/compose.node-data.yml`이다. 커밋 4d5ba51(2026-08-30).
  command 블록 `:21-41`이 `synchronous_commit=on`, 유휴 트랜잭션 타임아웃, keepalive 3종을
  카오스 스택에서 그대로 승계하고 `archive_mode`·`archive_command`를 변수로 열어 PITR 배선 자리를 남긴다.
  카오스 스택의 `ports: ["55432:5432"]`(`harness/docker-compose.chaos.yml:35`)는 승계하지
  않는다. 사유는 `deploy/compose.node-data.yml:8-9`에 있다.
- 4d5ba51 커밋 본문은 data 오버레이를 실제로 기동해 확인했다고 적는다. 조사 중
  `tcp_keepalives_idle`이 0으로 보였으나 SHOW가 현재 연결 기준으로 보고하는 것이었고,
  유닉스 소켓 연결은 0, TCP 연결은 5를 낸다. 정본 주장이 틀렸다고 결론 낼 뻔한 지점이다.
- 반영 한계. `deploy/README.md:36-39` "상태" 절이 미검증을 명시한다. 실제 다중 호스트에서
  기동한 적이 0회다. AWS 실기동 근거는 저장소에 없다. 위 기동 확인은 단일 호스트 범위다.
- 남는 한계는 문서에 이미 적혀 있다. `docs/architecture/아키텍처_개관.md` 3.3은 RPO=0이 단일
  PostgreSQL 인스턴스 범위에서만 성립한다고 적고, 동기 standby·STONITH 운영 요건이 미충족이라고
  적는다. 정본 13.2 한계 8은 봇과 관측이 한 노드에 있어 그 노드가 죽으면 기저 트래픽과 지표
  수집이 함께 멈춘다고 적는다. 이 ADR은 그 한계를 닫지 않는다.
- 되돌리려면 다음을 함께 고쳐야 한다. 정본 4.2 선택표와 4.3 비채택 표, 5.3 이미지 지문,
  7.9 백업과 복구 표, `deploy/compose.node-data.yml` 전체, 9.2 상시면 단가표의 데이터베이스 행.
  실험 계획도 걸린다. nftables 5432 블랙홀은 E2 재현에 필수이고 PG 프로세스 SIGSTOP·kill -9는
  E7 계열에 필수인데 둘 다 RDS에서 불가다. 정본 11.1 W6의 최종 완주에서 최소 E2·E7이 빠진다.
