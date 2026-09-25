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
 * /auth/·/community/ 평면도 여기서 상한이 걸린다. 인증·서명 검증보다 앞서(order 4 < ApiAuthFilter 10)
 * 실행돼 미인증 요청의 무제한 버퍼링·거대 password 유입을 바인딩 전에 끊는다.
 *
 * 상한 내 바디는 캐시해 다운스트림(소유권 선검사·ApiAuthFilter 서명 검증·컨트롤러 파싱)이 같은 바이트를 재소비한다.
 * 순서는 바디 상한 4, 소유권 선검사 5, 수용 제한 6, IP 버킷 7, 인증 10이다(API 설계 §2-5-d v2.57). 선검사가 바디의
 * 마켓을 읽으므로 바디를 한 번만 읽도록 이 필터가 앞에 선다.
 */
@Component
@Order(4)
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
        // 바디가 있는 요청은 Content-Type을 application/json으로 강제한다(§2-7 415). 바디 없는
        // 요청(쿠키 기반 logout·refresh 등)은 미디어타입 대상이 아니라 통과시킨다. 컨트롤러가
        // raw 바이트를 직접 파싱해 프레임워크 미디어타입 협상을 우회하므로 이 필터가 유일 게이트다.
        if (body.length > 0 && !isJsonContentType(request.getContentType())) {
            errorRenderer.write(response, new ApiException(415, "unsupported_media_type",
                    "요청 Content-Type은 application/json이어야 합니다."));
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        int semi = contentType.indexOf(';');
        String base = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim()
                .toLowerCase(java.util.Locale.ROOT);
        return base.equals("application/json") || base.endsWith("+json");
    }
}
