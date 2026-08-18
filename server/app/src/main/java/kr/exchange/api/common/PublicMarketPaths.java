package kr.exchange.api.common;

/**
 * 무인증 공개 시장 데이터 경로(§4-A) 단일 판정.
 *
 * 불변식은 '인증 제외 경로는 반드시 어떤 유량 방벽 하나에 속한다'이다. ApiAuthFilter의 인증
 * 제외 목록은 여기에 데모 세션·게시판·회원 인증 평면을 더한 상위 집합이고, 그 셋은 각자
 * SlidingWindowThrottle을 들고 있다(DemoSessionController·CommunityController·MemberAuthController).
 * 시장 데이터만 방벽이 없어 rank11로 지적됐고, 그 부분집합을 여기서 한 곳에 모은다. 목록을 두
 * 곳에 두면 한쪽만 늘었을 때 인증도 레이트리밋도 없는 경로가 생긴다.
 *
 * 신규 무인증 경로를 추가할 때: 시장 데이터가 아니면 여기 넣지 말고 자기 방벽을 붙인다.
 * 아래 weight 규칙이 시장 데이터 응답 모양을 전제하기 때문이다.
 */
public final class PublicMarketPaths {

    private PublicMarketPaths() {
    }

    /** §4-A 공개 평면. 인증을 요구하지 않으므로 계정 버킷이 붙을 principal이 없다. */
    public static boolean isMarketData(String path) {
        return path.startsWith("/api/v1/markets")
                || path.startsWith("/api/v1/orderbook")
                || path.startsWith("/api/v1/trades")
                || path.startsWith("/api/v1/ticker")
                || path.startsWith("/api/v1/candles")
                || path.startsWith("/api/v1/exchange-config");
    }

    /**
     * IP 버킷 차감 weight(§2-5 표 '공개 조회 1~2'). 다중행 리스트는 2, 단건 객체는 1이다.
     *
     * ticker의 `markets=` 배치 조회를 마켓 수만큼 계상하지 않는 이유: 그러면 마켓별 개별 폴링이
     * 배치보다 싸져 요청 수를 늘리는 방향으로 클라이언트를 유인한다. 보호 대상은 행 수가 아니라
     * 요청이 점유하는 스레드·커넥션이다.
     */
    public static int weight(String path) {
        boolean singleObject = path.startsWith("/api/v1/orderbook")
                || path.startsWith("/api/v1/exchange-config");
        return singleObject ? 1 : 2;
    }
}
