package kr.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.exchange.api.common.ApiException;
import kr.exchange.journal.DbOutageErrors;
import kr.exchange.journal.SqlErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * mTLS 신원 유래 단기 관리자 세션(§3-6 브라우저 관리 콘솔 세션). 검증은 서명·exp·scope가
 * 권위이며(§3-7 데모 세션과 동형), 추가로 admin_sessions 원장에서 폐기·만료를 확인하고
 * 세션 operator가 현재 요청의 mTLS 신원과 일치하는지 매 요청 재대조한다.
 *
 * 서명 키는 프로세스 한정 랜덤이다(데모 세션과 동일 계약). 재기동 시 전 세션 무효.
 * mTLS 게이트가 항상 선행하므로 세션 유실은 재발급으로 흡수된다.
 *
 * 원장이 DB 장애로 닿지 않으면 조회 요청에 한해 유예 시간 안에 원장에서 살아 있음을 확인한 세션을 받는다(LedgerGrace).
 * 관제 화면이 감시하는 DB 가 죽은 바로 그때 화면까지 멈추지 않게 하려는 것이다.
 */
public class AdminSessionService {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionService.class);

    private static final String TYP = "admin_session";
    private static final String SCOPE = "admin";
    private static final String HEADER_B64 = base64Url(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    /**
     * 원장이 닿지 않는 동안 조회 요청이 마지막 확인을 쓰는 시간. Patroni 자동 전환은 30초 안에 끝나지만 Patroni 가 없는
     * 배치의 수동 승격은 몇 분이 걸리고, 그동안 운영자가 관제 화면으로 장애를 봐야 한다
     */
    static final Duration LEDGER_GRACE = Duration.ofMinutes(10);
    /** 유예로 받은 요청의 경고 간격. 화면은 2초마다 읽으므로 요청마다 남기지 않는다 */
    private static final long GRACE_LOG_EVERY_MS = 30_000;

    /** admin_sessions 원장에서 세션이 폐기·만료되지 않았는지 본다 */
    interface SessionLedger {
        boolean live(String jti);
    }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final byte[] key;
    private final SessionLedger ledger;
    private final Clock clock;
    private final LedgerGrace<Boolean> confirmed;
    private final AtomicLong graceLoggedAt = new AtomicLong(-GRACE_LOG_EVERY_MS);

    public AdminSessionService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, null, Clock.systemUTC());
    }

    /** ledger 가 null 이면 admin_sessions 를 읽는다. 시험은 DB 없이 원장과 시계를 바꿔 끼운다 */
    AdminSessionService(JdbcClient jdbc, ObjectMapper objectMapper, SessionLedger ledger, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.key = new byte[32];
        new SecureRandom().nextBytes(this.key);
        this.ledger = ledger != null ? ledger : this::liveInDb;
        this.clock = clock;
        this.confirmed = new LedgerGrace<>(clock, LEDGER_GRACE);
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
     * 강제해 세션 탈취·평면 교차 사용을 차단한다. 원장이 닿지 않으면 503 이다.
     */
    public String verify(String authorizationHeader, String certOperatorId) {
        return verify(authorizationHeader, certOperatorId, false);
    }

    /**
     * readOnly 는 상태를 바꾸지 않는 요청(GET·HEAD)이다. 서명·만료·범위·mTLS 신원 대조는 늘 먼저 통과해야 한다. 그 뒤 원장
     * 조회가 DB 장애로 실패하면 조회 요청은 유예 시간 안의 원장 확인으로 받고, 그 밖의 요청은 503 이다.
     */
    public String verify(String authorizationHeader, String certOperatorId, boolean readOnly) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw unauthorized("Authorization Bearer 관리 세션 토큰이 필요합니다.");
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw unauthorized("세션 토큰 형식 오류");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw unauthorized("세션 토큰 인코딩 오류");
        }
        if (!MessageDigest.isEqual(hmac(signingInput), provided)) {
            throw unauthorized("세션 서명 검증 실패");
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception e) {
            throw unauthorized("세션 토큰 디코딩 실패");
        }
        if (!isAdminSession(payload) || !SCOPE.equals(payload.path("scope").asText())) {
            throw unauthorized("관리 세션 토큰이 아닙니다.");
        }
        if (payload.path("exp").asLong(0) <= System.currentTimeMillis() / 1000) {
            throw unauthorized("세션 만료. 다시 발급 필요");
        }
        String operatorId = payload.path("operator_id").asText(null);
        String jti = payload.path("jti").asText(null);
        if (operatorId == null || jti == null) {
            throw unauthorized("세션 클레임 누락");
        }
        // mTLS 신원 재대조: 세션 operator는 현재 인증서 CN과 같아야 한다(§3-6 자가신고 금지).
        if (!operatorId.equals(certOperatorId)) {
            throw unauthorized("세션 신원이 mTLS 인증서와 불일치");
        }
        boolean live;
        try {
            live = ledger.live(jti);
        } catch (RuntimeException e) {
            if (!DbOutageErrors.isOutage(e)) {
                throw e;
            }
            if (readOnly && confirmed.recent(jti).isPresent()) {
                logGrace(operatorId, e);
                return operatorId;
            }
            log.warn("관리 세션 원장 확인이 DB 장애로 끝났다(operator={}, readOnly={}): {}",
                    operatorId, readOnly, SqlErrors.describe(e));
            throw ledgerUnavailable();
        }
        if (!live) {
            confirmed.forget(jti);
            throw unauthorized("폐기 또는 만료된 세션");
        }
        confirmed.confirm(jti, Boolean.TRUE);
        return operatorId;
    }

    /** 원장이 닿지 않아 확인하지 못한 것이다. 거부가 아니라 일시 실패라 503 이다(API 설계 §2-5-c) */
    static ApiException ledgerUnavailable() {
        return new ApiException(503, "service_unavailable", "관리 원장 확인 불가. 잠시 후 재시도 필요", null, 1);
    }

    private boolean liveInDb(String jti) {
        return jdbc.sql("""
                        SELECT count(*) FROM admin_sessions
                        WHERE jti = ?::uuid AND revoked_at IS NULL AND expires_at > now()
                        """)
                .param(jti)
                .query(Long.class).single() > 0;
    }

    private void logGrace(String operatorId, RuntimeException e) {
        long now = clock.millis();
        long last = graceLoggedAt.get();
        if (now - last >= GRACE_LOG_EVERY_MS && graceLoggedAt.compareAndSet(last, now)) {
            log.warn("관리 세션 원장이 DB 장애로 닿지 않아 조회 요청을 마지막 원장 확인으로 받는다(operator={}, 유예 {}): {}",
                    operatorId, LEDGER_GRACE, SqlErrors.describe(e));
        }
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
