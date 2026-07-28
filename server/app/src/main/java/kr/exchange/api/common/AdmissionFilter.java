package kr.exchange.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 수용 제한 게이트(API 설계 §2-5-a). `/api/v1/*` 요청을 쓰기·조회 두 버킷으로 갈라 각각 유계화하고
 * 초과분을 429로 즉시 거절한다.
 *
 * 왜 ApiAuthFilter가 아닌 별도 필터인가. ApiAuthFilter의 shouldNotFilter가 공개 시장 데이터
 * (markets·orderbook·ticker·trades·candles)와 auth·community 평면을 통째로 제외한다. 그런데
 * 노드를 죽인 조회가 정확히 그 공개 시장 데이터 다중행 조회다. 인증 필터 안에 조회 게이트를 두면
 * 정작 막아야 할 트래픽이 게이트 밖에 있게 된다.
 *
 * 필터 순서 6. RequestSizeLimitFilter(5) 뒤라 상한 초과 바디는 슬롯을 소비하지 않고 끊기고,
 * ApiAuthFilter(10) 앞이라 인증의 api_keys 조회 이전에 막힌다. 인증 뒤에서 막으면 이미 DB를 탄
 * 뒤라 실효가 없다. 쓰기 수요 계측(ApiAuthFilter의 recordAttempt)도 이 게이트 뒤에 오므로,
 * 수용 거절은 좀비 판정의 수요 축에 계상되지 않는다(DB 설계 §5.4).
 *
 * 면제 경로.
 *  - `/actuator/**`(health·readiness·liveness·prometheus)와 `/harness/v1/**`: 이 필터의 URL 패턴
 *    자체가 `/api/v1/*`이라 도달하지 않는다. 노드가 살아 있다는 것은 곧 이 질문들에 답할 수 있다는
 *    뜻이고, LB·docker healthcheck·하네스가 전부 그 응답으로 판단한다. 막히면 생존 판정이 무너진다.
 *  - `GET /api/v1/orders/by-client/{coid}`: §7-6 UNKNOWN 재조회의 유일한 수렴 경로다. 여기가
 *    429가 되면 클라이언트는 접수 여부를 영영 확정하지 못하고, 안전 재전송과 이중 접수 중
 *    하나를 골라야 한다. 멱등 규약이 깨지는 것이라 조회 버킷에서 뺀다. 비용은 인덱스 단건 조회
 *    둘(command_journal·orders)로 시스템에서 가장 싼 질의이고, 유발 조건이 5xx·타임아웃이라
 *    백프레셔의 주 응답인 429는 이 경로를 부르지 않는다(k6·봇 모두 429에 재조회로 반응하지
 *    않는다). 예비분(풀의 20%)이 이 트래픽의 몫이다.
 *  - `OPTIONS`: CORS 프리플라이트는 DB를 건드리지 않는다. 여기서 막으면 브라우저 요청이 본
 *    요청을 보내보지도 못하고 실패한다.
 */
public class AdmissionFilter extends OncePerRequestFilter {

    private final AdmissionControl orderAdmission;
    private final AdmissionControl queryAdmission;
    private final ErrorRenderer errorRenderer;

    public AdmissionFilter(AdmissionControl orderAdmission, AdmissionControl queryAdmission,
                           ErrorRenderer errorRenderer) {
        this.orderAdmission = orderAdmission;
        this.queryAdmission = queryAdmission;
        this.errorRenderer = errorRenderer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        AdmissionControl bucket = bucketFor(request.getMethod(), request.getRequestURI());
        if (bucket == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!bucket.tryAcquire()) {
            errorRenderer.write(response, bucket.rejection());
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // 정상·거부·예외·에러 전 경로에서 정확히 한 번 반납한다. OncePerRequestFilter라 요청당
            // 이 메서드가 한 번만 돌고, 반납이 획득 성공 분기 안에만 있어 이중 반납이 불가능하다.
            // `/api/v1` 평면에 비동기 디스패치가 없으므로 반납 시점이 요청 수명과 일치한다.
            bucket.release();
        }
    }

    /** null이면 면제. 분류 규칙이 곧 계약이라 정적 메서드로 두고 테스트가 직접 고정한다. */
    AdmissionControl bucketFor(String method, String path) {
        if ("OPTIONS".equals(method)) {
            return null;
        }
        if (isOrderWrite(method, path)) {
            return orderAdmission;
        }
        if (isRequeryConvergence(method, path)) {
            return null;
        }
        return queryAdmission;
    }

    static boolean isOrderWrite(String method, String path) {
        return path.startsWith("/api/v1/orders")
                && ("POST".equals(method) || "DELETE".equals(method));
    }

    static boolean isRequeryConvergence(String method, String path) {
        return "GET".equals(method) && path.startsWith("/api/v1/orders/by-client/");
    }
}
