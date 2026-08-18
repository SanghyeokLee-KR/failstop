package kr.exchange.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 무인증 공개 시장 데이터(§4-A)의 IP 토큰버킷(§2-5 '공개 조회 300토큰/10s(IP)').
 *
 * 왜 컨트롤러가 아니라 필터인가. 이 평면은 ApiAuthFilter 제외 목록이라 계정 버킷을 걸 principal이
 * 없고, 컨트롤러마다 같은 차감 코드를 반복하면 신규 공개 경로가 조용히 누락된다. 적용 대상 판정은
 * 인증 제외 목록과 같은 PublicMarketPaths를 쓴다.
 *
 * 필터 순서 7. AdmissionFilter(6) 뒤라 노드 용량 거절이 토큰을 소비하지 않고(백프레셔로 거절한
 * 요청이 버킷을 갉으면 정상 클라이언트의 예산이 장애 중에 깎인다), ApiAuthFilter(10) 앞이라
 * 미인증 플러드가 api_keys 조회로 DB를 타기 전에 끊긴다.
 *
 * 노드 로컬 한도다(AccountRateLimiter와 동일 제약). 다중 노드에서 실효 한도는 노드 수만큼
 * 곱해지고 재시작 시 소실된다. 분산 버킷(Redisson RRateLimiter) 교체는 M2 승격 선행 조건이다.
 */
public class PublicRateLimitFilter extends OncePerRequestFilter {

    private final AccountRateLimiter limiter;
    private final ErrorRenderer errorRenderer;

    public PublicRateLimitFilter(AccountRateLimiter limiter, ErrorRenderer errorRenderer) {
        this.limiter = limiter;
        this.errorRenderer = errorRenderer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // OPTIONS 면제 근거는 AdmissionFilter와 같다. 프리플라이트는 DB를 건드리지 않고,
        // 여기서 막으면 브라우저가 본 요청을 보내보지도 못한다.
        return "OPTIONS".equals(request.getMethod())
                || !PublicMarketPaths.isMarketData(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        AccountRateLimiter.Decision decision =
                limiter.acquire(ClientIp.of(request), PublicMarketPaths.weight(path));
        // 통과 응답에도 잔량을 싣는다(§2-5 버킷별 헤더 노출). 클라이언트가 429를 맞기 전에
        // 스스로 감속할 수 있어야 헤더가 의미를 갖는다.
        limiter.writeHeaders(response, decision);
        if (!decision.allowed()) {
            errorRenderer.write(response, limiter.rejection(decision));
            return;
        }
        chain.doFilter(request, response);
    }
}
