package kr.exchange.api.ws;

import java.security.Principal;

/** WS 세션 인증 주체. name = user_id 문자열이며 user-destination 라우팅 키다. */
public record StompPrincipal(String name) implements Principal {

    @Override
    public String getName() {
        return name;
    }
}
