package kr.exchange.sim;

/**
 * 플릿 논리 봇 유형(API §6-A 어휘 MARKET_MAKER|TAKER|TREND|NOISE).
 * 코드 경로는 둘뿐이다(MarketMakerBot·TakerBot). TREND·NOISE는 신규 클래스가 아니라
 * 테이커 확신 게인 프리셋으로 실현한다(설계 §2-1). MARKET_MAKER만 MM 경로다.
 */
public enum BotType {
    MARKET_MAKER,
    TAKER,
    TREND,
    NOISE
}
