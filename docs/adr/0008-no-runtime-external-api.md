# ADR 0008. 런타임 외부 API 의존을 0으로 두고 외부 데이터는 시드·빌드 타임에만 수집한다

- 상태: 채택
- 날짜: 2026-07-22
- 결정자: 아키텍처 담당(정본 문서 관리자)

## 배경

완전 시뮬레이션 거래소라 실거래 체결은 자체 엔진이 만든다. 그러나 초기 시세, 코인 메타데이터, 폰트, 차트 라이브러리, 아이콘은 외부에서 와야 한다. 이 다섯 갈래를 런타임에 참조할지 빌드·시드 타임에 내재화할지 결정이 필요했다.

2026-07-21 후보 6종을 공식 문서·npm 레지스트리·라이브 응답으로 직접 조사했다(`docs/외부API_전략.md:3-4`). 확인된 한도는 다음과 같다.

- Upbit Quotation: 초당 10회, IP 단위, 엔드포인트 그룹별 쿼터 공유(`docs/외부API_전략.md:29`).
- CoinGecko Demo: 100 calls/min, 월 10,000 calls, 데이터 신선도 60초(`docs/외부API_전략.md:57`).
- Binance: REQUEST_WEIGHT 6,000/분/IP. 초과 시 429, 반복 위반 시 418로 IP 자동 차단 2분~3일(`docs/외부API_전략.md:83`).

한도 단위는 갈린다. Upbit(`docs/외부API_전략.md:29`)와 Binance(`docs/외부API_전략.md:83`)는 IP 단위이고, CoinGecko Demo는 발급 키 인증이라 키 단위다(`docs/외부API_전략.md:55`). 런타임 호출 경로를 두면 캡스톤 심사 환경(학교망 등 공유 IP)에서 Upbit 초당 10회를 다른 사용자 트래픽과 나눠 쓰게 된다. 차단 지속 시간이 문서로 확인된 것은 Binance 418(2분~3일)뿐이고, 채택 대상 2종에는 해당 서술이 없다.

법적 제약도 걸려 있다. Upbit Open API 이용약관 제5조(저작권)는 서비스상 제공 데이터의 무단 사용·변경을 금지한다(`docs/외부API_전략.md:30`). CoinGecko Demo는 어트리뷰션이 필수다(`docs/외부API_전략.md:59`). lightweight-charts는 Apache-2.0이지만 NOTICE 어트리뷰션과 tradingview.com 링크를 사용자에게 보이는 페이지에 요구한다(`docs/외부API_전략.md:126`).

## 결정

런타임에 외부 네트워크를 참조하는 구성 요소를 0으로 둔다.

- Upbit Quotation과 CoinGecko Demo는 시드·빌드 타임 1회 수집으로만 쓴다. 결과물(시세 시드, 코인 메타, 로고 파일)은 저장소·DB에 내재화한다.
- 코인 로고는 다운로드해 self-host한다. 런타임 `coin-images.coingecko.com` 참조를 금지한다(`docs/외부API_전략.md:73`).
- Pretendard·차트 라이브러리·아이콘은 npm 번들로 self-host한다(`docs/외부API_전략.md:117`, `:135`, `:153`). CDN 링크 금지 문구는 Pretendard 항목에만 있다(`docs/외부API_전략.md:117`).
- Binance는 쓰지 않는다.
- 수집한 원본 시세를 그대로 재배포·전시하지 않는다. 시뮬레이션 초기값·통계 파라미터(변동성 등) 추출 용도로만 가공 사용한다(`docs/외부API_전략.md:47`).

이 결정을 `CLAUDE.md:63` 핵심 불변 규칙 한 줄로 승격한다. "런타임 외부 API 의존 금지. 외부 데이터는 시드 타임에만 수집한다."

## 근거

- 재현성이 결정 기준이다. 시드 내재화 후에는 네트워크가 끊긴 환경에서도 화면이 같은 값을 낸다. 런타임 호출은 심사 시점의 외부 상태를 데모 결과에 끌어들인다.
- IP 단위 한도가 런타임 경로에서 사라진다. 시드 스크립트는 100ms 이상 스로틀로 초당 10회 제한을 지킬 수 있고(`docs/외부API_전략.md:47`), 실패해도 재실행하면 그만이다. 런타임 실패는 재실행 대상이 아니다.
- 저작권 노출면이 좁아진다. 원본 시세를 전시하지 않고 파라미터만 뽑으면 제5조 저촉 여지가 줄어든다.
- 라이선스가 self-host를 이미 허용한다. Pretendard OFL-1.1(`docs/외부API_전략.md:107`), lightweight-charts Apache-2.0(`docs/외부API_전략.md:125`), lucide-react ISC(`docs/외부API_전략.md:143`). 번들 대신 CDN을 택할 라이선스상 이유가 없다.
- 프론트가 Next.js 정적 export라 런타임 서버 로직이 없다(`web/next.config.ts:8`, `output: "export"`). 외부 호출을 두려면 프록시 계층을 새로 만들어야 하고, 그 계층이 곧 새 장애점이 된다.

## 대안

- 런타임에 Upbit Quotation을 직접 호출해 실시세를 표시한다. 기각. IP 단위 초당 10회 한도(`docs/외부API_전략.md:29`)를 다중 클라이언트가 공유하고, 브라우저에서 직접 호출하면 CORS와 키 없는 공개 호출의 남용 책임이 남는다. 시뮬레이션 거래소의 체결 가격과 실시세가 어긋나 화면 정합도 깨진다.
- Binance 공개 시세를 보조로 채택한다. 기각. USDT 마켓 중심이라 KRW 환산이 추가로 필요하고, FIU 미신고 VASP로 2025-03 미신고 해외거래소 17곳 앱 차단 요청, 2026-01-28부터 구글플레이 신규 설치·업데이트 차단 수순에 포함됐다(`docs/외부API_전략.md:84`). 기술적으로는 조사 시점에 접근 가능했으나 규제 방향상 지속성이 불확실해 평가 환경의 재현성을 걸 이유가 없다(`docs/외부API_전략.md:97`).
- 폰트·차트를 jsDelivr 등 CDN에서 로드한다. 기각. 번들 대비 이득은 초기 빌드 용량뿐이고, 대가로 런타임 외부 의존이 생긴다. 정적 export 산출물이 자기완결성을 잃는다.
- CoinGecko 응답의 `image` URL을 그대로 렌더한다(`docs/외부API_전략.md:58`). 기각. 런타임 외부 호스트 의존이 그대로 남고, 링크가 바뀌면 화면이 깨진다.
- 수집한 원본 시세를 그대로 재배포·전시한다. 기각. 이용약관 제5조 저작권 조항에 정면으로 걸린다.

## 영향

2026-08-30 기준 구현 대조. 확인 명령을 함께 남긴다.

- `web/package.json:13-22` 의존 8종 전부 번들 대상이고 외부 호스트 참조가 없다. `grep -rn "<img\|next/image\|https://" web/components web/app --include=*.tsx` 결과 0건. `grep -rn "coin-images\|coingecko" web/ server/ scripts/` 결과 0건.
- 서버의 HTTP 클라이언트는 1개다. `grep -rnE "HttpClient|RestTemplate|WebClient|OkHttpClient|HttpURLConnection|URLConnection" server/app/src/main/java --include=*.java` 결과 2행이 전부 `BotApiClient.java`다(import 9행, 생성 `server/app/src/main/java/kr/exchange/sim/BotApiClient.java:29`). 대상은 자기 게이트웨이다. baseUrl은 생성자 주입이고(`server/app/src/main/java/kr/exchange/sim/BotApiClient.java:38-39`) 주입값은 `server/app/src/main/resources/application.yml:207`의 `base-url: http://localhost:8080`이다. 외부 호스트가 아니다.
- 로고 self-host 조항은 결과적으로 실행되지 않았다. `web/public` 디렉터리가 없고, 이미지 에셋은 앱 라우터 파비콘 `web/app/icon.svg` 1건뿐이다. 확인 명령은 `find web -not -path "*/node_modules/*" -not -path "*/.next/*" -not -path "*/out/*" -type f \( -name "*.png" -o -name "*.svg" -o -name "*.jpg" -o -name "*.jpeg" -o -name "*.webp" -o -name "*.ico" -o -name "*.gif" -o -name "*.avif" \)`이고 결과는 1건이다. 그 1건은 `rect` 1개와 `path` 2개짜리 32x32 SVG이고 외부 호스트를 참조하지 않는다. 자체 브랜드 마크로 194f859가 추가한 파비콘이라 외부 수집물이 아니다. 코인 로고 에셋은 0건이다. 코인 식별은 브랜드색 모노그램 칩으로 대체됐다(`web/components/SymbolChip.tsx:2-4`, 시드 12종은 `web/lib/sim/engine.ts:70-81`). 결정보다 강한 상태(외부 수집 이미지 에셋 0)에 도달했으므로 조항은 유지하되 로고 수집 자체가 불필요해졌다.
- 정본 문서와 코드가 어긋난 지점 3건이 남았다. 문서를 먼저 고쳐야 한다.
  - Pretendard는 `next/font/local` 권장(`docs/외부API_전략.md:117`)과 달리 패키지 CSS 직접 import로 적재된다(`web/app/layout.tsx:2`). CDN 미사용 조건은 충족하나 방식이 다르다.
  - klinecharts 9.8.12(`web/package.json:15`)가 7dd0f2d로 추가됐다. 커밋 본문이 "외부 CDN 0"을 명시한다. 조사 문서가 2026-07-21자라 최종 권장 구성 표(`docs/외부API_전략.md:159-166`)에 이 항목이 없다.
  - lucide-react는 문서 §6이 사용을 권장했으나(`docs/외부API_전략.md:153`) `web/package.json` 의존성에 없고 `web/node_modules`에도 설치돼 있지 않다. 미채택 상태다.
- 비용. 시세가 시드 상수라 실시세와 계속 벌어진다. 갱신하려면 시드 재수집과 재빌드가 필요하고, 자동 갱신 경로는 설계상 존재하지 않는다.
- 강제 수단이 없다. `grep -rn "cdn\|외부\|external" .github/workflows/*.yml` 결과 0건이고 `scripts/check-browser.mjs`에도 네트워크 요청 검사가 없다. 현재 이 불변식을 지키는 것은 `CLAUDE.md:63` 규칙과 리뷰뿐이다. 외부 호스트 참조를 잡는 게이트 추가가 후속 과제다.
- 되돌리려면. `CLAUDE.md:63` 삭제, `docs/외부API_전략.md` 최종 권장 구성 표 개정, 그리고 런타임 호출 경로 신설이 필요하다. 정적 export라 프록시 계층부터 만들어야 하고 키 보관·한도 처리·장애 폴백·캐시가 함께 따라온다. 되돌리기 비용이 도입 비용보다 크다.
