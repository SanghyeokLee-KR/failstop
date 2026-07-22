package kr.exchange.api.ws;

import java.security.Principal;

/** WS 세션 인증 주체. name = user_id 문자열이며 user-destination 라우팅 키다. */
public record StompPrincipal(String name) implements Principal {

    public StompPrincipal {
        // 라우팅 키가 null이면 user-destination 배달이 조용히 어긋난다. 생성 시점에 막는다.
        if (name == null) {
            throw new IllegalArgumentException("StompPrincipal name must not be null");
        }
    }

    @Override
    public String getName() {
        return name;
    }
}
