package kr.exchange.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * /api/v1/* 전 경로 바디 크기 상한(§2-6·§2-7). ApiAuthFilter가 서명 검증 대상에서 제외하는
 * /auth/·/community/ 평면도 여기서 상한이 걸린다. 인증·서명 검증보다 앞서(order 5 < ApiAuthFilter 10)
 * 실행돼 미인증 요청의 무제한 버퍼링·거대 password 유입을 바인딩 전에 끊는다.
 *
 * 상한 내 바디는 캐시해 다운스트림(ApiAuthFilter 서명 검증·컨트롤러 파싱)이 같은 바이트를 재소비한다.
 */
@Component
@Order(5)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final ErrorRenderer errorRenderer;

    public RequestSizeLimitFilter(ErrorRenderer errorRenderer) {
        this.errorRenderer = errorRenderer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/v1/")) {
            return true;
        }
        // 바디 없는 메서드는 상한 대상 아님. 그 외(POST·PUT·PATCH·DELETE)는 raw 바디를 읽을 수 있어 상한을 건다.
        String method = request.getMethod();
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)
                || "TRACE".equals(method);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        byte[] body;
        try {
            body = RequestBodyLimit.readBounded(request);
        } catch (ApiException e) {
            errorRenderer.write(response, e);
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }
}
