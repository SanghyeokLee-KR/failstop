# ADR 0011. 정적 웹과 API를 CloudFront 단일 배포의 두 오리진으로 묶어 same-origin을 만든다

- 상태: 채택
- 날짜: 2026-08-26
- 결정자: 아키텍처 담당(정본 문서 관리자), harness-engineer

## 배경

학교 공지의 필수 조건은 "반드시 배포하여 외부에서 접속 가능한 상태로 서비스할 것"이다(`제출_20260901/기획안_원본.md:340`). 이 프로젝트의 증명 수단은 장애 주입이다. 두 요구가 정면 충돌한다. 요구표는 R1을 "외부 도메인으로 상시 접속 가능"으로, 그 충돌 지점을 "실험이 서비스를 망가뜨린다"로 적는다(`기획안_원본.md:344`).

서빙 대상은 Next.js 정적 export다. §6.6은 결정 시점 규모를 66페이지 6.3MB로 기록했다(`기획안_원본.md:477`). 오늘 재측정하면 html 74개, 6.9M이다(`find web/out -name "*.html" | wc -l`, `du -sh web/out`). 빌드 산출물이라 앱 노드와 생명주기를 공유할 이유가 없다.

두 번째 제약은 정적 export의 빌드 타임 인라인이다. `NEXT_PUBLIC_*` 값이 번들에 굳는다(`기획안_원본.md:510`). 오리진 주소가 빌드에 박히므로, 웹과 API가 서로 다른 오리진이면 CORS 허용 목록과 WS 오리진 화이트리스트가 배포 대상마다 따라 늘어난다.

충돌의 상위 해법은 평면 분리다. 상시면은 24시간 구동에 비파괴 실험만 허용하고, 증명면은 실험·발표 창에서만 띄워 전 실험을 받는다(`기획안_원본.md:350-355`). 그러나 평면 분리만으로는 상시면에서 리더 kill을 돌리는 동안 웹까지 같이 흔들리는 문제가 남는다. 웹 서빙 경로를 어디에 두느냐가 남은 결정이다.

## 결정

CloudFront 단일 배포에 오리진 둘을 걸고, 경로 패턴으로 가른다(`기획안_원본.md:485-490`).

| 경로 패턴 | 오리진 | 캐시 정책 |
|---|---|---|
| `/*` (기본) | S3, OAC로 비공개 | CachingOptimized |
| `/api/*` | origin.<도메인> | CachingDisabled + AllViewer |
| `/ws/*` | origin.<도메인> | CachingDisabled |
| `/actuator/*`, `/admin/*`, `/harness/*` | 라우팅 없음 | |

웹 빌드 파라미터를 이 배치에 맞춰 고정한다(`기획안_원본.md:512-516`).

- `NEXT_PUBLIC_API_BASE`는 빈 문자열. same-origin 상대 경로다.
- `NEXT_PUBLIC_WS_BASE`는 `wss://<도메인>`. 절대 URL이다.
- `NEXT_PUBLIC_DATASOURCE`는 `http`.

실험 판정은 CloudFront 뒤가 아니라 VPC 내부 오리진 직접 측정으로 한다(`기획안_원본.md:496`).

## 근거

- CORS·WS 오리진 화이트리스트 추가 작업이 0이 된다. 브라우저 관점에서 웹과 API가 같은 오리진이라 목록을 넓힐 일이 없다(`기획안_원본.md:494`). 두 설정 자체는 서버에 이미 있다. `server/app/src/main/java/kr/exchange/config/ProdCorsConfig.java:29`가 `exchange.cors.allowed-origins`를, `server/app/src/main/java/kr/exchange/api/ws/WebSocketConfig.java:28`이 `app.ws.allowed-origins`를 주입받는다. same-origin 계약에서는 이 값이 배포 도메인 하나로 좁아진다.
- R1과 실험이 양립한다. EC2 전체를 죽이는 실험 중에도 `https://<도메인>`이 200을 돌려준다. 웹은 살고 API만 5xx인 상태가 된다(`기획안_원본.md:495`).
- 계측이 오염되지 않는다. CloudFront는 재시도와 캐시를 하므로 그 뒤에서 RTO와 오류율을 재면 복구 시각이 실제보다 좋게 나온다. 판정 경로를 오리진 직접 측정으로 못박아 분리한다.
- 관리 평면이 경로 표에서부터 빠진다. 라우팅 없음은 인증 검사보다 앞선 방어다. 접근 경로는 대상마다 다르다. 관리 평면 8443 mTLS와 Prometheus 9090·Grafana 3000은 SSM 포트 포워딩, actuator 8081은 edge 노드 내부 스크레이프만, harness API는 `exchange.harness.enabled=false`로 상시면 미등록, PostgreSQL 5432는 프라이빗 서브넷이다(`기획안_원본.md:500-506`).
- 캐시 정책을 경로별로 가를 수 있다. `/*`는 빌드 산출물이라 캐시 가능하고, `/api/*`는 인증 헤더·서명·멱등키를 그대로 전달해야 해서 CachingDisabled에 AllViewer가 필요하다(`기획안_원본.md:487-488`). 오리진이 하나였다면 이 두 성격을 한 정책으로 묶어야 한다.
- WS가 CloudFront를 통과한다. `/ws/*`는 CachingDisabled로 두고, heartbeat가 10s/10s라 idle 타임아웃이 발생하지 않는다(`기획안_원본.md:489`).
- 코드가 이 계약에 이미 맞춰져 있다. `web/lib/api/datasource.ts:476-477`이 `NEXT_PUBLIC_API_BASE`, `NEXT_PUBLIC_WS_BASE`를 각각 빈 문자열 기본으로 읽고, `web/lib/api/httpDatasource.ts:364`가 `${wsBase}/ws/v1`을 만든다. 목적지가 `/ws/*` 패턴 안에 들어간다.

## 대안

- nginx 단독 서빙. 기각. 웹이 앱 노드와 운명을 공유한다. 앱 노드를 죽이는 실험이 곧 R1 위반이 되어, 필수 조건이 실험에 인질로 잡힌다(`기획안_원본.md:481`).
- S3 + CloudFront 단독(API는 별도 도메인). 기각. 웹 가용성은 앱과 독립하지만 오리진이 갈라져 CORS와 WS 오리진 화이트리스트가 늘어난다(`기획안_원본.md:482`). 정적 export는 오리진 주소를 빌드에 인라인하므로, 도메인이 바뀔 때마다 재빌드 대상이 늘어나는 비용도 같이 붙는다.

## 영향

- 도메인 확보가 W1 최우선 작업이 된다(`기획안_원본.md:518`). 도메인이 정해지기 전에는 배포용 웹을 빌드할 수 없다. `deploy/README.md:30`은 `sang.it.kr`(가비아 네임서버)를 대상으로 적고 A 레코드 미설정 상태로 남긴다.
- `NEXT_PUBLIC_WS_BASE`를 비워 두면 조용히 깨진다. `web/lib/api/httpDatasource.ts:363`의 폴백이 `apiBase.replace(/^http/, "ws")`인데, same-origin 계약에서 apiBase가 빈 문자열이라 폴백 결과도 빈 문자열이다. wsUrl이 `/ws/v1` 상대 경로가 되고 STOMP 클라이언트는 절대 URL을 요구한다. 이 값은 CI가 주입해야 한다.
- 추적 설정 파일에 API_BASE와 WS_BASE를 두지 않는다. 이유는 배포 계약이 same-origin이라 API_BASE가 빈 문자열이고 WS_BASE가 도메인에 종속되기 때문이다. 추적 파일에 localhost를 박으면 그 값이 배포 번들에 들어간다(`web/.env.production:8-10`, 커밋 `6a5efe9`). 같은 커밋이 `NEXT_PUBLIC_DATASOURCE=http`만 추적 대상으로 고정하고 CI 게이트를 붙였다.
- 오리진 쪽 대응이 엣지 nginx에 필요하다. `deploy/nginx-edge.conf.template:65-67`이 `^/(admin|harness|actuator)/`에 404를 돌려주고, `:70`이 `/ws/`를, `:79-80`이 `/api/v1/(orders|transfers)`를 받는다. CloudFront가 라우팅하지 않는 경로를 오리진도 거절하는 이중 차단이다.
- 되돌리려면 네 곳을 같이 고쳐야 한다. §6.6 경로 표, §6.7 빌드 파라미터 표, 웹 빌드 주입 값, 서버 오리진 화이트리스트다. 마지막 항목은 신규 구현이 아니라 값 확대다. `ProdCorsConfig.java:29`와 `WebSocketConfig.java:28`이 이미 주입 지점을 갖고 있어, 웹 오리진이 갈라지면 그 목록에 도메인이 하나 더 붙는다.
- same-origin이어도 prod 프로파일은 오리진 값을 요구한다. `ProdCorsConfig.java:30-33`이 `exchange.cors.allowed-origins` 미지정 시 IllegalStateException으로 기동을 중단한다. `WebSocketConfig.java:33-42`도 `exchange.require-secrets=true`에서 localhost나 demo.exchange.local이 남아 있으면 같이 중단한다. 도메인이 확정되면 두 값에 그 도메인을 넣어야 부팅한다.

## 반영 한계

문서와 웹 빌드 파라미터 계약까지만 반영됐다. CloudFront 배포 실물도, 그것을 정의하는 IaC 파일도 저장소에 없다. `deploy/`의 추적 파일은 7개다(`git ls-files deploy/ | wc -l`). compose 오버레이 3종(node-app·node-data·node-edge), nginx 템플릿, proxy-common.conf, .env.example, README.md다. CloudFront를 언급하는 곳은 `deploy/compose.node-edge.yml`의 주석 2줄(`:3`, `:43`)과 `deploy/README.md:32`의 도메인 절뿐이고, 어느 쪽도 배포를 정의하지 않는다(`grep -rn -i cloudfront deploy/`). `.tf` 파일은 0개다(`find . -name "*.tf" -not -path "*/node_modules/*" | wc -l`). `deploy/README.md:38-39`도 배포 산출물 전체를 미검증으로 선언한다.
