# 접수 동시성 기준 칸 B0 (2026-09-13)

ADR 0022 측정 계획의 B0 칸이다. 그 계획은 저장소 밖 작업 계획이고 저장소 정본이 아니다. WP3 계측 직후, 접수 락은 전역 `submitLock` 이고 채번은 전역
`command_seq` 행이다. B4, B6g, B6b 는 이 칸과 같은 날 같은 절차의 비로만 판정한다. 이 문서의 절대값을 다른
날짜나 다른 조건의 값과 나란히 놓지 않는다(stage0c-20260912 6절).

## 조건

| 항목 | 값 |
|---|---|
| 커밋 | `ceecaef` (분리 작업 트리 `C:\dev\exchange-b0`, 추적 파일 변경 없음) |
| 앱 이미지 | `up.ps1` 이 이 트리 `server` 에서 bootJar 를 굽고 만든 `sha256:e740b25a...`. `exchange-chaos-app:b0-ceecaef` 로 다시 태그하고 모든 칸을 `CHAOS_APP_IMAGE` 로 그 태그에 고정 |
| jar | sha256 `e1b4d099...5033c6c`. 빌드 산출물, `harness/app.jar`, 칸마다 chaos-api-1·2 컨테이너 안 `/app/app.jar` 가 모두 같음 |
| 호스트 | Windows 11 Home 10.0.26200, 논리 CPU 16. Docker 서버 29.5.2(OS 판과 함께 주행 뒤 17:25 읽기 전용 조회, `docker-info.txt`), WSL2 커널 6.6.114.1, VM NCPU 16, 메모리 16,299,147,264바이트 |
| 스택(부하 칸) | 왕복 240, 왕복 600, 사다리. 카오스 obs 프로파일, `docker-compose.scale.yml` 겹침. 풀 32, 수용 정원 유도값(0), PG 4.0코어 4g. 칸마다 `down -v` 후 기동 |
| 스택(stage0) | `docker-compose.chaos.yml` 만으로 chaos-pg 하나. scale 겹침을 쓰지 않았다. 기준 컴포즈의 chaos-pg 에는 CPU·메모리 제한이 없다(`deploy` 는 api-1, api-2, bots 셋뿐). PG 4.0코어 4g 는 이 칸에 해당하지 않는다 |
| PG 설정 | `synchronous_commit=on`, `wal_sync_method=fdatasync`, `commit_delay=0`, `commit_siblings=5`, `shared_buffers=128MB`, `max_wal_size=1GB` |
| 마켓 | 12개(k6 `MARKETS_12`). 부하 계정 4개, 기준자산 10^6, KRW 10^9 |
| 봇 기저 | 20 OPS(`sim_commands` 큐). 칸마다 봇 체결 안정 확인 뒤 주입 |
| 부하기 | `grafana/k6:1.4.0`, `calibrate.js`, 카오스 망 안에서 chaos-lb 로 개방 루프 |
| 리더 | 칸마다 epoch 1. api-2 는 접수 계측값 0, 라이터 계측 없음. 사다리 칸 api-2 CPU 0.8~4.4퍼센트 |

fsync 상태(`clean_suite.fsync_state`, `pg_test_fsync -s 5`, 8kB 한 번 쓰기 fdatasync).

| 시점 | 회/s | 건당 |
|---|---:|---:|
| chaos-pg 단독 16:06 | 2,190.117 | 0.457ms |
| B0-240 주입 전 | 2,142.820 | 0.467ms |
| B0-600 주입 전 | 2,130.549 | 0.469ms |
| 사다리 주입 전 | 2,122.068 | 0.471ms |

공유 호스트 부하(`host_samples_*.csv`). 스택 밖 게임 클라이언트가 전 구간 돌았다. 사람 개입으로 멈추지 않았다.

- `host_sampler.ps1` 은 표본마다 CPU 상위 8개 프로세스만 적는다. 표에 없는 표본은 그 표본 8위 아래라는 뜻이지 0 이 아니다.
- 그래서 평균은 그 프로세스가 등장한 표본만의 평균이다. 등장 표본 수를 함께 적는다. 값은 논리 CPU 16개 대비 퍼센트다.
- 표본 창은 `run_probe.sh` 시작부터 표본기 정지까지다. 초기화와 봇 안정 대기가 들어 있고 부하 구간만이 아니다.
- `summary_*.json` 의 상위 8 목록은 등장 표본 평균 순위다. 거기 없는 프로세스도 표본에는 있을 수 있다.

| 프로세스 | B0-240 (표본 22) | B0-600 (표본 23) | 사다리 (표본 53) |
|---|---|---|---|
| vmmemwsl (Docker VM) | 21개, 23.0 | 23개, 22.9 | 53개, 29.1 |
| league of legends | 18개, 10.6 | 23개, 10.7 | 48개, 10.9 |
| leagueclient | 22개, 4.1 | 23개, 3.8 | 53개, 3.9 |
| vgc | 1개, 1.8 (16:09:41, 부하 전) | 10개, 6.8 | 4개, 4.95 |
| oracle | 없음 | 없음 | 1개, 5.4 (16:36:24, 마지막 구간 끝 16:36:07 뒤) |

## 계측 노출 확인

`up.ps1` 직후, 부하 전 16:02 에 두 노드 `/actuator/prometheus` 를 긁었다(`prom-18080.txt`, `prom-28080.txt`).

- api-1(18080): `exchange_writer_catchup_calls_total{market}`, `exchange_writer_catchup_nanos_total`,
  `exchange_inflight_lock_hold_nanos_total`, `exchange_inflight_lock_wait_nanos_total`,
  `exchange_submit_lock_hold_nanos_total`, `exchange_submit_lock_wait_nanos_total`, `exchange_submit_count_total`,
  `exchange_journal_commit_nanos_total`, `exchange_writer_apply_count_total`, `exchange_writer_sink_nanos_total`,
  `hikaricp_connections_pending{pool="exchange-append"}` 존재. 봇 부하로 0 이 아닌 값(submit 681 등).
- 마켓별 `catchup_calls`, `apply_count`, `catchup_nanos` 시계열은 market 1, 2, 4 세 개뿐이다. 그 시점까지 봇 명령이
  닿은 마켓만 시계열이 생긴 것으로 보인다(추정). 부하 뒤 긁기는 없다.
- api-2(28080): submit, inflight, journal commit 계측은 존재하고 전부 0. 라이터와 append 풀 계측은 없음.

## 명령

작업 디렉터리 `C:\dev\exchange-b0`. `<d>` 는 이 디렉터리.

1. `pwsh -NoProfile -File harness/scripts/up.ps1` (16:00:15~16:00:53, rc 0, `up.log`)
2. `docker tag e740b25ac7db exchange-chaos-app:b0-ceecaef`, `pwsh -NoProfile -File harness/scripts/down.ps1`,
   `docker compose -f harness/docker-compose.chaos.yml --env-file harness/.env.chaos up -d --wait chaos-pg`
3. `python harness/scale/stage0_probe.py --out <d>/stage0.json --levels 1,2,4,8,12,24` (chaos-pg 단독, rc 0, `stage0.log`)
4. `clean_suite.fsync_state()` 1회 (chaos-pg 단독, `fsync_pg_alone.log`)
5. `bash <d>/run_probe.sh roundtrip 240`. 안에서
   `python harness/scale/roundtrip_probe.py --label B0 --rate 240 --out <d>/B0-240.json` (`run_B0-240.log`)
6. `bash <d>/run_probe.sh roundtrip 600` (같은 형식, `--rate 600`, `run_B0-600.log`)
7. `bash <d>/run_probe.sh ceiling`. 안에서 `python <d>/prep_stack.py` 뒤
   `python <d>/k6_ceiling_logged.py --rungs 120,240,360,480,600 --duration 90 --out <d>/k6_ceiling.json` (`run_B0-ceiling.log`)
8. 사다리 칸 스택이 떠 있는 채로 누계 조회(`down.log` 첫 줄들). 실행 세션 명령 기록에서 찾은 원문이고 저장소 스크립트에는 없다.
   `docker exec chaos-pg psql -U exchange -d exchange -t -A -F '|' -c "select xact_commit, deadlocks, stats_reset from pg_stat_database where datname='exchange'"`.
   출력 `1763348|0|` 의 셋째 열은 `stats_reset` 이다. 새 볼륨에서 통계를 리셋한 적이 없어 NULL 이고 `-A` 출력에서 빈 칸이 된다.
9. `pwsh -NoProfile -File harness/scripts/down.ps1` (`down.log`, 잔여 컨테이너와 볼륨 없음)

`run_probe.sh` 가 주행마다 함께 돈다. `lock_sampler.py`(PG 1초, 프로메테우스 15초), `host_sampler.ps1`,
주행 뒤 `per_market.py`, 이미지와 jar 해시 대조. 요약은 `summarize_samples.py`, `summarize_ceiling.py`.

저장소 밖 작업 계획의 명령과 다른 점.

- `k6_ceiling.py` 는 인자가 그 작업 계획과 같다. 다만 `main` 이 봇 기저를 관리 평면 mTLS 로 넣고 그 인증서 경로
  (`load_ceiling.PKI`)가 이 호스트에 없어 openssl 에서 죽는다. 첫 시도(16:20:33)는 사다리 구간 하나도 못 돌았고
  12마켓 접수 0/12 로 양성 축이 걸렀다. 파일은 `*-attempt1-invalid.*` 로 남기고 판정에 쓰지 않는다.
- 그래서 `k6_ceiling_logged.py` 가 `admin_call` 을 생략으로 바꾸고 k6 출력을 파일로 받는다. 봇 기저는
  `prep_stack.py` 가 `sim_commands` 로 20 OPS 를 넣는다. 측정 식 `measure` 와 사다리 절차는 그대로다.
- `k6_ceiling.py` 는 초기화와 키 발급을 하지 않는다. `prep_stack.py` 가 `roundtrip_probe` 준비 절차를 같은
  ENV 로 한다(16:23:17 시작).
- 카운터 행 대기 표본은 15초가 아니라 1초 간격이다. 15초면 130초 주행에 표본이 8개다.

## 결과: 저장소 동시 커밋 (stage0 (a), (e))

chaos-pg 단독, 새 볼륨. 앱을 안 띄워 마이그레이션이 없고 저널 행이 0 이다. (a)는 `commit_probe`, (e)는
`fan_probe` 전용 표를 쓰므로 저널 행 수와 무관하다.

| 동시 | 커밋/s | 평균 지연 |
|---:|---:|---:|
| 1 | 1,756.1 | 0.569ms |
| 2 | 2,046.6 | 0.977ms |
| 4 | 3,705.4 | 1.080ms |
| 8 | 7,472.5 | 1.071ms |
| 12 | 11,011.0 | 1.090ms |
| 24 | 20,248.5 | 1.185ms |

12 대 1 배수 6.27. 잎 3, 15, 67 의 멱등 조회 중앙값 0.203ms, 0.572ms, 1.844ms(최대 0.532, 0.729, 3.396).

- CPU·메모리 제한 없는 chaos-pg 의 값이다(조건 표 stage0 행). 부하 칸 PG 는 4.0코어 4g 로 묶였고 DB CPU 가
  143.7~335.3퍼센트였다. 이 커밋/s 를 부하 칸 저장소 여유로 읽으면 과대로 읽힌다. 4.0코어 제한 판 stage0 는
  NOT RUN 이라 차이 크기는 미확인이다.

## 결과: 왕복 프로브 (roundtrip_probe)

값은 `roundtrip_probe.sample()` 1회 호출값이다(서버 계측, 1분 rate). 호출 시각은 기록되지 않았다. k6 Popen 뒤 65초이고
Popen 은 k6 첫 ACK(16:11:21, 16:17:51) 이전이다. 아래 시계열에서 보듯 두 칸 모두 그 시점에 정상 상태가 아니었다.
이 표의 값은 과도 상태 값이다.

| 지표 | 식 | B0-240 | B0-600 |
|---|---|---:|---:|
| 접수/s | `sum(rate(http_server_requests_seconds_count{uri="/api/v1/orders",method="POST",status="201"}[1m]))` | 184.97 | 165.00 |
| 수용거절/s | `sum(rate(exchange_admission_rejected_total{scope="order"}[1m]))` | 73.86 | 424.25 |
| 체결/s | `sum(rate(exchange_trades_total[1m]))` | 13.62 | 6.21 |
| 체결확정 지연 p95 / p99 | `max(exchange:order_latency:p95_1m)`, p99 같은 형식 | 321.4 / 352.9ms | 421.3 / 442.2ms |
| 접수 락 보유 점유 | `sum(rate(exchange_submit_lock_hold_nanos_total[1m]))/1e9` | 0.983 | 0.906 |
| 접수 락 대기 점유 | 같은 식, `wait` | 7.275 | 6.891 |
| 락 보유 1회 | 보유 점유 / `sum(rate(exchange_submit_count_total[1m]))` | 5.294ms | 5.488ms |
| 커밋 동시성 | `sum(rate(exchange_journal_commit_nanos_total[1m]))/1e9` | 0.245 | 0.231 |
| 커밋 1회 | 커밋 동시성 / `sum(rate(exchange_journal_intx_count_total[1m]))` | 1.318ms | 1.402ms |
| 머리말 1회 | `sum(rate(exchange_journal_prologue_nanos_total[1m]))/1e9` / 같은 inTx 수 | 3.373ms | 3.433ms |
| catch-up 비 | `sum(rate(exchange_writer_catchup_calls_total[1m]))` / `sum(rate(exchange_writer_apply_count_total[1m]))` | 0.946 | 0.934 |
| InflightReserves 락 보유 점유 | `sum(rate(exchange_inflight_lock_hold_nanos_total[1m]))/1e9` | 0.099 | 0.095 |
| append 풀 대기 | `sum(hikaricp_connections_pending{pool="exchange-append"})` | 0 | 0 |
| 교착 증가 | `pg_stat_database` 의 `deadlocks` 주행 전후 차(주행 뒤 11초 대기) | 0 | 0 |
| 정산 점유 / 1회 | `sum(rate(exchange_writer_sink_nanos_total[1m]))/1e9`, / 적용 수 | 2.057 / 11.082ms | 1.855 / 11.235ms |
| 명령당 트랜잭션 | `sum(rate(pg_stat_database_xact_commit{datname="exchange"}[1m]))` / 적용 수 | 14.75 | 14.35 |
| DB CPU / api1 CPU | `max(container_cpu_percent{name="chaos-pg"})`, `chaos-api-1` | 238.5 / 143.5퍼센트 | 241.8 / 180.4퍼센트 |

### 왕복 칸 표본 시계열

`lock_samples_B0-*.csv` 의 k6 가동 표본 전부. 식은 위 표와 같다(`lock_sampler.py` PROM). 창 충족은 표본 시각 60초
전이 k6 첫 ACK 이후이고 표본 시각이 끝 ACK 이전이라 1분 rate 창 전부가 부하 구간인 표본이다. 보유 1회는 보유 점유를
`submit_s` 로 나눈 계산값이다. k6 첫·끝 ACK 시각은 `k6_runs_summary.json`.

B0-240 (k6 ACK 16:11:21~16:13:31)

| 시각 | 창 충족 | 접수/s | 수용거절/s | 보유 점유 | 대기 점유 | 보유 1회 |
|---|---|---:|---:|---:|---:|---:|
| 16:11:30 | 아니오 | 47.52 | 5.25 | 0.1920 | 0.6497 | 3.872ms |
| 16:11:45 | 아니오 | 89.00 | 22.21 | 0.4136 | 2.2097 | 4.552ms |
| 16:12:00 | 아니오 | 137.63 | 39.62 | 0.6696 | 4.1362 | 4.817ms |
| 16:12:15 | 아니오 | 175.51 | 60.85 | 0.8964 | 6.1100 | 5.084ms |
| 16:12:30 | 예 | 179.56 | 79.42 | 0.9849 | 7.5456 | 5.467ms |
| 16:12:45 | 예 | 187.39 | 71.55 | 0.9494 | 6.6631 | 5.050ms |
| 16:13:00 | 예 | 205.37 | 52.97 | 0.8650 | 4.6471 | 4.193ms |
| 16:13:15 | 예 | 223.28 | 34.48 | 0.8095 | 2.9246 | 3.605ms |
| 16:13:30 | 예 | 249.84 | 7.50 | 0.7458 | 0.7225 | 2.969ms |

B0-600 (k6 ACK 16:17:51~16:20:01)

| 시각 | 창 충족 | 접수/s | 수용거절/s | 보유 점유 | 대기 점유 | 보유 1회 |
|---|---|---:|---:|---:|---:|---:|
| 16:17:56 | 아니오 | 29.57 | 29.79 | 0.1181 | 0.4167 | 3.761ms |
| 16:18:11 | 아니오 | 84.16 | 138.76 | 0.3766 | 2.3098 | 4.408ms |
| 16:18:26 | 아니오 | 126.07 | 244.40 | 0.6037 | 4.2484 | 4.755ms |
| 16:18:42 | 아니오 | 165.42 | 365.38 | 0.8631 | 6.4162 | 5.206ms |
| 16:18:57 | 예 | 165.00 | 424.25 | 0.9057 | 6.8908 | 5.488ms |
| 16:19:12 | 예 | 146.70 | 443.32 | 0.9067 | 7.3074 | 6.180ms |
| 16:19:27 | 예 | 134.34 | 455.67 | 0.9072 | 7.5160 | 6.750ms |
| 16:19:42 | 예 | 127.17 | 463.02 | 0.9075 | 7.6725 | 7.134ms |
| 16:19:57 | 예 | 133.04 | 486.90 | 0.9936 | 8.8359 | 7.465ms |

- 창 충족 표본만 봐도 접수/s 가 B0-240 에서 179.56 에서 249.84 로 오르고(최대와 최소 차가 평균 209.09 의 33.6퍼센트),
  B0-600 에서 165.00 에서 127.17 까지 내렸다가 133.04 다(평균 141.25 의 26.8퍼센트). 두 칸 모두 정상 상태가 아니다.
- B0-240 은 수용거절이 79.42 에서 7.50 으로 줄고 보유 1회가 5.467 에서 2.969ms 로 준다. 워밍업(JIT 컴파일, 새 볼륨의
  자동 ANALYZE) 효과로 보이나 추정이다. 원인은 재지 않았다.
- B0-600 은 반대로 보유 점유가 0.906~0.994 에 붙은 채 보유 1회가 5.488 에서 7.465ms 로 는다. 워밍업으로는 설명되지
  않고 원인은 미확인이다.
- ADR 0022 7절 4단계는 T1 을 제시 부하 600/s 왕복 프로브 산출물끼리 비교하도록 정한다(be1caa0 647행). 그 개정 커밋
  be1caa0 의 시각 2026-09-13 16:43:42 는 B0 주행(k6 첫 ACK 16:11:21, 끝 ACK 16:36:07, `down.log` 16:37:08)보다 늦다.
  그래서 이 문턱은 B4 에 대해서만 주행 전 고정이다. 측정 커밋 `ceecaef` 의 ADR 0022 에는 비교 원천, 양성 축, T1 이 없다.
  이 칸의 1회 값을 그대로 B4 와 비교하면 과도 상태끼리의 비가 된다. 조치는 둘 중 하나다.
  - 왕복 프로브에 안정 판정을 넣고 그 뒤에 표본을 뜬다. 예: 창 충족 연속 표본 3개의 접수/s 최대와 최소 차가 평균의
    5퍼센트 이내. 이 기준값은 정본에 없으므로 B4 주행 전에 ADR 0022 4단계(be1caa0 판)에 고정해야 한다. 이 칸의 두
    주행은 그 예시 기준을 채우지 못한다.
  - 사다리 구간 값을 쓴다. 사다리는 구간마다 창 충족 표본이 1~2개이고 두 표본 차가 평균의 0.12~3.29퍼센트다.
    표본 2개로 안정을 판정할 수는 없다. T1 의 비교 원천(be1caa0 647행)을 바꾸는 것도 ADR 0022 개정 사항이다.
  - 어느 쪽이든 B4 당일 같은 절차로 B0 를 다시 돈다. 이 칸의 절대값은 B4 비의 분모로 쓰지 않는다.
- ADR 0022 4단계 양성 축 "B0 접수 락 보유 점유 0.9 이상"(be1caa0 651행)도 B0 주행 뒤 커밋이라 이 칸에는 사후 적용이다.
  사후 대조로는 B0-600 창 충족 표본 다섯이 모두 0.9057 이상이라 1회 값에 기대지 않고도 0.9 이상이다.

카운터 행 대기(`pg_lock_samples_*.csv`, 1초, k6 컨테이너 가동 표본만).

| 칸 | 표본 | 머리말 활성 > 0 표본 | 머리말 활성 최대 | Lock 대기 | advisory | 카운터 행(transactionid, tuple) |
|---|---:|---:|---:|---:|---:|---:|
| B0-240 | 130 | 53 | 1 | 0 | 0 | 0 |
| B0-600 | 131 | 71 | 1 | 0 | 0 | 0 |

- 분류기가 다르다. `lock_sampler.py` 는 `pg_stat_activity` 의 `wait_event_type`, `wait_event` 와 `pg_blocking_pids` 로
  가른다. ADR 0022 4단계 양성 축은 `pg_locks`·`pg_blocking_pids` 패턴을 적는다(be1caa0 653행). 두 방식이 같은 대기를
  같게 세는지는 확인하지 않았다.
- B0 에서 카운터 행 대기 0 은 구조적이다. 머리말 동시 활성이 전 표본 최대 1 이라 행을 두고 다툴 두 번째 세션이 없다.
  그래서 이 0 은 분류기가 대기를 잡을 수 있다는 증거가 아니다(양성 대조 없음).
- B4 전에 두 세션이 `journal_counters('command_seq')` 행을 잠그고 다투게 만든 뒤 분류기가 `counter_row_wait` 1 을
  내는지 확인해야 한다. 그 전까지 B4 의 카운터 행 대기 0 은 반증으로 쓰지 못한다.

## 결과: 천장 사다리 (k6_ceiling)

열마다 원천과 식이 다르다.

| 열 | 원천 | 식 |
|---|---|---|
| 접수/s | `k6_ceiling.measure`(k6 Popen 뒤 65초 1회) | `sum(rate(http_server_requests_seconds_count{uri="/api/v1/orders",method="POST",status="201"}[1m]))` |
| 수용거절/s | 같음 | `sum(rate(exchange_admission_rejected_total[1m]))`. scope 필터 없음. 왕복 프로브(`scope="order"`)와 식이 다르고 조회 scope 몫이 섞일 수 있다. 차이 크기는 재지 않았다 |
| 체결/s | 같음 | `max(exchange:trades_per_second:rate1m)`. 기록 규칙 식은 `sum(rate(exchange_trades_total[1m]))` 로 왕복 프로브와 같고 평가 시점만 다르다 |
| 체결확정 p95 / p99 | 같음 | `max(exchange:order_latency:p95_1m)`, 규칙 식 `histogram_quantile(0.95, sum by (le) (rate(exchange_order_latency_seconds_bucket[1m])))`. p99 같은 형식 |
| DB / api1 CPU | 같음 | `max(container_cpu_percent{name="chaos-pg"})`, `chaos-api-1` |
| 락 보유 점유, 커밋 동시성, inflight 보유 점유, append 풀 대기 | `lock_samples_B0-ceiling.csv` 중 로그 생성 시각 65초 뒤부터 로그 수정 시각까지(`summarize_ceiling.py`) | 왕복 프로브 표와 같은 식 |
| 락 보유 1회 | 같음 | `hold_occ` / `submit_s` 계산값 |
| catch-up 비 | 같음 | `catchup_s` / `apply_s` |
| 정산 1회 | 같음 | `sink_occ` / `apply_s` |

`lock_samples` 열은 표본이 둘이면 둘 다 적는다. 480 구간만 표본이 하나다.

| 요청/s | 접수/s | 수용거절/s | 체결/s | 체결확정 p95 / p99 | 락 보유 점유 | 락 보유 1회 | 커밋 동시성 | catch-up 비 | inflight 보유 점유 | append 풀 대기 | 정산 1회 | DB / api1 CPU |
|---:|---:|---:|---:|---|---|---|---|---|---|---|---|---|
| 120 | 136.54 | 0.12 | 17.54 | 39.0 / 78.0ms | 0.579, 0.638 | 4.19, 4.62ms | 0.145, 0.145 | 0.955, 0.954 | 0.061, 0.061 | 0, 0 | 9.48, 9.43ms | 143.7 / 77.0 |
| 240 | 255.36 | 1.45 | 22.14 | 251.1 / 329.9ms | 0.764, 0.766 | 2.98, 2.98ms | 0.303, 0.304 | 0.983, 0.984 | 0.130, 0.130 | 0, 0 | 10.58, 10.59ms | 244.0 / 109.3 |
| 360 | 272.67 | 106.09 | 18.19 | 345.8 / 419.6ms | 0.973, 0.967 | 3.53, 3.39ms | 0.348, 0.355 | 0.959, 0.962 | 0.151, 0.153 | 0, 0 | 11.55, 11.37ms | 284.3 / 142.2 |
| 480 | 273.20 | 226.25 | 16.10 | 340.7 / 363.8ms | 0.968 | 3.60ms | 0.351 | 0.945 | 0.155 | 0 | 11.71ms | 335.3 / 149.5 |
| 600 | 272.48 | 347.14 | 4.29 | 79.7 / 100.7ms | 0.989, 0.989 | 3.69, 3.78ms | 0.373, 0.353 | 0.938, 0.935 | 0.162, 0.154 | 0, 0 | 11.38, 11.29ms | 310.7 / 159.0 |

- 접수가 360/s 부터 272~273/s 에서 평평하고 보유 점유가 0.97~0.99 다. 늘어난 요청은 수용거절로 간다.
- 카운터 행 대기: 구간마다 1초 표본 91개, 머리말 활성 > 0 표본 21~30개, 활성 최대 1, Lock 대기 0.
- 교착: 구간별 증가분은 `k6_ceiling` 이 기록하지 않는다. 칸 전체 누계가 deadlocks 0, xact_commit 1,763,348 이다
  (명령 8). 이 누계는 사다리만의 값이 아니다. 새 볼륨 기동 뒤 마이그레이션, 키 발급, 봇 기저 20 OPS 안정 대기와
  기저 측정, 구간 사이 20초, 주행 뒤 `per_market.py` 조회, 1초 잠금 표본 조회, pg-exporter 긁기가 모두 섞였다.

## k6 접수 교차 확인

`k6_runs_summary.json`. 주행별 k6 로그의 ACK 줄 수(멱등 재생이 아닌 2xx 응답 수. `ceecaef` 판 `harness/load/order_flow.js` 330행
`if (!replay)` 안의 332행에서만 ACK 를 찍는다)와
`per_market_*.json` 의 저널 NEW 합이 일곱 주행 모두 같다. ACK 줄의 `client_order_id` 도 전부 고유하다.

| 주행 | ACK 줄 | 저널 NEW 합 | 첫 ACK | 끝 ACK | warn / error 줄 |
|---|---:|---:|---|---|---|
| rtB0240 | 26,191 | 26,191 | 16:11:21 | 16:13:31 | 0 / 0 |
| rtB0600 | 19,159 | 19,159 | 16:17:51 | 16:20:01 | 0 / 0 |
| k6ceil-120 | 10,795 | 10,795 | 16:27:12 | 16:28:42 | 0 / 0 |
| k6ceil-240 | 21,476 | 21,476 | 16:29:03 | 16:30:33 | 0 / 0 |
| k6ceil-360 | 24,884 | 24,884 | 16:30:54 | 16:32:24 | 0 / 0 |
| k6ceil-480 | 24,703 | 24,703 | 16:32:45 | 16:34:15 | 0 / 0 |
| k6ceil-600 | 23,738 | 23,738 | 16:34:37 | 16:36:07 | 0 / 0 |

- 로그의 줄은 전부 info 수준 ACK 줄이다. k6 종료 요약 줄은 없다(`--quiet`, `handleSummary` 가 파일 경로 하나만 반환).

## 작업 계획 B0 기대값과 실측

B0 는 기준 칸이라 문턱 판정 대상이 아니다. 기대값 열은 저장소 밖 작업 계획의 B0 열이라 판정어를 붙이지 않고 나란히만 둔다.

| 지표 | 작업 계획 B0 기대값 | 실측 |
|---|---|---|
| 접수 락 보유 점유 | 1.0 이하 | 전 표본 최대 0.9936 |
| 커밋 동시성 | 1.0 이하 | 최대 0.3735 |
| 카운터 행 대기 | 0 근처 | 전 칸 0. 머리말 동시 활성 최대 1 이라 구조적 0 이고 양성 대조 없음(왕복 프로브 절) |
| catch-up 비 | 계산값 1 - 1/12 = 0.9167(마켓 12개) | 0.934~0.984. 계산값보다 0.017~0.067 높다. 원인 미확인 |
| 교착 | 0 | 0 |

- 두 상한의 구조 근거. `ceecaef` 판 `EngineBridge.submit` 은 `ReentrantLock` 하나(`submitLock`)를 잡은 채
  `appender.append` 를 부르고, 보유 나노초를 잠금 뒤부터 해제 전까지 센다(EngineBridge.java 116~154행). 한 노드에서
  보유 구간이 겹치지 않아 초당 보유 합은 1.0 을 넘지 못한다. api-2 는 접수 계측값이 0 이다.
- 같은 판 서버 main 소스에서 `appender.append` 호출은 그 한 곳이다(`git grep`). 커밋 나노초는 append 안에서 쌓이므로(ceecaef 판 `CommandJournalAppender.java` 173행)
  커밋 동시성은 보유 점유 이하, 곧 1.0 이하다. be1caa0 659행 T2 도 같은 근거를 적는다.
- catch-up 계산값의 전제는 저장소 밖 작업 계획에 있다. 이 문서는 그 식을 코드에서 유도하지 않았다.

- catch-up 비 원인을 좁힐 마켓별 값이 없다. 부하 전 긁기(`prom-18080.txt`)의 마켓별 시계열은 market 1, 2, 4 셋이고
  봇만 돈 누계 비가 0.568, 0.661, 0.725 다. 12마켓 부하 뒤 긁기는 없다.
- 재주행 때 주행마다 k6 종료 직후, `down` 전에 api-1 `/actuator/prometheus` 를 긁어 12마켓 `catchup_calls` 와
  `apply_count` 를 남긴다.

## 양성 축

| 칸 | 12마켓 각 접수 > 0 (저널 NEW 행, 최소 마켓) | submit > 0 | xact_commit 증가 > 0 | k6_rc == 0 | fsync 키 | 판정 성립 |
|---|---|---|---|---|---|---|
| B0-240 (`rtB0240`) | 12/12, 합 26,191, 최소 2,146 | 185.64/s | 417,273 | 0 | 있음 | 예 |
| B0-600 (`rtB0600`) | 12/12, 합 19,159, 최소 1,555 | 165.03/s | 288,107 | 0 | 있음 | 예 |
| 사다리 (`k6ceil-*`) | 구간 다섯 모두 12/12, 최소 895, 1,784, 2,046, 2,031, 1,908 | 표본 138~286/s | 구간별 NOT RUN, 칸 누계 1,763,348 | 다섯 구간 모두 0 | 있음 | 예. xact_commit 은 칸 단위로만 성립 |
| 사다리 첫 시도 | 0/12 | 없음 | 없음 | 구간 없음 | 없음 | 아니오(무효) |
| stage0 | 해당 없음(앱 없음) | 해당 없음 | 동시 여섯 칸 모두 커밋/s 값 있음 | 해당 없음 | 해당 없음 | 예 |

머리말 활성 > 0 표본 수(칸마다 21~71개)는 질의가 `append_command_prologue` 호출을 실제로 잡았다는 축이다. 대기 분류기가
대기를 1 로 세는지는 이 축으로 보이지 않는다(왕복 프로브 절 분류기 항목).

## 한계

- 같은 날 같은 ENV 에서 요청 240/s 가 두 값을 냈다. 왕복 프로브 1회 184.97/s(락 보유 1회 5.294ms, 머리말 3.373ms)와
  사다리 240 구간 255.36/s(보유 1회 2.98ms)다. 600/s 도 165.00 과 272.48 이다. 왕복 프로브 값은 과도 상태 1회
  값이다(왕복 칸 표본 시계열). 왕복 프로브는 초기화 뒤 첫 부하이고 사다리 240 은 120 구간 뒤다. 원인은 미확인이다.
  B4 는 같은 순서(왕복 240, 왕복 600, 사다리)로 돌아야 비가 성립한다.
- p95, p99 는 접수 지연이 아니다. `exchange.order.latency` 는 체결 이벤트의 엔진 시각에서 발행까지이고 체결된 건만
  센다(`MarketDataPublisher.fillTimer`). 사다리 600 구간 p95 79.7ms 는 체결이 4.29/s 로 줄어 표본 구성이 바뀐 결과로
  보이나 추정이다. 체결 건의 구성은 재지 않았다.
- 프로메테우스 표본이 15초 간격이라 사다리 구간당 65초 이후 표본이 1~2개다.
- 카운터 행 표본은 순간 상태다. 1초 간격이라 1밀리초 미만 대기는 놓칠 수 있다.
- 공유 호스트에서 게임 클라이언트(league 로 시작하는 프로세스와 vgc 합)가 16코어 대비 표본 평균 18.9, 21.4, 19.0퍼센트
  (B0-240, B0-600, 사다리, 표본 범위 11.5~27.7)를 썼다. 표본마다 상위 8 에 든 몫만 더한 하한이다. 통제하지 않았다.
- 로컬 WSL2 카오스 스택이다. 클라우드 스택 값이 아니다. 장애 주입이 없는 칸이라 프록시와 커널 구분은 해당 없다.
- 스크립트 부수 효과로 이 디렉터리 밖에 `harness/app.jar`, `harness/load/secrets/k6_keys.json`,
  `harness/scale/__pycache__/` 가 생겼다. 모두 gitignore 대상이다.
- k6 원 로그(`k6-rt-B0-*.log`, `k6-k6ceil-*.log`)는 ACK 줄마다 부하 계정 접근 키 식별자를 담아 저장소에 넣지 않는다.
  이 문서의 k6 수치는 `k6_runs_summary.json` 을 인용한다. 원 로그 sha256 과 크기가 거기 있다.
- `run_B0-ceiling-attempt1-invalid.log` 도 저장소에 넣지 않는다. 개인 사용자 경로와 다른 프로젝트의 인증서 경로를 담는다.
  첫 시도가 무효라는 근거는 같은 접두의 csv, json(12마켓 접수 0/12)에 남는다.
- `summarize_ceiling.py` 는 구간 창을 저장소 밖 k6 원 로그의 파일 생성·수정 시각으로 잡아 이 디렉터리만으로는 다시 돌릴 수
  없다. 잡힌 창은 `summary_B0-ceiling-rungs.json` 의 `창` 에 남는다. `k6_runs_summary.py` 도 원 로그 디렉터리를 인자로 받아야 돈다.

## NOT RUN

- k6 클라이언트 쪽 접수 p95, p99. 이 문서의 p95, p99 는 체결확정 지연(`exchange:order_latency`)이라 대체하지 못한다.
  - 원인: `harness/scale/k6_ceiling.py` `run_k6` 가 `CAL_OUT=/tmp/calibration.json` 을 주고 `--rm` 으로 컨테이너를 띄운다.
    `/load` 는 읽기 전용 마운트이고 쓰기 마운트가 없다. `calibrate.js` `handleSummary` 가 `accepted_p95_ms`,
    `accepted_p99_ms` 를 그 경로에만 쓰고 컨테이너 삭제와 함께 사라졌다.
  - 조치: 재주행 전에 `run_k6` 에 쓰기 가능한 호스트 마운트를 붙이고 `CAL_OUT` 을 그 아래 주행별 파일로 뺀다.
- 사다리 구간별 xact_commit, deadlocks 증가분: `k6_ceiling` 이 재지 않는다. 칸 누계만 있다.
- 4.0코어 4g 제한 chaos-pg 의 stage0: 이 칸은 제한 없는 chaos-pg 로만 돌았다.
- 헤더 게이트용 클라우드 저장소 판 stage0: 클라우드 스택이 없다.
- 카운터 행 대기 분류기 양성 대조: 두 세션 행 잠금 경합을 만들지 않았다.
- 부하 뒤 12마켓 계측 긁기: 하지 않았다.
- B4 칸: 이 작업 범위 밖이다. ADR 0022 4단계는 B0 와 B4 를 같은 날 같은 스택에서 비교하도록 정한다(be1caa0 647행).
  이미지를 같은 날 굽는다는 조건은 저장소 밖 작업 계획에 있다. 이 값과 비교하려면 2026-09-13 에 돌아야 한다.
- 장애 시험(E1, E2, E4, E5, E6, E7): 이 칸 범위 밖이다.

## 파일

| 파일 | 내용 |
|---|---|
| `stage0.json`, `stage0.log` | 동시 커밋 사다리와 잎 팬아웃 |
| `fsync_pg_alone.log` | chaos-pg 단독 fsync |
| `B0-240.json`, `B0-600.json` | 왕복 프로브 산출물 |
| `k6_ceiling.json` | 사다리 산출물(`fsync`, `base`, 구간별 `_k6_exit`) |
| `per_market_*.json` | 주행 식별자별 12마켓 저널 NEW 행과 주문 행 |
| `k6_runs_summary.json` | `k6_runs_summary.py` 가 k6 원 로그에서 뽑은 주행별 ACK 줄 수, 고유 주문 키 수, 첫·끝 시각, 수준별 줄 수, 원 로그 sha256. 접근 키 식별자 없음 |
| `lock_samples_*.csv`, `pg_lock_samples_*.csv` | 15초 프로메테우스 표본, 1초 PG 잠금 표본 |
| `host_samples_*.csv`, `host-procs-stage0.txt` | 호스트 프로세스 CPU(표본마다 상위 8) |
| `summary_*.json` | 표본 요약 |
| `run_*.log`, `up.log`, `down.log` | 실행 기록, 이미지와 jar 해시 대조 |
| `prom-18080.txt`, `prom-28080.txt` | 부하 전 계측 노출 긁기 |
| `docker-info.txt` | Docker VM 규격, 주행 뒤 조회한 Docker 서버 판과 OS 판(조회 명령 포함) |
| `run_probe.sh`, `prep_stack.py`, `k6_ceiling_logged.py`, `lock_sampler.py`, `host_sampler.ps1`, `per_market.py`, `summarize_samples.py`, `summarize_ceiling.py`, `k6_runs_summary.py` | 재현 스크립트 |
| `*-attempt1-invalid.*` | 사다리 첫 시도. 무효. 실행 로그(`run_B0-ceiling-attempt1-invalid.log`)는 저장소 밖에 둔다 |
