package kr.exchange.api.transfer;

import kr.exchange.api.common.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 입출금 입력 검증(API 설계 §4-E).
 *
 * 자금 이동의 입구다. 여기서 통과한 값이 그대로 원장 분개 JSON에 실린다. DB도 Spring도
 * 안 쓰므로 Docker 없는 환경에서도 회귀가 돈다.
 */
class TransferInputsTest {

    private static String err(Runnable r) {
        return assertThrows(ApiException.class, r::run).name();
    }

    @Test
    void directionAcceptsOnlyTwoValues() {
        assertEquals("DEPOSIT", TransferInputs.direction("DEPOSIT"));
        assertEquals("WITHDRAW", TransferInputs.direction("WITHDRAW"));
        for (String bad : new String[]{null, "", "deposit", "TRANSFER", "IN"}) {
            assertEquals("invalid_parameter", err(() -> TransferInputs.direction(bad)), "거절 대상: " + bad);
        }
    }

    @Test
    void assetIsKrwOnly() {
        assertEquals("KRW", TransferInputs.asset("KRW"));
        // 생략은 KRW로 읽는다. 단일 자산이라 클라이언트가 매번 실을 이유가 없다
        assertEquals("KRW", TransferInputs.asset(null));
        for (String bad : new String[]{"BTC", "ETH", "krw", "USD"}) {
            assertEquals("invalid_transfer_asset", err(() -> TransferInputs.asset(bad)), "거절 대상: " + bad);
        }
    }

    @Test
    void amountAcceptsWholeWon() {
        assertEquals(new BigDecimal("1000000"), TransferInputs.amount("1000000"));
        assertEquals(new BigDecimal("1"), TransferInputs.amount("1"));
        assertEquals(TransferInputs.MAX_TRANSFER_KRW, TransferInputs.amount("100000000"));
        // 소수 0은 유효 자릿수가 아니다. 1000.00은 1000과 같은 값이라 통과시킨다
        assertEquals(new BigDecimal("1000"), TransferInputs.amount("1000.00"));
        assertEquals(new BigDecimal("1000"), TransferInputs.amount("  1000  "));
    }

    @Test
    void amountRejectsFractionAndOutOfRange() {
        assertEquals("invalid_transfer_amount", err(() -> TransferInputs.amount("1000.5")));
        assertEquals("invalid_transfer_amount", err(() -> TransferInputs.amount("0")));
        assertEquals("invalid_transfer_amount", err(() -> TransferInputs.amount("-1000")));
        assertEquals("invalid_transfer_amount", err(() -> TransferInputs.amount("100000001")));
        assertEquals("invalid_transfer_amount", err(() -> TransferInputs.amount("")));
        assertEquals("invalid_transfer_amount", err(() -> TransferInputs.amount(null)));
        assertEquals("invalid_transfer_amount", err(() -> TransferInputs.amount("1,000,000")));
        assertEquals("invalid_transfer_amount", err(() -> TransferInputs.amount("abc")));
    }

    @Test
    void amountFlattensExponentNotation() {
        // 1E+8은 값으로는 1억이라 한도를 통과한다. 그대로 두면 원장 분개 JSON에 실려
        // NUMERIC 파싱이 구현마다 갈린다. 평문으로 눕혀 저장한다
        assertEquals("100000000", TransferInputs.amount("1E+8").toPlainString());
        assertEquals("1000", TransferInputs.amount("1E3").toPlainString());
        // 지수 표기라도 소수면 거절이다. 문자열에 점이 없다고 통과시키면 안 된다
        assertEquals("invalid_transfer_amount", err(() -> TransferInputs.amount("1E-2")));
    }

    @Test
    void idemKeyRequiredAndBounded() {
        assertEquals("web-1a2b3c", TransferInputs.idemKey("web-1a2b3c"));
        assertEquals("A_1", TransferInputs.idemKey("  A_1  "));
        assertEquals("a".repeat(64), TransferInputs.idemKey("a".repeat(64)));
        for (String bad : new String[]{null, "", "   ", "a".repeat(65), "key with space", "키", "a/b"}) {
            assertEquals("invalid_parameter", err(() -> TransferInputs.idemKey(bad)), "거절 대상: " + bad);
        }
    }

    @Test
    void reasonIsOptionalAndTruncated() {
        assertNull(TransferInputs.reason(null));
        assertNull(TransferInputs.reason("   "));
        assertEquals("한도 초과", TransferInputs.reason("  한도 초과  "));
        // 컬럼 폭을 넘기면 22001로 거절 자체가 실패한다. 자르는 쪽이 맞다
        assertEquals(TransferInputs.MAX_REASON, TransferInputs.reason("가".repeat(300)).length());
    }
}
