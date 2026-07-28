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
 *  - `OPTIONS`: CORS 프리플라이트는 DB를 건드리지 않는다. 여기서 막으면 브라우저 요청이 본
 *    요청을 보내보지도 못하고 실패한다.
 *
 * `GET /api/v1/orders/by-client/{coid}`는 면제가 아니라 **전용 소형 버킷**이다. §7-6 UNKNOWN
 * 재조회의 유일한 수렴 경로라 429를 줄 수 없어 조회 버킷에 넣을 수 없지만, 완전 면제도 안 된다.
 * 이 경로는 하필 노드가 굶는 순간에 몰린다. 커넥션 획득 3초 초과가 503을 만들고 클라이언트는
 * 5xx에 재조회로 반응하므로(k6 order_flow.js) 되먹임 고리가 된다. 상한을 두되 초과분은 거절이
 * 아니라 판정 보류(409 requery_deferred)로 내보내 수렴을 유지한다.
 *
 * 대기를 두지 않는 이유. '짧은 대기 후 무조건 통과'는 상한이 아니라 지연 추가일 뿐이라 동시성이
 * 다시 무제한이 된다. '짧은 대기 후 거절'은 대기열(= arrival x wait)을 되살리면서 거절도 그대로
 * 남는다. 세마포어 대기는 가상 스레드를 pin하지 않아 JDBC 블로킹보다 싸지만, 보류 응답 자체가
 * 이미 비종결·저비용(Retry-After 1s)이라 대기로 성공률을 사는 이득이 크지 않다. 세 버킷 모두
 * 무대기로 통일한다.
 */
public class AdmissionFilter extends OncePerRequestFilter {

    private final AdmissionControl orderAdmission;
    private final AdmissionControl queryAdmission;
    private final AdmissionControl requeryAdmission;
    private final ErrorRenderer errorRenderer;

    public AdmissionFilter(AdmissionControl orderAdmission, AdmissionControl queryAdmission,
                           AdmissionControl requeryAdmission, ErrorRenderer errorRenderer) {
        this.orderAdmission = orderAdmission;
        this.queryAdmission = queryAdmission;
        this.requeryAdmission = requeryAdmission;
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
            return requeryAdmission;
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
