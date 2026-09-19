# 거래소 웹

Next.js로 만든 데스크톱 거래 화면이다. 시세, 호가, 차트, 주문과 자산 내역을 보여주며 회원·게시판·관리자 화면도 포함한다. [HTTP/STOMP 어댑터](lib/api/httpDatasource.ts)로 백엔드에 연결하거나, 브라우저 시뮬레이터로 화면만 실행할 수 있다.

전체 프로젝트는 [루트 README](../README.md), 웹과 서버를 함께 띄우는 절차는 [실행 가이드](../실행_가이드.md)를 본다.

## 개발 시작

아래 명령은 `web/`에서 실행한다. CI는 Node.js 22를 사용한다.

```powershell
npm ci
npm run dev
```

개발 서버 주소는 `http://localhost:3000`이다. 데이터 연결 방식은 [datasource.ts](lib/api/datasource.ts)가 선택한다.

- `NEXT_PUBLIC_DATASOURCE=http`: 실제 백엔드에 HTTP와 STOMP로 연결한다.
- 그 외의 값 또는 미설정: 브라우저의 [SimEngine](lib/sim/engine.ts)을 사용한다. 시뮬레이터의 주문·자산은 새로고침하면 초기화된다.

API·WebSocket 주소 설정은 실행 가이드에 있다. `NEXT_PUBLIC_*` 값은 빌드할 때 결과물에 들어가므로, 배포 후 주소를 바꾸려면 다시 빌드해야 한다. 관리자 연결은 [lib/admin/api.ts](lib/admin/api.ts)의 `NEXT_PUBLIC_ADMIN_DATASOURCE`와 `NEXT_PUBLIC_ADMIN_BASE`로 별도 설정할 수 있다.

## 화면을 고칠 때

| 작업 | 시작할 파일 또는 폴더 |
|---|---|
| 첫 방문 경로 | [app/page.tsx](app/page.tsx). 방문·로그인 상태에 따라 `/welcome` 또는 `/home`으로 이동한다. |
| 홈 대시보드 | [app/home/](app/home/), [components/dashboard/](components/dashboard/) |
| 거래 화면 배치와 패널 | [ExchangeMarketClient.tsx](app/exchange/[market]/ExchangeMarketClient.tsx), [components/trade/](components/trade/) |
| 메인 차트 | [components/chart/](components/chart/). klinecharts를 사용하며 대시보드의 미니 차트는 lightweight-charts를 쓴다. |
| API 통신과 화면 상태 | [lib/api/](lib/api/), [lib/store.ts](lib/store.ts) |
| 금액 계산과 주문 규칙 | [lib/decimal.ts](lib/decimal.ts), [lib/orderContract.ts](lib/orderContract.ts) |
| 관리자 화면 | [app/admin/](app/admin/), [components/admin/](components/admin/), [lib/admin/](lib/admin/) |
| 색상·간격·공통 스타일 | [styles/tokens.css](styles/tokens.css), [app/globals.css](app/globals.css) |

마켓별 페이지는 빌드할 때 미리 만든다. 목록은 [app/exchange/[market]/page.tsx](app/exchange/[market]/page.tsx)가 `COIN_META`에서 읽는다. 마켓 구성을 바꾸면 정적 페이지도 다시 빌드해야 한다.

## 화면 규칙

최소 폭은 1280px이며, 좁은 화면에서는 가로 스크롤을 허용한다. 가로 폭에 따라 모바일 배치로 바꾸지 않는다. 높이가 낮을 때의 압축과 스크롤 처리는 [레이아웃 설계](../docs/reference/contracts/데스크톱_레이아웃설계.md)를 따른다.

색상과 간격은 디자인 토큰을 사용한다. 상승·매수는 빨강, 하락·매도는 파랑이며 방향을 색만으로 표시하지 않는다. 라이트·다크 테마, 키보드 조작, 움직임 줄이기 설정도 함께 확인한다. 세부 값은 [디자인 시스템](../docs/reference/contracts/프론트_디자인시스템.md)에 있다.

관리 화면의 인증·권한·승인은 [관리자 설계](../docs/reference/product/관리자_설계.md)를 따른다. 현재는 [ADR 0019](../docs/reference/adr/0019-single-operator-approval.md)의 1인 승인 방식이다. 공개 거래 화면과 관리 화면은 배포 때 분리한다.

## 빌드와 검사

```powershell
npm test
npm run build
```

테스트는 TypeScript 테스트 코드를 컴파일한 뒤 Node 테스트 러너로 실행한다. 빌드는 [next.config.ts](next.config.ts)의 정적 export 설정에 따라 `out/`을 만든다. 결과물은 정적 서버로 제공하며 `next start`로 실행하지 않는다.

개발 서버가 실행 중인 같은 작업 폴더에서 빌드하면 `.next` 산출물이 섞일 수 있다. 개발 서버를 종료한 뒤 빌드하거나 별도 체크아웃을 사용한다.

추가 검사는 `npm run check:tokens`, `npm run check:browser`로 실행한다. 브라우저 검사에 필요한 기동 조건은 [check-browser.mjs](../scripts/check-browser.mjs)에 있다. CI가 실행하는 전체 순서는 [ci-web.yml](../.github/workflows/ci-web.yml)을 따른다.

공개 배포 전에는 관리 화면을 결과물에서 제거하고 잔존 여부를 검사한다. 아래 명령 역시 `web/`에서 실행한다.

```powershell
node ../scripts/prune-admin-bundle.mjs
node ../scripts/prune-admin-bundle.mjs --check
```

전체 정적 결과물을 그대로 공개하면 관리 화면도 함께 포함된다. 배포 절차는 [배포 안내](../deploy/README.md)와 CI 설정을 함께 확인한다.
