package kr.exchange.admin;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Set;

/**
 * 운영자 레지스트리 시드(dev/demo·통합테스트 전용). 마이그레이션은 프로파일 무관 적용이라
 * 데모 신원을 넣지 않고, 운영자 등록은 런타임 시드로 분리한다. 멱등(기존 행 skip).
 *
 * operator_id는 데모 mTLS 클라이언트 인증서 CN(op-01/op-02)과 정합해야 세션 발급이 성립한다.
 */
public class AdminOperatorSeeder {

    private final JdbcClient jdbc;

    public AdminOperatorSeeder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureOperator(String operatorId, String displayName, Set<AdminRole> roles) {
        jdbc.sql("""
                        INSERT INTO admin_operators (operator_id, display_name)
                        VALUES (?, ?) ON CONFLICT (operator_id) DO NOTHING
                        """)
                .params(operatorId, displayName)
                .update();
        for (AdminRole role : roles) {
            jdbc.sql("""
                            INSERT INTO operator_roles (operator_id, role)
                            VALUES (?, ?) ON CONFLICT (operator_id, role) DO NOTHING
                            """)
                    .params(operatorId, role.name())
                    .update();
        }
    }
}
