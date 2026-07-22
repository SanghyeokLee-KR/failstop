package kr.exchange.admin;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** admin_operators·operator_roles 조회. 운영자 신원과 효력 역할의 권위 소스. */
public class AdminOperatorDirectory {

    public record Operator(long id, String operatorId, String displayName, String status,
                           Set<AdminRole> roles) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }

        public boolean has(AdminRole role) {
            return roles.contains(role) || roles.contains(AdminRole.SUPER);
        }
    }

    private final JdbcClient jdbc;

    public AdminOperatorDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Operator> find(String operatorId) {
        Optional<Operator> base = jdbc.sql("""
                        SELECT id, operator_id, display_name, status
                        FROM admin_operators WHERE operator_id = ?
                        """)
                .param(operatorId)
                .query((rs, n) -> new Operator(rs.getLong("id"), rs.getString("operator_id"),
                        rs.getString("display_name"), rs.getString("status"),
                        EnumSet.noneOf(AdminRole.class)))
                .optional();
        if (base.isEmpty()) {
            return base;
        }
        Set<AdminRole> roles = rolesOf(operatorId);
        Operator o = base.get();
        return Optional.of(new Operator(o.id(), o.operatorId(), o.displayName(), o.status(), roles));
    }

    public Set<AdminRole> rolesOf(String operatorId) {
        List<String> raw = jdbc.sql("SELECT role FROM operator_roles WHERE operator_id = ?")
                .param(operatorId)
                .query(String.class)
                .list();
        Set<AdminRole> roles = EnumSet.noneOf(AdminRole.class);
        for (String r : raw) {
            roles.add(AdminRole.valueOf(r));
        }
        return roles;
    }

    public List<Operator> list() {
        List<Operator> ops = jdbc.sql("""
                        SELECT id, operator_id, display_name, status
                        FROM admin_operators ORDER BY operator_id
                        """)
                .query((rs, n) -> new Operator(rs.getLong("id"), rs.getString("operator_id"),
                        rs.getString("display_name"), rs.getString("status"),
                        EnumSet.noneOf(AdminRole.class)))
                .list();
        return ops.stream()
                .map(o -> new Operator(o.id(), o.operatorId(), o.displayName(), o.status(),
                        rolesOf(o.operatorId())))
                .toList();
    }
}
