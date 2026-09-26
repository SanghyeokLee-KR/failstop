package kr.exchange.api.auth;

import kr.exchange.journal.DbReadRetry;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

/**
 * api_keys 조회. 폐기(deleted_at) 키는 즉시 무효다(API 설계 §3-2 revocation).
 *
 * 조회가 연결 끊김으로 끝나면 다른 연결로 한 번 더 읽는다(API 설계 §2-5-d 'DB가 흔들린 직후'). 끊긴 연결 하나로 인증이 503 을
 * 내 리더가 쓰기 업스트림에서 빠지는 일을 줄이려는 것이다. 읽기라 두 번 불러도 부수 효과가 없다.
 */
public class ApiKeyDirectory {

    public record ApiKey(long userId, String accessKey, String encSecret, String scope) {
    }

    private final JdbcClient jdbc;
    private final DbReadRetry readRetry;

    public ApiKeyDirectory(JdbcClient jdbc) {
        this(jdbc, DbReadRetry.untracked());
    }

    public ApiKeyDirectory(JdbcClient jdbc, DbReadRetry readRetry) {
        this.jdbc = jdbc;
        this.readRetry = readRetry;
    }

    public Optional<ApiKey> findActive(String accessKey) {
        return readRetry.read(DbReadRetry.AUTH, () -> jdbc.sql("""
                        SELECT user_id, access_key, enc_secret, scope
                        FROM api_keys
                        WHERE access_key = ? AND deleted_at IS NULL
                        """)
                .params(accessKey)
                .query((rs, rowNum) -> new ApiKey(
                        rs.getLong("user_id"), rs.getString("access_key"),
                        rs.getString("enc_secret"), rs.getString("scope")))
                .optional());
    }
}
