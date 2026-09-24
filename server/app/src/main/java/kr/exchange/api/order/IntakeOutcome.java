package kr.exchange.api.order;

/**
 * append 가 예외로 끝난 주문 생성 요청의 접수 결과(API 설계 §2-5-b, S4 설계 4-12). 마켓 접수 경합의 응답 코드를 가른다.
 * 이 요청이 접수되지 않았음이 확정되는 것은 DISCARDED 하나다.
 */
enum IntakeOutcome {
    /** 키를 잡지 않은 재전송이다. 같은 키의 주문이 이미 저널에 있다. */
    REPLAY,
    /** V47 판정 자체가 실패했다. 키는 남았다. */
    UNRESOLVED,
    /**
     * 이 요청이 넣은 키를 V47 이 지웠다. V47 은 같은 키의 append 와 같은 어드바이저리 락 아래에서 이 요청의 명령이 저널에 없고
     * 같은 키를 대조한 다른 요청의 공유 표시도 없음을 확인했다. 같은 키로 새 주문을 커밋할 수 있는 요청은 키를 넣은 요청과
     * 공유 표시를 채운 요청뿐이므로 그 순간 이 키로 커밋됐거나 커밋 중인 주문이 없다.
     */
    DISCARDED,
    /** 공유 표시 때문에 키를 남겼다. 같은 키의 다른 요청이 커밋할 수 있다. */
    KEPT,
    /** 키 행이 없었다. */
    ABSENT,
    /** 공유 표시를 채운 요청이고 자기 명령이 저널에 없다. 키를 넣은 원본이 커밋할 수 있다. */
    NOT_JOURNALED;

    /** 이 요청이 접수되지 않았음이 확정인가. 확정일 때만 경합을 429 로 낸다. */
    boolean confirmedNotAccepted() {
        return this == DISCARDED;
    }

    /** V47 resolve_failed_order_intake 의 out_outcome(JOURNALED 는 여기 오지 않는다). */
    static IntakeOutcome ofResolution(String outcome) {
        return switch (outcome) {
            case "DISCARDED" -> DISCARDED;
            case "KEPT" -> KEPT;
            case "ABSENT" -> ABSENT;
            case "NOT_JOURNALED" -> NOT_JOURNALED;
            default -> throw new IllegalArgumentException("저널에 없는 결과가 아니다: " + outcome);
        };
    }
}
