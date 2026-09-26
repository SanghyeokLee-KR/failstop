package kr.exchange.api.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.MarketHint;
import kr.exchange.engine.EngineBridge;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 소유권 선검사(API 설계 §2-5-d, S5 설계 4-1). 주문 쓰기가 이 노드가 접수하지 않는 마켓을 가리키면 아무 상태도 건드리기 전에
 * 503 service_unavailable + Retry-After: 1 + detail.route=not_owner + X-Exchange-Route: not-owner 로 끝낸다. 인그레스가 같은
 * 요청을 다른 노드로 다시 보낸다.
 *
 * 필터 순서는 바디 상한 4, 이 필터 5, 수용 제한 6, IP 버킷 7, 인증 10 이다. 그래서 거절은 수용 슬롯, IP 토큰, 좀비 판정 수요,
 * nonce, 계정 토큰, 멱등 키, 예약, 채번, DB 를 모두 건드리지 않는다. 수용 제한보다 앞에 두는 이유는 포화된 비소유 노드가 429 를
 * 내면 인그레스가 다시 보내지 않아 한가한 소유 노드가 있어도 클라이언트가 429 를 받기 때문이다.
 *
 * 마켓은 서명된 값을 먼저 본다. 생성은 바디 최상위의 market, 배치는 market 이 문자열인 첫 원소의 값, 일괄 취소는 market
 * 쿼리다. 그 값이 없거나 카탈로그에 없을 때만 힌트를 본다. 단건 취소는 서명된 값에 마켓이 없어 힌트만 본다. 힌트가 틀려도
 * (소문자, 다른 마켓) 소유하지 않는 노드가 그 요청을 인증하지 않게 하려는 것이다. 힌트와 서명된 값이 다르면 소유 노드가 인증 뒤
 * 400 을 낸다.
 *
 * 메모리만 읽는다. 카탈로그 스냅샷(cachedBySymbol)과 파티션 대응(acceptingIfKnown)을 다시 읽지 않는다. 인증 전의 요청이 DB
 * 조회를 일으키지 않게 하려는 것이다. 모르는 마켓, 깨진 JSON, 마켓을 찾지 못한 요청은 거절하지 않고 뒤 단계로 넘긴다. 뒤
 * 단계가 종전처럼 판정한다. 라우팅은 권고다. 거절만 하고 다른 노드 대신 수락하지 않으며, 쓰기 판정은 DB fencing 이 한다.
 *
 * 조회 역할 노드에는 등록하지 않는다(조회 역할 필터가 쓰기를 먼저 막는다). 엔진을 끈 노드에는 등록한다. 그 노드는 모든 주문
 * 쓰기를 여기서 돌려보낸다.
 */
public class MarketRoutingFilter extends OncePerRequestFilter {

    /** 선검사 대상. 나머지와 OPTIONS 는 그대로 넘긴다. */
    enum Target { CREATE, BATCH, CANCEL_ALL, CANCEL_ONE }

    private static final String ORDERS = "/api/v1/orders";

    private final ErrorRenderer errors;
    private final MarketDirectory markets;
    private final EngineBridge bridge;
    private final ObjectMapper objectMapper;
    private final RoutingCounters counters;

    public MarketRoutingFilter(ErrorRenderer errors, MarketDirectory markets, EngineBridge bridge,
                               ObjectMapper objectMapper, RoutingCounters counters) {
        this.errors = errors;
        this.markets = markets;
        this.bridge = bridge;
        this.objectMapper = objectMapper;
        this.counters = counters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return target(request.getMethod(), request.getRequestURI()) == null;
    }

    static Target target(String method, String path) {
        if (path == null) {
            return null;
        }
        if ("POST".equals(method)) {
            if (ORDERS.equals(path)) {
                return Target.CREATE;
            }
            return (ORDERS + "/batch").equals(path) ? Target.BATCH : null;
        }
        if ("DELETE".equals(method)) {
            if (ORDERS.equals(path)) {
                return Target.CANCEL_ALL;
            }
            // DELETE /api/v1/orders/{id} 와 /api/v1/orders/by-client/{client_order_id}
            return path.startsWith(ORDERS + "/") ? Target.CANCEL_ONE : null;
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Target target = target(request.getMethod(), request.getRequestURI());
        String hint;
        try {
            hint = MarketHint.from(request);
        } catch (ApiException duplicate) {
            counters.hintRejected(RoutingCounters.HINT_DUPLICATE);
            errors.write(response, duplicate);
            return;
        }
        HttpServletRequest effective = request;
        String signed = null;
        if (target == Target.CREATE || target == Target.BATCH) {
            byte[] body;
            try {
                // 바디 상한 필터가 캐시한 바이트를 다시 읽는다. 그 필터가 없는 배선이면 여기서 읽고 캐시해 넘긴다.
                body = RequestBodyLimit.readBounded(request);
            } catch (ApiException tooLarge) {
                errors.write(response, tooLarge);
                return;
            }
            if (!(request instanceof CachedBodyRequest)) {
                effective = new CachedBodyRequest(request, body);
            }
            signed = target == Target.CREATE ? createMarket(body) : batchMarket(body);
        } else if (target == Target.CANCEL_ALL) {
            signed = request.getParameter("market");
        }
        MarketDirectory.Market market = signed == null ? null : markets.cachedBySymbol(signed);
        if (market == null && hint != null) {
            market = markets.cachedBySymbol(hint);
        }
        Boolean owned = market == null ? null : bridge.acceptingIfKnown(market.id());
        if (owned == null) {
            counters.precheck(RoutingCounters.UNRESOLVED);
            chain.doFilter(effective, response);
            return;
        }
        if (owned) {
            counters.precheck(RoutingCounters.ACCEPTED);
            chain.doFilter(effective, response);
            return;
        }
        counters.precheck(RoutingCounters.NOT_OWNER);
        errors.write(response, ApiException.notOwner());
    }

    /**
     * 생성 바디 최상위 객체의 market. 같은 이름이 여럿이면 마지막 값이다. 컨트롤러의 트리 파싱이 마지막 값을 쓰므로 같은 값을
     * 봐야 선검사를 통과한 요청이 다른 마켓으로 접수되지 않는다. 문자열이 아니거나 JSON 이 깨졌으면 null 이다.
     */
    String createMarket(byte[] body) {
        try (JsonParser p = objectMapper.getFactory().createParser(body)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            return objectMarket(p);
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }

    /**
     * 배치 바디에서 market 이 문자열인 첫 원소의 값. market 이 없거나 문자열이 아닌 원소는 건너뛴다(컨트롤러가 그 원소만
     * rejected 로 적는다). 컨트롤러의 한 마켓 규칙과 같은 기준이다. JSON 이 깨졌으면 null 이다.
     */
    String batchMarket(byte[] body) {
        try (JsonParser p = objectMapper.getFactory().createParser(body)) {
            if (p.nextToken() != JsonToken.START_ARRAY) {
                return null;
            }
            String first = null;
            JsonToken t;
            while ((t = p.nextToken()) != null && t != JsonToken.END_ARRAY) {
                if (t == JsonToken.START_OBJECT) {
                    String market = objectMarket(p);
                    if (first == null) {
                        first = market;
                    }
                } else {
                    p.skipChildren();
                }
            }
            return t == null ? null : first;
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }

    /**
     * 파서가 객체 시작에 있을 때 그 객체를 끝까지 읽어 최상위 market 을 돌려준다. 같은 이름이 여럿이면 마지막 값이고 마지막
     * 값이 문자열이 아니면 null 이다. 객체가 닫히지 않으면 IOException 이다.
     */
    private static String objectMarket(JsonParser p) throws IOException {
        String market = null;
        JsonToken t;
        while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken value = p.nextToken();
            if ("market".equals(name)) {
                market = value == JsonToken.VALUE_STRING ? p.getText() : null;
            }
            p.skipChildren();
        }
        if (t != JsonToken.END_OBJECT) {
            throw new IOException("객체가 닫히지 않았다");
        }
        return market;
    }
}
