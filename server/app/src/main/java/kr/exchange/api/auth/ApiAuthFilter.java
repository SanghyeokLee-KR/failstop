package kr.exchange.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.CachedBodyRequest;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.common.PublicMarketPaths;
import kr.exchange.api.common.RequestBodyLimit;
import kr.exchange.engine.LeaderWriteProgress;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * /api/v1 보호 경로 인증 필터. 공개 시장 데이터(§4-A)는 무인증, 그 외는 §3-4 검증을 통과해야
 * 컨트롤러에 도달한다. 바디는 여기서 전량 캐시해 서명 검증과 컨트롤러 파싱이 같은 바이트를 본다.
 *
 * 바디 크기 상한(64KB)은 앞단 RequestSizeLimitFilter(order 5)가 /api/v1/* 전 경로에 먼저 건다.
 * 이 필터가 제외하는 /auth/·/community/ 평면도 그 필터가 커버한다. 여기서의 readBounded는 서명 canonical용
 * 바이트 확보가 주목적이며 상한은 공유 상수(RequestBodyLimit)로 이중 방어한다.
 */
public class ApiAuthFilter extends OncePerRequestFilter {

    private final ApiAuthService authService;
    private final ErrorRenderer errorRenderer;
    // 주문 쓰기 수요 계측. 인증보다 앞선 유일한 공통 지점이라 여기서 센다(DB 설계 §5.4).
    private final LeaderWriteProgress writeProgress;

    public ApiAuthFilter(ApiAuthService authService, ErrorRenderer errorRenderer,
                         LeaderWriteProgress writeProgress) {
        this.authService = authService;
        this.errorRenderer = errorRenderer;
        this.writeProgress = writeProgress;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/")) {
            return true;
        }
        // 공개 시장 데이터 목록은 PublicMarketPaths가 단일 정본이다. 여기와 IP 버킷 필터가
        // 각자 목록을 들면 한쪽만 늘었을 때 인증도 레이트리밋도 없는 경로가 생긴다.
        return PublicMarketPaths.isMarketData(path)
                || path.equals("/api/v1/demo/session")
                // 게시판 평면(게시판_설계.md §2.4)은 거래 평면과 경계 분리한다. 읽기는 무인증, 쓰기는
                // aud=community 회원 토큰을 커뮤니티 계층이 자체 검증한다(CommunityAuthenticator).
                // 거래 평면 aud=exchange 검증을 여기서 강제하면 경계가 뭉개지므로 필터에서 제외한다.
                || path.startsWith("/api/v1/community/")
                // 회원 인증 엔드포인트는 무인증(가입·로그인) 또는 쿠키 rt(갱신·로그아웃)라
                // Bearer 서명 검증 대상이 아니다. me·keys는 여기서 제외되지 않아 회원 세션을 통과한다.
                || path.startsWith("/api/v1/auth/")
                // 공지 조회는 무인증 읽기다(§4-F). 시장 데이터가 아니라 PublicMarketPaths가
                // 아니라 NoticeController가 자기 스로틀을 든다. 쓰기는 관리 평면 단독이다.
                || path.equals("/api/v1/notices");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String method = request.getMethod();
        String path = request.getRequestURI();
        boolean needsTrade = path.startsWith("/api/v1/orders")
                && ("POST".equals(method) || "DELETE".equals(method));
        if (needsTrade) {
            // 쓰기 수요 계측(DB 설계 §5.4 좀비 리더 판정). 반드시 authService.verify 이전이다.
            // verify는 api_keys를 조회하므로 요청 경로가 죽은 좀비 상태에서는 여기서 이미
            // 실패한다. 계측을 인증 뒤에 두면 정작 대상 장애에서 한 번도 증가하지 않는다.
            //
            // 수용 판정은 앞단 AdmissionFilter(order 6)가 이미 끝냈다. 수용 거절된 요청은 이
            // 필터에 도달하지 않으므로 수요 축에 계상되지 않으며 그것이 의도다. 거절을 수요로
            // 세면 부하를 정상적으로 흘리는 건강한 노드가 분모만 키워 헛강등한다.
            writeProgress.recordAttempt();
        }
        HttpServletRequest effective;
        try {
            effective = request;
            String canonical;
            if ("GET".equals(method)) {
                canonical = CanonicalRequests.forQuery(method, path, request.getParameterMap());
            } else {
                // 비 GET은 실제 바이트 존재로 판정한다(§3-3 v2.7 W5). Content-Length 기반 판정은
                // chunked 전송(길이 헤더 부재)으로 바디 서명을 쿼리 canonical로 우회할 수 있다.
                byte[] body = RequestBodyLimit.readBounded(request);
                if (body.length > 0) {
                    canonical = CanonicalRequests.forBody(method, path, body);
                    effective = new CachedBodyRequest(request, body);
                } else {
                    canonical = CanonicalRequests.forQuery(method, path, request.getParameterMap());
                }
            }
            AuthPrincipal principal = authService.verify(
                    request.getHeader("Authorization"), canonical, needsTrade);
            // 데모 세션은 읽기 전용이다(API 설계 §3-7 v2.12). 판정을 경로 목록이 아니라 메서드로
            // 두는 이유는, 목록 방식은 새 쓰기 엔드포인트가 등재에서 빠진 채 열리기 때문이다.
            // 이 경계는 자금 이동이라 빠뜨렸을 때 닫히는 쪽으로 기울여야 한다.
            if (AuthPrincipal.KIND_DEMO.equals(principal.kind()) && isWrite(method)) {
                throw new ApiException(403, "member_session_required",
                        "둘러보기 중에는 주문 불가. 로그인 필요");
            }
            effective.setAttribute(AuthPrincipal.REQUEST_ATTRIBUTE, principal);
        } catch (ApiException e) {
            errorRenderer.write(response, e);
            return;
        } catch (TransientDataAccessException | CannotGetJdbcConnectionException e) {
            // 인증의 api_keys 조회가 커넥션 획득 실패·락 타임아웃 등 일시 사유로 깨진 경우. 획득
            // 타임아웃을 3초로 낮춘 뒤 이 경로가 실제로 발화하므로 계약대로 신호해야 한다. 아래
            // catch-all에 맡기면 재시도 가능한 일시 실패가 500 server_error로 나가, 같은 사유를
            // 503으로 매핑하는 컨트롤러 계층(ApiExceptionHandler)과 코드가 갈린다.
            errorRenderer.write(response, new ApiException(503, "service_unavailable",
                    "일시적 처리 불가. 잠시 후 재시도 필요", null, 1));
            return;
        } catch (RuntimeException e) {
            // 인증 단계에서 던진 비 ApiException(예: HMAC 계산 실패)이 컨테이너 기본 페이지로 새면
            // 클라이언트가 표준 에러 엔벌로프(§2-3)로 파싱하지 못한다. 500으로 표준화한다.
            errorRenderer.write(response, new ApiException(500, "server_error", "인증 처리 중 오류"));
            return;
        }
        // 컨트롤러 다운스트림 예외는 각 어드바이스가 표준화하므로 이 필터가 삼키지 않는다.
        chain.doFilter(effective, response);
    }

    /** 상태를 바꾸지 않는 메서드만 열거한다. 목록 밖은 전부 쓰기로 본다(미상 메서드도 닫힘) */
    private static boolean isWrite(String method) {
        return !"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method);
    }
}
