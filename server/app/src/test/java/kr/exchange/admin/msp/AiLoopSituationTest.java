package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static kr.exchange.admin.msp.AiFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 판단 주기의 상황과 지문(설계 7-1, 7-19). 부하 판정이 일시적에서 지속으로 바뀌면 상황이 바뀌어 재판단 간격을 기다리지 않고,
 * 주기마다 바뀌는 부하 특징(이어진 시간, 기울기)은 지문을 바꾸지 않는다.
 */
class AiLoopSituationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LoadPattern load(LoadPattern.Kind kind, long aboveFor, double slope) {
        return new LoadPattern(kind, 0.72, new BigDecimal("0.60"), aboveFor, 180, aboveFor, 1, 900, slope, 0.9, 1, NOW,
                NOW.minusSeconds(aboveFor));
    }

    @Test
    void 부하_판정이_바뀌면_상황이_바뀐다() {
        String brief = AiLoop.situation(List.of(), List.of("read.scale_out"), load(LoadPattern.Kind.TRANSIENT, 60, 0.1));
        String held = AiLoop.situation(List.of(), List.of("read.scale_out"), load(LoadPattern.Kind.SUSTAINED, 200, 0.1));

        assertNotEquals(brief, held);
        assertEquals("load:TRANSIENT,read.scale_out", brief);
        assertEquals("read.scale_out", AiLoop.situation(List.of(), List.of("read.scale_out"), null));
    }

    @Test
    void 자동_모드에서_실행을_기다리는_요청이_있을_때만_분석을_미룬다() {
        assertTrue(AiLoop.deferAnalysis(AiFixtures.auto(), true), "연속 판단(A4)의 간격을 지킨다");
        assertFalse(AiLoop.deferAnalysis(AiFixtures.auto(), false));
        assertFalse(AiLoop.deferAnalysis(AiFixtures.manual(), true), "수동 모드는 승인 전에 분석을 본다");
    }

    @Test
    void 지문은_부하_판정의_이름만_보고_이어진_시간과_기울기에는_흔들리지_않는다() {
        AiEvidence a = new AiFixtures.Ev().cpu(0.72, null).load(load(LoadPattern.Kind.TRANSIENT, 60, 0.1)).build();
        AiEvidence b = new AiFixtures.Ev().cpu(0.72, null).load(load(LoadPattern.Kind.TRANSIENT, 105, 0.3)).build();
        AiEvidence c = new AiFixtures.Ev().cpu(0.72, null).load(load(LoadPattern.Kind.SUSTAINED, 195, 0.3)).build();

        assertEquals(AiLoop.fingerprint(a, MAPPER), AiLoop.fingerprint(b, MAPPER));
        assertNotEquals(AiLoop.fingerprint(b, MAPPER), AiLoop.fingerprint(c, MAPPER));
    }
}
