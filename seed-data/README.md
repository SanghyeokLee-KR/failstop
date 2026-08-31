# seed-data

시드 타임 1회 수집 스크립트. "런타임 외부 의존 0" 원칙(docs/contracts/외부API_전략.md)의 실제 구현이다.

외부 API는 여기서 단 한 번 호출한다. 결과(파생 통계 JSON, 코인 메타, self-host 로고)를 저장소에 커밋한다.
런타임에는 어떤 외부 네트워크도 참조하지 않는다. 다만 런타임이 `out/`을 읽는 배선은 아직 없다.
현재 성립 근거는 아래 소비 배선 절에 적는다.

## 흐름

```
외부 API (Upbit, CoinGecko)
   |  시드 타임 1회, 스로틀 준수
   v
seed-data/out/  (markets.json, candles/*.json, coin-meta.json, logos/*)
   |  저장소에 커밋
   v
런타임 (server 시드 적재 / web 정적 에셋)   <- 계획. 현재 미배선
```

마지막 단계만 계획이다. 앞 두 단계는 성립한다.

## 요구 사항

- Node 20 이상. 내장 fetch를 쓰므로 별도 의존 패키지가 없다.
- Upbit 수집은 API 키가 필요 없다.
- CoinGecko 수집은 Demo 키가 필요하다(무료, 카드 등록 불필요).

## 실행

Upbit 마켓·캔들 파생 통계 수집.

```
node fetch-markets.mjs
# 또는 npm run markets
```

- 기본 대상은 KRW 마켓 전체. `SEED_MARKET_QUOTE`로 통화, `SEED_MARKET_LIMIT`로 개수를 좁힌다.
- 예: 상위 5개만 샘플 수집 `SEED_MARKET_LIMIT=5 node fetch-markets.mjs`

CoinGecko 코인 메타·로고 수집.

```
COINGECKO_DEMO_KEY=발급받은키 node fetch-coin-meta.mjs
# 또는 .env.example 을 .env 로 복사해 키를 채운 뒤 npm run coin-meta
```

- 키가 없으면 안내만 출력하고 스킵한다(실패 아님).

## 산출물

```
out/
  markets.json          Upbit 마켓 인덱스 + 수집 메타(성공/실패 집계)
  candles/KRW-BTC.json  종목별 파생 통계 (시드가, 변동성, 드리프트, 유동성)
  coin-meta.json        CoinGecko 코인 카탈로그 (이름, 심볼, 로고 파일 경로)
  logos/<id>.png        self-host 로고 + ATTRIBUTION.txt
```

현재 저장소에 내재화된 범위는 `markets.json`과 `candles/*.json`(269종)이다. CoinGecko 경로
(`coin-meta.json`·로고)는 Demo 키가 있어야 수집되며 아직 미수집이라 커밋물에 없다.

캔들 파일에 담기는 것은 원본 OHLC 시계열이 아니라 파생 통계다.

- `seed_price`: 최신 종가. 시뮬레이션 초기가.
- `volatility.daily_stddev` / `annualized`: 로그수익률 표본 표준편차와 sqrt(365) 연 환산.
- `drift.daily_mean_log_return`: 로그수익률 평균.
- `liquidity`: 일 거래대금·거래량 평균.

금액·수량은 전부 문자열이다(정본 규칙: float 금지).

## 소비 배선 (미배선)

`out/`을 읽는 런타임 코드가 없다. `server` `web` `harness` `compose.yaml` `scripts` 전체에서
`seed-data`·`markets.json`·`candles/KRW` 참조가 0건이다.

현재 런타임 마켓 원천은 하드코딩 12종이고 두 곳에 각각 있다.

- `server/app/src/main/java/kr/exchange/dev/DevDataSeeder.java`
- `web/lib/sim/engine.ts` 의 SEEDS

배선 전에 매핑을 먼저 정해야 한다. 하드코딩 12종 중 `KRW-MATIC`이 `out/candles/`에 없다.
수집 시점 업비트 목록에 있는 것은 `KRW-POL`이다(MATIC이 POL로 개명). 하드코딩 목록이 수집
시점 시장과 이미 어긋나 있으므로, 소비 배선은 개명 종목 대응을 포함해야 한다.

"런타임 외부 의존 0" 원칙 자체는 성립한다. 런타임 코드에 외부 호스트 참조가 없다.
성립 근거가 `out/` 소비가 아니라 하드코딩 시드라는 점만 다르다. 이 절을 두는 이유는
설계가 문서에 있다는 이유로 완료로 판정하지 않기 때문이다(`CLAUDE.md` 2절·32절).

## 스로틀

- Upbit: 초당 10회/IP 상한. 스크립트는 요청 간 최소 150ms 간격을 강제해 약 6.7회/초에 머문다.
- CoinGecko Demo: 100 calls/min. 요청 간 최소 200ms.
- 429·5xx·네트워크 오류는 지수 백오프로 재시도하고, 종목 단위 실패는 격리해 다음으로 진행한다.

## 라이선스·어트리뷰션

ATTRIBUTION.md 참조. 요지.

- Upbit: 원본 시세 재배포 금지. 파생 통계만 저장. 비상업 교육용. 출처 표기 필요.
- CoinGecko: 어트리뷰션 필수. "Data provided by CoinGecko" + API 페이지 링크.
- TradingView Lightweight Charts: NOTICE 어트리뷰션 + tradingview.com 링크(프론트 번들 항목).
- Binance: 규제·재현성 리스크로 미사용.

## 커밋 정책

`out/` 는 .gitignore에서 제외하지 않는다. 파생 JSON과 self-host 로고는 커밋 대상이다(현재
커밋물은 markets.json·candles/*, coin-meta·로고는 수집 시 추가). 시크릿(.env)과 node_modules,
임시 로그만 무시한다.
