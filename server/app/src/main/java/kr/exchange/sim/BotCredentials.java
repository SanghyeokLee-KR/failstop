package kr.exchange.sim;

/** 봇의 외부 클라이언트 자격(§3-2 발급 키). 내부 서비스 참조를 갖지 않는다. */
public record BotCredentials(long userId, String email, String accessKey, String secretKey) {
}
