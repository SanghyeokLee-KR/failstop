package kr.exchange.api.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * inet 사전 검증 회귀(감사 XFF·입력검증). 채택 IP가 ?::inet 캐스트로 500·고아 세션을 만들지 않도록
 * 유효 리터럴만 통과하고 나머지는 null로 접는다. getRemoteAddr() 정상값(IPv4·IPv6)은 그대로 보존한다.
 */
class ClientIpTest {

    @Test
    void keepsValidIpv4() {
        assertEquals("203.0.113.7", ClientIp.inetOrNull("203.0.113.7"));
        assertEquals("10.0.0.1", ClientIp.inetOrNull("10.0.0.1"));
        assertEquals("127.0.0.1", ClientIp.inetOrNull(" 127.0.0.1 "));
    }

    @Test
    void keepsValidIpv6() {
        assertEquals("::1", ClientIp.inetOrNull("::1"));
        assertEquals("0:0:0:0:0:0:0:1", ClientIp.inetOrNull("0:0:0:0:0:0:0:1"));
        assertEquals("2001:db8::ff00:42:8329", ClientIp.inetOrNull("2001:db8::ff00:42:8329"));
    }

    @Test
    void rejectsNonIpLiterals() {
        assertNull(ClientIp.inetOrNull(null));
        assertNull(ClientIp.inetOrNull(""));
        assertNull(ClientIp.inetOrNull("   "));
        // 스푸핑된 XFF 최좌측이 이런 비 IP 문자열로 들어오던 경로(감사): 이제 null로 접힌다.
        assertNull(ClientIp.inetOrNull("not-an-ip"));
        assertNull(ClientIp.inetOrNull("evil'; DROP TABLE users;--"));
        assertNull(ClientIp.inetOrNull("256.0.0.1"));
        assertNull(ClientIp.inetOrNull("10.0.0"));
        assertNull(ClientIp.inetOrNull("1.2.3.4.5"));
        assertNull(ClientIp.inetOrNull("01.2.3.4"));
        // 존 식별자는 Postgres inet 미지원이라 저장 대상에서 제외한다.
        assertNull(ClientIp.inetOrNull("fe80::1%eth0"));
        // '::' 축약 2회는 모호해 무효다.
        assertNull(ClientIp.inetOrNull("2001::db8::1"));
        assertNull(ClientIp.inetOrNull("gg:hh::1"));
    }
}
