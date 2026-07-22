package kr.exchange.admin;

import kr.exchange.api.common.ApiException;

import java.util.Set;

/**
 * 관리 세션 인증 통과 컨텍스트. operator_id는 세션 발급 시 mTLS 인증서 CN에서 유래하고
 * 매 요청에서 현재 mTLS 신원과 재대조된 값이다(자가신고 아님).
 */
public record AdminPrincipal(String operatorId, String sessionJti, Set<AdminRole> roles) {

    public static final String REQUEST_ATTRIBUTE = "exchange.admin.principal";

    /**
     * §1-2 역할 포함관계. SUPER는 전 권한, CHAOS_OPS는 봇 제어(BOT_OPS)를 포함하고,
     * 조회(VIEWER)는 운영 역할을 가진 단독 운영자 누구나 가능하다(VIEWER 이상). 프론트
     * roles.ts can()과 3자 일치를 유지한다. 위반 시 프론트가 버튼을 열고 백엔드가 거부한다.
     */
    public boolean has(AdminRole role) {
        if (roles.contains(AdminRole.SUPER)) {
            return true;
        }
        return switch (role) {
            case VIEWER -> !roles.isEmpty();
            case BOT_OPS -> roles.contains(AdminRole.BOT_OPS) || roles.contains(AdminRole.CHAOS_OPS);
            case CHAOS_OPS -> roles.contains(AdminRole.CHAOS_OPS);
            case MEMBER_MOD -> roles.contains(AdminRole.MEMBER_MOD);
            case SUPER -> false;
        };
    }

    /** 역할 부족은 403 admin_forbidden(§1-2). detail.reason으로 부족 사유를 구분한다. */
    public void require(AdminRole role) {
        if (!has(role)) {
            throw new ApiException(403, "admin_forbidden",
                    "이 액션에는 " + role + " 역할이 필요합니다.",
                    java.util.Map.of("reason", "role_required", "required_role", role.name()),
                    null);
        }
    }
}
