package kr.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;

/**
 * admin_audit_log append. 전 관리 액션(조회 포함)이 감사 대상이다(§3-6). operator_id는
 * 인증 주체 유래 값을 적재한다. 테이블은 forbid_mutation 트리거로 수정·삭제가 봉쇄된다.
 */
public class AdminAuditService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AdminAuditService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void record(String operatorId, String action, String target, String requestId,
                       Long approvalId, String result, Map<String, Object> detail) {
        String json;
        try {
            json = objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (Exception e) {
            json = "{}";
        }
        jdbc.sql("""
                        INSERT INTO admin_audit_log
                            (operator_id, action, target, request_id, approval_id, result, detail)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                        """)
                .params(operatorId, action, target, requestId, approvalId, result, json)
                .update();
    }
}
