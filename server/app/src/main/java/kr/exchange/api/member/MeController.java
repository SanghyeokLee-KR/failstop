package kr.exchange.api.member;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 내 정보(회원_설계.md §3-1). 회원 세션 Bearer로 게이트웨이가 주입한 user_id만 조회한다.
 * 바디·경로의 사용자 지정을 받지 않아 타 회원 자원 접근이 불가하다(§1-5).
 */
@RestController
public class MeController {

    private final MemberAuthService auth;

    public MeController(MemberAuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/api/v1/me")
    public Map<String, Object> me(HttpServletRequest request) {
        AuthPrincipal principal =
                (AuthPrincipal) request.getAttribute(AuthPrincipal.REQUEST_ATTRIBUTE);
        if (principal == null) {
            throw new ApiException(401, "jwt_verification", "인증 컨텍스트가 없습니다.");
        }
        // 회원 세션 전용(§3-1). 데모·API 키 주체는 user_profiles 행이 없어 조회가 깨진다.
        // 여기서 물리 차단해 EmptyResultDataAccessException 500 오분류를 막고 403으로 명시한다.
        if (!AuthPrincipal.KIND_MEMBER.equals(principal.kind())) {
            throw new ApiException(403, "out_of_scope", "회원 세션 전용 엔드포인트입니다.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("user", auth.userView(principal.userId()));
        return body;
    }
}
