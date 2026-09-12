# PostgreSQL 수동 페일오버 절차

상태: 2-4 승격과 2-7 재편입은 단일 호스트에서 실측했다(`harness/evidence/failover-rpo-20260912/`).
2-2 fencing, 2-5 엔드포인트 전환, 2-6 리스 재획득을 포함한 전체 리허설은 0회이고 다중 호스트
기동도 0건이다.
정본 관계: 복제 정책은 `docs/adr/0024-async-two-node-replication.md`. 배선은
`deploy/compose.node-data.yml`.

**자동 페일오버가 아니다.** 저장소에 `pg_ctl promote`, `pg_promote()`, `promote_trigger_file` 이
한 건도 없다. 사람이 판단하고 사람이 승격한다. 이 문서가 그 순서다.

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
2. 도달 불가면 보안그룹에서 5432 인바운드를 제거한다. 앱이 붙을 수 없게 만든다.
3. 인스턴스 정지. 마지막 수단이다.

fencing 이 확인되기 전에는 아래로 내려가지 않는다.

### 2-3. standby 복제 상태 확인

승격 전에 얼마나 받았는지 본다. **이 값이 이번 장애의 실제 RPO 다.** 기록한다.

```sql
SELECT pg_last_wal_receive_lsn(),
       pg_last_wal_replay_lsn(),
       now() - pg_last_xact_replay_timestamp() AS replay_delay;
```

`receive` 와 `replay` 가 벌어져 있으면 재생이 끝나기를 기다린다. 재생 중 승격하면 그만큼 더
잃는다.

구 primary 에 접근할 수 있다면 그쪽 `pg_current_wal_lsn()` 과 비교해 유실 구간을 산출한다.
접근 불가면 유실량을 모른다. 모르면 모른다고 기록한다.

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

앱 노드의 `DB_HOST` 를 새 primary 로 바꾸고 재기동한다.

```
# 각 앱 노드에서
sed -i 's/^DB_HOST=.*/DB_HOST=<새 primary>/' .env
docker compose -f compose.node-app.yml --env-file .env up -d --force-recreate app
```

**`deploy/` 에 엔드포인트 자동 전환 장치가 없다.** DNS 전환이든 변수 교체든 사람이 한다.

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
  앱 리더 페일오버가 잰 4.265초와 10.176초(E5·E7)는 이 절차의 값이 아니다.
- **RPO 의 기대값.** 유실량은 그 순간의 복제 지연과 같다. 정상 구간에서 0 이 나온 것을 무손실로
  읽지 않는다. 2-3 에서 그 회차의 값을 산출한다.
- **판단 기준.** 언제 페일오버할지는 이 문서가 정하지 않는다. 잠깐의 네트워크 흔들림에
  승격하면 타임라인이 갈려 되돌리는 비용이 더 크다.
