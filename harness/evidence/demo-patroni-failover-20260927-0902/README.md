# 시연 스택 Patroni 주 DB 강제 종료 1회차(2026-09-27 09:02 UTC, 무효·사람 개입)

시연 스택(compose 프로젝트 `exchange-chaos`)을 Patroni 엄격 동기로 옮긴 직후 `harness/scripts/demo-db-failover.ps1 -Action run`(당시 판, 옛 주 DB 복귀 T0+30초, k6 켬)으로 돌린 회차다. 판정 파일의 결과는 `INCONCLUSIVE`지만 이 회차는 자동 복구 측정으로 쓰지 않는다. 옛 주 DB가 예비로 돌아오지 못해 사람이 재초기화했다.

## 일어난 일

| 시각(UTC) | T0 기준 | 사건 |
|---|---|---|
| 09:03:24.449 | 0 | 주 DB `chaos-db-1`(db-1) `docker kill` |
| 09:03:45 무렵 | +20.9초 | db-2 승격(관측기 /cluster 표본). 조회는 +25.3초에 돌아옴 |
| 09:03:55 | +30.5초 | 스크립트가 `chaos-db-1` 을 `docker start` |
| 09:03:56~58 | | Patroni 가 단일 사용자 모드 크래시 복구 뒤 `pg_rewind` 실행, 종료코드 0(분기 1/C04E1808, 마지막 공통 체크포인트 1/BE5A1CA0) |
| 09:03:58~ | | 되감은 노드가 REDO 1/BBC14830(타임라인 1)부터 다시 읽으려 했으나 세그먼트 `0000000100000001000000BB` 가 자기 pg_wal 과 새 주 DB 모두에 없어 `requested WAL segment ... has already been removed` 를 되풀이하며 'starting' 에 머묾. 엄격 동기라 동기 예비가 없어 쓰기(거래)가 멈춤 |
| 09:08:55 무렵 | | 스크립트의 재개 대기(300초)가 끝남. 재개 뒤 창·k6 정지·ACK 적재·봇 정지·드레인·사후 불변식 run 을 이어서 함 |
| 09:13:17 | +593초 | **사람 개입**: 부모 세션의 지시로 `patronictl reinit exchange-chaos-demo db-1 --force` 실행(새 주 DB db-2 에서 `--no-slot` 베이스백업) |
| 09:13:27 무렵 | +603초 | db-1 이 `sync_standby` 로 붙고 거래 재개(관측기 T_resume +606.8초) |

원인과 대책은 `harness/patroni/README.md` 의 '시연 스택의 Patroni 판'과 `harness/scripts/demo-rejoin.ps1` 머리 주석에 있다. Patroni 의 `remove_data_directory_on_rewind_failure` 는 `pg_rewind` 자체가 실패할 때만 걸려 이 경우를 스스로 풀지 못한다. 이 회차 뒤 도구가 그 로그를 보거나 60초 안에 붙지 못하면 `patronictl reinit` 으로 넘어가게 고쳤다.

## 이 회차에서 확인한 것(자동 복구가 아닌 축)

- 유실 0. DB 수준 ACK 기록기의 확인된 쓰기 10,480건이 최종 주 DB에 모두 있다(빠진 번호 0). 주입 직전 60초에 보인 주문 877건·체결 469건이 모두 있다. k6 접수 응답 419건은 사후 불변식 C16 위반 0이다.
- 쓰기 중단 동안 주문 POST 표본은 423(거래 일시 중단) 582, 502 18, 503 1, 429 1 이었다.
- 앱 리스 epoch 20→21, 타임라인 1→2.

## 부작용

k6(`harness/load/order_flow.js`)는 KRW-BTC 를 고정 가격(매수 49,900,000·매도 50,100,000)으로 내므로 이 회차와 2회차에서 봇 호가와 체결해 BTC 시세를 약 8,440만에서 7,480만으로 끌어내렸고 미체결 534건을 남겼다. 09:34 UTC 에 `harness/load/cleanup.js` 로 모두 취소했다(남은 미체결 0). 그 뒤 도구의 k6 는 기본으로 끄고(`-WithK6`), 켜면 회차 끝에 취소하게 바꿨다.

## 파일

- `DBFAIL-20260927-090240.result.json` 도구 결과(당시 판의 `db_write_gap_s` 0.003초는 T0 직후 확인을 잰 잘못된 값이다. 고친 계산으로 다시 재면 최장 빈틈 599.9초다)
- `DBFAIL-20260927-090240.verdict.json` `patroni_axes.py --scenario E-P1` 판정
- `*.timeline.json`, `*.facts.json`, `*.observe.jsonl`, `*.ackrec-verify.json`, `*.visible-before.json`, `*.hashes.json`
- 원자료(기록기 원본, k6 로그, 부하 계정 키)는 저장소 밖 `%TEMP%\exchange-demo-dbfail\DBFAIL-20260927-090240\`
