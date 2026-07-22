package kr.exchange.api.common;

import kr.exchange.engine.NotLeaderException;
import kr.exchange.engine.NotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
    public ResponseEntity<Object> apiException(ApiException e) {
        return render(e);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> missingParameter(MissingServletRequestParameterException e) {
        return render(new ApiException(400, "invalid_parameter",
                "필수 파라미터 누락: " + e.getParameterName()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return render(new ApiException(405, "method_not_allowed", "허용되지 않은 메서드입니다."));
    }

    @ExceptionHandler(NotLeaderException.class)
    public ResponseEntity<Object> notLeader(NotLeaderException e) {
        // 페일오버 창. 클라이언트는 UNKNOWN 처리 후 재조회 계약(§7-6)으로 수렴한다.
        return render(new ApiException(503, "service_unavailable",
                "페일오버 진행 중입니다. 재조회 후 판단하십시오.", null, 1));
    }

    @ExceptionHandler(NotReadyException.class)
    public ResponseEntity<Object> notReady(NotReadyException e) {
        // 승격 재생 중. 리더 인지 LB는 readiness DOWN으로 이미 제외하나, 경합 창 접수는 여기서 흡수.
        return render(new ApiException(503, "service_unavailable",
                "승격 준비 중입니다. 잠시 후 재시도하십시오.", null, 1));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Object> illegalState(IllegalStateException e) {
        log.error("게이트웨이 내부 상태 오류", e);
        return render(new ApiException(503, "service_unavailable",
                "일시적으로 요청을 처리할 수 없습니다.", null, 1));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return render(new ApiException(500, "server_error", "일시적 서버 오류입니다."));
    }

    private ResponseEntity<Object> render(ApiException e) {
        String requestId = UUID.randomUUID().toString();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.valueOf(e.status()))
                .header("X-Request-Id", requestId)
                .header("Content-Type", "application/json;charset=utf-8");
        if (e.retryAfter() != null) {
            builder.header("Retry-After", String.valueOf(e.retryAfter()));
        }
        return builder.body(renderer.body(e.name(), e.getMessage(), e.detail(),
                e.retryAfter(), requestId));
    }
}
