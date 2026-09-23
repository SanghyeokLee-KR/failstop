package kr.exchange.api.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.EngineBridge;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 트랜잭션 시작 실패가 일시 DB 실패와 같은 503 재시도 신호로 나간다(API 설계 §2-3, ADR 0022 4-1). */
class ApiExceptionHandlerTest {

    @RestController
    static class FailingController {

        // 접수 append 전용 풀이 connectionTimeout 안에 커넥션을 못 주면 트랜잭션 관리자가 이렇게 감싼다.
        @GetMapping("/transaction-start-failure")
        String transactionStartFailure() {
            throw new CannotCreateTransactionException("Could not open JDBC Connection for transaction",
                    new SQLTransientConnectionException("exchange-append - Connection is not available"));
        }

        @GetMapping("/integrity-violation")
        String integrityViolation() {
            throw new DataIntegrityViolationException("uq_orders_client");
        }

        @GetMapping("/accept-busy")
        String acceptBusy() {
            throw new EngineBridge.AcceptBusyException(1, 3000);
        }

        // append 트랜잭션의 lock_timeout(55P03)이 Spring 6 기본 번역을 거친 모양이다. 분류되지 않아
        // UncategorizedSQLException 으로 온다(AcceptLockTimeoutIntegrationTest 실측).
        @GetMapping("/append-lock-timeout")
        String appendLockTimeout() {
            throw new UncategorizedSQLException("append", "SELECT * FROM append_command_prologue(...)",
                    new SQLException("canceling statement due to lock timeout", "55P03"));
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ApiExceptionHandler(new ErrorRenderer(new ObjectMapper())))
            .build();

    @Test
    void transactionStartFailureIsRetryableServiceUnavailable() throws Exception {
        mvc.perform(get("/transaction-start-failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error.name").value("service_unavailable"))
                .andExpect(jsonPath("$.error.retry_after").value(1));
    }

    /** 접수 락 대기 상한 초과는 저널 전 거부라 503 재시도 신호로 나간다(ADR 0034 S1, API 설계 v2.53). */
    @Test
    void acceptBusyIsRetryableServiceUnavailable() throws Exception {
        mvc.perform(get("/accept-busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error.name").value("service_unavailable"));
    }

    /**
     * 잠금 대기 초과(55P03)는 이번 판에서 503 으로 바꾸지 않는다(API 설계 v2.52·v2.53, ADR 0034 결정 11).
     * 쓰기 업스트림의 max_fails=1 이 503 하나로 리더를 빼므로 인그레스 설정과 함께 S3·S4 에서 정한다. 그 결정 없이
     * 매핑이 먼저 들어오면 이 시험이 실패해 계약과 함께 고치게 한다.
     */
    @Test
    void appendLockTimeoutStaysServerErrorUntilIngressDecision() throws Exception {
        mvc.perform(get("/append-lock-timeout"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.name").value("server_error"));
    }

    /** 대조. 무결성 위반은 재시도 신호가 아니다. 위 매핑이 넓게 삼키면 이것도 503 이 된다. */
    @Test
    void integrityViolationStaysServerError() throws Exception {
        mvc.perform(get("/integrity-violation"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.name").value("server_error"));
    }
}
