# PostgreSQL 수동 페일오버 절차

2026-09-18에는 [격리 실습 도구](../../../deploy/lab/README.md)로 primary 정지 확인, 승격, 앱 주소 전환·리스 회복, 새 볼륨 재편입과 백업 복원을 한 Docker 호스트에서 검증했다. 실제 체결·ACK·원장과 불변식 판정은 [실행 근거](../../../artifacts/operations-20260918/db-recovery-drill.json)에 있다. 쓰기를 멈추고 복제를 따라잡게 한 조건이며 AWS 다중 호스트나 급작스러운 장애의 무손실 결과가 아니다. 이전 승격·재편입 측정은 `harness/evidence/failover-rpo-20260912/`에 보존한다.
정본 관계: 복제 정책은 `docs/reference/adr/0024-async-two-node-replication.md`. 배선은
`deploy/compose.node-data.yml`.

사람이 장애와 격리를 판단하고 수동으로 승격한다. 실습 도구가 명시적으로 실행하는 `pg_promote()`를 운영 자동 페일오버 정책으로 해석하지 않는다.

앱 리더 페일오버(`leader_lease`, `assert_current_leader` fencing)와 혼동하지 않는다. 그것은
DB 가 살아 있을 때 앱 사이에서 도는 장치이고, 여기는 DB 자체가 죽었을 때다.

---

## 1. 원칙

**구 primary 를 쓰기 불가로 만든 뒤에 새 primary 를 연다.** 순서를 바꾸면 두 노드가 동시에
쓰기 가능해진다. 복제로 이어져 있지 않은 두 쓰기는 병합할 방법이 없다.

이 순서는 타협 대상이 아니다. "구 primary 가 확실히 죽었다" 는 판단에 기대지 않는다. 죽은 줄
알았던 노드가 깨어나는 것이 E7 이 재현한 시나리오다.

---

## 2. 절차

### 2-1. 장애 확인

primary 가 실제로 못 쓰는 상태인지 본다. 네트워크만 끊긴 것인지 프로세스가 죽은 것인지
가른다.

```
pg_isready -h <primary> -p 5432 -U <db_user> -d exchange
```

앱 쪽 신호도 함께 본다. `/actuator/health` 와 `exchange_leader_lease_renew_total{outcome="error"}`
증가다. 리스 갱신이 실패하면 앱은 스스로 강등한다.

### 2-2. 구 primary fencing

**이 단계를 건너뛰지 않는다.** 가능한 수단을 위에서부터 시도한다.

1. 컨테이너 정지. `docker stop <primary 컨테이너>`
2. 연결을 차단할 때는 새 접속뿐 아니라 이미 연결된 앱의 쓰기까지 차단됐는지 확인한다. 보안 그룹 규칙 제거만으로 기존 추적 연결이 즉시 끊겼다고 판단하지 않는다.
3. 인스턴스 정지. 마지막 수단이다.

fencing 이 확인되기 전에는 아래로 내려가지 않는다.

### 2-3. standby 복제 상태 확인

승격 전에 receive·replay WAL 위치와 마지막 replay 시각을 기록한다. 마지막 replay 이후 경과 시간은 쓰기가 없을 때도 증가하므로 이 값만으로 실제 RPO나 유실 거래 수를 단정하지 않는다.

```sql
SELECT pg_last_wal_receive_lsn(),
       pg_last_wal_replay_lsn(),
       now() - pg_last_xact_replay_timestamp() AS replay_delay;
```

`receive` 와 `replay` 가 벌어져 있으면 재생이 끝나기를 기다린다. 재생 중 승격하면 그만큼 더
잃는다.

구 primary에 접근할 수 있다면 `pg_current_wal_lsn()`과 비교해 WAL 거리와 재생 대기 여부를 확인한다. WAL 바이트 차이는 거래 건수나 손실 시간과 같지 않다. 실제 승인된 주문 ACK·명령 저널·체결을 비교해 유실 범위를 판정한다. 접근이 불가하거나 원자료가 없으면 유실량을 미확인으로 남긴다.

### 2-4. standby 승격

```
docker exec -u postgres <standby> pg_ctl promote -D /var/lib/postgresql/data
```

`-u postgres` 를 빠뜨리면 `pg_ctl: cannot be run as root` 로 끝난다. `docker exec` 의 기본
사용자가 root 이고 `pg_ctl` 은 root 실행을 거부한다(실측).

확인.

```sql
SELECT pg_is_in_recovery();   -- f 가 나와야 한다
```

승격은 되돌릴 수 없다. 타임라인이 올라가므로 구 primary 를 그대로 standby 로 붙일 수 없다.

### 2-5. 앱 DB 엔드포인트 전환

앱·조회·봇의 `DB_HOST`를 새 primary로 바꾸고 재기동한다. exporter와 백업 대상도 새 역할에 맞춘다.

```
# 각 앱 노드에서
sed -i 's/^DB_HOST=.*/DB_HOST=<새 primary>/' .env
docker compose -f compose.node-app.yml --env-file .env up -d --force-recreate app
```

운영 주소 전환은 DNS든 변수 교체든 사람이 수행한다. 실습 도구의 전용 환경 파일 전환은 실제 AWS 전환 장치가 아니다.

### 2-6. 리스 재획득과 앱 정상화

앱이 새 primary 에 붙으면 `leader_lease` 를 다시 잡는다. 이 부분은 자동이다.

확인.

```sql
SELECT resource, holder, epoch, lease_until FROM leader_lease;
```

epoch 가 단조 증가했는지 본다. 앱 쪽은 `/actuator/health` 가 200 이고
`exchange_leader_is_leader` 가 한 노드에서만 1 이어야 한다.

### 2-7. 구 primary 재편입

타임라인이 갈렸으므로 그대로 붙지 않는다. **기본 경로는 전량 베이스백업이다**(ADR 0025).
`pg_rewind` 는 되면 좋은 지름길이지 기다릴 경로가 아니다.

**1. 구 primary 를 fencing 유지한 채 한 번 띄웠다 내린다.**

급사한 노드는 크래시 복구를 한 번 돌려야 깨끗하게 정지한다. 네트워크를 끊거나
`listen_addresses` 를 비워 띄운다. 그러지 않으면 새 primary 와 구 primary 가 동시에 쓰기
가능한 창이 생긴다. **그 기동에 원래 설정을 그대로 준다.** `wal_log_hints` 는 기동할 때마다
`pg_control` 에 다시 쓰이므로, 설정을 빼고 한 번 띄우면 되감기 전제가 그 자리에서 사라진다.

**2. `pg_rewind` 를 한 번만 시도한다. 실패하면 즉시 3으로 간다.**

조건이 둘이다. 위 1이 끝나 있어야 하고, **마지막 공통 체크포인트까지 거슬러 올라간** WAL 을
아직 들고 있어야 한다. 분기 지점 이후가 아니다. **뒤쪽이 자주 깨진다.** `wal_keep_size` 가
기본값 0 이라 쓰기가 많았으면 그 구간이 이미 재활용된다. 실측 회차가 그렇게 실패했고 필요했던
소급 폭이 209.8MiB 였다. 되감기를 사는 보존 정책은 채택하지 않았다(ADR 0025 사유 절).

**3. 볼륨을 비우고 베이스백업을 다시 받는다.**

`deploy/pg-standby-entrypoint.sh` 가 빈 PGDATA 에서 그 경로를 탄다. 느리지만 확실하다.
실측은 428.7MiB 에 264.7초이고 그 상당 부분이 체크포인트 대기로 추정된다(단계 분할 미측정). **부하가 걸린 primary 에
`-c fast` 를 임의로 붙이지 않는다.** 리스 갱신이 밀려 리더가 내려앉은 실측이 있다
(`harness/evidence/replication-fault-20260908/`).

새 primary 에 슬롯이 없으면 만든다.

```sql
SELECT pg_create_physical_replication_slot('standby_1');
```

---

## 3. 운영 중인 primary 에 복제를 처음 붙일 때

`deploy/pg-init-replication.sh` 는 initdb 직후 한 번만 돈다. 이미 초기화된 볼륨에서는 무시되므로
아래를 손으로 한다.

```sql
CREATE ROLE replicator WITH LOGIN REPLICATION PASSWORD '<REPL_PASSWORD>';
SELECT pg_create_physical_replication_slot('standby_1');
```

`pg_hba.conf` 에 한 줄을 더하고 설정을 다시 읽는다.

```
host    replication     replicator      10.0.0.0/16     scram-sha-256
```

```sql
SELECT pg_reload_conf();
```

`wal_level` 이 `replica` 미만이면 재기동이 필요하다. 그 값만 재기동을 요구한다.

---

## 4. 실측된 것과 정하지 않는 것

`harness/evidence/failover-rpo-20260912/` 가 단일 호스트에서 잰 값이다.

| 구간 | 값 |
|---|---|
| 2-4 승격 명령 실행 | 1.697초, 1.886초 |
| 2-3 유실, 복제가 따라오는 중 급사 | ACK 88 건 중 0 건 |
| 2-3 유실, standby 8 초 정지 뒤 급사 | ACK 122 건 중 36 건 |
| 2-7 재편입, 전량 베이스백업(428.7MiB) | 264.7초. 스트리밍 재개까지 합 272.5초 |

이 절차가 정하지 않는 것은 다음이다.

- **RTO.** 위 1.7초대는 명령 실행 시간이다. 사람이 장애를 알아차리는 시간, 승격 여부를 판단하는
  시간, 2-5 엔드포인트 전환 시간이 그 안에 없다. 그 셋이 실제 RTO 를 지배하고 미측정이다.
  앱 리더 페일오버가 잰 10.041초와 10.226초(E5·E7, 2026-09-13 `harness/evidence/chaos-runs/`)는 이 절차의 값이 아니다.
- **RPO 의 기대값.** 유실량은 그 순간의 복제 지연과 같다. 정상 구간에서 0 이 나온 것을 무손실로
  읽지 않는다. 2-3 에서 그 회차의 값을 산출한다.
- **판단 기준.** 언제 페일오버할지는 이 문서가 정하지 않는다. 잠깐의 네트워크 흔들림에
  승격하면 타임라인이 갈려 되돌리는 비용이 더 크다.
