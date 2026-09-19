package kr.exchange.config;

import io.micrometer.common.KeyValue;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicRequestObservationConventionTest {

    private final PublicRequestObservationConvention convention = new PublicRequestObservationConvention();
    private final DefaultServerRequestObservationConvention defaults = new DefaultServerRequestObservationConvention();

    @Test
    void rejectedKnownRoutesUseFiniteTemplatesAndRetainDefaultTags() {
        for (String path : new String[]{"/api/v1/markets", "/api/v1/orderbook", "/api/v1/trades",
                "/api/v1/ticker", "/api/v1/exchange-config", "/api/v1/candles/1m",
                "/api/v1/candles/private-input"}) {
            ServerRequestObservationContext context = context("GET", path, 429);
            context.getCarrier().setAttribute("test", "not-a-metric-label");
            Map<String, String> expected = tags(defaults, context);
            expected.put("uri", path.startsWith("/api/v1/candles/") ? "/api/v1/candles/{unit}" : path);
            assertEquals(expected, tags(convention, context), path);
        }
    }

    @Test
    void unknownAndUnmatchedRequestsKeepDefaultCardinality() {
        for (String path : new String[]{"/api/v1/trades/private-id", "/api/v1/trades-other",
                "/api/v1/candles", "/api/v1/candles/1m/private-id", "/api/v1/candles/1m;secret=value",
                "/api/v1/orders/private-id", "/api/v1/markets/", "/private-id"}) {
            ServerRequestObservationContext context = context("GET", path, 429);
            assertEquals(tags(defaults, context), tags(convention, context), path);
        }
        assertEquals("/api/v1/trades", tags(convention, context("HEAD", "/api/v1/trades", 429)).get("uri"));
        assertEquals("UNKNOWN", tags(convention, context("POST", "/api/v1/trades", 429)).get("uri"));
    }

    @Test
    void matchedRoutesAndOtherStatusesRemainUnchanged() {
        for (int status : new int[]{200, 400, 404, 429, 500}) {
            ServerRequestObservationContext context = context("GET", "/api/v1/candles/1m", status);
            if (status == 429) {
                context.setPathPattern("/matched/{id}");
            }
            assertEquals(tags(defaults, context), tags(convention, context));
        }
    }

    private ServerRequestObservationContext context(String method, String path, int status) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setQueryString("market=private-market&cursor=private-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        return new ServerRequestObservationContext(request, response);
    }

    private Map<String, String> tags(DefaultServerRequestObservationConvention source,
                                   ServerRequestObservationContext context) {
        Map<String, String> result = new LinkedHashMap<>();
        for (KeyValue value : source.getLowCardinalityKeyValues(context)) {
            result.put(value.getKey(), value.getValue());
        }
        return result;
    }
}
