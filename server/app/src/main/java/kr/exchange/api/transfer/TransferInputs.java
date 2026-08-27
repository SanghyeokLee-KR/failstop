package kr.exchange.api.transfer;

import kr.exchange.api.common.ApiException;

import java.math.BigDecimal;

/**
 * 입출금 요청 입력 검증(API 설계 §4-E).
 *
 * TransferService에서 분리한 이유는 MemberInputs와 같다. DB도 Spring 컨텍스트도 필요 없는
 * 순수 규칙인데 서비스 안에 있으면 Testcontainers 통합 테스트로만 검증된다. 자금 이동
 * 입구의 검증이라 Docker 없는 환경에서 회귀가 멈추면 곤란하다.
 */
public final class TransferInputs {

    private TransferInputs() {
    }

    /** 1회 한도. 정책이 아니라 표시·입력 파손을 막는 데모 가드다 */
    public static final BigDecimal MAX_TRANSFER_KRW = new BigDecimal("100000000");
    public static final String TRANSFER_ASSET = "KRW";
    public static final int MAX_IDEM_KEY = 64;
    public static final int MAX_REASON = 200;

    public static String direction(String raw) {
        if (!"DEPOSIT".equals(raw) && !"WITHDRAW".equals(raw)) {
            throw new ApiException(400, "invalid_parameter", "direction은 DEPOSIT 또는 WITHDRAW");
        }
        return raw;
    }

    /**
     * 자산은 KRW만 받는다. null은 생략으로 보고 KRW로 읽는다.
     * 코인 입출금이 막힌 이유는 스키마가 아니라 V13 position_cost 계층 논증이다.
     */
    public static String asset(String raw) {
        if (raw != null && !TRANSFER_ASSET.equals(raw)) {
            throw new ApiException(400, "invalid_transfer_asset", "원화 입출금만 지원");
        }
        return TRANSFER_ASSET;
    }

    /**
     * 원화라 소수점을 받지 않는다. 스케일 위반을 반올림으로 삼키면 사용자가 요청한 금액과
     * 실제 이동한 금액이 갈린다. 자금 경로에서 그 갈림은 조용히 넘어갈 수 없다.
     */
    public static BigDecimal amount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "invalid_transfer_amount", "금액 필요");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid_transfer_amount", "금액 형식 오류");
        }
        if (value.signum() <= 0) {
            throw new ApiException(400, "invalid_transfer_amount", "금액은 0보다 커야 함");
        }
        // 지수 표기(1E+8)도 stripTrailingZeros 후 scale로 판정된다. 문자열 검사로는 못 거른다
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > 0) {
            throw new ApiException(400, "invalid_transfer_amount", "원화는 소수점 불가");
        }
        if (stripped.compareTo(MAX_TRANSFER_KRW) > 0) {
            throw new ApiException(400, "invalid_transfer_amount",
                    "1회 한도 " + MAX_TRANSFER_KRW.toPlainString() + "원 초과");
        }
        // 지수 표기를 평문으로 눕혀 둔다. 원장 JSON에 1E+8이 실리면 NUMERIC 파싱이 갈린다
        return new BigDecimal(stripped.toPlainString());
    }

    public static String idemKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "invalid_parameter", "Idempotency-Key 헤더 필요");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_IDEM_KEY || !trimmed.matches("[A-Za-z0-9_-]+")) {
            throw new ApiException(400, "invalid_parameter",
                    "Idempotency-Key는 " + MAX_IDEM_KEY + "자 이내 [A-Za-z0-9_-]");
        }
        return trimmed;
    }

    /** 거절 사유는 사용자 내역에 그대로 노출된다. 컬럼 폭을 넘기면 22001로 거절이 실패한다 */
    public static String reason(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.length() > MAX_REASON ? trimmed.substring(0, MAX_REASON) : trimmed;
    }
}
