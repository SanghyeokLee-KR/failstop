# Patroni 실험 E-P1~E-P6 한 벌 (2026-09-25 10:14~14:30 UTC)

[Patroni 설계](../../../docs/reference/architecture/DB_자동전환_Patroni_설계.md) 8-3의 E-P1~E-P6을 compose 프로젝트 `exchange-patroni`에서 실제로 돌린 기록이다. 회차마다 스택을 새로 세우고 끝에 지웠다. 앱 이미지는 이 작업 트리(브랜치 `claude/patroni`, 기준 9a6d2474에 작업 트리 변경을 더한 상태)의 bootJar로 첫 회차에서 새로 만들어 `exchange-chaos-app:patroni`로만 태그했다. AWS에는 접속하지 않았다.

결론부터 적는다. E-P1, E-P2a, E-P2b, E-P3a, E-P3b, E-P4a, E-P4b, E-P5, E-P6(a·b)는 회차 무효 조건에 걸리지 않은 회차(E-P4a·b는 시간 축이 없는 실험)에서 모두 **PASS**였다. E-P1b는 처음 판정에서 '타임라인 불변' 축 하나로 FAIL이었다. Patroni 4.1.5는 리더 키를 쥔 채 재기동한 노드를 읽기 전용으로 띄운 뒤 스스로 승격시켜 타임라인을 하나 올린다(아래 E-P1b). 사용자가 2026-09-25에 타임라인 변경을 허용하고 기록 항목으로 두기로 확정했고, 바꾼 판정 도구로 같은 원자료를 다시 판정한 결과는 **PASS** 7/7이다. 앱 코드는 고치지 않았다.

## 조건

| 항목 | 값 |
|---|---|
| 스택 | `harness/patroni` 3-7 구성. PostgreSQL 17.11, Patroni 4.1.5, etcd v3.5.34 세 멤버, HAProxy 3.0.27, 앱 2노드(현재 P2: 한 노드가 전 마켓 리더), 봇 10 OPS(E-P5는 40 OPS), k6 접수 주행, DB 수준 ACK 기록기, 관측기, Prometheus |
| 동기 복제 | `synchronous_mode` + `synchronous_mode_strict`, `synchronous_node_count 1`, `failsafe_mode` 켬, `ttl 20`·`loop_wait 5`·`retry_timeout 5` |
| 호스트 | Windows 11, Docker Desktop(16 CPU, 16GB). 호스트 포트는 59xxx 루프백 |
| env | `harness/patroni/.env.patroni`는 `.env.patroni.example`과 같은 파일(해시 일치)이다. 실제 비밀 값은 없다 |
| 병렬 작업 | 시작 때 `chaos-*` 컨테이너는 없었다. 11:01부터 마켓별 리더 S5 스택(`chaos-*`)과 S5·S6의 Gradle 시험(Testcontainers), `pml-s6-watch-W2` k6 컨테이너가 번갈아 돌았다. 그 컨테이너·이미지·프로젝트에는 명령을 보내지 않았다 |

보존 볼륨 두 개(`exchange-chaos_chaos-pgdata` 생성 2026-09-13T11:42:01Z, `failstop-keep-chaos-pgdata-20260924` 생성 2026-09-23T15:47:36Z)는 시작과 끝의 이름·생성 시각이 같다([시작](preserved-volumes-start.txt), [끝](preserved-volumes-end.txt)). 끝에 `exchange-patroni`의 컨테이너·볼륨·망은 0이었다.

## 돌린 명령

```powershell
pwsh -NoProfile -File C:\dev\exchange-patroni\harness\scripts\ep-p1-primary-kill.ps1 -Build   # 첫 회차만 bootJar·이미지 빌드
pwsh -NoProfile -File <scratchpad>\ep-batch.ps1 -RunList "ep-p6b-reinit"
pwsh -NoProfile -File <scratchpad>\ep-batch.ps1 -RunList "ep-p2a-standby-stop,ep-p2b-standby-pause,ep-p3a-primary-isolate,ep-p3b-asymmetric-isolate,ep-p4-etcd:a,ep-p4-etcd:b,ep-p5-load-failover,ep-p1b-primary-restart"
$env:EP_WAIT_BUDGET='1800'; pwsh -NoProfile -File <scratchpad>\ep-batch.ps1 -RunList "ep-p3b-asymmetric-isolate,ep-p5-load-failover"
python harness/scale/patroni_axes.py --scenario E-P1 --dir <E-P6b 원시 폴더> --out ...EP6b-20260925-101936.as-EP1.verdict.json
python harness/evidence/patroni-20260925-1015/ep6b_axes.py <E-P6b 원시 폴더> ...EP6b-20260925-101936.ep6b.verdict.json
# 사용자 확정(2026-09-25) 뒤 E-P1b 재판정. 스택은 띄우지 않고 기록된 원자료만 다시 판정했다.
python harness/scale/patroni_axes.py --scenario E-P1b --dir <E-P1b 원시 폴더> --out ...EP1b-20260925-141742.verdict-rejudge-20260925.json
```

[ep-batch.ps1](ep-batch.ps1)은 실행기를 차례로 부르는 순서기다. 회차마다 앞에서 `chaos-*`·`pml-*`·다른 프로젝트의 k6 컨테이너, Testcontainers, 다른 작업 트리의 Gradle 시험 JVM이 있으면 2분 간격으로 기다렸다(누적 3시간 상한). 순서기의 줄 기록은 [batch-log.txt](batch-log.txt)다. 누적 대기가 3시간에 닿은 14:09 뒤의 세 회차(E-P4b, 첫 E-P5, E-P1b)는 S5 스택이 떠 있는 채로 시작했다. 판정 도구의 무효 조건 검사는 그 세 회차에서 실험 밖 부하 도구를 찾지 못했고 CPU 표본 평균은 16~34%였다. S5 스택은 떠 있었지만 부하 주행은 없었던 것으로 본다(프로세스 목록과 CPU 표본으로만 판단, 설계 8-1 (d)).

## 판정표

시각은 주입(T0) 기준 초다. 시간 축의 상한은 설계 8-3(승격 30초, 승격 뒤 조회 복구 10초, 재개 지연 5초, 체결 재개 지연 30초, E-P2 중단 탐지 5초·재개 뒤 첫 체결 10초)이다. 재개 지연이 음수인 것은 앱의 상태 API가 `/cluster`의 `sync_standby` 표시(Patroni HA 루프만큼 늦다)보다 먼저 ACTIVE로 돌아왔다는 뜻이다.

| 실험 | 회차(증거 폴더) | T0(UTC) | 판정 | 핵심 값 | 무효 조건 |
|---|---|---|---|---|---|
| E-P1 Primary `docker kill` | [EP1-20260925-101449](../patroni-EP-20260925-1014/) | 10:16:30 | **PASS** 18/18 | 승격 22.75, 승격 뒤 조회 3.92, 중단 탐지 3.13, HAProxy 내림 2.18, 재개 지연 −1.36, 체결 재개 −0.89, epoch 1→2(api-1→api-2), 옛 Primary pg_rewind 재합류 뒤 두 노드 표 건수·합 같음 | 없음(CPU 16·17·11·16·12%) |
| E-P6(b) 옛 Primary 볼륨 삭제 뒤 재초기화 | [EP6b-20260925-101936](../patroni-EP-20260925-1019/) | 10:20:52 | **PASS**(E-P1 축 18/18, 재초기화 축 4/4) | 재초기화 시작부터 새 멤버 walsender까지 2.54초, T_sync까지 10.51초. 그 사이 ACTIVE 0·acked 0, 백업 스트림 `sync` 표본 0, `pg_basebackup ... --no-slot --checkpoint=fast` 명령줄 확인 | 없음 |
| E-P2a 동기 Standby `docker stop` 90초 | [EP2a-20260925-104852](../patroni-EP-20260925-1048/) | 10:50:22 | **PASS** 18/18 | 중단 탐지 1.68, 재개 지연 −1.70, 재개 뒤 첫 체결 −0.55. 중단 구간 조회 200 비율 1.0(n=435), 쓰기 표본 87건 모두 423·429 0, epoch 불변, 강등 0, 출금 요청 불확실 키 95개 모두 같은 키로 재전송·중복 행 0 | 없음(CPU 34·30·13·12·9%) |
| E-P2b 동기 Standby `docker pause` 60초 | [EP2b-20260925-105309](../patroni-EP-20260925-1053/) | 10:54:30 | **PASS** 18/18 | 중단 탐지 3.41, 재개 지연 −1.60. `/cluster`가 동기 Standby를 잃었다고 적은 것보다 17.18초 앞서 중단을 냈다(탐지 백엔드의 SyncRep 대기). 쓰기 표본 56건 모두 423, 조회 200 비율 1.0(n=280) | 없음 |
| E-P3a Primary 두 망 격리 | [EP3a-20260925-105647](../patroni-EP-20260925-1056/) | 10:58:13 | **PASS** 19/19 | 승격 27.27, 승격 뒤 조회 1.59, 재개 지연 −1.51, 체결 재개 0.68. 두 노드가 같은 시간대에 확인한 커밋 0(옛 Primary 확인 끝 T0+0.04초, 새 Primary 확인은 멤버가 붙은 뒤부터) | 없음(마지막 CPU 표본 80%, 평균 28%) |
| E-P3b 비대칭 격리(DB 망만) | [EP3b-20260925-142059](../patroni-EP-20260925-1420/) | 14:22:16 | **PASS** 19/19 | 승격 27.68, HAProxy 내림 21.12, 승격 뒤 조회 2.95, 재개 지연 −1.39. 두 노드 확인 겹침 0(옛 Primary 확인 끝 T0+0.05초) | 없음 |
| E-P4a etcd-3(bot-1 역할) 정지 | [EP4a-20260925-133551](../patroni-EP-20260925-1335/) | 13:37:19 | **PASS** 7/7 | 상태 표본 중단 0, `EtcdMemberDown` 발화, Patroni 리더·타임라인·epoch 불변 | 실험 밖 부하(`pml-s6-watch-W2`, 다른 작업 Gradle). 이 실험에는 시간 축이 없다 |
| E-P4b etcd 두 멤버 정지 | [EP4b-20260925-140938](../patroni-EP-20260925-1409/) | 14:11:10 | **PASS** 8/8 | 상태 표본 중단 0, `patroni_failsafe_mode_is_active` 1, `EtcdQuorumLost` 발화, Primary 유지 | S5 스택이 떠 있음(부하 도구 검출 없음). 시간 축 없음 |
| E-P5 부하 중 Primary kill(봇 40 OPS) | [EP5-20260925-141322](../patroni-EP-20260925-1413/), [EP5-20260925-142512](../patroni-EP-20260925-1425/) | 14:14:50, 14:26:28 | **PASS** 20/20, **PASS** 20/20 | 승격 23.18·22.98, 같은 `client_order_id` 주문 둘 0, 출금 불확실 키 62·62개 모두 같은 키로 재전송·요청 하나에 행 둘 0, C07'·C16 0, epoch +1 | 첫 회차는 S5 스택이 떠 있음(검출 없음), 둘째는 깨끗함 |
| E-P1b Primary kill 직후 start | [EP1b-20260925-141742](../patroni-EP-20260925-1417/) | 14:19:03 | **PASS** 7/7(재판정). 처음 판정은 FAIL 7/8 | 타임라인 1→2(기록 항목). 리더는 같은 노드(db-2), 앱 리더·epoch 불변, 재개 T0+10.3초, 확정 응답 보류 12건. 처음 판정의 FAIL 축은 `timeline_unchanged` 하나였다 | S5 스택이 떠 있음(검출 없음) |

모든 회차에서 불변식 사후 run 위반 0(이름을 든 C01·C02·C05·C07'·C13A·C14'·C17·C31 포함), C16 0, 구 epoch 쓰기 증분 0, 주문 `500` 0, DB 수준 ACK 유실 0이었다. acked가 아닌 시도는 강제 종료·격리 순간의 연결 끊김 4건(unknown)과, 승격 직후 HAProxy가 새 Primary로 넘긴 뒤 승격이 끝나기 전의 `25006 read-only` 실패(E-P6b 20건, E-P5 첫 회차 4건, E-P1b 4건)다. 후자는 커밋 전에 받은 오류라 `failed`이고, failed인데 표에 있는 번호는 0이었다.

과제 문장의 판정 항목과 대응은 다음과 같다. E-P1의 '커밋된 주문·원장 대조 유실 0'은 C16(k6 ACK 대조)과 DB 수준 ACK 기록기 대조, '불변식 0'은 사후 권위 run, '구 epoch 쓰기 0'은 `stale_epoch_writes`다. E-P2의 '웹 상태 API trading_suspended=true'는 두 노드 `/api/v1/status` 표본이 1초 간격으로 참을 낸 것(T_suspend)이고, Standby 복귀 뒤 사람 조작 없이 ACTIVE로 돌아왔다(T_resume). E-P3의 분할 뇌 없음은 두 노드 확인 커밋 겹침 0과 epoch 정확히 +1, E-P5의 '같은 키 재시도 결과 일관'은 출금 요청기의 같은 키 재전송(불확실 키 전부)과 행 중복 0, 주문의 같은 `client_order_id` 중복 0이다. E-P6은 (a) pg_rewind 되감기(E-P1·E-P3a·E-P3b·E-P5 모두 `rewind`)와 (b) 재초기화 둘 다 사람 조작 없이 동기 Standby로 돌아왔다.

## 사고와 조치

**E-P2a 첫 회차(10:24, [EP2a-20260925-102409](../patroni-EP-20260925-1024/))는 실행 환경 사고로 무효다.** 90초 뒤의 `docker start patroni-db-2`가 `ports are not available: ... 127.0.0.1:59009 ... forbidden by its access permissions`로 실패했다. 같은 PC의 S5 작업 트리(`C:\dev\exchange-pml`)가 10:25:03에 띄운 Gradle 시험 JVM이 그 사이 59009에서 수신 대기를 시작했다. 59xxx는 Windows 동적 포트 대역(49152~65535) 안이라, DB 컨테이너가 멈춘 동안 다른 프로세스가 호스트 포트를 가져갈 수 있다. Standby가 돌아오지 않아 클러스터는 엄격 동기로 쓰기를 멈춘 채였고, 실행기는 재개를 240초 기다린 뒤 사후 단계의 ACK 적재(`load-acks.ps1`의 psql)에서 커밋 대기로 멈췄다. 포트가 풀린 10:48:03에 손으로 `docker start patroni-db-2`를 해 실행기가 끝나게 했다. 그 회차의 판정 파일(INCONCLUSIVE)은 T_back을 실행기가 명령을 낸 시각(T0+90.2초)으로 적었지만 실제 복귀는 T0+1,357초였다. 이 회차는 판정에 쓰지 않는다. 다만 약 22분의 쓰기 정지 뒤에도 ACK 유실 0, 사후 불변식 0이었고, 판정 도구가 본 창(T_suspend + 2초부터 명령상 T_back까지)의 쓰기 표본 83건은 모두 423이었다는 것은 사실로 남긴다.

그 뒤 실행기에 `Start-EpContainer`(2초마다 다시 시도, 30초 안에 못 띄우면 회차를 오류로 끝냄)를 더하고 E-P1·E-P1b·E-P2a·E-P5의 복귀를 그것으로 바꿨다(`harness/scripts/ep-lib.ps1`). 이후 회차에서 시작 실패는 없었다(`timeline.start_failures` 빈 목록). 포트 대역 자체를 옮기지는 않았다.

**E-P3b 첫 회차(11:11, [EP3b-20260925-111111](../patroni-EP-20260925-1111/))는 INCONCLUSIVE다.** 시간 축이 아닌 축은 모두 PASS였고 시간 축 넷도 값으로는 상한 안(승격 21.50초 등)이었지만, S5 스택이 잠깐 내려간 틈에 시작한 뒤 다른 프로젝트의 k6 컨테이너 `pml-s6-watch-W2`가 떠 무효 조건 (c)에 걸렸다. 14:20에 깨끗한 조건으로 다시 돌린 회차가 위 표의 PASS다. 이 일 뒤 순서기의 대기 조건에 `pml-*`와 다른 프로젝트의 k6 컨테이너를 더했다.

## 설계·과제 문장과 다른 점

- **거절 코드.** 과제 문장은 E-P2의 주문 응답을 '503(거래 일시 중단)'으로 적었지만, 이 브랜치의 계약(API 설계 §2-5-d v2.57, ADR 0036)과 코드는 동기 Standby가 없어 쓰기를 멈춘 상태(SUSPENDED)를 `423 trading_suspended` + `Retry-After: 5`로, DB에 닿지 못하는 상태(UNREACHABLE)를 `503 service_unavailable`로 가른다. 판정은 계약대로 423으로 했다. E-P2a·E-P2b 중단 구간의 쓰기 표본은 모두 423이었고 503은 없었다. E-P1·E-P5처럼 Primary가 없어지는 구간에서는 게이트 503(E-P5 첫 회차 319건)과 LB 502가 먼저 오고, 승격 뒤 동기 Standby가 없는 동안 423이 온다.
- **E-P1b의 타임라인.** 설계 8-3은 'T_promote 없음(타임라인 불변)'을 PASS 조건으로 두었다. Patroni 4.1.5 로그는 `starting as readonly because i had the session lock` 다음 `promoted self to leader because I had the session lock`, PostgreSQL은 `selected new timeline ID: 2`였다(14:19:05~06). 노드 전환은 없었고(리더 db-2 그대로, 앱 리더·epoch 불변) 사후 불변식·ACK 유실·C16·구 epoch 쓰기는 모두 0이었다. 처음 판정 도구는 이 타임라인 변화를 `timeline_unchanged` FAIL로 냈다([처음 판정](../patroni-EP-20260925-1417/EP1b-20260925-141742.verdict.json), 이력으로 보존).

  사용자가 2026-09-25에 타임라인 변경을 허용하고 기록하는 항목으로 두기로 확정했다(설계 13절 26). 판정 도구 `patroni_axes.py`의 E-P1b에서 그 축을 빼고 `extra.timeline_change`(전후 값과 차이)와 `extra.patroni_leader_changed`로 기록하게 바꿨다. 같은 원자료(저장소 밖 원시 폴더, `ackrec.jsonl` SHA-256이 `.hashes.json`과 같고 나머지 네 파일은 저장소 사본과 같음을 확인)를 바꾼 도구로 다시 판정한 결과가 [재판정](../patroni-EP-20260925-1417/EP1b-20260925-141742.verdict-rejudge-20260925.json)이다. 판정은 **PASS**이고 축 7개(`ackrec_rpo` 0, `ackrec_failed_present` 0, `post_invariants_gated` 0, `post_invariants_named` 0, `c16_rpo_records` 0, `stale_epoch_writes` 0, `order_500` 0)가 모두 참, 판정 불가 0이다. 기록 항목은 타임라인 1→2(차이 1), Patroni 리더 교체 없음, 앱 리더 교체 없음, 확정 응답 보류 증가 12다. 시각·무효 조건·기록기 요약은 처음 판정과 같다.

  설계가 E-P1b에 요구한 '첫 확인 탐지 전에 나간 by-client 200·V47 201 0'은 판정 도구에 축이 없어 재판정에서도 판정하지 않았고, 확정 응답 보류 계수(`exchange_write_definitive_deferred_total`) 증가 12만 기록했다. epoch 규칙(앱 리더가 그대로면 epoch 불변)도 도구의 축이 아니며, 실행기가 적은 전후 값(epoch 1→1, 앱 리더 api-1 그대로)으로만 확인했다. 재판정 PASS는 도구의 축 7개에 대한 판정이다.
- **E-P6(b)의 T_sync.** 설계 문구는 '재초기화 시작부터 T_sync까지 ACTIVE 0회'다. 새 멤버의 walsender가 붙은 뒤 `/cluster`가 `sync_standby`를 적기까지 8초가 걸렸고 그 사이 ACTIVE 표본이 3개 있었다. 그 구간은 멤버 사본이 있는 정상 확인이다(설계 8-1 (7)이 확인 커밋 축의 끝을 T_sync에서 멤버 walsender 시작으로 바꾼 것과 같은 이유). 판정은 멤버 walsender 시작을 끝으로 했고 설계 문구대로 센 값 3은 기록으로 남겼다.
- **E-P5의 V47.** 두 E-P5 회차 모두 V47 결과(`commit_recovered`, `unresolved`, `unavailable`)와 확정 응답 보류가 0이었다. Primary를 죽인 순간 커밋을 기다리던 주문 접수가 없었거나, 있었어도 앱이 결과를 모르는 상태로 남기지 않았다. 설계가 기대한 '결과를 모르는 접수가 생기고 V47이 새 Primary에서 판정'하는 장면은 이 두 회차에서 나오지 않았다. 판정 도구의 E-P5 축은 모두 통과했지만 V47 경로는 이 실험으로 검증하지 못했다.

## 한계

- k6의 `idempotent_safe` 검사는 두 E-P5 회차에서 98.79%·98.81%였다(실패 21건씩). 5xx·시간 초과를 받은 주문을 곧바로 by-client로 한 번 재조회하는데, DB가 없는 동안에는 그 재조회도 5xx라 k6가 그 키를 확정하지 못한 채 넘어간다. k6에는 재전송 경로가 없어 중복 접수를 만들 수는 없고, DB의 같은 `client_order_id` 중복은 0이었다. 설계 E-P5의 'k6 재조회 수렴(끝없는 UNKNOWN 0)'은 판정 도구에 축이 없고, k6가 그런 키를 재개 뒤 다시 묻지 않아 이 회차들로는 판정하지 못했다. WebSocket `ws_epoch_resync_fail`이 E-P5 첫 회차에서 3이었다. 이것도 판정 축 밖이다.
- E-P6(b)의 '백업 스트림이 `sync`인 표본 0'은 표본이 없어서 0이다. 베이스백업이 약 0.6초(10:21:54.548~55.135)에 끝나 0.5초 간격 복제 표본이 그 스트림을 한 번도 잡지 못했다. `--no-slot` 전달은 실행 중 `/proc` 명령줄로 확인했다. 데이터가 작은 로컬 스택이라 재초기화 소요(멤버까지 2.5초)는 AWS 규모를 대표하지 않는다.
- E-P6(b) 전용 축은 판정 도구(`patroni_axes.py`)에 없어 이 폴더의 [ep6b_axes.py](ep6b_axes.py)로 계산했다. 실행기 [ep-p6b-reinit.ps1](../../scripts/ep-p6b-reinit.ps1)는 이번에 새로 만들어 한 회차만 돌렸다.
- 모든 실험은 로컬 Docker 한 대의 결과다. 학교·개인 AWS에서의 시간 값(etcd·Patroni TTL 기본값 30·10·10, 망 지연)은 다르다. 이번 회차는 설계 3-2의 로컬 값(20·5·5)이다.
- E-P2c, E-P7, E-P8a·b, E-P9는 이번 범위(E-P1~E-P6) 밖이라 돌리지 않았다.
- 같은 날 앞선 회차들(07:32~08:32, [harness/patroni/README.md](../../patroni/README.md) 기록 절)은 다른 작업의 Gradle 부하로 시간 축이 무효였다. 이번 한 벌이 시간 축까지 판정한 첫 회차들이다.

## 파일

| 파일 | 내용 |
|---|---|
| `../patroni-EP-<YYYYMMDD-HHMM>/<run>.verdict.json` | 회차 판정(시각, 축, 무효 조건, 기록기 요약) |
| `.timeline.json`, `.facts.json`, `.observe.jsonl`, `.ackrec-verify.json`, `.hashes.json`, `.json` | 실행기 시각·사실, 관측 표본, 기록기 대조, 원문 로그 해시, lib.ps1 표준 판정 |
| `../patroni-EP-20260925-1019/EP6b-20260925-101936.as-EP1.verdict.json`, `.ep6b.verdict.json` | E-P6(b) 회차의 E-P1 축 판정과 재초기화 축 판정 |
| `../patroni-EP-20260925-1417/EP1b-20260925-141742.verdict-rejudge-20260925.json` | E-P1b 재판정(타임라인을 기록 항목으로 바꾼 도구). 같은 폴더의 `.verdict.json`과 `.json`은 처음 판정(FAIL)으로 보존 |
| [preserved-volumes-start.txt](preserved-volumes-start.txt), [preserved-volumes-end.txt](preserved-volumes-end.txt) | 보존 볼륨과 남은 자원 확인 |
| [batch-log.txt](batch-log.txt), [ep-batch.ps1](ep-batch.ps1) | 순서기 기록과 순서기 |
| [summarize.py](summarize.py) | 판정 파일에서 표의 값을 뽑는 도구 |

기록기 원본, 컨테이너 로그 원문, k6 로그, 부하 계정 키는 저장소 밖(`%TEMP%\exchange-patroni-ep\<run>`)에 있고 저장소에는 해시만 남겼다.
