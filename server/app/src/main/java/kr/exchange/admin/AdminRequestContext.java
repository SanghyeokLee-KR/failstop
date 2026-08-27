package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;

/** 컨트롤러가 인증 필터 산출물(운영자 신원·요청 id)을 읽는 접근점. */
public final class AdminRequestContext {

    private AdminRequestContext() {
    }

    public static AdminPrincipal principal(HttpServletRequest request) {
        Object p = request.getAttribute(AdminPrincipal.REQUEST_ATTRIBUTE);
        if (p instanceof AdminPrincipal ap) {
            return ap;
        }
        throw new ApiException(401, "jwt_verification", "인증 컨텍스트 없음");
    }

    public static String certOperator(HttpServletRequest request) {
        Object c = request.getAttribute(AdminAuthFilter.CERT_OPERATOR_ATTRIBUTE);
        return c instanceof String s ? s : null;
    }

    public static String requestId(HttpServletRequest request) {
        Object r = request.getAttribute(AdminAuthFilter.REQUEST_ID_ATTRIBUTE);
        return r instanceof String s ? s : null;
    }
}
