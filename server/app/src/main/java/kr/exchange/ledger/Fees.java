package kr.exchange.ledger;

import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.OrderCommand;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 체결 수수료 확정 계산 단일 지점. 정산(§5.2 분개·trades·payload 박제)과 재생 대조
 * (ReplayService)가 같은 산식을 써야 event_journal payload 바이트 대조가 성립한다.
 *
 * 정책(DB 설계 §5, §1.4): 각 당사자는 자신이 받는 자산으로 수수료를 낸다.
 * 매수측은 base(수량 x 요율), 매도측은 quote(대금 x 요율). 해당 자산 scale로
 * HALF_UP 선양자화한 값이 확정 금액이고, 이 값이 payload·trades에 박제된다.
 * 요율은 명령 payload 박제값만 쓴다(markets 현재 요율 재계산 금지, §5.2).
 */
public final class Fees {

    public record TradeFees(BigDecimal makerFee, BigDecimal takerFee) {
    }

    private Fees() {
    }

    public static TradeFees of(EngineEvent trade, BigDecimal makerRate, BigDecimal takerRate,
                               MarketCatalog.MarketMeta meta) {
        BigDecimal qty = trade.quantity();
        BigDecimal notional = qty.multiply(trade.price());
        boolean takerBuys = trade.takerSide() == OrderCommand.Side.BUY;
        BigDecimal buyerRate = zeroIfNull(takerBuys ? takerRate : makerRate);
        BigDecimal sellerRate = zeroIfNull(takerBuys ? makerRate : takerRate);
        BigDecimal buyerFee = quantize(qty.multiply(buyerRate), meta.baseScale(), RoundingMode.HALF_UP);
        BigDecimal sellerFee = quantize(notional.multiply(sellerRate), meta.quoteScale(), RoundingMode.HALF_UP);
        return takerBuys
                ? new TradeFees(sellerFee, buyerFee)
                : new TradeFees(buyerFee, sellerFee);
    }

    public static BigDecimal quantize(BigDecimal value, int scale, RoundingMode mode) {
        return value.setScale(scale, mode);
    }

    /** payload 박제 표기. 0은 scale 무관하게 "0"으로 고정한다(M1-2 자리표시 "0"과 동일 표기). */
    public static String text(BigDecimal fee) {
        return fee.signum() == 0 ? "0" : fee.toPlainString();
    }

    public static boolean zero(BigDecimal rate) {
        return rate == null || rate.signum() == 0;
    }

    private static BigDecimal zeroIfNull(BigDecimal rate) {
        return rate == null ? BigDecimal.ZERO : rate;
    }
}
