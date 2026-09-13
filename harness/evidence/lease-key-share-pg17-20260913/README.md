# 리스 행 잠금 모드 격리 실측 (2026-09-13)

## 결론

PostgreSQL 17.10(`postgres:17-alpine`) 버린 컨테이너에서 `leader_lease` 표와 V1·V30·V33 함수를
복제해 잠금 모드별 거동을 쟀다. ADR 0026 의 근거다.

- FOR SHARE 보유자가 겹치면 `renew_leader_lease` 가 14초 동안 행을 얻지 못한다(S2).
- FOR KEY SHARE 와 `UNIQUE(resource, holder, epoch)` 에서는 쓰기 잠금이 겹친 동안 갱신 10회가 2.2~3.3ms 에 끝나고(S5),
  `try_acquire_leader` 와 새 epoch 게시는 쓰기 트랜잭션 커밋까지 기다린다(S3-C, S3-D).
- 인덱스가 없으면 승격이 기다리지 않고 쓰기 트랜잭션이 새 holder 를 본 채 커밋한다(S4).
- `UNIQUE(resource, epoch)` 만으로는 holder 만 바꾸는 UPDATE 를 막지 못한다(S7).

## 파일

| 파일 | 내용 |
|---|---|
| `experiment.sh` | 컨테이너 기동, 스키마, 시나리오 스크립트 전문. 비밀번호 `exp` 는 버린 컨테이너 전용 값이다 |
| `scenarios.json` | 시나리오 22개의 설정, 기대, 관측 출력, 기대 일치 여부, 뜻밖의 관측 |

## 기대 불일치 둘

- **S5-publish.** `lock_timeout` 1초로 끊길 것을 기대했으나 5,557ms 기다린 뒤 성공했다.
  `lock_timeout` 은 대기 한 번의 상한이라 보유자가 바뀔 때마다 새로 잰다. 겹친 보유자 앞에서 총 대기를
  묶지 못한다. 그래서 통합 시험의 굶김 판정은 `statement_timeout` 으로 한다.
- **S7.** 인덱스 변형별 기대를 한 칸에 적어 일치 판정이 거짓으로 찍혔다. 관측은 결론 절과 같다.

## 한계

- 단일 컨테이너, 로컬 소켓이다. 네트워크 지연을 넣지 않았다.
- 앱 코드가 아니라 함수 사본과 psql 세션으로 쟀다. 앱 경로의 같은 성질은
  `server/app/src/test/java/kr/exchange/journal/LeaderLeaseLockModeIntegrationTest.java` 가 V41 적용 DB 에서
  단언한다.
- 컨테이너는 실측 뒤 삭제했다. 재현은 `experiment.sh` 로 한다.
