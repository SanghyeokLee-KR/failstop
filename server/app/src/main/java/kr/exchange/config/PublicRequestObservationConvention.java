package kr.exchange.config;

import io.micrometer.common.KeyValue;
import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.PublicMarketPaths;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerHttpObservationDocumentation;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/** 컨트롤러 도달 전 거절된 공개 조회도 정해진 경로 단위로 관측한다. */
@Component
public final class PublicRequestObservationConvention extends DefaultServerRequestObservationConvention {

    @Override
    protected KeyValue uri(ServerRequestObservationContext context) {
        HttpServletRequest request = context.getCarrier();
        if (context.getPathPattern() != null || context.getResponse() == null
                || context.getResponse().getStatus() != 429
                || !("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))) {
            return super.uri(context);
        }
        String path = request.getRequestURI();
        if (!PublicMarketPaths.isMarketData(path)) {
            return super.uri(context);
        }
        // 필터의 prefix 판정은 경로 템플릿이 아니다. 등록된 경로만 이름을 부여하고
        // 임의 경로·쿼리 문자열은 기본 UNKNOWN에 남겨 지표 라벨 증가와 정보 노출을 막는다.
        String pattern = switch (path) {
            case "/api/v1/markets", "/api/v1/orderbook", "/api/v1/trades",
                    "/api/v1/ticker", "/api/v1/exchange-config" -> path;
            default -> path.matches("/api/v1/candles/[^/;]+") ? "/api/v1/candles/{unit}" : null;
        };
        return pattern == null ? super.uri(context)
                : KeyValue.of(ServerHttpObservationDocumentation.LowCardinalityKeyNames.URI, pattern);
    }
}
