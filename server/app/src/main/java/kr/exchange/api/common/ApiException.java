package kr.exchange.api.common;

import java.util.Map;

/**
 * 표준 에러 엔벌로프(API 설계 §2-3)로 렌더링되는 예외. name이 계약 어휘의 정본이고
 * HTTP status는 name에 종속된 표기다.
 */
public class ApiException extends RuntimeException {

    /**
     * 좀비 판정의 수요에서 뺄 경합 응답이라는 요청 속성(DB 설계 §5.4-c 8). 예외 처리기가 응답을 쓰면서 세우고 인증 필터가
     * 체인 뒤에 본다.
     */
    public static final String DEMAND_EXCLUDED_ATTRIBUTE = ApiException.class.getName() + ".demandExcluded";

    private final int status;
    private final String name;
    private final Map<String, Object> detail;
    private final Integer retryAfter;
    private final boolean demandExcluded;

    public ApiException(int status, String name, String message) {
        this(status, name, message, null, null);
    }

    public ApiException(int status, String name, String message,
                        Map<String, Object> detail, Integer retryAfter) {
        this(status, name, message, detail, retryAfter, false);
    }

    /**
     * demandExcluded 는 이 응답이 마켓 접수 경합이라 좀비 판정의 수요에서 빼야 한다는 표식이다(API 설계 §2-5-b).
     */
    public ApiException(int status, String name, String message,
                        Map<String, Object> detail, Integer retryAfter, boolean demandExcluded) {
        // 제어흐름 전용 예외. 스택트레이스 캡처를 끊어 4xx 정상 경로의 오버헤드를 없앤다.
        super(message, null, false, false);
        this.status = status;
        this.name = name;
        this.detail = detail;
        this.retryAfter = retryAfter;
        this.demandExcluded = demandExcluded;
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

    public boolean demandExcluded() {
        return demandExcluded;
    }
}
