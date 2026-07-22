package kr.exchange.api.auth;

/** 인증 통과 컨텍스트(API 설계 §3-4 최종 단계). scope는 api_keys.scope 값이다. */
public record AuthPrincipal(long userId, String accessKey, String scope) {

    public static final String REQUEST_ATTRIBUTE = "exchange.auth.principal";
}
