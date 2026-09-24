# ADR 0034 S4 설계의 PostgreSQL·nginx 동작 확인 (2026-09-24)

[S4 설계](../../../docs/reference/architecture/마켓분할_리더_S4_설계.md) 3절의 근거다. 두 실행기 모두 폐기용 컨테이너를 띄워 확인하고 끝나면 지운다. 카오스 스택(`chaos-*`), 보존 볼륨 `exchange-chaos_chaos-pgdata`, 백업 볼륨 `failstop-keep-*`, 실험 스택 `exchange-pml`에는 접근하지 않았다. 이미지는 로컬에 있던 태그만 썼고 새로 받지 않았다. 호스트 포트는 PostgreSQL 확인이 127.0.0.1 무작위 포트 하나를 열었고, nginx 확인은 전용 도커 망 안에서만 돌아 호스트 포트를 열지 않았다.

| 파일 | 하는 일 | 출력 |
|---|---|---|
| `nginx-probe.sh`, `nginx-template.conf`, `nginx-backend.py`, `nginx-client.sh` | N. nginx 1.29.8 쓰기 업스트림 두 형태(하네스 `exchange_write`의 1차·백업, 엣지의 라운드로빈)에서 리더의 일시 `503` 한 건과 `429` 한 건이 업스트림 판정에 어떻게 들어가는지 C1~C5 로 본다. 가짜 노드 둘(`python:3.12-alpine`)은 설정한 상태 코드를 돌려주고, 헤더로 한 요청에만 다른 코드를 낸다. `proxy_next_upstream`·`max_fails`·`fail_timeout`·`backup max_fails=0`은 저장소의 두 설정과 같게 뒀다 | `nginx-probe.log` |
| `s4-pg-probe.sh`, `s4-pg-apply.sh` | G. 저장소의 V0~V52 를 `postgres:17-alpine`(PostgreSQL 17.10)에 파일마다 한 트랜잭션으로 올리고, DB 설계 §3.8 의 V53 계약 SQL 을 문서에서 그대로 뽑아 올린 뒤 G1~G5 를 확인한다 | `s4-pg-probe.log` |

다시 돌리는 법(Git Bash, 이 폴더에서): `bash nginx-probe.sh > nginx-probe.log 2>&1`, `bash s4-pg-probe.sh > s4-pg-probe.log 2>&1`. `s4-pg-probe.sh`는 문서에서 SQL 을 뽑으므로 문서가 바뀌면 결과도 바뀐다. 설계 초안 단계에서는 문서에 V53 을 넣기 전에 `S4_V53_DRAFT=<파일>`로 같은 SQL 을 시험했고, 지금 로그는 문서에서 뽑은 판(12줄, SHA-256 앞 16자리 `a009eb5a807bf35f`)이다.

## 결과

N(nginx, 16:17 KST).

- C1 하네스 형태. 1차가 리더, 백업이 팔로워(`503`)인 상태에서 1차가 한 요청에만 `503`을 내면, 그 요청은 백업으로 넘어가 `503`을 받았고, 이어 보낸 여섯 건(사례 시작 뒤 0.22~1.26초)이 모두 `502`(`no live upstreams`)였다. `fail_timeout` 2초가 지난 3.47초의 요청은 1차의 `200`이었다.
- C2 같은 배치에서 1차가 `429`를 내면 그 한 건만 클라이언트에 `429`로 가고 재시도·퇴출이 없었다. 다음 여섯 건은 모두 1차의 `200`이었다.
- C3 1차가 팔로워, 백업이 리더이면 첫 요청이 1차 `503` 뒤 백업 `200`으로 넘어가고, 이어지는 요청은 백업으로 바로 간다. 종전 페일오버 동작 그대로다.
- C4 엣지 형태(두 노드 `max_fails=1`, 라운드로빈). 리더가 한 요청에만 `503`을 내자 그 요청을 포함해 일곱 건(0.84~2.09초)이 모두 `502`였다. 팔로워는 늘 `503`이라 이미 퇴출 상태였고 리더까지 빠졌기 때문이다. 4.50초부터 다시 `200`이었다.
- C5 같은 배치에서 리더의 `429`는 그 한 건만 돌아가고 다음 여섯 건이 모두 `200`이었다.

그래서 오픈소스 nginx 에서는 리더의 일시 `503`을 재시도하면서 노드 장애로 세지 않게 하는 설정이 없고, `429`는 재시도도 계상도 되지 않는다. 설계 4-12 가 접수 경합을 `429`로 낸 근거다.

G(PostgreSQL, 16:38 KST).

- G0 저장소 마이그레이션 53개와 문서의 V53 이 오류 없이 올라갔다.
- G1 모드를 소유자 UPDATE 로 3 으로 두면 최고 모드 2 의 획득은 55000(`이 호출은 P2 까지만 안다`), `matching-engine` 획득도 55000 이고, 최고 모드 3 의 `matching-p0` 획득은 epoch 1 을 받았다. S4 바이너리(최고 모드 2)가 P3 DB 에서 리더가 되지 못한다는 근거다.
- G2 다른 세션이 `matching-p1` 행에 KEY SHARE 를 4초 쥔 동안, `lock_timeout` 700ms 인 앱 로그인의 p1 획득은 `transactionid` 잠금을 기다리다 702.6ms 에 55P03 으로 끝났다. 그동안 같은 로그인의 p0 갱신 다섯 번은 각각 0.9~2.9ms 에 참을 돌려줬고, 다른 세션의 p1 새 KEY SHARE 는 1.2ms 에 잡혔다. 55P03 뒤 획득 이력은 1 행 그대로이고 p1 행은 holder 빈 값·epoch 0 그대로였으며 그 로그인이 쥔 잠금은 0 개였다.
- G3 KEY SHARE 가 풀린 뒤 `lock_timeout` 없는 획득은 epoch 2 를 받았다. 획득 이력은 epoch 1(p0), 2(p1) 두 행이다.
- G4 V53 열은 `text`, 널 허용, 기본값 없음이고 CHECK 와 주석이 붙었다. 앱 로그인이 `resource`를 넣은 행과 넣지 않은 행(NULL)을 모두 적재했고, 목록 밖 이름 `matching-p9`는 23514(`chk_failover_runs_resource`)로 거부됐다. 조회 로그인(`harness_role`)은 두 행과 NULL 이 아닌 값 하나를 읽었다.
- G5 마켓 1·2 를 만들면 대응 트리거가 1 을 p0, 2 를 p1 에 넣고, P3 도출은 각각 `matching-p0`·`matching-p1`, P2 도출은 둘 다 `matching-engine`이다. 하네스 T5 술어에 파티션 마켓 한정을 붙인 문장이 실행됐다(체결이 없어 NULL).

이 확인은 PostgreSQL 과 nginx 의 동작만 본다. 앱 코드는 아직 없으므로 S4 설계 6절의 시험과 7절의 폐기용 스택 실험은 실행하지 않았다(NOT RUN).
