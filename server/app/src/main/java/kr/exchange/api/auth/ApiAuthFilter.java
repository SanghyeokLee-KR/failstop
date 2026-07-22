package kr.exchange.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ErrorRenderer;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * /api/v1 보호 경로 인증 필터. 공개 시장 데이터(§4-A)는 무인증, 그 외는 §3-4 검증을 통과해야
 * 컨트롤러에 도달한다. 바디는 여기서 전량 캐시해 서명 검증과 컨트롤러 파싱이 같은 바이트를 본다.
 */
public class ApiAuthFilter extends OncePerRequestFilter {

    private final ApiAuthService authService;
    private final ErrorRenderer errorRenderer;

    public ApiAuthFilter(ApiAuthService authService, ErrorRenderer errorRenderer) {
        this.authService = authService;
        this.errorRenderer = errorRenderer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/")) {
            return true;
        }
        return path.startsWith("/api/v1/markets")
                || path.startsWith("/api/v1/orderbook")
                || path.startsWith("/api/v1/trades")
                || path.startsWith("/api/v1/ticker")
                || path.startsWith("/api/v1/candles")
                || path.equals("/api/v1/demo/session");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String method = request.getMethod();
            String path = request.getRequestURI();
            boolean needsTrade = path.startsWith("/api/v1/orders")
                    && ("POST".equals(method) || "DELETE".equals(method));

            HttpServletRequest effective = request;
            String canonical;
            if ("GET".equals(method)) {
                canonical = CanonicalRequests.forQuery(method, path, request.getParameterMap());
            } else {
                // 비 GET은 실제 바이트 존재로 판정한다(§3-3 v2.7 W5). Content-Length 기반 판정은
                // chunked 전송(길이 헤더 부재)으로 바디 서명을 쿼리 canonical로 우회할 수 있다.
                byte[] body = request.getInputStream().readAllBytes();
                if (body.length > 0) {
                    canonical = CanonicalRequests.forBody(method, path, body);
                    effective = new CachedBodyRequest(request, body);
                } else {
                    canonical = CanonicalRequests.forQuery(method, path, request.getParameterMap());
                }
            }
            AuthPrincipal principal = authService.verify(
                    request.getHeader("Authorization"), canonical, needsTrade);
            effective.setAttribute(AuthPrincipal.REQUEST_ATTRIBUTE, principal);
            chain.doFilter(effective, response);
        } catch (ApiException e) {
            errorRenderer.write(response, e);
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        byte[] body() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
