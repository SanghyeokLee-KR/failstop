package kr.exchange.api.community;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.member.MemberSessionService;

import java.util.Base64;
import java.util.OptionalLong;

/**
 * 게시판 쓰기 인증(게시판_설계.md §2.4·§4). 거래 평면과 경계 분리해 aud=community 회원 토큰만
 * 통과시킨다. 읽기(GET)는 이 검증을 거치지 않는다. 서명 검증은 로컬 공개키 연산이라 코어·M2로의
 * 런타임 호출이 없다(격리). 검증 실패는 401 jwt_verification.
 */
public class CommunityAuthenticator {

    private final MemberSessionService memberSessions;
    private final ObjectMapper objectMapper;

    public CommunityAuthenticator(MemberSessionService memberSessions, ObjectMapper objectMapper) {
        this.memberSessions = memberSessions;
        this.objectMapper = objectMapper;
    }

    /** 인증 통과 회원 user_id를 반환한다. 실패 시 401. */
    public long requireMember(HttpServletRequest request) {
        return resolveMember(request);
    }

    /**
     * 읽기(GET) 선택 인증. 유효 회원 Bearer면 user_id를, 없거나 검증 실패면 비어 있음을 반환한다.
     * 읽기는 비로그인 허용(설계 §1.2)이라 토큰 부재·무효가 조회를 막지 않는다. 서버가 이 신원으로
     * 소유권(mine)을 산출하므로 공개 응답이 작성자 식별자를 노출하지 않는다(설계 §4).
     */
    public OptionalLong optionalMember(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(resolveMember(request));
        } catch (ApiException e) {
            // 무효·만료 토큰은 비로그인으로 강등해 조회를 계속 성립시킨다(mine=false 안전 측 기본).
            return OptionalLong.empty();
        }
    }

    private long resolveMember(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw unauthorized("Authorization Bearer 회원 세션이 필요합니다.");
        }
        String[] parts = header.substring("Bearer ".length()).trim().split("\\.");
        if (parts.length != 3) {
            throw unauthorized("세션 토큰 형식 오류");
        }
        JsonNode headerNode = decode(parts[0]);
        if (!"EdDSA".equals(headerNode.path("alg").asText())) {
            // 게시판 쓰기는 회원 세션(EdDSA)만 받는다. HMAC API 키·데모 세션 경로는 배격한다(설계 §2.4).
            throw unauthorized("게시판 쓰기는 회원 세션 전용입니다.");
        }
        JsonNode payload = decode(parts[1]);
        AuthPrincipal principal = memberSessions.verifyCommunity(parts, payload);
        return principal.userId();
    }

    private JsonNode decode(String base64Url) {
        try {
            return objectMapper.readTree(Base64.getUrlDecoder().decode(base64Url));
        } catch (Exception e) {
            throw unauthorized("세션 토큰 디코딩 실패");
        }
    }

    private static ApiException unauthorized(String message) {
        return new ApiException(401, "jwt_verification", message);
    }
}
