package kr.exchange.api.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 클라이언트 IP 파생 단일 경로(API 설계 §2-5). 원시 X-Forwarded-For 최좌측 채택은 원 클라이언트가
 * 임의 주입 가능해 IP 레이트리밋·데모 발급 방어를 우회시키므로 폐기한다. getRemoteAddr()만 신뢰한다.
 *
 * getRemoteAddr()는 forward-headers-strategy(RemoteIpValve, 배포 설정 소관)가 신뢰 프록시 체인을
 * 우측부터 제거하고 파생한 클라이언트 IP다. 신뢰 프록시 미설정 환경(직결)에서는 소켓 피어 IP다.
 * 어느 경우든 컨테이너가 검증한 IP 리터럴이라 스푸핑 가능한 헤더를 다시 파싱하지 않는다.
 */
public final class ClientIp {

    private ClientIp() {
    }

    /** 레이트리밋 버킷 키·감사용 클라이언트 IP. 항상 컨테이너 파생값이다. */
    public static String of(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return addr == null ? "" : addr;
    }

    /**
     * inet 컬럼 저장용. 유효 IPv4·IPv6 리터럴이면 그대로, 아니면 null(컬럼 nullable)로 반환해
     * ?::inet 캐스트 500·저장 실패를 차단한다. getRemoteAddr() 정상값은 그대로 통과한다.
     */
    public static String inetOrNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.indexOf(':') >= 0) {
            return isIpv6(v) ? v : null;
        }
        return isIpv4(v) ? v : null;
    }

    private static boolean isIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) {
                    return false;
                }
            }
            // 선행 0(예: 01)은 8진수 오해 소지가 있어 거부한다.
            if (p.length() > 1 && p.charAt(0) == '0') {
                return false;
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv6(String s) {
        // 존 식별자(fe80::1%eth0)는 Postgres inet이 받지 않으므로 저장 대상에서 제외한다.
        if (s.indexOf('%') >= 0) {
            return false;
        }
        // '::' 축약은 최대 1회.
        int dbl = s.indexOf("::");
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) {
            return false;
        }
        String[] groups = s.split(":", -1);
        if (groups.length > 8) {
            return false;
        }
        for (String g : groups) {
            if (g.isEmpty()) {
                continue;
            }
            if (g.indexOf('.') >= 0) {
                // 마지막 그룹의 IPv4 임베딩(예: ::ffff:192.0.2.1).
                if (!isIpv4(g)) {
                    return false;
                }
                continue;
            }
            if (g.length() > 4) {
                return false;
            }
            for (int i = 0; i < g.length(); i++) {
                char c = g.charAt(i);
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex) {
                    return false;
                }
            }
        }
        return true;
    }
}
