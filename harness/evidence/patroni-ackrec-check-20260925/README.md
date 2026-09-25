# DB 수준 ACK 기록기 동작 확인 (2026-09-25)

[ack_recorder.py](../../scale/ack_recorder.py)가 실제 PostgreSQL 과 psycopg 3.3.6 의 저수준 API 로 설계 8-1 의 분류를 내는지 한 번 본 기록이다. E-P 실험이 아니다. Patroni 스택이 아니라 폐기용 PostgreSQL 17 컨테이너 하나(`postgres:17-alpine`)에 붙였고, 기록기는 DB 이미지(`exchange-patroni-db:local`)의 가상 환경에서 돌렸다. 소켓 제한만 확인을 위해 3초로 줄였다(실험 기본값은 90초다).

## 장면

| 시각(UTC) | 조작 | 기대 |
|---|---|---|
| 07:06:47 | 기록 시작(스레드 넷, 20ms 간격) | acked |
| 07:06:52.8 | `ALTER SYSTEM SET synchronous_standby_names = 'ghost'` 와 재적재. 모든 커밋이 동기 복제를 기다린다 | 보낸 시도는 소켓 제한 뒤 unknown(socket_timeout). 소켓만 닫고 다시 붙는다 |
| 07:07:00.1 | SyncRep 대기 백엔드 12개 확인 뒤 설정 해제 | 기다리던 커밋이 풀린다. 버려진 unknown 행 가운데 일부가 표에 나타난다 |
| 07:07:04.4 | `docker restart` | 연결이 FATAL 57P01 로 끝나 unknown, 0.5초 뒤 다시 붙는다 |
| 07:07:18 | 기록 끝(30초) 뒤 대조 | 빠진 번호 0 |

## 결과

[verify.json](verify.json)이 대조 결과다. acked 4,192 건이 모두 표에 있었다(missing_count 0). unknown 은 12 건이고 그 가운데 8 건(설정을 풀 때 커밋된 소켓 제한 행)이 표에 있었다. failed 로 분류했는데 표에 있는 번호는 0 이다. [not-acked-and-connects.jsonl](not-acked-and-connects.jsonl)은 acked 가 아닌 시도와 연결 기록만 옮긴 것이다. 소켓 제한 8 건은 두 차례(07:06:53 과 07:06:56 에 보낸 시도), FATAL 57P01 4 건은 재기동 순간의 시도다. 스레드마다 acked 끝 시각 사이의 가장 긴 공백은 7.36초(설정을 건 구간)였다.

기록 원본(`ackrec.jsonl`, 4,342 줄)은 저장소 밖에 두었다. SHA-256 은 `6e5b2a28bf796708bff4a93249c9d4b88f666ded3927e180a84b46b3283a9d76` 이다.

## 확인하지 않은 것

Patroni 전환, HAProxy 경유 재연결, keepalive 로 끊기는 죽은 연결(컨테이너 격리)은 이 확인에 없다. 그 경로는 E-P 실행기 회차에서 본다.
