package kr.exchange.api.auth;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

/** api_keys 조회. 폐기(deleted_at) 키는 즉시 무효다(API 설계 §3-2 revocation). */
public class ApiKeyDirectory {

    public record ApiKey(long userId, String accessKey, String encSecret, String scope) {
    }

    private final JdbcClient jdbc;

    public ApiKeyDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ApiKey> findActive(String accessKey) {
        return jdbc.sql("""
                        SELECT user_id, access_key, enc_secret, scope
                        FROM api_keys
                        WHERE access_key = ? AND deleted_at IS NULL
                        """)
                .params(accessKey)
                .query((rs, rowNum) -> new ApiKey(
                        rs.getLong("user_id"), rs.getString("access_key"),
                        rs.getString("enc_secret"), rs.getString("scope")))
                .optional();
    }
}
