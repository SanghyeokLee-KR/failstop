package kr.exchange.api.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.exchange.engine.EngineBridge;
import kr.exchange.journal.DbFailures;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

        @GetMapping("/outage/{kind}")
        String readOutage(@PathVariable("kind") String kind) {
            throw OUTAGES.get(kind).get();
        }

        @PostMapping("/unsafe/{kind}")
        String unsafeWrite(@PathVariable("kind") String kind) {
            throw OUTAGES.get(kind).get();
        }

        @GetMapping("/client-gone")
        String clientGone() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to write: Broken pipe");
        }
    }

    /** 다시 보내도 한 번의 효과로 모이는 쓰기 처리기(주문·이체와 같은 표식). */
    @RestController
    @RetrySafeWrite
    static class RetrySafeController {

        @PostMapping("/safe/{kind}")
        String safeWrite(@PathVariable("kind") String kind) {
            throw OUTAGES.get(kind).get();
        }
    }

    private static final Map<String, Supplier<RuntimeException>> OUTAGES = Map.of(
            "connection-lost", DbFailures::connectionFailure,
            "terminated", () -> DbFailures.sql("57P01"),
            "no-state", () -> new DataAccessResourceFailureException("connection refused"),
            "rollback-failed", () -> DbFailures.rollbackFailed(DbFailures.connectionFailure()),
            "resource", () -> DbFailures.sql("53200"),
            "acquire", DbFailures::acquireFailure,
            "program-limit", () -> DbFailures.sql("54000"));

    private final AtomicInteger rendered = new AtomicInteger();

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController(), new RetrySafeController())
            .setControllerAdvice(new ApiExceptionHandler(new ErrorRenderer(new ObjectMapper()) {
                @Override
                public ObjectNode body(String name, String message, Map<String, Object> detail, Integer retryAfter,
                                       String requestId) {
                    rendered.incrementAndGet();
                    return super.body(name, message, detail, retryAfter, requestId);
                }
            }))
            .build();

    @Test
    void transactionStartFailureIsRetryableServiceUnavailable() throws Exception {
        mvc.perform(get("/transaction-start-failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error.name").value("service_unavailable"))
                .andExpect(jsonPath("$.error.retry_after").value(1));
    }

    /**
     * 접수 락 대기 상한 초과는 저널 전 거부라 429(bucket market)다(API 설계 v2.55 §2-5-b). 503 이면 max_fails=1 인 쓰기
     * 업스트림이 리더를 빼 모든 마켓의 쓰기가 502 가 된다.
     */
    @Test
    void acceptBusyIsMarketContention429() throws Exception {
        mvc.perform(get("/accept-busy"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error.name").value("too_many_requests"))
                .andExpect(jsonPath("$.error.detail.bucket").value("market"))
                .andExpect(jsonPath("$.error.detail.scope").value("order"));
    }

    /**
     * 주문 생성·취소 밖 경로의 잠금 대기 초과(55P03)는 S4 에서도 500 이다(API 설계 v2.55 §2-3). 인그레스를 바꾸지 않으므로
     * 503 매핑만 켜면 리더를 쓰기 업스트림에서 빼는 경로가 새로 생긴다. S5 가 파티션별 업스트림과 함께 다시 정한다. 그 결정
     * 없이 매핑이 먼저 들어오면 이 시험이 실패해 계약과 함께 고치게 한다.
     */
    @Test
    void nonIntakeLockTimeoutStaysServerErrorUntilS5() throws Exception {
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

    /** 안전 메서드의 DB 연결 장애는 아무것도 쓰지 않았으므로 503 이다(API 설계 §2-5-c). */
    @Test
    void safeMethodOutageIs503() throws Exception {
        for (String kind : new String[] {"connection-lost", "terminated", "no-state", "rollback-failed", "resource"}) {
            mvc.perform(get("/outage/" + kind))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.error.name").value("service_unavailable"))
                    .andExpect(jsonPath("$.error.message").value("DB 연결 장애. 재조회 후 판단 필요"));
        }
    }

    /** 재전송 안전 처리기의 쓰기는 다시 보내도 한 번의 효과로 모이므로 연결 끊김도 503 이다. */
    @Test
    void retrySafeWriteOutageIs503() throws Exception {
        for (String kind : new String[] {"connection-lost", "rollback-failed"}) {
            mvc.perform(post("/safe/" + kind))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.error.name").value("service_unavailable"));
        }
    }

    /**
     * 표식 없는 쓰기(게시판 글 작성처럼 멱등 키 없는 쓰기)의 연결 끊김은 커밋됐을 수 있으므로 500 이다. 인그레스는 500 을 다른
     * 노드로 다시 보내지 않는다. 로그는 처리되지 않은 예외가 아니라 원인을 밝힌 WARN 이다.
     */
    @Test
    void unmarkedWriteConnectionLossStays500WithoutTheUnhandledLog() throws Exception {
        ListAppender<ILoggingEvent> logs = captureHandlerLogs();
        try {
            for (String kind : new String[] {"connection-lost", "rollback-failed", "resource"}) {
                mvc.perform(post("/unsafe/" + kind))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.error.name").value("server_error"));
            }
            assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("처리되지 않은 예외")),
                    "재전송이 안전하지 않은 쓰기의 연결 장애를 처리되지 않은 예외로 남겼다");
            assertEquals(3, logs.list.stream().filter(e -> e.getFormattedMessage()
                    .contains("재전송이 안전하지 않은 경로라 500")).count());
        } finally {
            releaseHandlerLogs(logs);
        }
        // 커넥션 획득 실패는 문장을 보내기 전이라 v2.17 부터 모든 메서드에서 503 이다.
        mvc.perform(post("/unsafe/acquire"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.name").value("service_unavailable"));
    }

    /** 연결이 아닌 자원 실패(54000)는 종전처럼 처리되지 않은 예외의 500 이다. */
    @Test
    void programLimitStaysServerError() throws Exception {
        mvc.perform(get("/outage/program-limit"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.name").value("server_error"));
    }

    /** 클라이언트가 먼저 끊은 요청은 받을 쪽이 없으므로 렌더하지 않는다. */
    @Test
    void unusableResponseIsNotRendered() throws Exception {
        ListAppender<ILoggingEvent> logs = captureHandlerLogs();
        try {
            mvc.perform(get("/client-gone")).andExpect(status().isOk());
            assertEquals(0, rendered.get(), "응답을 쓸 수 없는 요청에 오류 본문을 렌더했다");
            assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("클라이언트 연결이 끊겨")));
            assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("처리되지 않은 예외")));
        } finally {
            releaseHandlerLogs(logs);
        }
    }

    private static ListAppender<ILoggingEvent> captureHandlerLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ApiExceptionHandler.class)).addAppender(appender);
        return appender;
    }

    private static void releaseHandlerLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(ApiExceptionHandler.class)).detachAppender(appender);
    }
}
