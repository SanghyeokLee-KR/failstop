package kr.exchange.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 수용 제한 게이트(API 설계 §2-5-a). `/api/v1/*`를 쓰기·조회 버킷으로 갈라 유계화하고 초과분을
 * 429로 즉시 거절한다. 버킷 분류·면제 경로·전용 재조회 버킷·무대기 근거는 그 절에 있다.
 *
 * ApiAuthFilter가 아닌 별도 필터인 이유는 그 필터의 shouldNotFilter가 공개 시장 데이터를 통째로
 * 제외하는데, 노드를 죽인 조회가 정확히 그 경로여서다.
 *
 * 필터 순서 6. 바디 상한(4)과 소유권 선검사(5) 뒤라 초과 바디와 이 노드가 접수하지 않는 마켓의 주문 쓰기는 슬롯을
 * 소비하지 않고, 인증(10) 앞이라 api_keys 조회 이전에 막힌다. 쓰기 수요 계측도 이 게이트 뒤라 수용 거절은 좀비 판정의 수요 축에 계상되지
 * 않는다(DB 설계 §5.4).
 *
 * 런타임 스위치(v2.41). AdmissionGate 가 꺼져 있으면 버킷을 타지 않고 통과시킨다. 수용 제어가
 * 없는 상태를 재현하는 실험 전용이고, 기본은 켜짐이다. 스위치는 permit 을 직접 건드리지 않는다.
 * 끄기 전에 이미 획득된 슬롯은 그 요청이 끝나야 반납되므로 켜는 순간 in-flight 가 0 이라는
 * 보장은 없다(API 설계 §2-5-a v2.42).
 */
public class AdmissionFilter extends OncePerRequestFilter {

    private final AdmissionGate gate;
    private final AdmissionControl orderAdmission;
    private final AdmissionControl queryAdmission;
    private final AdmissionControl requeryAdmission;
    private final ErrorRenderer errorRenderer;

    public AdmissionFilter(AdmissionGate gate, AdmissionControl orderAdmission,
                           AdmissionControl queryAdmission, AdmissionControl requeryAdmission,
                           ErrorRenderer errorRenderer) {
        this.gate = gate;
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
        if (!gate.enabled()) {
            chain.doFilter(request, response);
            return;
        }
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
        // 거래 상태 조회는 노드 메모리만 읽는다(API 설계 §4-A-7). 중단 동안 조회 버킷이 차도 화면이 재개를 알아야 한다.
        if ("OPTIONS".equals(method) || WriteGateFilter.STATUS_URL.equals(path)) {
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
