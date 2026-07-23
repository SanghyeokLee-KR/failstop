package kr.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.exchange.api.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * mTLS 신원 유래 단기 관리자 세션(§3-6 브라우저 관리 콘솔 세션). 검증은 서명·exp·scope가
 * 권위이며(§3-7 데모 세션과 동형), 추가로 admin_sessions 원장에서 폐기·만료를 확인하고
 * 세션 operator가 현재 요청의 mTLS 신원과 일치하는지 매 요청 재대조한다.
 *
 * 서명 키는 프로세스 한정 랜덤이다(데모 세션과 동일 계약). 재기동 시 전 세션 무효.
 * mTLS 게이트가 항상 선행하므로 세션 유실은 재발급으로 흡수된다.
 */
public class AdminSessionService {

    private static final String TYP = "admin_session";
    private static final String SCOPE = "admin";
    private static final String HEADER_B64 = base64Url(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final byte[] key;

    public AdminSessionService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.key = new byte[32];
        new SecureRandom().nextBytes(this.key);
    }

    public record IssuedSession(String token, String jti, long expiresAt) {
    }

    /** mTLS 통과 후 인증서 CN 유래 operator_id에 바인딩된 세션을 발급하고 원장에 적재한다. */
    public IssuedSession issue(String operatorId, Duration ttl) {
        String jti = UUID.randomUUID().toString();
        long iat = System.currentTimeMillis() / 1000;
        long exp = iat + ttl.toSeconds();
        jdbc.sql("""
                        INSERT INTO admin_sessions (jti, operator_id, expires_at)
                        VALUES (?::uuid, ?, to_timestamp(?))
                        """)
                .params(jti, operatorId, exp)
                .update();
        // 문자열 접합 대신 Jackson으로 조립해 CN 유래 operator_id의 JSON 메타문자(따옴표·역슬래시)가
        // payload를 깨거나 클레임을 주입하지 못하게 한다(자매 DemoSessionService·MemberSessionService와 동형).
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("typ", TYP);
        payload.put("operator_id", operatorId);
        payload.put("scope", SCOPE);
        payload.put("jti", jti);
        payload.put("iat", iat);
        payload.put("exp", exp);
        byte[] payloadBytes;
        try {
            payloadBytes = objectMapper.writeValueAsBytes(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("세션 payload 직렬화 실패", e);
        }
        String signingInput = HEADER_B64 + "." + base64Url(payloadBytes);
        String token = signingInput + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(signingInput));
        return new IssuedSession(token, jti, exp);
    }

    public static boolean isAdminSession(JsonNode payload) {
        return TYP.equals(payload.path("typ").asText());
    }

    /**
     * Bearer 관리 세션 토큰을 검증한다. mTLS 신원(certOperatorId)과 세션 operator 일치를
     * 강제해 세션 탈취·평면 교차 사용을 차단한다.
     */
    public String verify(String authorizationHeader, String certOperatorId) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw unauthorized("Authorization Bearer 관리 세션 토큰이 필요합니다.");
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw unauthorized("세션 토큰 형식이 올바르지 않습니다.");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw unauthorized("세션 토큰 인코딩이 올바르지 않습니다.");
        }
        if (!MessageDigest.isEqual(hmac(signingInput), provided)) {
            throw unauthorized("세션 서명 검증에 실패했습니다.");
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception e) {
            throw unauthorized("세션 토큰 디코딩에 실패했습니다.");
        }
        if (!isAdminSession(payload) || !SCOPE.equals(payload.path("scope").asText())) {
            throw unauthorized("관리 세션 토큰이 아닙니다.");
        }
        if (payload.path("exp").asLong(0) <= System.currentTimeMillis() / 1000) {
            throw unauthorized("세션이 만료되었습니다. 다시 발급받으십시오.");
        }
        String operatorId = payload.path("operator_id").asText(null);
        String jti = payload.path("jti").asText(null);
        if (operatorId == null || jti == null) {
            throw unauthorized("세션 클레임이 누락되었습니다.");
        }
        // mTLS 신원 재대조: 세션 operator는 현재 인증서 CN과 같아야 한다(§3-6 자가신고 금지).
        if (!operatorId.equals(certOperatorId)) {
            throw unauthorized("세션 신원이 mTLS 인증서와 일치하지 않습니다.");
        }
        boolean live = jdbc.sql("""
                        SELECT count(*) FROM admin_sessions
                        WHERE jti = ?::uuid AND revoked_at IS NULL AND expires_at > now()
                        """)
                .param(jti)
                .query(Long.class).single() > 0;
        if (!live) {
            throw unauthorized("세션이 폐기되었거나 만료되었습니다.");
        }
        return operatorId;
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiException unauthorized(String message) {
        return new ApiException(401, "jwt_verification", message);
    }
}
