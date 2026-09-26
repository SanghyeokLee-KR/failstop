package kr.exchange.api.order;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * 마켓 힌트 헤더(API 설계 §2-5-d). 인그레스가 주문 쓰기를 그 마켓 파티션의 소유 노드로 보내는 라우팅 입력이다. 서명 밖이라
 * 아무 권한도 주지 않고, 서버는 요청이 실제로 가리키는 마켓과 대조해 다르면 400 으로 끝낸다. 대조는 문자열 그대로이고
 * 대소문자를 가린다.
 */
public final class MarketHint {

    public static final String HEADER = "X-Exchange-Market";

    private MarketHint() {
    }

    /**
     * 요청의 힌트. 없거나 공백뿐이면 null 이고 앞뒤 공백은 뺀다. 같은 이름의 헤더가 둘 이상이거나 값에 쉼표가 있으면 여러 마켓을
     * 실은 것이라 400 market_hint_duplicate 다. HTTP 는 같은 이름의 헤더를 쉼표로 이어 붙인 한 줄과 같은 뜻으로 보므로 둘을
     * 같게 거절한다. 운영에서는 소유권 선검사가 인증 전에 먼저 거절하고, 여기 판정은 선검사가 없는 배선을 위한 것이다.
     */
    public static String from(HttpServletRequest request) {
        List<String> values = values(request);
        if (values.size() > 1) {
            throw duplicate();
        }
        if (values.isEmpty()) {
            return null;
        }
        String value = values.get(0).trim();
        if (value.indexOf(',') >= 0) {
            throw duplicate();
        }
        return value.isEmpty() ? null : value;
    }

    /** 같은 이름으로 온 헤더 값 목록. 없으면 빈 목록이다. */
    static List<String> values(HttpServletRequest request) {
        List<String> values = new ArrayList<>();
        Enumeration<String> headers = request.getHeaders(HEADER);
        if (headers == null) {
            return values;
        }
        while (headers.hasMoreElements()) {
            values.add(headers.nextElement());
        }
        return values;
    }

    /** 힌트가 요청이 가리키는 마켓과 다르다. */
    public static ApiException mismatch() {
        return new ApiException(400, "invalid_parameter", "마켓 힌트가 요청의 마켓과 다름",
                Map.of("constraint", "market_hint_mismatch"), null);
    }

    /** 같은 이름의 힌트 헤더가 둘 이상이다. */
    public static ApiException duplicate() {
        return new ApiException(400, "invalid_parameter", "마켓 힌트 헤더가 둘 이상",
                Map.of("constraint", "market_hint_duplicate"), null);
    }

    /** 배치 원소의 마켓이 둘 이상이다(API 설계 §4-B-6 v2.57). */
    public static ApiException batchSingleMarket() {
        return new ApiException(400, "invalid_parameter", "한 배치에는 한 마켓만 가능",
                Map.of("constraint", "batch_single_market"), null);
    }
}
