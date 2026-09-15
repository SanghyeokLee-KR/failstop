# ADR 0022 9단계 이벤트 대역 확대 모드 장애 주행 E2·E4·E5·E6·E7 (2026-09-15)

## 결론

- **다섯 실험 모두 실험 판정 PASS 이고 9단계 분리 판정도 모두 PASS 다.** 규약은 `docs/contracts/하네스_검증설계.md` §2-4 끝의 ADR 0022 9단계 이벤트 대역 확대 모드 장애 주행 분리 판정이다.
  - 확대 성립: 다섯 모두 `band_span` 10^12, `max_band_index` 63 이고, 이벤트 행이 있는 마켓 셋 모두에 대역 행(`event_seq >= band_span`)이 있다.
  - 섞임 행과 파생 불일치 행이 0 이다.
  - 사후 권위 run 의 C06, C21 이 0 이다. C05, C13A, C22, C28 도 0 이다.
- 명령 축은 래치 모드(트래픽 전 래치)로 돌았고, 6단계 대역 모드 분리 판정(C28)도 다섯 모두 PASS 다.
- RTO 는 5.979~10.134초로 목표 30초 안이다. `rpo_records` 는 다섯 모두 0 이다. 사후 창 C22 두 팔과 마켓 안 `created_at` 역행도 0 이고 연결 고갈은 없었다.
- 실험별 판정 파일은 `harness/evidence/chaos-runs/` 의 `E2-20260915-141045.json`, `E4-20260915-141258.json`, `E5-20260915-141437.json`, `E6-20260915-141725.json`, `E7-20260915-141937.json` 이다.

## 조건

| 항목 | 값 |
|---|---|
| 코드 | `adr0022-stage9` `3b61c24`(7·8단계 머리 `f533a74` 위) |
| 이미지 | `exchange-chaos-app:adr0022-stage9-3b61c24` |
| 주행 창(KST) | 14:10:11 ~ 14:21:22 |
| 명령 축 모드 | 래치 모드(`-LatchBeforeTraffic`) |

- 이미지는 `3b61c24` 워크트리에서 `:app:bootJar` 를 빌드해 `harness/Dockerfile.app` 으로 만들었다. 주행은 `harness/scripts/adr0022-stage5-chaos.ps1 -Image exchange-chaos-app:adr0022-stage9-3b61c24 -Experiments E2,E4,E5,E6,E7 -LatchBeforeTraffic` 이고 실험마다 새 스택이다.
- 식별 원천은 실험 폴더의 `band_state.json`(`harness/scale/band_cutover.py state`) `이벤트 대역` 절이다. 불변식은 같은 폴더 `invariant_checks.json` 의 사후 창 행이다.
- 첫 시도는 주행을 감싼 스크립트가 빌드 컨텍스트에 `app.jar` 를 복사하지 않아 이미지 빌드에서 멈췄다. 실험은 시작하지 않았다. 고친 뒤 다시 돈 주행이 이 증거이고, 첫 시도의 준비 로그는 덮어썼다.

## 실험별 값

| 실험 | RTO(초) | rpo_records | 확대 성립 | 섞임 행 | 파생 불일치 행 | C06 | C21 | C28 | 분리 판정(C28) |
|---|---:|---:|---|---:|---:|---:|---:|---:|---|
| E2 | 7.913 | 0 | 성립 | 0 | 0 | 0 | 0 | 0 | PASS |
| E4 | 7.925 | 0 | 성립 | 0 | 0 | 0 | 0 | 0 | PASS |
| E5 | 6.021 | 0 | 성립 | 0 | 0 | 0 | 0 | 0 | PASS |
| E6 | 5.979 | 0 | 성립 | 0 | 0 | 0 | 0 | 0 | PASS |
| E7 | 10.134 | 0 | 성립 | 0 | 0 | 0 | 0 | 0 | PASS |

## 한계

- 로컬 카오스 스택에서 실험마다 한 번이다. 클라우드 스택은 NOT RUN 이다.
- 새 스택 DB 에는 V46 까지 한 번에 적용돼 10^7 대역 행이 없다. 10^7 행에서 10^12 행으로 넘는 경계와 V46 적용 전 확인은 도커에서 재지 않았다. 통합 시험 `EventSeqBandWidenMigrationIntegrationTest` 와 `EventBandWidenBoundaryIntegrationTest` 가 본다.
- 명령 축은 래치 모드로만 돌았다. `adr0022-stage5-chaos.ps1` 이 전역 모드에서는 `band_state` 를 뜨지 않아 식별 원천이 남지 않기 때문이다.
- 이벤트 행이 있는 마켓은 셋이다.
- 확대는 첫 대역 행이 쓰인 뒤 되돌릴 수 없다(ADR 0022 9단계). 이 주행은 폐기용 도커 DB 에서만 했고, 클라우드 DB 적용은 배포 때 판단한다.
- 컨테이너 로그 원본은 저장소 밖 임시 폴더(`C:/Users/kopo26/AppData/Local/Temp/adr0022-stage9-chaos-20260915`)에 두고 해시만 `container_logs.json` 에 남겼다.
