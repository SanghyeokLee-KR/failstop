package kr.exchange.api.common;

import java.util.Map;

/**
 * 표준 에러 엔벌로프(API 설계 §2-3)로 렌더링되는 예외. name이 계약 어휘의 정본이고
 * HTTP status는 name에 종속된 표기다.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String name;
    private final Map<String, Object> detail;
    private final Integer retryAfter;

    public ApiException(int status, String name, String message) {
        this(status, name, message, null, null);
    }

    public ApiException(int status, String name, String message,
                        Map<String, Object> detail, Integer retryAfter) {
        super(message);
        this.status = status;
        this.name = name;
        this.detail = detail;
        this.retryAfter = retryAfter;
    }

    public int status() {
        return status;
    }

    public String name() {
        return name;
    }

    public Map<String, Object> detail() {
        return detail;
    }

    public Integer retryAfter() {
        return retryAfter;
    }
}
