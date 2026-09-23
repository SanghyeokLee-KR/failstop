# S1 관문 장애 주행 E2·E4·E5·E6·E7 (2026-09-24 04:41~04:52 KST)

ADR 0034 [단계 계획](../../../docs/reference/architecture/마켓분할_리더_단계계획.md) S1 관문의 카오스 주행이다. 작업 브랜치 `claude/per-market-leader`의 `b04acd5`(S1 잔여와 S2 코드가 함께 든 커밋)로 구운 이미지를 prod 타임아웃 프로필로 띄우고, 폐기용 스택에서 실험마다 스택을 새로 세워 한 번씩 돌렸다. 이 브랜치에는 S1만 든 이미지가 따로 없으므로 이 주행은 S2 관문의 "카오스 E4·E7 재실행"도 겸한다.

## 조건

- 이미지: `exchange-chaos-app:pml`(`sha256:09aacc67818b…`), 라벨 revision `b04acd5f05ae99ff7f12e910c7f115e84bda659e`. bootJar sha256 `44acc862a568af51…`. 빌드 기록은 [S2 관문 폴더의 images.json](../pml-s2-gate-20260924-0440/images.json)이다.
- compose 프로젝트 `exchange-pml`(볼륨 `exchange-pml_chaos-pgdata`, 망 `exchange-pml_chaos-net`). 보존 스택 `exchange-chaos`는 주행 전에 컨테이너만 내렸다(`down --remove-orphans`, `-v` 없음, [exchange-chaos-down.log](exchange-chaos-down.log)). 보존 볼륨 `exchange-chaos_chaos-pgdata`와 백업 볼륨 `failstop-keep-chaos-pgdata-20260924`는 주행 전후 모두 남아 있고 생성 시각이 그대로다([volumes-inspect-before.txt](volumes-inspect-before.txt), 사후 확인은 S2 폴더 README).
- 프로필 `SPRING_PROFILES_ACTIVE=dev,chaos-monkey,prod-timeouts`(E5 주행 중 `docker inspect`로 확인). 리스 TTL 6초, 틱 2초, 리스 보관소 postgres, 앱 로그인은 스키마 소유자 `exchange`다(카오스 스택 기본).
- 모드는 P1이다. V48이 적용됐고 `leader_protocol`은 1, 쓰기 경로는 `matching-engine` 한 행으로 판정한다.
- 하네스는 `b04acd5` 위에 폐기용 스택 지원 변경 여섯 파일을 얹은 상태로 돌았다(판정 파일의 `uncommittedPaths` 6). 바꾼 것은 compose 프로젝트 이름(`CHAOS_PROJECT`)과 망 이름 도출, 셸의 이미지 태그 우선, 증거 폴더 지정(`CHAOS_EVIDENCE_DIR`), 파이썬 러너의 `-p`와 k6 망 이름, 추가 오버레이(`CHAOS_EXTRA_COMPOSE`)다. 판정 술어와 E 스크립트의 쿼리는 바꾸지 않았다. 같은 변경을 이 증거와 함께 커밋한다.
- E 스크립트는 고치지 않고 돌았다. 다섯 스크립트의 리스 조회는 모두 `resource='matching-engine'` 조건이 있어 V48 뒤 P1에서 그대로 맞는다. 파티션 인지 판정은 S4·S7 과제다(S2 설계 7절).

## 명령

셸 환경변수 `CHAOS_PROJECT=exchange-pml`, `COMPOSE_PROJECT_NAME=exchange-pml`, `CHAOS_APP_IMAGE=exchange-chaos-app:pml`, `CHAOS_SPRING_PROFILES=dev,chaos-monkey,prod-timeouts`, `CHAOS_EVIDENCE_DIR=<이 폴더>\chaos-runs`를 둔 뒤 compose 해석 이름이 `exchange-pml`인지 확인하고 다음을 실행했다.

```
pwsh -NoProfile -File harness/scripts/adr0022-stage5-chaos.ps1 -Image exchange-chaos-app:pml -Experiments E2,E4,E5,E6,E7 -EvidenceDir harness/evidence/pml-s1-gate-20260924-0440 -LocalLogDir %TEMP%\pml-s1-logs
```

이 절차는 실험마다 `-p exchange-pml` 스택을 `down -v` 한 뒤 `up --wait` 하고, 실험 스크립트를 돌린 다음 DB 쪽 증거를 뜨고 다시 `down -v` 한다. 시간 순서와 종료코드는 [commands.log](commands.log), 실험별 판정과 기준 다섯 가지는 [summary.json](summary.json)에 있다. 컨테이너 로그는 저장소 밖에 두고 해시만 각 실험의 `container_logs.json`에 남겼다.

## 판정

**다섯 실험 모두 PASS 다. S1 관문의 카오스 조건(RTO < 30초, 구 epoch 쓰기 증분 0, C01·C02·C05·C13A·C14·C22 0)을 모두 채웠다.**

| 실험 | RTO(초) | downtime(초) | epoch | rpo_records | 구 epoch 쓰기 증분 | C01·C02·C05·C13A·C14·C22 | C29 | C22 두 팔 | created_at 역행 | 연결 |
|---|---:|---:|---|---:|---|---|---:|---|---:|---|
| E2 DB 연결 절단 | 10.362 | 12.366 | 1→2 | 0 | 측정 안 함(아래) | 모두 0 | 0 | 0, 0 | 0 | 고갈 없음 |
| E4 리더 kill | 5.186 | 6.888 | 1→2 | 0 | 측정 안 함(아래) | 모두 0 | 0 | 0, 0 | 0 | 고갈 없음 |
| E5 네트워크 분리 | 5.794 | 7.982 | 1→2 | 0 | 0 | 모두 0 | 0 | 0, 0 | 0 | 고갈 없음 |
| E6 부하 급증 중 kill | 7.882 | 8.241 | 1→2 | 0 | 측정 안 함(아래) | 모두 0 | 0 | 0, 0 | 0 | 고갈 없음 |
| E7 리더 pause | 10.155 | 7.027 | 1→2 | 0 | 0 | 모두 0 | 0 | 0, 0 | 0 | 고갈 없음 |

- 불변식 값은 각 실험의 사후 권위 run(post 창)이다. 게이팅 면제 셋(C13A·C16·C19)도 모두 0이었고 post 창 위반 체크는 다섯 실험 모두 없다. 판정 파일은 [chaos-runs/](chaos-runs/)와 각 실험 폴더의 `verdict.json`이다.
- 구 epoch 쓰기 증분은 E5·E7 스크립트만 잰다(`Get-EpochWriteCounts`의 주입 직후 대비 관찰 뒤 차, 두 실험 모두 `staleWrites` 0). E4·E6는 구 리더를 `docker kill`하므로 프로세스가 쓰지 못하고, E2는 구 리더와 DB 사이를 끊는다. 세 실험은 증분을 따로 세지 않으며, 구 epoch 행이 새 epoch 뒤에 들어오면 잡히는 C22(fencing 단조)의 두 팔과 권위 run C22가 모두 0이라는 것으로 대신한다. 게이트 문구의 "구 epoch 쓰기 증분 0"을 다섯 실험에서 같은 측정으로 확인한 것은 아니다.
- 목표 계층 도달은 E4(kill·die), E5(disconnect·connect), E6(kill·die), E7(pause·unpause)이 도커 사건으로 확인됐다. E2는 Toxiproxy 주입이라 도커 사건이 없고 승격(epoch 1→2)으로 간접 확인했다.
- 다섯 실험 모두 시계 스큐 가드를 통과했다.

## 09-14 결과와 비교

[docs/measurements/20260914.md](../../../docs/measurements/20260914.md) 5.7.18(ADR 0022 4단계 이미지 `4bccb2a`, dev 프로필)과 같은 절차, 같은 표로 놓았다. 이미지와 프로필과 날짜가 달라 RTO 차이를 코드 효과로 해석하지 않는다. 판정 기준을 넘지 않았다는 비교다.

| 실험 | 09-14 RTO(초) | 09-24 RTO(초) | 09-14 rpo | 09-24 rpo |
|---|---:|---:|---:|---:|
| E2 | 10.507 | 10.362 | 0 | 0 |
| E4 | 5.866 | 5.186 | 0 | 0 |
| E5 | 10.079 | 5.794 | 1 | 0 |
| E6 | 5.277 | 7.882 | 0 | 0 |
| E7 | 10.024 | 10.155 | 0 | 0 |

## 한계

- 실험별 1회다. 반복하지 않았고 RTO 분포는 없다.
- 로컬 Docker Desktop 단일 호스트다. AWS의 호스트·가용영역 장애를 대신하지 않는다.
- 앱은 스키마 소유자 로그인으로 돌았다(카오스 스택 기본). 앱 역할 로그인 주행은 S2 관문 폴더에서 따로 했다.
- prod 타임아웃 프로필은 기본 풀의 `lock_timeout` 2초·`statement_timeout` 3초만 바꾼다. `accept-lock-wait`·`accept-lock-timeout`은 기본 0(끔) 그대로다.
- E1·E3·E8·E9는 S1 관문 범위가 아니라 돌리지 않았다.
