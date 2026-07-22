package kr.exchange.api.auth;

/**
 * 인증 통과 컨텍스트(API 설계 §3-4 최종 단계). scope는 api_keys.scope 값이다.
 * kind는 인증 경로 구분자다. 회원 전용 리소스(/me)가 데모·API 키 주체를 물리 차단한다.
 */
public record AuthPrincipal(long userId, String accessKey, String scope, String kind) {

    public static final String REQUEST_ATTRIBUTE = "exchange.auth.principal";

    public static final String KIND_MEMBER = "MEMBER";
    public static final String KIND_DEMO = "DEMO";
    public static final String KIND_API_KEY = "API_KEY";
}
