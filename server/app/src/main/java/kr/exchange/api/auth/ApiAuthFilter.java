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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * /api/v1 보호 경로 인증 필터. 공개 시장 데이터(§4-A)는 무인증, 그 외는 §3-4 검증을 통과해야
 * 컨트롤러에 도달한다. 바디는 여기서 전량 캐시해 서명 검증과 컨트롤러 파싱이 같은 바이트를 본다.
 */
public class ApiAuthFilter extends OncePerRequestFilter {

    // §2-6·§2-7 요청 바디 상한 64KB. 서명 검증 앞단이라 미인증 요청도 상한을 넘으면 즉시 413으로 끊어
    // 무제한 버퍼링을 막는다. Tomcat maxPostSize는 form 인코딩만 걸려 raw JSON에는 무관하다.
    private static final int MAX_BODY_BYTES = 64 * 1024;

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
                || path.startsWith("/api/v1/exchange-config")
                || path.equals("/api/v1/demo/session")
                // 회원 인증 엔드포인트는 무인증(가입·로그인) 또는 쿠키 rt(갱신·로그아웃)라
                // Bearer 서명 검증 대상이 아니다. me·keys는 여기서 제외되지 않아 회원 세션을 통과한다.
                || path.startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        HttpServletRequest effective;
        try {
            String method = request.getMethod();
            String path = request.getRequestURI();
            boolean needsTrade = path.startsWith("/api/v1/orders")
                    && ("POST".equals(method) || "DELETE".equals(method));

            effective = request;
            String canonical;
            if ("GET".equals(method)) {
                canonical = CanonicalRequests.forQuery(method, path, request.getParameterMap());
            } else {
                // 비 GET은 실제 바이트 존재로 판정한다(§3-3 v2.7 W5). Content-Length 기반 판정은
                // chunked 전송(길이 헤더 부재)으로 바디 서명을 쿼리 canonical로 우회할 수 있다.
                byte[] body = readBounded(request);
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
        } catch (ApiException e) {
            errorRenderer.write(response, e);
            return;
        } catch (RuntimeException e) {
            // 인증 단계에서 던진 비 ApiException(예: HMAC 계산 실패)이 컨테이너 기본 페이지로 새면
            // 클라이언트가 표준 에러 엔벌로프(§2-3)로 파싱하지 못한다. 500으로 표준화한다.
            errorRenderer.write(response, new ApiException(500, "server_error", "인증 처리 중 오류가 발생했습니다."));
            return;
        }
        // 컨트롤러 다운스트림 예외는 각 어드바이스가 표준화하므로 이 필터가 삼키지 않는다.
        chain.doFilter(effective, response);
    }

    /**
     * 상한을 넘으면 전량 적재하지 않고 즉시 413으로 끊는다. Content-Length가 있으면 선검사로 조기 차단하고,
     * 없거나 위조된 경우에도 실제 읽기에서 상한+1 바이트를 확인해 chunked 우회를 막는다.
     */
    private static byte[] readBounded(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            throw payloadTooLarge();
        }
        InputStream in = request.getInputStream();
        byte[] buffer = new byte[MAX_BODY_BYTES + 1];
        int total = 0;
        int read;
        while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
            total += read;
        }
        if (total > MAX_BODY_BYTES) {
            throw payloadTooLarge();
        }
        byte[] body = new byte[total];
        System.arraycopy(buffer, 0, body, 0, total);
        return body;
    }

    private static ApiException payloadTooLarge() {
        return new ApiException(413, "payload_too_large", "요청 바디가 64KB 상한을 초과했습니다.");
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

        @Override
        public BufferedReader getReader() {
            // getInputStream만 재정의하면 getReader를 쓰는 컨트롤러(@RequestBody 대신 reader 파싱)가
            // 원본 소진된 스트림을 읽어 빈 바디를 본다. 캐시 바이트를 요청 charset으로 재현한다.
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }
    }
}
