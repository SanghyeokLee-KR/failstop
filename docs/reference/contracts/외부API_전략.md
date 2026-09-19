# 외부 API·에셋 조사 보고서

2026-09-18 적용 범위: 아래의 런타임 외부 비의존은 거래용 시세·콘텐츠 범위다. [ADR 0027](../adr/0027-msp-operations-and-autoscaling.md)에 따라 별도 운영 보고 작업에서 AI와 Microsoft Teams를 호출할 수 있다. 그 호출 실패가 거래 처리나 기본 장애 알림을 막지 않도록 분리한다. 아래의 조사일·당시 수치는 과거 기록이다.

- **조사일**: 2026-07-21
- **조사 방법**: 각 항목의 공식 문서·npm 레지스트리·라이브 API 응답을 직접 확인 (아래 근거 URL 전부 조사 시점에 실제 접근하여 검증함)
- **전제**: 완전 시뮬레이션 거래소이므로 **런타임 외부 의존 0**. 외부 API는 빌드/시드 타임 1회 수집 용도로만 검토.
- **변경 이력**: 2026-07-21 스타일 정리, 내용 무변경.

---

## 1. Upbit Open API (공개 Quotation)

### 확인된 사실

- **인증 불필요**: 시세(quotation) API는 API 키 없이 호출 가능. 계정·자산 API(exchange)만 JWT 인증 필요.
- **Base URL**: `https://api.upbit.com`
- **엔드포인트 (2026-07 현재, 공식 문서에서 경로·파라미터 직접 확인)**:

| 용도 | 엔드포인트 | 주요 파라미터 |
|---|---|---|
| 페어(마켓) 목록 | `GET /v1/market/all` | `is_details`(선택, 기본 false) |
| 페어 단위 현재가 | `GET /v1/ticker` | `markets`(필수, 쉼표 구분: `KRW-BTC,KRW-ETH`) |
| 마켓 단위 현재가 | `GET /v1/ticker/all` | `quote_currencies`(필수: `KRW,BTC,USDT`) |
| 분 캔들 | `GET /v1/candles/minutes/{unit}` | `unit`∈{1,3,5,10,15,30,60,240}, `market`(필수), `to`, `count`(최대 200) |
| 일 캔들 | `GET /v1/candles/days` | `market`(필수), `to`, `count`(최대 200), `converting_price_unit` |
| 초/주/월/년 캔들 | `/v1/candles/seconds`·`weeks`·`months`·`years` (문서 목차에서 존재 확인) | 동일 패턴 |
| 호가 | `GET /v1/orderbook` | `markets`(필수), `level`(KRW 마켓만), `count`(최대 30) |
| 체결 이력 | `GET /v1/trades/ticks` | `market`(필수), `days_ago`(1~7), `to`, `count`(최대 500), `cursor` |

- **Rate limit (quotation)**: **초당 최대 10회, IP 주소 단위** 적용. `market`/`candle`/`trade`/`ticker`/`orderbook` 그룹별로 적용되며 같은 그룹 내 API는 쿼터를 공유. 잔여 횟수는 응답 헤더 `Remaining-Req`로 확인. 현재 문서에는 분당 제한은 별도 명시되어 있지 않음(초 단위 제한만 공표).
- **이용약관상 데이터 사용 제한**: "교육/포트폴리오 용도 허용"을 **명시한 조항은 존재하지 않음**. 공개된 Open API 이용약관(레거시 전문)에는 **제5조(저작권)** "서비스상에서 제공되는 모든 데이터 및 내용에 대한 저작권은 회사에 있으므로 사용자는 이를 무단으로 사용하거나 변경하여서는 안됩니다", 제6조(사용자의 의무, 사용량 제한 준수) 조항이 확인됨. 또한 업비트는 시세 데이터를 **개인적 이용 목적 중심으로 허용하고 상업적 이용·재배포·판매를 금지**하는 입장이며(Historical Market Data 안내 기준), 2025-10-30 적용된 Open API 이용약관 개정 공지가 존재함(공지 본문은 JS 렌더링으로 전문 확인 불가. 최종 제출 전 원문 확인 권장).

### 근거 URL

- https://docs.upbit.com/kr/reference/rate-limits (요청 수 제한: 초당 10회, IP 단위, 그룹별)
- https://docs.upbit.com/kr/reference/list-trading-pairs (GET /v1/market/all)
- https://docs.upbit.com/kr/reference/list-tickers (GET /v1/ticker)
- https://docs.upbit.com/kr/reference/list-quote-tickers (GET /v1/ticker/all)
- https://docs.upbit.com/kr/reference/list-candles-minutes / list-candles-days (캔들)
- https://docs.upbit.com/kr/reference/list-orderbooks (GET /v1/orderbook)
- https://docs.upbit.com/kr/reference/list-pair-trades (GET /v1/trades/ticks)
- https://static.upbit.com/terms/legacy/open_api_terms_20180621.html (이용약관 전문, 제5조 저작권)
- https://www.upbit.com/service_center/notice?id=4528 (Open API 이용약관 개정 안내)
- https://www.upbit.com/historical_data/main (시세 데이터 개인적 이용 한정 안내)

### 사용 권장안

**빌드/시드 타임 1회 수집만 사용.** 마켓 목록(`/v1/market/all`) + 종목별 일/분 캔들(`count=200` 페이지네이션)로 시드 데이터를 생성해 PostgreSQL에 적재하고, 런타임에는 절대 호출하지 않는다. 초당 10회 제한이 있으므로 시드 스크립트에 100ms 이상 간격의 스로틀을 넣으면 안전. 데이터 저작권이 업비트에 있으므로 **수집한 원본 시세를 그대로 재배포·전시하지 말고, 시뮬레이션 엔진의 초기값/통계 파라미터(변동성 등) 추출 용도로 가공 사용**하는 것을 권장. 보고서·README에 "초기 데이터는 Upbit Open API 참고, 비상업 교육용" 명시.

---

## 2. CoinGecko API (무료 Demo 플랜)

### 확인된 사실

- **API 키 필요**: Demo(무료) 플랜도 **API 키 발급 필수**. Developer Dashboard에서 "+ Add New Key"로 발급. 인증은 헤더 `x-cg-demo-api-key`(권장) 또는 쿼리 `x_cg_demo_api_key`.
- **Demo 플랜 Base URL**: `https://api.coingecko.com/api/v3/` (유료는 `pro-api.coingecko.com`)
- **Rate limit / 한도 (2026-07 요금 페이지 기준)**: **100 calls/min, 월 10,000 calls, 무료(카드 등록 불필요)**. 데이터 신선도 60초. (유료 Basic: $35/mo, 300 calls/min, 월 100k)
- **로고 이미지 획득**: `GET /coins/markets?vs_currency=usd&...` 응답의 `image` 필드에 로고 URL 포함 (예: `https://coin-images.coingecko.com/coins/images/1/large/bitcoin.png`). `vs_currency`는 필수 파라미터.
- **어트리뷰션**: Demo/무료 사용 시 **어트리뷰션 필수**("Attribution required"). 유료 상업 플랜 기준 문구는 "Data provided by CoinGecko" + https://www.coingecko.com/en/api 하이퍼링크를 데이터 표시 위치 근처에 표기. 공식 Attribution Guide가 별도로 제공됨. **코인 로고 이미지 자체에 대한 별도 라이선스 조항은 문서에서 확인되지 않음**. 로고는 각 코인 프로젝트의 상표일 수 있으므로, 교육용 시뮬레이션이라도 CoinGecko 출처 표기를 붙이는 것이 안전.

### 근거 URL

- https://docs.coingecko.com/docs/setting-up-your-api-key (키 발급)
- https://docs.coingecko.com/demo/reference/authentication (Demo base URL, `x-cg-demo-api-key`)
- https://docs.coingecko.com/docs/common-errors-rate-limit (Demo 100 calls/min)
- https://www.coingecko.com/en/api/pricing (월 10,000 calls, 100/min, Attribution required)
- https://docs.coingecko.com/reference/coins-markets (`/coins/markets`, `vs_currency` 필수, `image` 필드)
- https://www.coingecko.com/en/api_terms (API 이용약관)
- https://brand.coingecko.com/resources/attribution-guide (어트리뷰션 가이드)

### 사용 권장안

**빌드/시드 타임 1회 수집.** `/coins/markets?vs_currency=krw&per_page=250` 1~2회 호출로 상위 코인의 이름·심볼·로고 URL을 받고, **로고 이미지는 다운로드하여 `public/` 또는 DB에 self-host**(런타임에 coin-images.coingecko.com을 참조하면 외부 의존이 생기므로 금지). 월 10,000콜 한도 대비 수십 콜 수준이라 여유가 크다. 푸터 또는 About 페이지에 "Logo/metadata sourced from CoinGecko" 표기 권장.

---

## 3. Binance 공개 시세 API

### 확인된 사실

- **인증**: `/api/v3/depth`, `/api/v3/klines`, `/api/v3/ticker/24hr`, `/api/v3/ticker/price` 등 market data 엔드포인트는 Security Type **NONE, API 키 불필요**.
- **Base URL**: `https://api.binance.com` (공개 시세 전용으로는 `https://data-api.binance.vision` 사용을 공식 권장).
- **Rate limit (weight 체계)**: 엔드포인트마다 weight가 부여되고 IP 단위로 합산. **라이브 `/api/v3/exchangeInfo` 응답의 `rateLimits`로 직접 확인한 현재 한도: REQUEST_WEIGHT = 6,000 / 1분 / IP**. 초과 시 429, 반복 위반 시 418(IP 자동 차단, 2분~3일). 사용량은 `X-MBX-USED-WEIGHT-*` 헤더로 확인. 예시 weight: `klines` 2, `ticker/price` 2~4, `ticker/24hr` 1~80(파라미터에 따라), `depth` 5~250(limit에 따라).
- **한국 접근성 이슈**: 바이낸스는 한국을 지원 국가로 명시하지 않으며 **FIU(금융정보분석원) 미신고 VASP**. 2025-03 FIU가 미신고 해외거래소 17곳 앱 차단을 요청했고, 구글플레이 정책 변경으로 **2026-01-28부터 한국에서 미신고 거래소 앱 신규 설치·업데이트 차단** 수순(바이낸스 포함). 다만 2026-02 언론 보도 기준 웹 접속·기존 앱은 동작 중이며, `api.binance.com`에 대한 국가 차원의 IP 차단이 공식화된 근거는 확인되지 않음. 즉 **기술적으로는 현재 접근 가능하나 규제 방향상 지속 가능성이 불확실**.

### 근거 URL

- https://developers.binance.com/docs/binance-spot-api-docs/rest-api/general-api-information (base URL, weight 체계, data-api.binance.vision)
- https://developers.binance.com/docs/binance-spot-api-docs/rest-api/market-data-endpoints (엔드포인트별 weight, 인증 불필요)
- https://api.binance.com/api/v3/exchangeInfo?symbol=BTCUSDT (라이브 응답: REQUEST_WEIGHT 6000/분/IP)
- https://www.fsc.go.kr/no010101/85060 , https://www.fsc.go.kr/no010101/84237 (금융위/FIU 보도자료)
- https://www.mt.co.kr/stock/2025/03/26/2025032616073587057 (미신고 17곳 앱 차단)
- https://zdnet.co.kr/view/?no=20260115093947 (구글플레이의 미신고 가상자산 앱 차단, 바이낸스 포함)

### 사용 권장안

**미사용 권장.** 우리 서비스는 KRW 마켓 시뮬레이션이므로 Upbit 데이터로 충분하고, 바이낸스는 (1) USDT 마켓 중심이라 환율 변환이 추가로 필요하고 (2) 한국 규제 환경상 접근 지속성이 불확실하다. 캡스톤 평가 환경(학교망 등)에서 재현성이 깨질 위험을 감수할 이유가 없다. 굳이 쓴다면 USDT 마켓 캔들 보조 수집 용도로 `data-api.binance.vision`을 빌드 타임에만 사용.

---

## 4. Pretendard 폰트

### 확인된 사실

- **최신 버전**: **v1.3.9** (GitHub 릴리스와 npm `latest`가 일치, 2023-11 이후 안정 유지)
- **npm 패키지명**: `pretendard` (npm 레지스트리 `license` 필드: **OFL-1.1**)
- **라이선스**: **SIL Open Font License 1.1**. "글꼴 단독 판매를 제외한 모든 상업적 행위 및 수정, 재배포가 가능" (공식 README). 웹폰트 self-host, 서비스 번들링 모두 허용.
- **서브셋 제공**: 정적 서브셋 woff2, 다이나믹 서브셋, **가변(variable) 다이나믹 서브셋**(최소 용량) 모두 공식 제공. npm 패키지 안에 `dist/web/static`, `dist/web/variable` 등의 woff2와 CSS가 포함되어 있어 **네트워크 없이 완전 self-host 가능**. README에 Next.js 로컬 폰트(`next/font/local`) 사용법도 안내됨.

### 근거 URL

- https://github.com/orioncactus/pretendard (버전, 라이선스, 서브셋·self-host 안내)
- https://registry.npmjs.org/pretendard/latest (name: pretendard, version: 1.3.9, license: OFL-1.1)

### 사용 권장안

**빌드 타임 번들(설치형).** `npm i pretendard` 후 가변 서브셋 woff2를 `next/font/local`로 로드. CDN(jsDelivr 등) 링크는 런타임 외부 의존이 되므로 **사용 금지**. 라이선스 고지(OFL 텍스트)는 저장소에 포함.

---

## 5. lightweight-charts (TradingView)

### 확인된 사실

- **현재 버전**: **v5 (npm latest = 5.2.0)**, 패키지명 `lightweight-charts`, npm `license` 필드 **Apache-2.0**.
- **어트리뷰션 요구**: Apache-2.0이지만 **NOTICE 조항에 따른 표기 의무가 명시**되어 있음. 공식 README 원문: "This license requires specifying TradingView as the product creator. You shall add the 'attribution notice' from the NOTICE file and a link to https://www.tradingview.com/ to the page of your website or mobile application that is available to your users." 즉 **사용자에게 보이는 페이지에 NOTICE 어트리뷰션 + tradingview.com 링크 필수**. v5부터는 차트 위 로고 표시용 `attributionLogo` 옵션이 제공되며(기본 활성), 이를 유지하는 것이 가장 간단한 준수 방법.

### 근거 URL

- https://github.com/tradingview/lightweight-charts (README 라이선스·어트리뷰션 문구, v5)
- https://registry.npmjs.org/lightweight-charts/latest (version: 5.2.0, license: Apache-2.0)

### 사용 권장안

**사용 (프론트 번들, 외부 의존 아님).** npm으로 설치해 번들에 포함되므로 런타임 외부 요청 없음. **`attributionLogo`를 끄지 말고 유지**하거나, 차트 페이지 하단에 "Charting by TradingView Lightweight Charts" + 링크를 표기하여 라이선스 준수.

---

## 6. Lucide 아이콘

### 확인된 사실

- **npm 패키지**: `lucide-react`. npm 레지스트리 `latest` 기준 **version 1.25.0, license ISC**.
- **라이선스**: 본체 **ISC**(저작권 고지 유지 조건의 허용적 라이선스). Feather Icons에서 파생된 약 120개 아이콘에는 **MIT**(Cole Bemis 저작권 고지)가 함께 적용. 상업/비상업 사용·수정·배포 모두 자유, 별도 어트리뷰션 UI 표기 의무 없음(라이선스 파일 포함이면 충분).

### 근거 URL

- https://registry.npmjs.org/lucide-react/latest (version 1.25.0, license: ISC)
- https://github.com/lucide-icons/lucide/blob/main/LICENSE (ISC + Feather 파생분 MIT)

### 사용 권장안

**사용 (프론트 번들).** 트리 셰이킹되는 named import(`import { ArrowUp } from "lucide-react"`)로 사용. 외부 요청 없음, 라이선스 부담 최소.

---

## 최종 권장 구성 ("런타임 외부 의존 0" 원칙)

| 항목 | 사용 방식 | 시점 | 런타임 외부 의존 | 준수 사항 |
|---|---|---|---|---|
| Upbit Quotation API | 시드 스크립트로 마켓 목록·캔들 수집 → PostgreSQL 적재, 시뮬레이션 초기값/파라미터로 가공 | **시드 타임 1회** | 없음 | 초당 10회/IP 스로틀, 원본 시세 재배포 금지, 출처·비상업 교육용 명시 |
| CoinGecko Demo API | `/coins/markets`로 이름·심볼·로고 URL 수집, **로고 이미지는 다운로드해 self-host** | **빌드/시드 타임 1회** | 없음 | Demo API 키 발급, "Data from CoinGecko" 어트리뷰션 표기 |
| Binance Public API | 없음 | **미사용** | 없음 | 규제·재현성 리스크로 배제 (필요 시 data-api.binance.vision 빌드 타임 한정) |
| Pretendard | npm 설치 후 가변 서브셋 woff2를 `next/font/local`로 self-host | 빌드 타임 (번들) | 없음 | OFL-1.1 라이선스 파일 포함, CDN 링크 금지 |
| lightweight-charts | npm 5.2.0 번들 | 빌드 타임 (번들) | 없음 | `attributionLogo` 유지 또는 NOTICE + tradingview.com 링크 표기 |
| lucide-react | npm 1.25.0 번들, named import | 빌드 타임 (번들) | 없음 | 저장소에 ISC/MIT 고지 유지 |

**요약**: 런타임에 외부 네트워크를 참조하는 구성 요소는 0개다. 외부 API 2종(Upbit, CoinGecko)은 시드/빌드 타임 1회 수집 후 결과물(시세 시드 데이터, 코인 메타데이터, 로고 파일)을 저장소·DB에 내재화하고, 프론트 에셋 3종(Pretendard, lightweight-charts, lucide-react)은 전부 npm 번들로 self-host한다. 법적 리스크 관리 포인트는 (1) Upbit 시세 원본 재배포 금지(가공·교육용 한정), (2) CoinGecko 어트리뷰션, (3) TradingView 어트리뷰션 3가지이며, 모두 화면·문서 내 출처 표기로 해결 가능하다.
