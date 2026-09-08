# 엣지 노드 기동과 라우팅 실측 (2026-09-08)

`deploy/compose.node-edge.yml` 과 `deploy/nginx-edge.conf.template` 은 커밋 상태로 기동하지
못했다. 결함을 고치고 그 고침을 확인한다.

두 파일 모두 "미검증이다. 실제 EC2에서 기동한 적이 없다" 라고만 적고 있었다. 미검증과
기동 불가는 다른 등급이다. 이 회차가 그 등급을 갈랐다.

## 고치기 전에 무엇이 막고 있었나

기동을 세 번 막았다. 앞의 것을 고쳐야 뒤의 것이 드러난다.

| 순서 | 실측 오류 | 원인 |
|---|---|---|
| 1 | `[emerg] host not found in upstream "exchange_leader"` | 그 upstream 을 정의하는 것이 저장소에 없다 |
| 2 | `cannot load certificate /etc/letsencrypt/live/<도메인>/fullchain.pem` | 갓 세운 노드에 인증서가 없다 |
| 3 | `[emerg] "proxy_http_version" directive is not allowed here` | 1차 수정본이 `limit_except` 안에 `include` 를 넣었다 |

### 1. exchange_leader 미정의

템플릿 주석이 "사이드카가 `conf.d/upstream-leader.conf` 를 2 초 주기로 갱신한다" 고 적었다.
**그 사이드카는 존재한 적이 없다.** 저장소 전수에서 구현 0 건이고 주석 4 줄뿐이다.

게다가 그 설계는 정본이 이미 접은 것이다. 기획안 6.3 이 2026-08-30 에 ALB 를 채택하면서
사이드카를 기각했고 6.11 이 "6.3 의 ALB 채택으로 그 설계를 접었다" 고 적는다. 구현이 없는
채로 주석만 남아 정본과 어긋나 있었다.

부수 결함도 같이 나왔다. `compose.node-edge.yml` 이 `leader-conf` 볼륨을
`/etc/nginx/conf.d/leader` 라는 하위 디렉터리에 붙이는데, nginx 기본 `include conf.d/*.conf`
는 재귀가 아니라 그 안을 읽지 않는다. 사이드카가 있었어도 그 파일은 로드되지 않았다.

### 2. TLS 부트스트랩 교착

443 블록이 인증서 파일을 참조하는데 새 노드에는 그 파일이 없다. nginx 는 설정 파싱 단계에서
죽는다. nginx 가 안 뜨면 80 의 `/.well-known/acme-challenge/` 가 서비스되지 않고, 그러면
HTTP-01 검증이 불가능하다. **인증서가 없어서 못 뜨고 못 떠서 인증서를 못 받는다.**

최초 발급 명령도 없었다. `certonly` 저장소 전수 0 건이다. `compose.node-edge.yml` 의 certbot
서비스는 `certbot renew` 만 도는데, renew 는 빈 `/etc/letsencrypt` 에서 아무것도 하지 않으면서
종료 코드 0 을 낸다. 무동작이 성공으로 보이는 자리였다.

### 3. 헬스체크가 영구 초록

```
test: ["CMD-SHELL", "curl -fsS -o /dev/null http://localhost/.well-known/acme-challenge/ || exit 0"]
```

`|| exit 0` 이라 nginx 가 죽어도 healthy 로 보고한다. 게다가 그 경로는 파일이 없으면 404 라
정상 상태에서도 curl 이 실패한다. 곧 이 검사는 언제나 통과한다.

## 무엇을 고쳤나

| 결함 | 조치 |
|---|---|
| exchange_leader 미정의 | `exchange_write` 업스트림을 템플릿 안에 정의. 사이드카 의존 제거 |
| `leader-conf` 헛 마운트 | 마운트와 볼륨 삭제 |
| TLS 부트스트랩 교착 | `deploy/edge-bootstrap-cert.sh` 가 자체 서명 임시본을 깔아 기동시킨다 |
| 최초 발급 부재 | `deploy/issue-cert.sh` 신설. 임시본을 지우고 `certbot certonly` 를 돈다 |
| 헬스체크 무력 | `|| exit 0` 제거. 80 의 301 응답으로 생존 판정 |

### 리더 판별을 반응형으로 바꿨다

사이드카 없이 같은 결과를 낸다. 쓰기 업스트림에 두 노드를 다 넣고, 팔로워가 내는
503(REFUSING_TRAFFIC)을 `proxy_next_upstream` 이 실패로 보고 다른 노드로 넘긴다.
`proxy-common.conf` 가 `http_503` 과 `non_idempotent` 를 이미 목록에 넣고 `tries 2` 이므로,
노드가 둘이면 리더가 살아 있는 한 늦어도 두 번째 시도에서 닿는다.

`max_fails=1 fail_timeout=2s` 가 정상 상태를 만든다. 팔로워는 첫 503 으로 쓰기 업스트림에서
2 초간 빠지고, 그 뒤 쓰기는 리더로 직행한다.

실패 계수는 업스트림 블록마다 따로 센다. 곧 팔로워가 쓰기에서 빠져도 `exchange_read` 에서는
그대로 조회를 받는다. 6.11 의 "조회는 갈리고 주문은 갈리지 않는다" 가 이 두 블록으로 실현된다.

## 환경

| 항목 | 값 |
|---|---|
| 엣지 | `nginx:1.29`. `deploy/` 의 실제 파일을 그대로 마운트 |
| 앱 노드 | `nginx:1.29` 2 대. 리더는 전 메서드 200, 팔로워는 GET 200 / 그 외 503 |
| 도메인 | `edge-test.local` |
| 도구 | `docker compose -f deploy/compose.node-edge.yml -f <오버라이드>` |

앱 노드는 실제 애플리케이션이 아니라 응답 규약만 모사한 것이다. 재는 대상이 엣지의 라우팅
결정이지 앱의 처리가 아니다.

## 결과

### 기동

`[emerg]` 0 건. 헬스체크 `healthy`. 부트스트랩 로그가 임시 인증서 생성을 남긴다.

```
[bootstrap-cert] 인증서 없음. 자체 서명 임시본을 만든다. 도메인 edge-test.local
[bootstrap-cert] 임시본 생성 완료. 이 상태로는 TLS 경고가 뜬다.
```

재기동 시에는 기존 것을 알아본다.

```
[bootstrap-cert] 임시 인증서 존재. 발급 전 상태다. deploy/issue-cert.sh 를 돌린다.
```

임시본 검증: `subject=CN=edge-test.local`, `issuer=CN=edge-test.local`(자체 서명),
유효기간 1 일, `BOOTSTRAP` 표시 파일 존재.

### 라우팅

| 시험 | 결과 |
|---|---|
| 조회 20 회 | app1 10 / app2 10. **두 노드로 갈린다** |
| 쓰기 20 회 본문 | 20 회 전부 리더. **팔로워로 새지 않는다** |
| 쓰기 20 회 상태 | 200 x 20. **503 이 클라이언트로 나가지 않는다** |
| `/admin/` | 404 |
| `/harness/` | 404 |
| `/actuator/health` | 404 |
| 80 요청 | 301 -> `https://.../api/v1/markets` |

**쓰기 20 회에 503 이 0 건인 것이 `proxy_next_upstream` 상속의 증거다.** `limit_except` 는
별도 컨텍스트라 바깥 location 의 설정이 따라오는지가 불확실했고, 따라오지 않으면 팔로워
503 이 그대로 클라이언트에 나간다. 재서 확인했다.

### 리더 교체

두 앱의 역할을 맞바꾸고 `nginx -s reload` 한 직후부터 쓰기를 폴링했다.

| 항목 | 값 |
|---|---|
| 새 리더 도달까지 | **23ms** |
| 시도 횟수 | **1 회** |
| 상태 | 200 |

첫 시도에서 닿았다. 두 노드가 모두 업스트림에 남아 있어 재탐색이 필요 없었기 때문이다.
`fail_timeout=2s` 는 이 경로의 상한이지 실측값이 아니다.

### 노드 상실과 복구

리더 컨테이너를 정지시켰다.

| 구간 | 조회 | 쓰기 |
|---|---|---|
| 리더 정지 중 | **200 x 10** | 503 x 1, **502 x 9** |
| 리더 복구 뒤 | | **200 x 10** |

**조회는 살고 쓰기는 멈춘다.** 기획안 6.11 이 카오스 스택 nginx 로 잰 거동인데, 같은 것이
`deploy/` 의 설정에서도 재현된다. 복구는 자동이고 재기동이 필요 없다.

쓰기 502 는 결함이 아니라 설계다. 단일 라이터 구조에서 리더 없이 쓰기를 받으면 순서가
깨지므로 받아서 틀리는 것보다 거절이 맞다.

## 함께 확인한 운영 위험

**업스트림 호스트 하나가 이름 해석에 실패하면 nginx 가 통째로 기동하지 못한다.** 이 회차에서
app2 가 죽어 있는 동안 엣지가 `host not found in upstream "app2:8080"` 으로 뜨지 못했다.

배포 순서에 걸리는 제약이다. 앱 노드 둘이 다 서기 전에 엣지를 올리면 엣지가 기동에 실패한다.
반대로 이미 뜬 뒤에 앱 노드가 죽는 것은 위 표대로 견딘다. 곧 위험은 기동 시점에만 있다.

### 정정 (2026-09-08 같은 날 늦게)

**위 문단이 "배포 순서로 푼다" 로 끝나 있었다. 그 결론은 틀렸다.** 회피 수단을 `resolver` 와
변수 `proxy_pass` 조합으로만 보고, 그러면 `max_fails` 와 재시도가 사라진다고 적었다.

실제 수단은 업스트림 server 줄의 `resolve` 지시자다. `zone` 과 `resolver` 를 짝지으면
이름 해석이 기동 시점에서 런타임으로 미뤄지고 **`max_fails` 와 재시도는 그대로 남는다.**

A/B 실측:

| upstream 지시자 | 없는 호스트로 `nginx -t` |
|---|---|
| `server <없는호스트>:8080 resolve max_fails=1 fail_timeout=2s;` | **통과** |
| `server <없는호스트>:8080 max_fails=1 fail_timeout=2s;` | `[emerg] host not found in upstream` |

`deploy/nginx-edge.conf.template` 에 적용했고, 앱 노드 둘을 모두 없는 이름으로 둔 채
`compose.node-edge.yml` 을 띄워 `emerg` 0 건과 healthcheck 통과를 확인했다.

**제약은 사라졌다.** 상세는 `harness/evidence/asg-scenario-20260908/`.

## 한계

- **실제 EC2 기동은 여전히 0 건이다.** 이 회차는 로컬 도커의 같은 설정 파일이다.
- **앱 노드가 모사다.** 실제 애플리케이션의 readiness 전이, 승격 재생 창, WS 업그레이드는
  이 회차에 들어 있지 않다.
- **Let's Encrypt 실제 발급은 하지 않았다.** `issue-cert.sh` 는 도메인 위임이 끝나야 돌릴 수
  있고 그 위임은 미완이다. 스크립트 자체의 실행 경로는 미검증이다.
- 승격 재생 구간(두 노드가 모두 503)은 모사하지 않았다. 그 창에서 클라이언트가 503 을 받는
  것은 계약이지만 재지 않았다.
- 부하를 걸지 않았다. 라우팅 결정만 봤고 처리량과 지연은 이 회차의 대상이 아니다.
