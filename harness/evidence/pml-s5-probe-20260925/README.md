# ADR 0034 S5 설계의 nginx·PostgreSQL 동작 확인 (2026-09-25)

[S5 설계](../../../docs/reference/architecture/마켓분할_리더_S5_설계.md) 3절의 근거다. 모든 실행기는 폐기용 컨테이너(이름 `pml-probe-s5*-<난수>`, 끝나면 지움)만 쓰고, nginx 확인은 전용 도커 망 안에서만 돌아 호스트 포트를 열지 않았다. PostgreSQL 확인은 `--rm` 컨테이너 하나를 127.0.0.1 무작위 포트로 띄웠다. 카오스 스택(`chaos-*`), 실험 스택(프로젝트 `exchange-pml`), 보존 볼륨 `exchange-chaos_chaos-pgdata`, 백업 볼륨 `failstop-keep-*`, 영속 DB에는 접근하지 않았다. 같은 시기에 떠 있던 다른 작업의 컨테이너(`pml-probe-client`, `pml-probe-rabbit`)와 망(`pml-probe-net`)에는 명령을 보내지 않았다. 이미지는 로컬에 있던 태그(`nginx:1.29`, `python:3.12-alpine`, `curlimages/curl:8.11.0`, `postgres:17-alpine`)만 썼다. 실행이 끝난 뒤 `pml-probe` 이름의 컨테이너와 망이 남지 않은 것을 `docker ps -a`와 `docker network ls`로 확인했다.

| 파일 | 하는 일 | 출력 |
|---|---|---|
| `nginx-partition-draft.conf` | S5 설계 4-5의 파티션 LB 설정 초안. 구현 단계에서 `harness/nginx/nginx.partition.conf`가 된다(sha256 앞 16자 `739ee58804b4c569`) | |
| `check_partition_conf.py` | 초안이 `harness/nginx/nginx.conf`의 보존 블록을 줄 단위로 그대로 담는지, 힌트 맵이 V48 대응 시드와 `InitialMarketCatalog`의 심볼로 만든 표와 같은지 본다 | `check-partition-conf.log` |
| `nginx-s5-probe.sh`, `nginx-s5-client.sh`, `nginx-s5-backend.py`, `nginx-s5-compare.conf` | N5. 가짜 노드 둘(A, B)과 nginx 1.29.8 둘(초안, 비교 설정)로 힌트 라우팅, 비소유 노드의 `503` 재시도, 소유자의 일시 `503`이 파티션 쓰기를 얼마나 막는지, 장애 모양(E4)을 잰다. 가짜 노드는 앱의 소유권 선검사처럼 소유하지 않는 마켓에 `503` + `Retry-After: 1` + `X-Exchange-Route: not-owner`를 낸다 | `nginx-s5-probe.log` |
| `accel-probe.sh` | N5의 X1 대안(`421` + `X-Accel-Redirect`)이 상태 코드와 대상 모양에 따라 어떻게 넘기는지 본다 | `accel-probe.log` |
| `s5-pg-probe.sh`, `s5-pg-apply.sh`, `v54-draft.sql` | P5. 저장소의 V0~V53을 폐기용 PostgreSQL 17.10에 파일마다 한 트랜잭션으로 올리고, DB 설계 §3.9의 V54 SQL 블록을 문서에서 그대로 뽑아 올린 뒤 인계 표의 권한·제약·점유·포기·재점유를 본다. `S5_V54_DRAFT`에 파일을 주면 문서 대신 그 파일을 올린다 | `s5-pg-probe.log`(문서 원천), `s5-pg-probe-draft.log`(초안 원천) |
| `s5r-pg-probe.sh`, `s5r-pg-apply.sh`, `v54r-draft.sql` | P6. S5 설계 검토 대응(설계 11절)으로 고친 V54(상태 `EXPIRED`)와 DB 설계 §5.4-d의 네 문장(리스 확인이 붙은 점유·끝내기, 시작한 몫을 뺀 포기, 만료)을 같은 방법으로 확인한다(R0~R10). `S5R_V54_DRAFT`에 파일을 주면 문서 대신 그 파일을 올린다 | `s5r-pg-probe.log`(문서 원천), `s5r-pg-probe-draft.log`(초안 원천) |

다시 돌리는 법(Git Bash, 이 폴더에서): `bash nginx-s5-probe.sh > nginx-s5-probe.log 2>&1`, `bash accel-probe.sh > accel-probe.log 2>&1`, `bash s5-pg-probe.sh > s5-pg-probe.log 2>&1`, `bash s5r-pg-probe.sh > s5r-pg-probe.log 2>&1`. 초안 대조는 저장소 루트에서 `python harness/evidence/pml-s5-probe-20260925/check_partition_conf.py harness/nginx/nginx.conf harness/evidence/pml-s5-probe-20260925/nginx-partition-draft.conf server/app/src/main/java/kr/exchange/config/InitialMarketCatalog.java server/app/src/main/resources/db/migration/V48__market_partition_lease.sql`이다. PostgreSQL 비밀번호는 폐기용 컨테이너 안에서만 쓰는 고정 값이고 운영 자격증명이 아니다.

## 결과

초안 대조(12:38 KST). 보존 블록 여섯(업그레이드 맵, 리졸버, `exchange_write`, `exchange_read`, 조회 location, 기본 location)이 모두 같고, 힌트 맵 12개가 V48 시드와 같았다(`RESULT OK`).

N5(nginx 1.29.8, 12:06 KST 시작). A가 p0, B가 p1을 소유하는 P3 흉내가 기본이다. 시각은 사례 시작부터의 초다.

- R1 힌트를 실은 쓰기(생성, 배치, 단건 취소, 마켓을 준 일괄 취소) 7건이 모두 그 파티션 1차로 곧바로 가 `200`이었다. 힌트를 실은 GET과 조회 경로는 종전 경로였다.
- R2 힌트 없는 ETH 생성은 `exchange_write` 1차 A(비소유)의 `503` 뒤 B에서 `200`이었다. 곧이어 보낸 힌트 없는 BTC는 A가 빠진 `exchange_write`에서 B(비소유)의 `503` 뒤 `502`(`no live upstreams`)였다. 같은 순간 힌트를 실은 BTC·ETH는 `200`이었고, 2.53초 뒤 힌트 없는 BTC는 A에서 `200`이었다.
- R3 소문자 힌트(`krw-eth`)는 `exchange_p1`으로 갔다(nginx `map`은 대소문자를 가리지 않는다). 앞뒤 공백은 nginx가 걷어 `KRW-ETH`로 갔다. 같은 이름의 헤더 둘은 값이 `KRW-ETH, KRW-BTC`로 이어 붙어 `exchange_write`로 갔다.
- R4 A가 모든 파티션을 쥔 P2 흉내에서 ETH 힌트 쓰기 24건(0.25초 간격)과 BTC 1건이 모두 `200`이었다. ETH 첫 두 건은 1차 B의 `503` 뒤 백업 A로 넘어갔고, B가 빠진 동안은 A로 곧바로 갔으며, `fail_timeout`이 지난 3.37초에 한 건이 B를 다시 거쳤다(24건 가운데 셋이 두 번 시도).
- R5 초안(1차 `max_fails=2`)에서 소유자 A가 한 건에만 `503`을 내자 그 요청은 백업 B의 `503`으로 끝났고, 이어진 BTC 12건과 ETH 1건은 모두 `200`이었다.
- R6 같은 배치에서 A가 잇달아 두 건 `503`을 내게 하자 첫 요청은 `503`, 둘째 요청부터 약 2.1초 동안 BTC 12건이 `502`였다.
- 비교 설정(한 파티션 업스트림만 모양을 바꿈). `mf1`(1차 `max_fails=1`, 하네스 `exchange_write`와 같은 모양)은 소유자 `503` 한 건 뒤 약 2.1초 동안 14건 `502`, 두 건 뒤 약 2.7초 동안 19건 `502`였다. `mf2`는 한 건 뒤 이어진 14건이 모두 `200`, 두 건 뒤 약 2.7초 동안 18건 `502`였다. `mf3`은 한 건과 두 건 모두 뒤따른 요청이 모두 `200`이었다. `inv`(평소 소유자를 백업에 둔 뒤집은 모양)는 소유자의 `503`마다 그 요청만 `502`였고 뒤따른 요청은 모두 `200`이었지만, 평상시 약 2초마다 한 요청이 비소유 1차를 거쳤다. `rr`(두 노드 라운드로빈, 엣지와 같은 모양)은 소유자 `503` 한 건부터 약 2.1초 동안 15건이 `502`였다.
- `mf2` 분산: `503` 한 건, 1.2초 동안 성공 여섯, 다시 `503` 한 건이면 뒤이어 약 2.5초 동안 14건 `502`였다. `fail_timeout` 안의 성공이 실패 계수를 지우지 않는다.
- X1 비소유 노드가 `421` + `X-Accel-Redirect: @accel_other`를 내면 nginx가 이름 붙은 location으로 넘겨 메서드와 바디 길이가 보존됐고 5건 모두 `200`이었으며 `421`은 실패로 세지 않았다. `accel-probe.log`: 이름 붙은 location으로는 `200`·`421`·`503`·`409` 모두 넘겼고 POST·DELETE와 바디가 보존됐다. 내부 URI로 넘기면 메서드가 GET으로 바뀌었다.
- F E4 모양. A를 멈추고 약 6초 뒤 B가 두 파티션을 쥐게 했다. 그동안 BTC(p0) 요청은 `503` 둘, `502` 셋이었고 B가 쥔 뒤 모두 `200`이었다. ETH(p1) 요청 20건은 처음부터 모두 `200`이었다. A가 소유 없이 돌아온 뒤 BTC 16건은 1차 A의 `503` 뒤 백업 B에서 모두 `200`이었다.

P5(PostgreSQL 17.10, 12:35 KST, V54 원천은 DB 설계 §3.9, 57줄, sha256 앞 16자 `01c3309957317817`). 초안(`v54-draft.sql`, 같은 해시)으로 12:12에 돌린 로그와 휘발 값(시각, 무작위 UUID, 컨테이너 이름, 포트, 경과 시간)을 빼고 같다.

- P5-0 V0~V53 54개 파일과 V54가 오류 없이 올라갔다. 열 13개, 색인 셋(기본 키, `idx_ocr_open` 부분 색인, `idx_ocr_request`), `app_role` INSERT·SELECT·UPDATE, `harness_role` SELECT.
- P5-1 `app_role` 로그인이 다섯 행을 넣었고 DELETE는 42501로 거부됐다. `harness_role` 로그인은 읽기만 됐고 넣기는 거부됐다.
- P5-2 목록 밖 자원(`chk_ocr_resource`), 빈 목록·NULL 원소·501개(`chk_ocr_order_ids`), 없는 회원(외래키 23503), 결과 없는 `DONE`과 점유 없는 `CLAIMED`(`chk_ocr_claim`)가 모두 거부됐다.
- P5-3 X가 트랜잭션 안에서 p1 한 행을 점유한 채 멈춘 동안 Y는 기다리지 않고 나머지 두 행을 점유했다(5.2ms).
- P5-4 X가 커밋하기 전에 요청자가 `state = 'PENDING'` 조건으로 포기하자 X의 커밋까지 기다린 뒤(2.3초) X가 점유한 행은 건너뛰고 남은 `PENDING` 한 행만 `ABANDONED`로 바꿨다.
- P5-5 epoch 7 작업자는 epoch 7의 점유를 다시 잡지 않았고(0행), epoch 8 작업자는 세 행을 다시 점유했다. 옛 점유자(X, 7)의 끝내기는 0행, 새 점유자(W8, 8)의 끝내기는 `DONE` 1행이었다.
- P5-6 넣은 지 6분 된 `PENDING` 행은 점유되지 않았다.
- P5-7 끝난 행 2만 개를 더하고 ANALYZE한 뒤 점유 질의 계획은 `Limit → LockRows → Index Scan using idx_ocr_open`이었고, 요청자의 모아 읽기는 `idx_ocr_request` 비트맵 조회였다.
- P5-8 요청 행을 넣는 트랜잭션이 열린 동안(외래키가 회원 행에 KEY SHARE) 같은 회원의 `UPDATE users SET status = 'CLOSED'`는 기다리지 않았다(3.8ms).

P6(PostgreSQL 17.10, 13:32 KST, V54 원천은 설계 검토 대응 뒤의 DB 설계 §3.9, 59줄, sha256 앞 16자 `52f13181748b31c9`). 초안(`v54r-draft.sql`, 같은 해시)으로 13:39에 돌린 로그(`s5r-pg-probe-draft.log`)와 휘발 값(시각, 무작위 UUID, 컨테이너 이름, 포트, 경과 시간)을 빼고 같다. P5의 V54(57줄, `01c3309957317817`)와 점유 문장은 검토 대응 전의 첫 판이다. 리스 행은 소유자 로그인이 직접 바꿔 흉내 냈다.

- R0 V0~V53 54개 파일과 V54가 올라갔다. CHECK 일곱(상태 다섯), 색인 셋, 권한은 P5와 같다.
- R1 `app_role` 로그인이 `leader_lease`를 읽었고 DELETE는 거부됐다. `harness_role` 로그인의 넣기도 거부됐다.
- R2 점유 열 없는 `EXPIRED`와 점유 열 있는 `ABANDONED`는 `chk_ocr_claim`, `finished_at` 없는 `EXPIRED`는 `chk_ocr_finished`, 결과 있는 `EXPIRED`는 `chk_ocr_result`로 거부됐고, 점유 열과 `finished_at`이 있는 `EXPIRED`는 들어갔다.
- R3 p1 리스를 N1·7로 두고 같은 holder·epoch의 두 스레드가 점유했다. 한 스레드가 한 행을 쥔 채 멈춘 동안 다른 스레드는 5.8ms에 나머지 두 행만 점유했다.
- R4 포기 규칙. 한 행이 점유(커밋)된 요청의 포기는 0행이었다(a). p0 한 행·p1 두 행 요청에서 p1 한 행만 점유되면 p0 행만 포기됐다(b). 점유 트랜잭션이 커밋되기 전에 시작한 포기는 1.3초 기다린 뒤 점유된 행을 건너뛰고 나머지 두 행을 포기했고, 그 두 행은 뒤에 점유되지 않았다(c). 아무도 가져가지 않은 요청은 모두 포기됐다(d).
- R5 N1·7이 두 행을 점유한 뒤 p1 리스를 N2·8로 바꿨다. N1·7의 끝내기(점유자·epoch는 맞음)와 새 점유는 0행이었다. N2·8은 N1의 두 행과 `PENDING` 한 행을 다시 점유했고 끝내기가 `DONE` 1행이었다. 리스를 만료시키자 같은 N2·8의 점유와 끝내기가 0행이었다.
- R6 넣은 지 6분 된 `PENDING` 행은 유효한 리스로도 점유되지 않았다.
- R7 점유 트랜잭션과 끝내기 트랜잭션이 각각 열린 동안 `renew_lease`가 약 3ms에 참을 돌려줬고, 소유자 로그인의 리스 행 `FOR UPDATE NOWAIT`가 약 1ms에 됐다.
- R8 만료 문장이 넣은 지 6분 된 `PENDING`을 `ABANDONED`로, 6분 전 점유한 `CLAIMED`를 `EXPIRED`로 바꿨다. 새 `PENDING`, 6분 전에 넣었으나 1분 전 다시 점유한 `CLAIMED`, 하루 전 `DONE`은 그대로였다.
- R9 하루 지난 `PENDING` 5만 행과 `CLAIMED` 5만 행(두 파티션 반씩)이 있을 때 점유 질의는 `Seq Scan`(10만 행 걸러냄) 10.6ms, 새 행 없는 파티션의 확인은 9.4ms였다. 만료가 101문장(총 3.2초, 가장 긴 문장 47ms)으로 10만 행을 닫은 뒤 두 질의는 `idx_ocr_open` 색인 조회로 3.9ms·2.0ms, 닫을 행이 없을 때 만료 문장은 1.7ms였다.
- R10 요청 행을 넣는 트랜잭션이 열린 동안 같은 회원의 폐쇄 UPDATE가 3.9ms에 끝났다.

P6을 돌리는 동안 다른 작업의 컨테이너(`pml-probe-rmq3`)가 떠 있었고 명령을 보내지 않았다. 끝난 뒤 `pml-probe-s5r` 이름의 컨테이너가 남지 않은 것을 확인했다.

이 확인은 nginx와 PostgreSQL의 동작만 본다. S5 코드는 아직 없으므로 S5 설계 6절의 시험과 7절의 폐기용 스택 실험은 실행하지 않았다(NOT RUN).
