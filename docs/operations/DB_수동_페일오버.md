# PostgreSQL 수동 페일오버 절차

상태: 절차 확정, 라이브 리허설 0회. 실제 다중 호스트 기동이 0건이라 이 문서도 그 조건 위에 있다.
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
docker exec <standby> pg_ctl promote -D /var/lib/postgresql/data
```

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

타임라인이 갈렸으므로 그대로 붙지 않는다. 둘 중 하나다.

- `pg_rewind` 로 새 primary 타임라인에 맞춘다. 구 primary 가 깨끗하게 정지했어야 한다.
- 볼륨을 비우고 베이스백업을 다시 받는다. `deploy/pg-standby-entrypoint.sh` 가 빈 PGDATA 에서
  그 경로를 탄다. 느리지만 확실하다.

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

## 4. 이 절차가 정하지 않는 것

- **RTO.** 사람이 판단하고 승격하는 시간에 지배된다. 측정된 바 없다. 앱 리더 페일오버가 잰
  4.265초와 10.176초(E5·E7)는 이 절차의 값이 아니다.
- **RPO.** 비동기 복제이므로 0 이 아니다. 2-3 에서 그 회차의 값을 산출하되, 이 저장소에
  비동기 아래 유실량 실측이 0 건이다. 기대값을 적지 않는다.
- **판단 기준.** 언제 페일오버할지는 이 문서가 정하지 않는다. 잠깐의 네트워크 흔들림에
  승격하면 타임라인이 갈려 되돌리는 비용이 더 크다.
