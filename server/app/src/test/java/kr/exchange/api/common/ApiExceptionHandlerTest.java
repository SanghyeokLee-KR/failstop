package kr.exchange.api.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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

    /** 대조. 무결성 위반은 재시도 신호가 아니다. 위 매핑이 넓게 삼키면 이것도 503 이 된다. */
    @Test
    void integrityViolationStaysServerError() throws Exception {
        mvc.perform(get("/integrity-violation"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.name").value("server_error"));
    }
}
