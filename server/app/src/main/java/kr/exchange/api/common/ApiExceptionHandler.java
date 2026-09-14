package kr.exchange.api.common;

import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.engine.NotLeaderException;
import kr.exchange.engine.NotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

/** 컨트롤러 계층 예외를 §2-3 표준 에러 포맷으로 변환한다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ErrorRenderer renderer;

    public ApiExceptionHandler(ErrorRenderer renderer) {
        this.renderer = renderer;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> apiException(ApiException e, HttpServletResponse response) {
        return render(e, response);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> missingParameter(MissingServletRequestParameterException e,
                                                   HttpServletResponse response) {
        return render(new ApiException(400, "invalid_parameter",
                "필수 파라미터 누락: " + e.getParameterName()), response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> typeMismatch(MethodArgumentTypeMismatchException e,
                                               HttpServletResponse response) {
        // count=abc 같은 타입 불일치. 노출 지점은 전부 멱등 GET이라 §2-3 400으로 정규화한다.
        return render(new ApiException(400, "invalid_parameter",
                "파라미터 형식 오류: " + e.getName()), response);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> methodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                   HttpServletResponse response) {
        return render(new ApiException(405, "method_not_allowed", "허용되지 않은 메서드입니다."), response);
    }

    @ExceptionHandler(NotLeaderException.class)
    public ResponseEntity<Object> notLeader(NotLeaderException e, HttpServletResponse response) {
        // 페일오버 창. 클라이언트는 UNKNOWN 처리 후 재조회 계약(§7-6)으로 수렴한다.
        return render(new ApiException(503, "service_unavailable",
                "페일오버 진행 중. 재조회 후 판단 필요", null, 1), response);
    }

    @ExceptionHandler(NotReadyException.class)
    public ResponseEntity<Object> notReady(NotReadyException e, HttpServletResponse response) {
        // 승격 재생 중. 리더 인지 LB는 readiness DOWN으로 이미 제외하나, 경합 창 접수는 여기서 흡수.
        return render(new ApiException(503, "service_unavailable",
                "승격 준비 중. 잠시 후 재시도 필요", null, 1), response);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Object> illegalState(IllegalStateException e, HttpServletResponse response) {
        String requestId = resolveRequestId(response);
        log.error("게이트웨이 내부 상태 오류 request_id={}", requestId, e);
        return render(new ApiException(503, "service_unavailable",
                "일시적 처리 불가", null, 1), response, requestId);
    }

    @ExceptionHandler({TransientDataAccessException.class, CannotGetJdbcConnectionException.class,
            CannotCreateTransactionException.class})
    public ResponseEntity<Object> transientDataAccess(RuntimeException e, HttpServletResponse response) {
        // 풀 고갈·락 타임아웃·직렬화 실패 등 일시 backpressure. catch-all 500으로 떨구면 자체 규약상
        // 재시도 가능한 신호가 소실되고 매 건 ERROR 로그로 알람이 폭증한다. 503+Retry-After로 신호하고
        // WARN으로 낮춘다. NonTransient(무결성 위반 등)는 이 핸들러를 타지 않고 아래 catch-all 500 유지.
        // TransactionTemplate 경로(접수 append 전용 풀 등)는 커넥션 획득 실패를 트랜잭션 시작 실패로
        // 감싸 던지므로 CannotCreateTransactionException 도 같은 사유로 받는다.
        String requestId = resolveRequestId(response);
        log.warn("일시적 DB 접근 실패 request_id={}: {}", requestId, e.getMessage());
        return render(new ApiException(503, "service_unavailable",
                "일시적 처리 불가. 잠시 후 재시도 필요", null, 1), response, requestId);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> noResource(NoResourceFoundException e, HttpServletResponse response) {
        // 미매핑 경로·정적 리소스 부재는 본래 404다. catch-all Exception 핸들러가 먼저 삼키면
        // 500 server_error로 오분류되고 오탐 경로마다 ERROR 로그가 쌓인다. 표준 404로 정규화한다.
        return render(new ApiException(404, "not_found", "존재하지 않는 리소스입니다."), response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception e, HttpServletResponse response) {
        String requestId = resolveRequestId(response);
        log.error("처리되지 않은 예외 request_id={}", requestId, e);
        return render(new ApiException(500, "server_error", "일시적 서버 오류입니다."), response, requestId);
    }

    private ResponseEntity<Object> render(ApiException e, HttpServletResponse response) {
        return render(e, response, resolveRequestId(response));
    }

    private ResponseEntity<Object> render(ApiException e, HttpServletResponse response, String requestId) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.valueOf(e.status()))
                .header("X-Request-Id", requestId)
                .header("Content-Type", "application/json;charset=utf-8");
        if (e.retryAfter() != null) {
            builder.header("Retry-After", String.valueOf(e.retryAfter()));
        }
        return builder.body(renderer.body(e.name(), e.getMessage(), e.detail(),
                e.retryAfter(), requestId));
    }

    // 상관 추적(§3-6). 업스트림 필터가 이미 발급한 X-Request-Id가 있으면 재사용해 감사행과
    // 클라이언트 수신값을 일치시킨다. 없으면(고객 평면 등) 신규 발급.
    private static String resolveRequestId(HttpServletResponse response) {
        String existing = response.getHeader("X-Request-Id");
        return existing != null ? existing : UUID.randomUUID().toString();
    }
}
