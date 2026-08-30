# 배포 산출물

기획안 §6.9 compose 오버레이 분리의 실물이다. 현행 `compose.yaml`은 단일 호스트를 전제해
그대로는 다중 호스트에 얹히지 않는다. 실측 결합 지점 17곳(`container_name` 고정 5,
서비스명 DNS 의존 5, 호스트 경로 바인드 7).

## 파일

| 파일 | 용도 |
|---|---|
| `compose.base.yml` | 서비스 정의. 이미지 태그와 환경변수는 전부 변수 참조 |
| `compose.node-app.yml` | 앱 노드 1개분. prod 프로파일 |
| `compose.node-data.yml` | PostgreSQL 1개분 |
| `compose.node-edge.yml` | nginx TLS 종단 + 관측 |
| `.env.example` | 변수 카탈로그. 값 없음. 커밋 대상 |

## 원칙

노드 간 통신은 DNS 이름이 아니라 변수로 받는다. 로컬은 compose 네트워크의 서비스명,
클라우드는 프라이빗 IP나 내부 DNS를 같은 변수에 넣는다. 코드 변경 0이 목표다.

`container_name`을 쓰지 않는다. 한 호스트에 한 역할만 올라가므로 compose 기본 이름으로
충분하고, 고정하면 같은 호스트에서 청록 배포가 불가능해진다.

호스트 경로 바인드는 세 가지로 줄인다. 시크릿 파일, TLS 인증서, 정적 산출물. 나머지는
이미지에 굽거나 named volume을 쓴다.

## 도메인

`sang.it.kr`(가비아 네임서버). A 레코드 미설정 상태다. 아래 값이 확정되면 웹 빌드가 가능하다.

- `NEXT_PUBLIC_API_BASE=""` same-origin. CloudFront 2 오리진 배치의 이득을 여기서 회수한다
- `NEXT_PUBLIC_WS_BASE=wss://sang.it.kr` STOMP 클라이언트가 절대 URL을 요구한다
- `NEXT_PUBLIC_DATASOURCE=http` `web/.env.production`이 이미 담고 있다

## 상태

미검증이다. 이 파일들은 아직 실제 다중 호스트에서 기동한 적이 없다. 검증 전까지
"배포 가능"으로 읽지 않는다.

## arm64 매니페스트 실측 (2026-08-30)

배포 대상이 t4g·c7g 계열(AWS Graviton, ARM64)이다. 쓰는 이미지 8종이 전부 arm64를
내는지 확인했다. 하나라도 없으면 인스턴스 계열을 전면 재선정하고 비용표를 다시 짜야 한다.

| 이미지 | arm64 | 출처 |
|---|---|---|
| `postgres:17-alpine` | 있음 | `harness/docker-compose.chaos.yml` |
| `ghcr.io/shopify/toxiproxy:2.12.0` | 있음 | 같은 파일 |
| `curlimages/curl:8.11.0` | 있음 | 같은 파일 |
| `nginx:1.29` | 있음 | 같은 파일 |
| `prom/prometheus:v3.13.0` | 있음 | 같은 파일 |
| `grafana/grafana:13.1.0` | 있음 | 같은 파일 |
| `grafana/k6:1.4.0` | 있음 | `compose.yaml` |
| `eclipse-temurin:21-jre` | 있음 | `harness/Dockerfile.app` |

전건 통과다. `docker manifest inspect`로 확인했고 기획안 §9 비용표를 재산정할 이유가 없다.
