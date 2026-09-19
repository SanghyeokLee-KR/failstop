# 출처·라이선스 표기

시드 타임 수집 산출물의 출처를 명시한다. 화면(푸터·About)과 저장소 양쪽에 유지한다.
근거는 docs/reference/contracts/외부API_전략.md.

## Upbit Open API (시세 파생)

- 용도: 마켓 목록과 일 캔들에서 시드가·변동성·드리프트·유동성 통계를 추출.
- 원본 시세 저작권은 Upbit에 있다. 원본 OHLC 시계열은 재배포하지 않으며, 저장소에는 파생 통계만 둔다.
- 성격: 비상업 교육용. 상업적 이용·재배포·판매 아님.
- 표기 문구: "초기 시세 파라미터는 Upbit Open API 참고 (비상업 교육용)".
- 참고: https://docs.upbit.com , https://www.upbit.com/historical_data/main

## CoinGecko Demo API (코인 메타·로고)

- 용도: 코인 이름·심볼·로고 URL 수집. 로고는 내려받아 self-host.
- 어트리뷰션 필수(Demo/무료 플랜).
- 표기 문구: "Data provided by CoinGecko" + https://www.coingecko.com/en/api 링크.
- 로고 이미지는 각 코인 프로젝트의 상표일 수 있다. 교육용 시뮬레이션 한정.
- 참고: https://brand.coingecko.com/resources/attribution-guide

## TradingView Lightweight Charts (프론트 번들)

- 이 폴더에서 수집하지는 않으나, 차트 라이브러리 어트리뷰션을 함께 관리한다.
- Apache-2.0 + NOTICE 조항. 사용자에게 보이는 페이지에 TradingView 표기와 링크 필요.
- 표기 문구: "Charting by TradingView Lightweight Charts" + https://www.tradingview.com/ (또는 attributionLogo 유지).

## 미수집 항목

- Binance: 규제·재현성 리스크로 미사용(docs/reference/contracts/외부API_전략.md 3절).
