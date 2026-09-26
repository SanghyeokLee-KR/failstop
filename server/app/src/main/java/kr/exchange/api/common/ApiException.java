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

    /**
     * 좀비 판정의 노드 층 수요에서 응답 코드와 무관하게 뺄 요청이라는 속성(DB 설계 §5.4-c 8 v2.57). 이 노드가 할 일이 아닌
     * 요청이다. 게이트웨이의 소유 아님 503 과, 이 노드에서 submit 을 하나도 하지 않은 일괄 취소가 세운다. 인증 필터가 체인
     * 뒤에 본다.
     */
    public static final String DEMAND_NOT_LOCAL_ATTRIBUTE = ApiException.class.getName() + ".demandNotLocal";

    /** 소유 아님 응답에 다는 응답 헤더와 값(API 설계 §2-5-d). 인그레스 접근 로그가 이 헤더를 적는다. */
    public static final String ROUTE_HEADER = "X-Exchange-Route";
    public static final String ROUTE_NOT_OWNER_VALUE = "not-owner";

    /**
     * 이 노드가 접수하지 않는 마켓의 주문 쓰기(API 설계 §2-5-d). 아무것도 쓰지 않은 거절이라 인그레스가 같은 요청을 다른
     * 노드로 다시 보낸다. 선검사 필터와 게이트웨이(힌트 없는 단건 취소)가 같은 모양을 낸다.
     */
    public static ApiException notOwner() {
        return new ApiException(503, "service_unavailable", "이 노드가 접수하지 않는 마켓. 다른 노드로 재시도",
                Map.of("route", "not_owner"), 1);
    }

    /**
     * 주문 생성·취소가 DB 연결 장애로 끝났을 때 저널 확인이나 대상 주문 재조회가 DB 에 닿았는가(API 설계 §2-5-c). 렌더하지
     * 않는다. 일괄 취소는 NONE 이 아니면 단건 응답과 무관하게 미완료로 세고, 배치 주문과 일괄 취소는 UNREACHABLE 이면 남은
     * 원소를 시도하지 않는다.
     */
    public enum DbOutage { NONE, REACHED, UNREACHABLE }

    private final int status;
    private final String name;
    private final Map<String, Object> detail;
    private final Integer retryAfter;
    private final boolean demandExcluded;
    private final DbOutage dbOutage;

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
        this(status, name, message, detail, retryAfter, demandExcluded, DbOutage.NONE);
    }

    public ApiException(int status, String name, String message,
                        Map<String, Object> detail, Integer retryAfter, boolean demandExcluded, DbOutage dbOutage) {
        // 제어흐름 전용 예외. 스택트레이스 캡처를 끊어 4xx 정상 경로의 오버헤드를 없앤다.
        super(message, null, false, false);
        this.status = status;
        this.name = name;
        this.detail = detail;
        this.retryAfter = retryAfter;
        this.demandExcluded = demandExcluded;
        this.dbOutage = dbOutage;
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

    public DbOutage dbOutage() {
        return dbOutage;
    }

    /** 소유 아님 503(detail.route=not_owner)인가. 렌더하는 곳이 X-Exchange-Route 헤더를 단다. */
    public boolean routeNotOwner() {
        return status == 503 && detail != null && "not_owner".equals(detail.get("route"));
    }
}
