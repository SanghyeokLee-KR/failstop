# app-3 조회 분산 확인: P3와 파티션·중계 LB에서의 조회 전용 노드 (2026-09-26 12:20 KST~)

사용자가 2026-09-26 확정한 앱 계층 모델은 이렇다. app-1·app-2는 늘 켜진 매칭 리더이고 조회도 처리한다. app-3은 조회 전용(read 프로파일)이며, AWS에서는 ASG가 0대에서 1대 사이로 자동 증설한다. 로컬 카오스 스택에서는 `chaos-api-3`이 app-3 자리다. S5~S7에서 LB 설정이 파티션·중계용(`nginx.partition.conf`, `nginx.relay.conf`, 둘을 합친 `nginx.partition-relay.conf`)으로 바뀌었으므로, 최종 조합(P3 + `nginx.partition-relay.conf`)에서 다음 네 가지를 한 번의 폐기용 스택 주행으로 확인한다.

1. 조회 GET이 api-3으로 실제로 나뉘는가.
2. api-3이 쓰기(주문·취소·출금)를 받지 않는가. 직접 받으면 계약대로 거절하고 아무것도 쓰지 않는가.
3. api-3을 멈췄다 다시 올려도(0대와 1대 사이의 모사) 조회가 오류 없이 api-1·api-2로 넘어가고 다시 돌아오는가.
4. api-3의 WS 방침(S6 설계 4-15 '조회 노드는 WS를 받지 않는다')이 지켜지는가.

9/23 리허설([scale-rehearsal-20260923](../scale-rehearsal-20260923/README.md), [측정 20260923](../../../docs/measurements/20260923.md))과 비교할 수 있게 같은 지표(노드별 조회 요청 비율, 오류율, p95)를 쓴다. 9/23 기록에는 p95가 없으므로 이번에는 api-3이 없는 두 노드 상태(R0)를 같은 주행 안에서 먼저 재어 비교 기준으로 둔다.

이 절부터 '실행 순서'까지는 주행 전에 적었다. 결과는 주행 뒤 '결과' 절에 적고, 판정 규칙과 문턱은 주행 뒤 바꾸지 않는다.

## 조건

작업 트리 `C:\dev\exchange-pml`, 브랜치 `claude/per-market-leader`, HEAD `97c6436d`에서 돈다. 앱 이미지는 S7 관문 때 이 트리에서 구운 `exchange-chaos-app:pml`(`sha256:267c799f…`, HEAD `58469ef9`)을 다시 굽지 않고 쓴다. `58469ef9`와 HEAD 사이에 `server/` 변경이 없어서다(실행기가 `git diff --stat 58469ef9 HEAD -- server/`를 [images.json](images.json)에 남긴다). compose 프로젝트는 `exchange-pml`이다. 보존 볼륨 `exchange-chaos_chaos-pgdata`와 `failstop-keep-chaos-pgdata-20260924`는 생성 시각만 시작과 끝에 읽는다. 프로젝트 `exchange-chaos`와 `exchange-patroni`에는 아무 명령도 보내지 않는다. 비밀 파일(`harness/.env.chaos`, `harness/load/secrets/*`)은 compose와 스크립트에 경로로만 넘긴다. AWS와 학교 서버에는 접속하지 않는다. 부하는 이 PC의 스택 안에서만 만든다.

스택은 S7 관문과 같다. 두 앱 노드는 `dev,chaos-monkey,prod-timeouts` 프로파일, WS 중계 `broker` 모드, 선호 파티션(api-1 `matching-p0`, api-2 `matching-p1`)으로 뜨고, 전환 도구가 P3로 올린 뒤 Q0 준비 판정 14축을 본다. 봇은 기본 목표 초당 10건으로 LB를 거쳐 주문·취소·조회를 낸다. 관측 프로파일(obs)은 띄우지 않는다.

## 주행 전 확인한 결함과 수정안

설정을 읽으며 아래를 확인했다. 결함으로 본 것은 원인과 수정안을 먼저 적고 하네스·LB 설정만 고쳤다. 서버 코드는 바꾸지 않는다.

**기본 compose의 api-3은 조회 노드 설정이 아니다(결함, 이번에 고침).** `harness/docker-compose.chaos.yml`의 `chaos-api-3`은 api-1 환경을 물려받아 `dev,chaos-monkey` 프로파일, 풀 32/8로 뜨고 엔진·flyway·하네스만 끈다. 그래서 `ReadRoleFilter`가 없고 쓰기 경로도 막히지 않는다(주문은 엔진이 꺼져 저널 전에 `503`이다). 사용자 모델의 app-3(read 프로파일)과 다르다. 원인은 9/23 측정 조건을 보존하려고 기본 파일을 바꾸지 않은 것이다(파일 주석). 수정은 기본 파일을 그대로 두고 오버레이 [harness/docker-compose.read-node.yml](../../docker-compose.read-node.yml)을 더하는 것이다. 이 오버레이는 `deploy/compose.node-read.yml`과 같게 `prod,read` 프로파일, 조회 전용 DB 로그인, 풀 8(최소 유휴 2)과 읽기 전용 풀, 관리 평면 8081(호스트 38081)과 그 헬스 검사를 준다. 조회 로그인은 배포와 같은 스크립트 `deploy/provision-runtime-roles.sql`로 폐기용 DB에 만든다(`pml_read`, `assets`·`markets`·`trades` SELECT와 읽기 전용 기본값, 비밀번호는 실행마다 새로 만들고 적지 않는다).

**P3 LB 파일에서 api-3은 조회 업스트림에만 있고 기본값은 `down`이다(결함 아님).** `nginx.partition-relay.conf`의 api-3 줄은 `exchange_read`에만 있고 끝에 `down`이 붙어 있다. `/ws/`(`exchange_ws`)와 호가·티커(`exchange_any`), 쓰기·파티션 업스트림에는 api-3이 없다. 조정기가 `down`만 토글해 조회 경로에 넣고 빼는 설계다. 그래서 이번 주행은 추적 파일을 고치지 않고 사본(`lb/nginx.partition-relay.conf`, 시작 때 추적 파일과 같은 바이트)을 `chaos-lb`에 마운트하고, 조정기와 같은 `asg_controller.Balancer`로 사본의 `down`만 바꾼 뒤 `nginx -t`와 reload를 한다.

**조정기(`capacity_reconciler.py`)는 read 프로파일 api-3을 등록하지 못한다(결함, 이번에는 고치지 않음).** 조정기의 헬스 확인(`asg_controller.healthy`)은 호스트 38080의 `/actuator/health/read`를 본다. `prod`는 관리 평면을 8081로 옮기므로 read 프로파일 api-3에서는 이 경로가 고객 평면에 없어 헬스가 늘 실패하고, 조정기는 `add.timeout`만 낸다. 수정안은 스택 규격에 노드별 헬스 포트(api-3 38081)를 두고 없으면 앱 포트로 돌아가게 하는 것이다. 이번 주행은 조정기 대신 실행기가 같은 순서(기동, read 헬스, 등록)를 직접 하므로 고치지 않고 남은 결함으로 적는다.

**조회 노드의 동시 처리 상한은 4다(주행 전 위험, 판정 기준은 바꾸지 않음).** 요청 수용 상한은 풀에서 유도된다(`AdmissionLimits`, 조회 0.40, 하한 4). 조회 노드 풀 8에서는 조회 동시 처리 상한이 4이고, 넘치면 `429`다. LB의 조회 location은 `429`를 다른 노드로 다시 보내지 않는다(`proxy_next_upstream error timeout http_502 http_503`). 20 작업자 부하에서 `least_conn`이 api-3에 5건 이상을 동시에 보내면 클라이언트가 `429`를 받을 수 있다. 9/23과 같은 부하(20 작업자)에서 오류 0을 그대로 판정 기준으로 둔다.

## 판정 규칙

판정기는 [harness/scale/read_node_probe.py](../../scale/read_node_probe.py)의 `judge`이고 문턱은 그 파일 머리의 상수와 같다. 축마다 참·거짓이고 읽지 못한 값은 참으로 세지 않는다. 한 묶음에서 양성 축(표본이 판정할 만큼 있었는지)이 하나라도 거짓이면 그 묶음은 NOT RUN, 아니면 모든 판정 축이 참일 때만 PASS다. 전제가 하나라도 거짓이면 전체가 BLOCKED다. BLOCKED·NOT RUN·기록은 통과에 합산하지 않는다.

노드별 비율의 판정 원천은 LB 접근 로그다. 실행기의 모든 요청은 User-Agent에 단계 표식(`pml-app3-probe/<단계>`)을 싣는다. 로그의 `ups`(시도한 업스트림 주소)에서 마지막 주소를 처리 노드로, 주소 가운데 api-3이 있으면 'api-3 시도'로 센다. 주소는 단계마다 뜬 `docker network inspect` 표본으로 컨테이너 이름에 맞춘다. 9/23의 `readsplit3.py` 방식(노드별 `http_server_requests_seconds_count{uri}` 증가분)은 같은 창에서 기록으로 함께 남긴다. 봇 요청이 섞이므로 판정에는 쓰지 않는다. 오류는 클라이언트가 받은 `200` 밖의 모든 응답과 전송 실패다. p95는 클라이언트가 잰 요청별 시간의 가장 가까운 순위 값이고 판정이 아닌 기록이다.

**전제(BLOCKED).** 가드(프로젝트·이미지), Q0 준비 14축, api-3 이미지가 `exchange-chaos-app:pml`, api-3 프로파일이 `prod,read`와 역할 `read`, LB 사본이 시작 때 추적 파일과 같은 바이트이고 `chaos-lb`에 마운트됨, 사본에서 api-3이 `down`으로 시작, 첫 기동 뒤 180초 안에 read 헬스 `200`, api-3의 DB 세션이 모두 조회 로그인, 등록 뒤 `nginx -T`에서 api-3 줄에 `down`이 없음.

**A 조회 분산.**

- R1·R3(9/23 `readsplit3.py`와 같은 순차 창, 조회 부하 없음). `/api/v1/markets` 90회와 `/api/v1/trades?market=KRW-BTC&count=3` 90회마다 api-3이 처리한 비율 0.25 이상, 세 노드 모두 1건 이상 처리, 모두 `200`. 양성 축은 LB 로그의 표식 줄 수가 호출 수와 같음이다. R3은 두 번의 멈춤·복귀 뒤 같은 창을 다시 잰다.
- R1 호가 창(`/api/v1/orderbook?market=KRW-BTC` 60회). api-3 시도 0.
- R2(api-3 포함 세 노드, 20 작업자 120초, `read-load.py`와 같은 세 경로). 오류 0, api-3 처리 비율 0.15 이상. 양성 축은 요청 5,000건 이상.
- R2b(같은 세 노드, 5 작업자 60초). 오류 0, api-3 처리 비율 0.15 이상. 양성 축은 요청 1,000건 이상.
- 기록. R0(api-3 없이 두 노드, 20 작업자 120초)과 R0b(5 작업자 60초)의 오류율·p95, 모든 단계의 p50·p95·p99·최대와 초당 요청 수, R1·R3의 노드별 `uri` 계수 증가분.

**B 쓰기.**

- LB 로그. api-3을 처음 등록한 뒤 끝까지, 조회 location 밖의 GET·HEAD·OPTIONS가 아닌 모든 줄에서 api-3 시도 0. 양성 축은 그 창의 `POST /api/v1/orders` 50줄, `DELETE /api/v1/orders…` 10줄, `POST /api/v1/transfers` 10줄, `DELETE /api/v1/transfers…` 10줄 이상이다(봇과 아래 쓰기 표본).
- LB 쓰기 표본. 가입한 회원 하나가 R2부터 C2까지 약 4초마다 LB로 지정가 매수(`KRW-ETH`, 힌트 헤더, 체결되지 않는 가격), 그 주문 취소, KRW 출금 10,000 요청, 그 출금 취소를 낸다. 네 종류 모두 2xx 10건 이상(양성), `404`·5xx·전송 실패 0, 실행 중 예외 0.
- api-3 직접 쓰기. 같은 회원이 LB로 열린 주문 하나와 출금 요청 하나를 먼저 만든 뒤, api-3(38080)에 주문 생성, 그 주문 취소, 출금 생성, 그 출금 취소를 직접 보낸다. 네 요청 모두 `404`와 오류 이름 `not_found`(ReadRoleFilter 계약), 그리고 앞뒤로 뜬 그 회원의 주문 수·출금 요청 수·저널 명령 수·목표 주문 상태·목표 출금 상태·KRW 잔고와 잠금이 같다. 양성 축은 목표 주문과 출금이 만들어졌음이다.
- 기록. 조회 location으로 온 `POST /api/v1/markets` 30회의 응답 분포(쓰기가 아니며 받은 노드에 따라 `404`·`405`가 갈린다).

**C 멈춤과 복귀(5 작업자 연속 부하).**

- C1 계획된 축소·증설(ASG 축소의 등록 해제·드레이닝과 증설의 헬스 뒤 등록을 모사). 30초 뒤 사본에서 api-3을 `down`으로 바꾸고 reload, 10초 드레이닝, `docker stop`, 45초 유지, `docker start`, read 헬스, `down` 해제와 reload, 60초 유지.
- C2 예고 없는 손실. 30초 뒤 LB 설정을 건드리지 않은 채 `docker kill`, 45초 유지, `docker start`, read 헬스까지, 60초 유지. 돌아오는 것은 nginx의 이름 재해석(`resolve`, 10초)에 맡긴다.
- 판정은 주기마다 같다. 주기 전체의 오류 0, 멈춘 창(`C1-out`은 `docker stop` 뒤부터 다시 등록할 때까지, `C2-out`은 `docker kill` 뒤 45초)에 api-3 처리 0, 복귀 뒤 60초 창에 api-3 처리 비율 0.15 이상, read 헬스가 180초 안에 돌아옴. 양성 축은 주기 요청 1,000건 이상, 멈춘 창 LB 줄 200 이상, 복귀 창 LB 줄 500 이상이다.
- 기록. 하위 창별 p95·최대, 멈춘 창의 api-3 시도 수(재시도된 요청), 기동에서 read 헬스까지 걸린 시간.

**D WS.**

- 실행 중인 `chaos-lb`의 `nginx -T`에서 `exchange_ws`와 `exchange_any`의 구성원이 api-1·api-2뿐이고, `/ws/` location이 `exchange_ws`로 가며, api-3이 든 업스트림이 `exchange_read` 하나뿐.
- api-3 직접. `/ws/v1` 업그레이드 요청, `/api/v1/orderbook`, `/api/v1/ticker`가 모두 `404`.
- LB. R2 동안 `/ws/v1` 업그레이드 핸드셰이크 30회가 모두 `101`(양성 축 30회), 등록 뒤 끝까지 LB 로그의 `/ws/` 줄(양성 30 이상)과 호가·티커 줄에서 api-3 시도 0.

**E 무결성.** 등록 뒤 LB 로그에서 api-3이 처리한 5xx 0. 봇을 멈추고 비운 뒤 권위 run의 게이팅 위반 0(`Test-ExperimentIntegrity`)과 `summary.sql` CRITICAL·HIGH PASS. 양성 축은 사후 판정 파일이 있음이다.

## 실행 순서

실행기는 [run-app3.ps1](run-app3.ps1)이고 단계 실행은 `read_node_probe.py run`이 한다. 모든 단계의 시각은 [commands.log](commands.log)와 `facts.json`의 `marks`에 남는다.

1. 가드(떠 있는 `chaos-*` 없음, `.env.chaos` 있음), 보존 볼륨·호스트 컨테이너·이미지 기록.
2. `Set-P3Overlay`와 `compose up -d --wait`(굽지 않음, obs 없음), 전환 도구 `transition --target 3 --latch-band`, `Invoke-P3Preflight`.
3. `deploy/provision-runtime-roles.sql` 적용(폐기용 DB), 조회 로그인의 권한 표와 금지 조작 시도(`orders` SELECT, `trades` INSERT, CREATE TABLE) 기록.
4. LB 사본 마운트(`chaos-lb` 재생성), api-3 컨테이너 생성(기동하지 않음).
5. R0·R0b(api-3 없이) → api-3 기동·read 헬스·등록 → R1 → api-3 직접 확인(B·D) → LB 쓰기 표본 시작 → R2(WS 핸드셰이크 30회)·R2b → C1 → C2 → R3.
6. 봇 정지, 드레인, 권위 run, `summary.sql`.
7. 앱·LB 로그를 저장소 밖에 저장하고 해시와 검사 수만 남김, LB 사본의 최종 차이 기록, 판정.
8. `docker compose -p exchange-pml … --profile obs --profile scale down -v --remove-orphans`, 남은 컨테이너·볼륨·네트워크와 보존 볼륨 확인.

## 결과

(주행 뒤에 적는다.)
