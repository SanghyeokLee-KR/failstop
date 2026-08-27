package kr.exchange.api.member;

import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ClientIp;
import kr.exchange.ledger.AccountBootstrap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 회원 인증 도메인(회원_설계.md §1·§2·§3). 가입·로그인·토큰 갱신·로그아웃·내 정보.
 *
 * 가입 자금 발급은 기존 원장 단일 경로 post_ledger_transaction만 재사용한다. 이 경로는
 * 엔진 기원 쓰기가 아니라 리더 리스(fencing) 대상이 아니고, 멱등은
 * ledger_transactions.UNIQUE(business_event_type, business_event_id)로 보증한다(§5).
 */
public class MemberAuthService {

    // GENESIS business_event_id 네임스페이스 예약(§2-4). 시드 GENESIS [1, SEED_MAX]와 서로소.
    static final long PROVISION_GENESIS_BASE = 1_000_000_000_000L;
    private static final String INITIAL_KRW = "100000000";

    private static final int MIN_PASSWORD_LENGTH = 8;
    // Argon2id는 입력 길이에 비례해 CPU를 쓴다. 상한이 없으면 거대 password가 해시 단계 DoS로 유입된다.
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final int LOCK_THRESHOLD = 5;
    private static final long BASE_LOCK_SECONDS = 30;
    private static final long MAX_LOCK_SECONDS = 900;
    private static final long REFRESH_TTL_SECONDS = 14L * 24 * 3600;

    public record SessionResult(String accessToken, String refreshTokenRaw,
                                long refreshTtlSeconds, Map<String, Object> userView) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PasswordHasher hasher;
    private final MemberSessionService sessions;
    private final AccountBootstrap bootstrap;
    private final SecureRandom random = new SecureRandom();
    // 미가입 이메일과 오비밀번호의 응답 시간을 맞추기 위한 더미 해시(타이밍 사이드채널 완화).
    private final String dummyHash;

    public MemberAuthService(JdbcClient jdbc, TransactionTemplate tx, PasswordHasher hasher,
                             MemberSessionService sessions, AccountBootstrap bootstrap) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.hasher = hasher;
        this.sessions = sessions;
        this.bootstrap = bootstrap;
        this.dummyHash = hasher.hash("timing-equalizer-" + UUID.randomUUID());
    }

    public SessionResult signup(String email, String password, String displayName,
                                String ip, String userAgent) {
        String normalizedEmail = MemberInputs.email(email);
        validatePassword(password);
        String name = MemberInputs.displayName(displayName);
        String passwordHash = hasher.hash(password);

        long userId;
        try {
            userId = tx.execute(status -> provision(normalizedEmail, passwordHash, name));
        } catch (DuplicateKeyException e) {
            // email UNIQUE 위반만 409 email_taken으로 분류한다. 원장 등 다른 유니크 위반을 email_taken으로
            // 오귀속하면 신선한 이메일에 409가 나가 오해를 부른다. email 충돌이면 상대 tx가 커밋한 행이
            // 보이고(23505는 커밋 후 발생), 우리 tx는 롤백돼 우리가 넣은 행은 없으므로 존재 검사로 구별된다.
            boolean emailExists = jdbc.sql(
                            "SELECT 1 FROM users WHERE email = ? AND kind = 'HUMAN'")
                    .params(normalizedEmail)
                    .query(Integer.class)
                    .optional()
                    .isPresent();
            if (emailExists) {
                throw new ApiException(409, "email_taken", "이미 가입된 이메일입니다.");
            }
            throw e;
        }
        return issueSession(userId, ip, userAgent);
    }

    private long provision(String email, String passwordHash, String displayName) {
        long userId = jdbc.sql("INSERT INTO users (email, kind) VALUES (?, 'HUMAN') RETURNING id")
                .params(email)
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO user_credentials (user_id, password_hash, algo) VALUES (?, ?, ?)")
                .params(userId, passwordHash, PasswordHasher.ALGO)
                .update();
        jdbc.sql("INSERT INTO user_profiles (user_id, display_name) VALUES (?, ?)")
                .params(userId, displayName)
                .update();

        List<Map<String, Object>> assets = jdbc.sql(
                        "SELECT id, symbol FROM assets WHERE status = 'ACTIVE' ORDER BY id")
                .query()
                .listOfRows();
        int krwAssetId = -1;
        for (Map<String, Object> asset : assets) {
            int assetId = ((Number) asset.get("id")).intValue();
            bootstrap.userAccounts(userId, assetId);
            if ("KRW".equals(asset.get("symbol"))) {
                krwAssetId = assetId;
            }
        }
        if (krwAssetId < 0) {
            throw new IllegalStateException("KRW 자산 미시드: 가입 초기 자금 발급 불가");
        }

        long genesisEventId = PROVISION_GENESIS_BASE + userId;
        jdbc.sql("INSERT INTO user_provisioning (user_id, genesis_event_id) VALUES (?, ?)")
                .params(userId, genesisEventId)
                .update();

        long genesisKrw = accountId("SYSTEM", 0, "GENESIS", krwAssetId);
        long userKrwAvailable = accountId("USER", userId, "AVAILABLE", krwAssetId);
        String entries = ("[{\"account_id\": %d, \"asset_id\": %d, \"amount\": \"-%s\","
                + " \"entry_type\": \"GENESIS\"},"
                + "{\"account_id\": %d, \"asset_id\": %d, \"amount\": \"%s\","
                + " \"entry_type\": \"GENESIS\"}]")
                .formatted(genesisKrw, krwAssetId, INITIAL_KRW,
                           userKrwAvailable, krwAssetId, INITIAL_KRW);
        jdbc.sql("SELECT post_ledger_transaction('GENESIS', ?, ?::jsonb)")
                .params(genesisEventId, entries)
                .query(Long.class)
                .single();
        jdbc.sql("UPDATE user_provisioning SET status = 'DONE', completed_at = now() WHERE user_id = ?")
                .params(userId)
                .update();
        return userId;
    }

    public SessionResult login(String email, String password, String ip, String userAgent) {
        // 로그인은 조회다. 형식 규칙을 여기서 다시 걸면 규칙이 엄해질 때마다 이전 규칙으로
        // 가입한 계정이 로그인을 잃는다(MemberInputs.normalizeEmailForLookup 주석)
        String normalizedEmail = MemberInputs.normalizeEmailForLookup(email);
        if (normalizedEmail == null || password == null || password.isEmpty()) {
            throw invalidCredentials();
        }
        Optional<Credential> found = jdbc.sql("""
                        SELECT u.id, u.status, c.password_hash, c.locked_until
                        FROM users u JOIN user_credentials c ON c.user_id = u.id
                        WHERE u.email = ? AND u.kind = 'HUMAN'
                        """)
                .params(normalizedEmail)
                .query((rs, rowNum) -> new Credential(
                        rs.getLong("id"), rs.getString("status"), rs.getString("password_hash"),
                        rs.getTimestamp("locked_until") == null ? null
                                : rs.getTimestamp("locked_until").toInstant()))
                .optional();
        if (found.isEmpty()) {
            // 존재 여부 비노출: 미가입도 더미 해시 검증으로 유사 지연 후 동일 코드 반환.
            hasher.matches(password, dummyHash);
            throw invalidCredentials();
        }
        Credential credential = found.get();
        if (!"ACTIVE".equals(credential.status())) {
            throw new ApiException(403, "account_suspended", "이용이 제한된 계정입니다.");
        }
        Instant now = Instant.now();
        if (credential.lockedUntil() != null && credential.lockedUntil().isAfter(now)) {
            throw accountLocked(credential.lockedUntil(), now);
        }
        if (!hasher.matches(password, credential.passwordHash())) {
            registerFailure(credential.userId(), now);
            throw invalidCredentials();
        }
        jdbc.sql("""
                        UPDATE user_credentials SET failed_attempts = 0, locked_until = NULL
                        WHERE user_id = ?
                        """)
                .params(credential.userId())
                .update();
        return issueSession(credential.userId(), ip, userAgent);
    }

    /**
     * 실패 카운트 증분과 잠금 판정을 단일 원자 UPDATE로 처리한다. READ COMMITTED에서 SELECT 후
     * 맹목 기입은 동시 오입력이 같은 값을 읽고 써 증분이 유실돼(lost update) LOCK_THRESHOLD에
     * 도달하지 못한다. failed_attempts = failed_attempts + 1로 행 잠금 내 증분해 유실을 없앤다.
     * 지수 백오프 상수(임계·기준·상한)는 SQL 리터럴로 주입한다(private static final int, 안전).
     */
    private void registerFailure(long userId, Instant now) {
        String sql = ("""
                        UPDATE user_credentials
                        SET failed_attempts = failed_attempts + 1,
                            locked_until = CASE
                                WHEN failed_attempts + 1 >= %d
                                THEN now() + make_interval(secs => least(%d,
                                         %d * power(2, least(failed_attempts + 1 - %d, 20))))
                                ELSE locked_until
                            END
                        WHERE user_id = ?
                        RETURNING (locked_until IS NOT NULL AND locked_until > now()) AS locked,
                                  extract(epoch FROM locked_until)::bigint AS locked_epoch
                        """).formatted(LOCK_THRESHOLD, MAX_LOCK_SECONDS, BASE_LOCK_SECONDS,
                                       LOCK_THRESHOLD);
        LockState state = jdbc.sql(sql)
                .params(userId)
                .query((rs, rowNum) -> new LockState(rs.getBoolean("locked"), rs.getLong("locked_epoch")))
                .single();
        if (state.locked()) {
            throw accountLocked(Instant.ofEpochSecond(state.lockedEpoch()), now);
        }
    }

    public SessionResult refresh(String rawRefresh, String ip, String userAgent) {
        if (rawRefresh == null || rawRefresh.isBlank()) {
            throw sessionExpired();
        }
        String hash = sha256Hex(rawRefresh);
        Optional<RefreshRow> found = jdbc.sql("""
                        SELECT id, user_id, family_id::text AS family_id, expires_at, used_at, revoked_at
                        FROM auth_sessions WHERE token_hash = ?
                        """)
                .params(hash)
                .query((rs, rowNum) -> new RefreshRow(
                        rs.getLong("id"), rs.getLong("user_id"), rs.getString("family_id"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("used_at") != null,
                        rs.getTimestamp("revoked_at") != null))
                .optional();
        if (found.isEmpty()) {
            throw sessionExpired();
        }
        RefreshRow row = found.get();
        if (row.burned() || row.revoked()) {
            // 소각·폐기된 토큰 재제출은 도난 신호다. family 전체를 폐기하고 재사용으로 판정한다(§1-3).
            revokeFamily(row.familyId());
            throw new ApiException(401, "refresh_reuse", "재사용된 세션입니다. 다시 로그인하십시오.");
        }
        if (!row.expiresAt().isAfter(Instant.now())) {
            throw sessionExpired();
        }
        String status = jdbc.sql("SELECT status FROM users WHERE id = ?")
                .params(row.userId())
                .query(String.class)
                .single();
        if (!"ACTIVE".equals(status)) {
            throw new ApiException(403, "account_suspended", "이용이 제한된 계정입니다.");
        }

        String rawNext = randomToken();
        try {
            tx.executeWithoutResult(txStatus -> {
                // revoked_at IS NULL을 조건에 포함해 폐기 family의 되살아남을 물리 차단한다. 초기 SELECT(:229)와
                // 소각 사이에 revokeFamily(로그아웃·재사용 탐지)가 커밋되면 소각이 0행이 되어 새 tip을 INSERT하지
                // 못한다. used_at IS NULL만 검사하면 폐기된 family에 살아있는 세션이 재생성된다(high).
                int burned = jdbc.sql("""
                                UPDATE auth_sessions SET used_at = now()
                                WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL
                                """)
                        .params(row.id())
                        .update();
                if (burned != 1) {
                    // 동시 회전 또는 폐기 선커밋: 소각 대상이 이미 소각·폐기됐다. 롤백 후 외부에서 재사용 처리한다.
                    throw new RotationRaceException();
                }
                insertSession(row.userId(), row.familyId(), rawNext, row.id(), ip, userAgent);
            });
        } catch (RotationRaceException e) {
            // 회전 소각·삽입은 롤백됐다. family 폐기는 별도 커밋으로 확정한다.
            revokeFamily(row.familyId());
            throw new ApiException(401, "refresh_reuse", "재사용된 세션입니다. 다시 로그인하십시오.");
        }

        String userExternalId = externalId(row.userId());
        String accessToken = sessions.issue(row.userId(), userExternalId, "HUMAN");
        return new SessionResult(accessToken, rawNext, REFRESH_TTL_SECONDS, userView(row.userId()));
    }

    public void logout(String rawRefresh) {
        if (rawRefresh == null || rawRefresh.isBlank()) {
            return;
        }
        String familyId = jdbc.sql("SELECT family_id::text FROM auth_sessions WHERE token_hash = ?")
                .params(sha256Hex(rawRefresh))
                .query(String.class)
                .optional()
                .orElse(null);
        if (familyId != null) {
            revokeFamily(familyId);
        }
    }

    public Map<String, Object> userView(long userId) {
        Map<String, Object> view = jdbc.sql("""
                        SELECT u.external_id::text AS external_id, u.email, u.kind, p.display_name
                        FROM users u JOIN user_profiles p ON p.user_id = u.id
                        WHERE u.id = ?
                        """)
                .params(userId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("external_id", rs.getString("external_id"));
                    row.put("email", rs.getString("email"));
                    row.put("display_name", rs.getString("display_name"));
                    row.put("kind", rs.getString("kind"));
                    return row;
                })
                .single();
        return view;
    }

    private SessionResult issueSession(long userId, String ip, String userAgent) {
        String externalId = externalId(userId);
        String rawRefresh = randomToken();
        String familyId = UUID.randomUUID().toString();
        insertSession(userId, familyId, rawRefresh, null, ip, userAgent);
        String accessToken = sessions.issue(userId, externalId, "HUMAN");
        return new SessionResult(accessToken, rawRefresh, REFRESH_TTL_SECONDS, userView(userId));
    }

    private void insertSession(long userId, String familyId, String rawRefresh, Long prevId,
                               String ip, String userAgent) {
        jdbc.sql("""
                        INSERT INTO auth_sessions
                            (user_id, family_id, token_hash, prev_id, expires_at, user_agent, ip)
                        VALUES (?, ?::uuid, ?, ?, now() + make_interval(secs => ?), ?, ?::inet)
                        """)
                .params(userId, familyId, sha256Hex(rawRefresh), prevId,
                        (double) REFRESH_TTL_SECONDS, truncate(userAgent, 256),
                        // 비 IP 값이 ?::inet 캐스트로 500·고아 세션을 만들지 않게 사전 검증한다. 무효면 null 저장.
                        ClientIp.inetOrNull(ip))
                .update();
    }

    private void revokeFamily(String familyId) {
        jdbc.sql("""
                        UPDATE auth_sessions SET revoked_at = now()
                        WHERE family_id = ?::uuid AND revoked_at IS NULL
                        """)
                .params(familyId)
                .update();
    }

    private String externalId(long userId) {
        return jdbc.sql("SELECT external_id::text FROM users WHERE id = ?")
                .params(userId)
                .query(String.class)
                .single();
    }

    private long accountId(String ownerType, long ownerId, String purpose, int assetId) {
        return jdbc.sql("""
                        SELECT id FROM accounts
                        WHERE owner_type = ? AND owner_id = ? AND purpose = ? AND asset_id = ? AND shard = 0
                        """)
                .params(ownerType, ownerId, purpose, assetId)
                .query(Long.class)
                .single();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }


    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ApiException(400, "weak_password",
                    "비밀번호는 최소 " + MIN_PASSWORD_LENGTH + "자입니다.");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new ApiException(400, "weak_password",
                    "비밀번호는 최대 " + MAX_PASSWORD_LENGTH + "자입니다.");
        }
    }


    private static ApiException invalidCredentials() {
        return new ApiException(401, "invalid_credentials", "이메일 또는 비밀번호를 확인해 주세요.");
    }

    private static ApiException sessionExpired() {
        return new ApiException(401, "session_expired", "세션이 만료되었습니다. 다시 로그인하십시오.");
    }

    private static ApiException accountLocked(Instant lockedUntil, Instant now) {
        int retryAfter = (int) Math.max(1, lockedUntil.getEpochSecond() - now.getEpochSecond());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("retry_after_seconds", retryAfter);
        return new ApiException(423, "account_locked",
                "로그인 실패가 누적되어 계정이 일시 잠금되었습니다.", detail, retryAfter);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 계산 실패", e);
        }
    }

    private record Credential(long userId, String status, String passwordHash, Instant lockedUntil) {
    }

    private record LockState(boolean locked, long lockedEpoch) {
    }

    private record RefreshRow(long id, long userId, String familyId, Instant expiresAt,
                              boolean burned, boolean revoked) {
    }

    // 회전 경합 롤백 신호. TransactionTemplate 밖에서 family 폐기를 별도 커밋하기 위한 제어 예외.
    private static final class RotationRaceException extends RuntimeException {
        RotationRaceException() {
            super(null, null, false, false);
        }
    }
}
