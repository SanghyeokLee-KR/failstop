package kr.exchange.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/** 조회 노드는 DB로 완결되는 시장 조회만 받는다. ALB 오배선도 이 경계에서 거부한다. */
public final class ReadRoleFilter extends OncePerRequestFilter {

    private static final Set<String> MARKET_PATHS = Set.of(
            "/api/v1/markets", "/api/v1/trades");
    private static final Set<String> MANAGEMENT_PATHS = Set.of(
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/read",
            "/actuator/health/readiness", "/actuator/prometheus", "/actuator/info");
    private final ErrorRenderer errors;

    public ReadRoleFilter(ErrorRenderer errors) {
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!allowed(request.getMethod(), request.getRequestURI())) {
            errors.write(response, new ApiException(404, "not_found", "조회 노드가 제공하지 않는 경로입니다."));
            return;
        }
        chain.doFilter(request, response);
    }

    public static boolean allowed(String method, String path) {
        if (!("GET".equals(method) || "HEAD".equals(method))) {
            return false;
        }
        // 인코딩·행렬 매개변수·중복 슬래시를 정규화해서 허용하지 않는다.
        // 실제 컨트롤러와 다른 해석이 생기지 않도록 원래 경로를 좁게 받는다.
        return MARKET_PATHS.contains(path) || MANAGEMENT_PATHS.contains(path)
                || path.matches("/api/v1/candles/[A-Za-z0-9]+");
    }
}
