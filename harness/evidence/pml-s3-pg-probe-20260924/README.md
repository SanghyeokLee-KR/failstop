# ADR 0034 S3 설계의 PostgreSQL·Flyway 동작 확인 (2026-09-24)

[S3 설계](../../../docs/reference/architecture/마켓분할_리더_S3_설계.md) 3절의 근거다. 모든 확인은 로컬 `postgres:17-alpine`(PostgreSQL 17.10) 폐기용 컨테이너를 `--rm`, 127.0.0.1 무작위 포트, 이름 `pml-probe-s3*`로 띄워 하고 지웠다. 카오스 스택(`chaos-*`), 보존 볼륨 `exchange-chaos_chaos-pgdata`, 백업 볼륨 `failstop-keep-*`, 실험 스택 `exchange-pml`에는 접근하지 않았다. 새 이미지는 받지 않았다.

| 파일 | 하는 일 | 출력 |
|---|---|---|
| `run-quick.sh`, `quick-probe.sh`, `gen_cases.py`, `UpCheck.java` | P1 예약액 올림 양자화의 Java 대조(경계 20 + 고정 시드 난수 3,000), P5 함수 속성 `SET lock_timeout`, P6 소유자 권한 함수 안 23514 의 제약 이름 | `quick.log` |
| `run-flyway.sh`, `FlywayOrder.java` | P7 Flyway 11.7.2(Gradle 캐시 jar)의 번호 순서 동작. 높은 번호 뒤 낮은 번호 추가, 앞 이미지가 뒤 번호 DB 에 붙는 경우 | `flyway.log` |
| `s3-contract-check.sh`, `s3-contract-apply.sh`, `s3-seed.sql` | DB 설계 §3.4 의 V49 계약 SQL 과 §3.5 의 V50 계약 SQL, 하네스 검증설계 §4-1 의 C14'·C31 술어와 C31 앱 판을 문서에서 그대로 뽑아 저장소 V0~V48 위에 올리고 C0~C22 를 확인 | `s3-contract-check.log` |
| `s3-bench.sh` | B1 접수 트랜잭션의 DB 안 비용(pgbench, P1 대 P2, 클라이언트 1·4, 사용자 분산·같은 사용자) | `s3-bench.log` |
| `s3-bench-breakdown.sh` | B2 같은 비용의 분해(`reserve_funds` 단독, 머리말까지만 롤백, `synchronous_commit` 켬·끔, 트랜잭션당 WAL 바이트) | `s3-bench-breakdown.log` |
| `s3-scale-probe.sh`, `s3-scale-apply.sh` | P8 감사 문장의 누적 행 비용. 문서의 V49·V50 계약 SQL 과 C14'·C31(전수 판, 앱 판)을 올린 DB 에 합성 주문 생성 명령 N 건(예약 행, RESERVE 거래와 분개, 정산 이벤트·레지스트리·주문 행)을 넣고 C14' 첫 판·커서 판, C31 전수 판·앱 판, 전환 선행 조건, 게이지 질의, 기존 C05 의 `EXPLAIN (ANALYZE, BUFFERS)` 시간을 JIT 켬·끔으로 잰다 | `s3-scale-probe-1m.log`(N=1,000,000), `s3-scale-probe-3m.log`(N=3,000,000) |

다시 돌리는 법(Git Bash, 저장소 루트 아래 이 폴더에서): `bash run-quick.sh > quick.log 2>&1`처럼 각 실행기를 부른다. `s3-contract-check.sh`와 `s3-scale-probe.sh`는 문서에서 SQL 을 뽑으므로 문서가 바뀌면 결과도 바뀐다. 규모 측정은 `N=1000000 bash s3-scale-probe.sh > s3-scale-probe-1m.log 2>&1`처럼 N 을 준다. B1·B2 는 DB 안 비용이고 앱·네트워크·접수 락이 없으므로 ADR 0034 결정 5(마켓당 접수 천장 손실)의 판정 근거가 아니다. 판정은 S3 설계 7절 X5 의 폐기용 스택 주행이 한다.

## 검토 대응 뒤 다시 돌린 것 (2026-09-24 같은 날)

S3 설계 검토 지적(설계 11절)을 받아 계약 SQL 을 고친 뒤 `s3-contract-check.log`를 새 판으로 바꿨다. 첫 판 V50 SQL 의 SHA-256 은 `0a428e88…`, 검토 뒤 판은 `7c757c86…`이다. 첫 판 로그와 비교하면 C1 에 `lock_balance_rows` 한 줄이 늘었고, C3·C4·C16·C17 에서 퇴역 epoch 가 `matching-p0`, `matching-p1`, `matching-engine` 순으로 붙은 것만 다르다. 새로 더한 확인은 넷이다.

- C19 전환 퇴역 순서와 외부 보관소(DynamoDB) 첫 게시. 전환 뒤 하한 + 1 게시가 첫 시도에 된다(검토 R4·R7).
- C20 정산 배치와 P2 접수의 교착. 잠금 계획 없는 정산은 40P01 로 끝나고(`pg_stat_database.deadlocks` 0 에서 1), 첫 게시 전에 `lock_balance_rows`로 계획을 잡은 정산은 접수를 커밋까지 기다리게 할 뿐 교착이 없다(검토 R6).
- C21 C14' 정산 전 예약 항의 커서 경계와 게이지 질의. 커서 위 예약은 두 판이 같고, 정산 틈은 커서 판만 초과 LOCKED 로 드러낸다(검토 R5·R8).
- C22 잠금 계획 함수의 권한과 반환, C31 앱 판의 양성·음성 대조.

규모 측정(P8)은 트리거를 끄고 넣은 합성 행이라 원장 균형과 잔고 정합이 성립하지 않고 시간만 본다. 시드가 3마켓이므로 12마켓 운영 분포의 판정은 S3 설계 7-3 X7 이 한다. 합성 적재는 100만 건에서 약 38초, 300만 건에서 약 123초 걸렸고 DB 크기는 1,896MB, 5,837MB 였다. 컨테이너는 실행이 끝나면 지워진다.
