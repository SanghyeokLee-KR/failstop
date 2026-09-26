# ADR 0034 S4b 설계의 Spring·PostgreSQL·nginx 동작 확인 (2026-09-25)

[S4b 설계](../../../docs/reference/architecture/마켓분할_리더_S4b_설계.md) 3절의 근거다. 두 실행기 모두 폐기용 컨테이너(이름 `pml-probe-*`, `--rm`)를 새 도커 망에 띄워 확인하고 끝나면 망까지 지운다. 카오스 스택(`chaos-*`), 실험 스택(`exchange-pml`), 보존 볼륨 `exchange-chaos_chaos-pgdata`, 백업 볼륨 `failstop-keep-*`에는 접근하지 않았다. 이미지는 로컬에 있던 태그만 썼고 새로 받지 않았다. 호스트에는 Spring 확인이 127.0.0.1 무작위 포트 셋(PostgreSQL, toxiproxy 프록시, toxiproxy API)을 열었고, nginx 확인은 전용 도커 망 안에서만 돌아 호스트 포트를 열지 않았다. 실행이 끝난 뒤 `pml-probe` 이름의 컨테이너와 망이 남지 않은 것을 `docker ps -a`와 `docker network ls`로 확인했다.

| 파일 | 하는 일 | 출력 |
|---|---|---|
| `spring-probe.sh`, `DbErrorProbe.java` | J. 앱 JAR(`harness/app.jar`, S4 재주행 이미지의 JAR와 같은 파일)에서 꺼낸 Spring JDBC·TX 6.2.19, pgjdbc 42.7.11, HikariCP 6.3.3 으로, PostgreSQL 17.10 오류가 어떤 스프링 예외로 번역되는지와 `JdbcTransactionManager` 위 `TransactionTemplate`이 연결 장애에서 무엇을 던지는지 본다. 연결 장애는 toxiproxy 2.12.0 을 앱과 같은 방식(`timeout` 0, `downstream`)으로 걸고 2초 뒤 지워 만든다 | `spring-probe.log` |
| `nginx-s4b-probe.sh`, `nginx-s4b-client.sh` | N2. S4 확인의 가짜 노드와 nginx 설정 틀(`../pml-s4-probe-20260924/nginx-backend.py`, `nginx-template.conf`, 하네스와 엣지의 쓰기 업스트림과 같은 줄)로, DB 연결 장애를 `503`으로 낼 때와 종전처럼 `500`으로 낼 때 클라이언트가 받는 응답과 리더 복구 뒤 `200`까지의 시간을 0.1초 간격 요청으로 잰다 | `nginx-s4b-probe.log` |

다시 돌리는 법(Git Bash, 이 폴더에서): `bash spring-probe.sh > spring-probe.log 2>&1`, `bash nginx-s4b-probe.sh > nginx-s4b-probe.log 2>&1`. Spring 확인은 호스트의 JDK 21 이 필요하고, 비밀번호는 실행마다 무작위로 만들어 어디에도 적지 않는다.

## 결과

J(Spring·PostgreSQL, 2026-09-25 02:31 KST). 각 사례 뒤 풀 연결로 `SELECT 1`이 성공했다(끊긴 연결은 풀이 버렸다).

- J1 `statement_timeout` 300ms 에 `pg_sleep(1)`: `QueryTimeoutException`(57014).
- J2 다른 세션이 행을 `FOR UPDATE`로 쥔 동안 `lock_timeout` 300ms: `UncategorizedSQLException`(55P03).
- J3 교착: `PessimisticLockingFailureException`(40P01). J4 반복 읽기 갱신 충돌: `CannotAcquireLockException`(40001).
- J5 `RAISE EXCEPTION 'fencing 위반: …'`: `UncategorizedSQLException`(P0001).
- J6 트랜잭션 안에서 관리 연결로 그 백엔드를 `pg_terminate_backend` 한 뒤 문장: `TransactionSystemException("JDBC rollback failed")`, 원인은 SQLSTATE 없는 `SQLException("Connection is closed")`, `getApplicationException()`은 `DataAccessResourceFailureException`(57P01).
- J7 E2 모양(문장 중 `timeout` 0 을 걸고 2초 뒤 제거): J6 과 같은 모양이고 `getApplicationException()`은 `DataAccessResourceFailureException`(08006, `EOFException`). S4 재주행 E2 의 접수 `500`(`TransactionSystemException: JDBC rollback failed`)과 같은 사슬이다.
- J8 같은 차단을 커밋 직전에 걸고 2초 뒤 제거: 앱은 `TransactionSystemException("JDBC rollback failed")`을 받았고 `getApplicationException()`은 없었다. 관리 연결로 본 그 행 수는 1 이라 **커밋은 됐다.**
- J9 프록시를 끈 직후 풀 연결로 조회: 0ms 만에 `DataAccessResourceFailureException`(08006). 풀이 방금 쓴 연결을 검증 없이 내줬다. 이어 트랜잭션 시작: 1.5초 뒤 `CannotCreateTransactionException`(원인 `SQLTransientConnectionException` 08001).

N2(nginx 1.29.8, 2026-09-25 02:28 KST). A 는 1차(엣지에서는 첫 노드), B 는 백업(엣지에서는 둘째 노드)이다. 시각은 사례 시작부터의 초다.

- D1 하네스, A(DB 불통인 옛 리더)가 `503`, B(새 리더)가 `200`: 20건 모두 `200`. A 는 첫 요청 한 번만 받았고 그 뒤 빠졌다.
- D1' 같은 배치에서 A 가 `500`: 20건 모두 `500`. B 는 한 번도 받지 않았다.
- D2 하네스, A(리더)가 DB 불통으로 `503`, B(팔로워)가 `503`: 첫 요청 `503` 뒤 `502`(`no live upstreams`), 2.59초의 A 재탐침 `503`. 3.25초에 A 를 `200`으로 되돌리자 첫 `200`은 5.61초(복구 뒤 2.36초)였다.
- D2' 같은 배치에서 A 가 `500`: 불통 동안 모두 `500`, 복구 뒤 첫 요청(0.11초 뒤)부터 `200`.
- D3 엣지, A 가 `503`, B 가 `200`: 20건 모두 `200`. A 는 한 번 받았다.
- D4 엣지, A(리더) `503`, B(팔로워) `503`: 불통 동안 `503` 두 건 외에는 `502`, 3.23초 복구 뒤 첫 `200`은 5.58초(복구 뒤 2.35초).
- D4' 같은 배치에서 A 가 `500`: 불통 동안 `500`, 복구 뒤 곧바로 `200`.
- nginx 오류 로그의 `no live upstreams` 98줄.

이 확인은 Spring·PostgreSQL·nginx 의 동작만 본다. S4b 코드는 아직 없으므로 S4b 설계 6절의 시험과 7절의 폐기용 스택 실험은 실행하지 않았다(NOT RUN).
