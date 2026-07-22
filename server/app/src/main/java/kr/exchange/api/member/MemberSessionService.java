package kr.exchange.api.member;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.ApiException;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 회원 액세스 토큰(회원_설계.md §1-3) 발급·검증. EdDSA(Ed25519) 비대칭 서명이다.
 *
 * 격리 근거: 개인키는 발급자에만 상주하고 검증자(게이트웨이·M3 community-api)는 공개키만
 * 갖는다. 검증자는 위조 능력이 없어 게시판 평면 침해가 거래 토큰 위조로 전파되지 않는다.
 * 본 M2는 단일 프로세스라 한 객체가 두 역할을 갖지만, verify()는 공개키만 사용하고 개인키
 * 접근은 issue()에 격리해 분리 원칙을 코드로 보존한다.
 *
 * 시드 미주입 시 프로세스 한정 랜덤 키로 동작한다(재기동 시 기존 세션 무효, 데모 허용 범위).
 * 32바이트 시드 주입 시 재기동 후에도 같은 키라 세션이 유지된다.
 *
 * M2는 단일 서명 키다. 헤더에 kid를 넣지 않는다. 회원_설계.md §6의 kid 교체 회전은 다중 키
 * 도입(후속 단위) 전제라 현재 물리 근거가 없다. 다중 키 도입 시 헤더 kid + kid별 공개키 선택을 더한다.
 */
public class MemberSessionService {

    public static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final String TYP = "access";
    private static final String AUDIENCE_EXCHANGE = "exchange";
    private static final String AUDIENCE_COMMUNITY = "community";
    private static final String HEADER_B64 = base64Url(
            "{\"alg\":\"EdDSA\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private static final Logger log = LoggerFactory.getLogger(MemberSessionService.class);

    private final ObjectMapper mapper;
    private final Ed25519PrivateKeyParameters privateKey;
    private final Ed25519PublicKeyParameters publicKey;

    public MemberSessionService(ObjectMapper mapper, String seedBase64) {
        this.mapper = mapper;
        byte[] seed;
        if (seedBase64 == null || seedBase64.isBlank()) {
            seed = new byte[32];
            new SecureRandom().nextBytes(seed);
            log.warn("회원 세션 서명 시드 미주입: 프로세스 한정 임시 키로 동작한다. 재기동 시 기존 세션 무효.");
        } else {
            seed = Base64.getDecoder().decode(seedBase64);
            if (seed.length != 32) {
                throw new IllegalArgumentException("회원 세션 시드는 base64 인코딩된 32바이트여야 한다");
            }
        }
        this.privateKey = new Ed25519PrivateKeyParameters(seed, 0);
        this.publicKey = privateKey.generatePublicKey();
    }

    public String issue(long userId, String externalId, String kind) {
        long iat = System.currentTimeMillis() / 1000;
        long exp = iat + ACCESS_TTL.toSeconds();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("typ", TYP);
        payload.put("alg", "EdDSA");
        payload.put("sub", externalId);
        payload.put("user_id", userId);
        payload.put("scope", "TRADE");
        payload.put("kind", kind);
        ArrayNode aud = payload.putArray("aud");
        aud.add(AUDIENCE_EXCHANGE);
        aud.add("community");
        payload.put("iat", iat);
        payload.put("exp", exp);
        byte[] payloadBytes;
        try {
            payloadBytes = mapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("회원 세션 payload 직렬화 실패", e);
        }
        String signingInput = HEADER_B64 + "." + base64Url(payloadBytes);
        byte[] signature = sign(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    /** ApiAuthService가 JWT 헤더 alg=EdDSA 판별 후 위임한다. 공개키 검증만 수행한다. */
    public AuthPrincipal verify(String[] tokenParts, JsonNode payload) {
        String signingInput = tokenParts[0] + "." + tokenParts[1];
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(tokenParts[2]);
        } catch (IllegalArgumentException e) {
            throw unauthorized("세션 토큰 인코딩이 올바르지 않습니다.");
        }
        if (!verifySignature(signingInput.getBytes(StandardCharsets.UTF_8), provided)) {
            throw unauthorized("세션 서명 검증에 실패했습니다.");
        }
        if (!TYP.equals(payload.path("typ").asText())) {
            throw unauthorized("세션 토큰 유형이 올바르지 않습니다.");
        }
        long exp = payload.path("exp").asLong(0);
        if (exp <= System.currentTimeMillis() / 1000) {
            throw unauthorized("세션이 만료되었습니다. 다시 로그인하십시오.");
        }
        if (!payload.hasNonNull("user_id") || !"TRADE".equals(payload.path("scope").asText())) {
            throw unauthorized("세션 클레임이 올바르지 않습니다.");
        }
        // verifyCommunity와 대칭으로 kind=HUMAN을 강제한다. 비 HUMAN 종류 토큰이 발급되어도
        // 거래 평면을 무검증 통과하지 못하게 막는다(KIND_MEMBER 고정 스탬프의 무결성 보강).
        if (!"HUMAN".equals(payload.path("kind").asText())) {
            throw unauthorized("회원 세션이 아닙니다.");
        }
        if (!audienceContainsExchange(payload)) {
            throw unauthorized("이 토큰은 거래 평면 대상이 아닙니다.");
        }
        return new AuthPrincipal(payload.get("user_id").asLong(), "member-session", "TRADE",
                AuthPrincipal.KIND_MEMBER);
    }

    /**
     * 게시판 평면(게시판_설계.md §2.4) 검증. 거래 평면과 aud로 경계 분리한다: 서명·exp·typ은 동일
     * 검증하되 aud가 community를 포함하고 kind=HUMAN인 회원 토큰만 통과시킨다. 거래 전용 클레임
     * (scope=TRADE)에는 의존하지 않아 게시판 침해가 거래 토큰 위조로 전파되지 않는 격리를 코드로 보존한다.
     */
    public AuthPrincipal verifyCommunity(String[] tokenParts, JsonNode payload) {
        String signingInput = tokenParts[0] + "." + tokenParts[1];
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(tokenParts[2]);
        } catch (IllegalArgumentException e) {
            throw unauthorized("세션 토큰 인코딩이 올바르지 않습니다.");
        }
        if (!verifySignature(signingInput.getBytes(StandardCharsets.UTF_8), provided)) {
            throw unauthorized("세션 서명 검증에 실패했습니다.");
        }
        if (!TYP.equals(payload.path("typ").asText())) {
            throw unauthorized("세션 토큰 유형이 올바르지 않습니다.");
        }
        if (payload.path("exp").asLong(0) <= System.currentTimeMillis() / 1000) {
            throw unauthorized("세션이 만료되었습니다. 다시 로그인하십시오.");
        }
        if (!"HUMAN".equals(payload.path("kind").asText()) || !payload.hasNonNull("user_id")) {
            throw unauthorized("회원 세션이 아닙니다.");
        }
        if (!audienceContains(payload, AUDIENCE_COMMUNITY)) {
            throw unauthorized("이 토큰은 게시판 평면 대상이 아닙니다.");
        }
        return new AuthPrincipal(payload.get("user_id").asLong(), "member-session", "COMMUNITY",
                AuthPrincipal.KIND_MEMBER);
    }

    private static boolean audienceContainsExchange(JsonNode payload) {
        return audienceContains(payload, AUDIENCE_EXCHANGE);
    }

    private static boolean audienceContains(JsonNode payload, String audience) {
        JsonNode aud = payload.path("aud");
        if (aud.isArray()) {
            for (JsonNode entry : aud) {
                if (audience.equals(entry.asText())) {
                    return true;
                }
            }
            return false;
        }
        return audience.equals(aud.asText());
    }

    private byte[] sign(byte[] message) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    private boolean verifySignature(byte[] message, byte[] signature) {
        // Ed25519 서명은 64바이트 고정. 다른 길이는 verifySignature가 예외를 던지므로 선차단한다.
        if (signature.length != 64) {
            return false;
        }
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, publicKey);
        verifier.update(message, 0, message.length);
        return verifier.verifySignature(signature);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiException unauthorized(String message) {
        return new ApiException(401, "jwt_verification", message);
    }
}
