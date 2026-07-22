# 고가용성 가상 암호화폐 거래소, 프론트엔드 웹

캡스톤 프로젝트의 고정형 데스크톱 전용 거래소 웹. 완전 시뮬레이션으로 동작하며 런타임 외부 API 의존이 없다(시세·호가·체결·주문 전부 클라이언트 시뮬레이터가 생성).

## 화면 정책 (고정형 데스크톱 전용)

docs/데스크톱_레이아웃설계.md §4가 정본.

- 최소 지원 폭 1280px. body에 `min-width: 1280px`, 더 좁은 뷰포트는 레이아웃을 접지 않고 가로 스크롤로 열람만 가능하다.
- 반응형 리플로우 없음, 모바일 미지원. 가로 미디어 쿼리를 두지 않는다.
- 콘텐츠 최대 폭 1920px 중앙 정렬. 초광폭 모니터는 좌우 여백만 확장.
- 세로만 높이 브레이크포인트를 둔다. `max-height: 820px` 압축 스펙, `max-height: 640px` 무스크롤 해제(페이지 스크롤 허용).
- 접속 차단이나 안내 배너는 두지 않는다(업비트 동형).

## 실행

```bash
npm install
npm run dev     # http://localhost:3000
npm run build   # 타입 검사 포함, out/ 정적 산출물 생성
```

`output: "export"` 설정이라 `next start`는 사용하지 않는다. 빌드 결과 `out/`을 정적 서버로 서빙한다.

시뮬레이터 상태(주문·자산)는 인메모리. SPA 내비게이션에서는 유지되고 전체 페이지 리로드 시 초기화된다.

## 라우트

| 라우트 | 화면 | 실시간 채널 |
|---|---|---|
| `/` | `/home`으로 클라이언트 리다이렉트 | 없음 |
| `/home` | 홈 대시보드(시장 개관 랜딩). 요약 스트립·순위 테이블·대표 코인·관심 사이드바 | `ticker`(전 마켓) |
| `/welcome` | 온보딩. 뷰포트 중앙 히어로 + CTA | 없음 |
| `/exchange` | 최근 마켓(없으면 KRW-BTC)으로 리다이렉트 | 없음 |
| `/exchange/[market]` | 거래 화면. 차트·호가·체결·주문·마켓 목록 동시 노출 | `ticker`+`orderbook`+`trade`+`myOrder` |
| `/assets` | 자산. 요약 카드·보유 테이블·비중 카드 | `ticker` |
| `/history` | 거래내역. 미체결/체결 필터 테이블 | `myOrder` |
| `/system` | 구 공개 시스템 상태. 관리 평면 이관 안내로 대체(내부 지표 미노출) | 없음 |
| `/market`, `/coin/[market]`, `/order/[market]` | 구 경로. `/exchange`로 클라이언트 리다이렉트(딥링크 보존, `/order`는 side·price 쿼리 승계) | 없음 |

관리 평면(`/admin`, 거래 UI와 분리된 AdminLayout · 공개 HeaderNav 미사용). 정본 `../docs/관리자_설계.md`.

| 라우트 | 화면 | 권한·승인 |
|---|---|---|
| `/admin` | 종합 상태 요약(헬스·불변식·RTO·봇·카오스·승인). 전역 승인 대기 | VIEWER |
| `/admin/monitoring` | 불변식 7종·복구 RTO 추이·노드·지표. 읽기 전용 | VIEWER |
| `/admin/bots` | 봇 플릿 start/stop/pause·OPS·생성(BOT_OPS), 파라미터 변경(2인 승인) | BOT_OPS |
| `/admin/chaos` | E1~E9 카탈로그 주입·해제(전부 2인 승인) | CHAOS_OPS |
| `/admin/config` | 마켓 halt/resume(SUPER·2인 승인), 수수료 조회, 공지 등록(SUPER) | SUPER |
| `/admin/operators` | 운영자·역할 관리(SUPER·2인 승인) | SUPER |
| `/admin/audit` | append-only 감사 로그 조회 | VIEWER |

- 진입: mTLS 클라이언트 인증서 제시 → 신원(CN) 유래 단기 세션 확립. 인증서 없으면 접근 불가 안내(§1-3). 데모는 `op-01`·`op-02` 인증서로 2인 승인을 시연한다(상단 바 인증서 전환).
- 파괴 액션 흐름: 요청(요구 권한 보유 운영자) → 다른 운영자 승인(반결탁, 요청자 자기승인 차단) → 요청자 실행(단회 소각). 서버·UI 이중 강제.
- 색 도메인: 관리 평면은 헬스 색(success/warning/danger)만 쓰고 시세색(up/down)을 차용하지 않는다. 상태는 색 단독 금지, 아이콘·라벨 병기.
- 시스템 내부 상태는 관리 평면으로 이관했다. 공개 HeaderNav의 "시스템 상태" 링크를 제거하고 `/admin/*`에서는 HeaderNav를 렌더하지 않는다.

홈 대시보드 상세는 ../docs/대시보드_설계.md. HeaderNav 첫 항목·로고가 `/home`을 가리킨다.

동적 라우트는 `generateStaticParams`로 마켓 12종(KRW-BTC, ETH, SOL, XRP, DOGE, ADA, TRX, LINK, DOT, MATIC, AVAX, ATOM)을 사전 생성한다. 뒤 6종은 대시보드 순위·집계를 채우기 위한 sim 전용 확장이며 백엔드와 무관하다(http 모드는 백엔드 마켓만 노출). 마켓 추가는 `lib/sim/engine.ts`의 `SEEDS` 수정으로 끝난다.

## 그리드 요약

거래 화면(`/exchange/[market]`, 설계서 §2).

```
grid-template-columns: minmax(0, 1fr) 300px 320px;
grid-template-rows: 64px minmax(380px, 1fr) 260px;
grid-template-areas:
  "summary summary side"
  "chart   book    side"
  "trades  book    side";
```

- HeaderNav 56px sticky 아래 `calc(100dvh - 56px)` 높이, gap 12px, 패딩 16px.
- side 열은 OrderPanel(auto) + MarketListPanel(minmax(160px, 1fr)) 세로 2분할.
- 821px 이상 무스크롤. 641~820px 압축 스펙(요약 48, 차트 min 300, 체결 180). 640px 이하 페이지 스크롤 허용.

서브 화면(`/assets` `/history` `/system`, 설계서 §3)은 `max-width: 1200px` 중앙 컨테이너, 세로 스크롤 허용. 자산은 `1fr 320px` 2열, 시스템 서비스 카드는 `repeat(3, 1fr)` 고정 3열.

## 정적 배포

- Nginx 예시.

  ```nginx
  location / {
    root /var/www/exchange/out;
    try_files $uri $uri.html /index.html;
  }
  ```

- Spring 서빙: `out/` 내용을 `src/main/resources/static/`에 복사하면 별도 설정 없이 동작한다.

## 구조

```
web/
├─ app/                  # App Router 페이지
│  ├─ globals.css        # 리셋 + min-width 1280 + 전역 리터럴 클래스(.price-cell, .status-pill 등)
│  └─ <route>/page.tsx + page.module.css
├─ components/
│  ├─ HeaderNav / PanelCard / DataTable / SegmentedTab / ListRow / Toast / Skeleton
│  ├─ PricePill(A-6) / TickCell(A-5 틱 플래시) / CandleChart(lightweight-charts, 대시보드 미니 전용)
│  ├─ chart/             # 메인 TA 차트(klinecharts). TradingChart·ChartToolbar·chartTheme·useChartTheme·candleStream·indicators·drawingTools
│  ├─ trade/             # 거래 화면 패널(TradeLayout·SummaryBar·ChartPanel·OrderbookPanel·TradesPanel·OrderPanel·MarketListPanel)
│  ├─ dashboard/         # 홈 대시보드 패널(MarketSummaryStrip·RankingTable·SpotlightPanel·WatchlistSidebar·Sparkline)
│  ├─ admin/             # 관리 평면 셸·조각(AdminLayout·AdminSidebar·OperatorBar·AdminLogin·RoleBadge·RequestButton·ApprovalList·ui + admin.module.css)
│  └─ SymbolChip / ThemeToggle
├─ lib/
│  ├─ types.ts           # 백엔드 API 계약(docs/백엔드_API_설계.md) 타입·채널 스키마 미러 + dest() 헬퍼
│  ├─ api/datasource.ts  # 고객 평면 DataSource 인터페이스 + SimDataSource + getDataSource() 싱글턴
│  ├─ admin/             # 관리 평면 계약. api.ts(AdminApi)+simAdmin·httpAdmin+types·roles·catalog·store. 고객 평면과 분리
│  ├─ sim/engine.ts      # 클라이언트 시뮬레이터(GBM 가격 모델, 호가/체결/주문 매칭)
│  ├─ store.ts           # zustand 스토어 + useLiveChannels 구독 훅
│  └─ format.ts          # Intl.NumberFormat('ko-KR') 포매터
├─ styles/tokens.css     # 디자인 계약 §1~5. 글자 그대로 소비, 수정 금지
└─ docs 참조: ../docs/프론트_디자인시스템.md, ../docs/데스크톱_레이아웃설계.md
```

의존성: `next` `react` `react-dom` `typescript` `pretendard`(self-host) `lightweight-charts` `klinecharts` `zustand`. 이외 추가 금지.

차트 이원화(설계 `../docs/차트_기술적분석_설계.md`). 메인 거래 화면 차트는 `klinecharts`(지표·그리기 내장, 순수 canvas, 런타임 외부 호출 0)로 `components/chart/TradingChart`에서 렌더하고 `ChartPanel`이 `next/dynamic` `ssr:false`로 로드해 코드 스플릿한다. 대시보드 미니 캔들·스팟라이트는 기존 `lightweight-charts`(`CandleChart`)를 유지한다. 두 라이브러리는 화면별로 격리돼 서로의 청크를 내려받지 않는다. 캔들은 지금 체결(trade) 합성 폴백으로 생성하며 백엔드 캔들 채널이 준비되면 `candleStream.ts` 내부 교체로 승격한다.

## 디자인 계약 요약 (../docs/프론트_디자인시스템.md)

- 상승/매수 = Red, 하락/매도 = Blue(한국 관례, 반전 금지). 방향은 색 단독 금지, 항상 색 + 부호(+/−) + 화살표(▲▼) 병기.
- green은 시스템 성공 전용(체결완료·헬스 정상). 가격 방향 사용 금지. 시스템 장애는 `--color-danger`(crimson, up-red와 hue 분리).
- 색·간격·라운드·모션은 `styles/tokens.css` 변수로만 소비. 하드코딩 hex 금지. 유일한 예외는 코인 브랜드 로고색 등 데이터성 색상(`lib/sim/engine.ts`의 `brandColor` + 심볼칩 라벨 쌍).
- 방향색 단계: 히어로(24px 이상/700) `--color-up/down`(500), 소형 텍스트 `--color-up/down-text`(600), 틴트(`-bg`) 위 `-strong`(700). 시스템 시맨틱 소형 텍스트는 `-text`(success=green-700, warning=orange-800).
- sunken 규칙(§9 v1.3): `--surface-sunken` 트랙/칩 위 정보 라벨은 `--text-secondary` 금지, `--text-default` 사용. `--text-tertiary`는 장식/비활성 전용.
- 숫자: `tabular-nums`(`.num`/`.price-cell`) + `Intl.NumberFormat('ko-KR')` + 컨테이너 폭 고정으로 실시간 갱신 레이아웃 시프트 0.
- 테마: 라이트/다크 지원. `[data-theme]` 속성 + `prefers-color-scheme` 초기값. 차트는 `getComputedStyle`로 토큰 주입, 테마 변경 시 `applyOptions` 재적용.
- 모션/접근성: 틱 플래시는 셀별 300ms throttle. `prefers-reduced-motion` 시 CSS 킬스위치 + JS `matchMedia` 스킵으로 완전 비활성. 탭 타깃 44px 이상, Toast `aria-live="polite"`, IconButton `aria-label` 필수.
- 오리지널리티: 특정 서비스 상표/로고/브랜드명 미사용. 자체 토큰으로만 아이덴티티를 정의한다.

## DataSource 교체 가이드 (Spring 백엔드 연동 시)

모든 화면은 `lib/api/datasource.ts`의 `DataSource` 인터페이스만 소비한다. 백엔드가 준비되면 `SimDataSource`를 REST/STOMP 어댑터로 교체한다. 화면·스토어 코드 수정 불필요.

1. 어댑터 구현: `lib/api/http-stomp.ts`(신규)에 `class HttpStompDataSource implements DataSource`.
   - REST 메서드(`getMarkets`/`getTicker`/`getOrderbook`/`getTrades`/`getCandles`/`getAccounts`/`getOpenOrders`/`getClosedOrders`/`placeOrder`/`cancelOrder`)는 `fetch`로 `docs/백엔드_API_설계.md` §4 엔드포인트 호출. 응답 타입은 `lib/types.ts`가 계약을 미러하므로 그대로 사용.
   - `subscribe(destination, cb)`는 STOMP 클라이언트(`@stomp/stompjs` 추가 필요)로 동일 destination(`lib/types.ts`의 `dest` 헬퍼가 §5-3 경로 생성)을 구독하고 unsubscribe 함수를 반환. 구독 즉시 snapshot 1건 수신(§5-2) 계약을 서버가 보장해야 화면 로딩 로직이 그대로 동작한다.
   - `onConnectionChange(cb)`는 STOMP 세션 연결/단절을 통지. HeaderNav 연결 점의 데이터 소스.
   - `connect`/`disconnect`는 STOMP 세션 수립/해제. orderbook은 `seq` gap 감지 시 `getOrderbook` 재스냅샷.
2. 싱글턴 전환: `getDataSource()`에서 환경 변수 분기.
   ```ts
   singleton = process.env.NEXT_PUBLIC_DATA_SOURCE === "live"
     ? new HttpStompDataSource(baseUrl, wsUrl)
     : new SimDataSource(new SimEngine());
   ```
3. 주의점: 시뮬레이터는 `myOrder` 채널로 주문 전이(접수, 부분체결, 체결/취소)를 push한다. 실서버도 동일 채널 스키마(§5)로 push해야 주문 패널·거래내역의 낙관 갱신·Toast가 그대로 동작한다. 수수료·최소주문금액(5,000원) 정책 값은 서버 응답 기준으로 치환한다.
