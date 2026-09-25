package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.api.auth.ApiAuthFilter;
import kr.exchange.api.auth.ApiAuthService;
import kr.exchange.api.common.AccountRateLimiter;
import kr.exchange.api.common.AdmissionControl;
import kr.exchange.api.common.AdmissionFilter;
import kr.exchange.api.common.AdmissionGate;
import kr.exchange.api.common.ApiExceptionHandler;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.common.MarketRoutingFilter;
import kr.exchange.api.common.PublicRateLimitFilter;
import kr.exchange.api.common.RequestSizeLimitFilter;
import kr.exchange.api.common.RoutingCounters;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.AccountRepository;
import kr.exchange.api.order.OrderController;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.api.order.OrderRejectionMetrics;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 운영과 같은 순서의 요청 경로(S5 설계 6-2). 바디 상한(4), 소유권 선검사(5), 수용 제한(6), 공개 IP 버킷(7), 인증(10) 뒤에 주문
 * 컨트롤러와 예외 처리기를 둔다. 수용 제한 앞에 둔 탐침이 그 단계에 닿은 요청 수를 센다(수용 슬롯을 쓴 요청 수).
 *
 * precheckAfterAuth 는 변이 확인용이다. 선검사를 인증 뒤로 옮긴 배선이다.
 */
final class RoutedChain {

    static final int ORDER_CAPACITY = 100;

    final LeaderWriteProgress progress = new LeaderWriteProgress();
    final AtomicInteger admissionReached = new AtomicInteger();
    final RoutingCounters counters;
    final MockMvc mvc;

    RoutedChain(JdbcClient jdbc, MarketDirectory markets, EngineBridge bridge, OrderGateway gateway,
                ApiAuthService authService, boolean withPrecheck, boolean precheckAfterAuth) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectMapper mapper = new ObjectMapper();
        ErrorRenderer renderer = new ErrorRenderer(mapper);
        this.counters = gateway.routing();
        OrderController controller = new OrderController(gateway, new OrderQueryService(jdbc),
                new EpochSource(TestPartitions.unstarted(PartitionPlan.single()), jdbc), mapper,
                new AccountRepository(jdbc), new OrderRejectionMetrics(registry), markets,
                new AccountRateLimiter(10_000, 10_000, "account", "query"), ORDER_CAPACITY);
        MarketRoutingFilter precheck = new MarketRoutingFilter(renderer, markets, bridge, mapper, counters);
        Filter probe = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                admissionReached.incrementAndGet();
                chain.doFilter(request, response);
            }
        };
        AdmissionFilter admission = new AdmissionFilter(new AdmissionGate(true),
                new AdmissionControl("order", 8, AdmissionControl.Shed.CAPACITY, registry),
                new AdmissionControl("query", 8, AdmissionControl.Shed.CAPACITY, registry),
                new AdmissionControl("requery", 4, AdmissionControl.Shed.REQUERY_DEFERRED, registry), renderer);
        PublicRateLimitFilter publicBucket = new PublicRateLimitFilter(
                new AccountRateLimiter(300, 10_000, "ip", "public"), renderer);
        ApiAuthFilter auth = new ApiAuthFilter(authService, renderer, progress);
        List<Filter> filters = new ArrayList<>();
        filters.add(new RequestSizeLimitFilter(renderer));
        if (withPrecheck && !precheckAfterAuth) {
            filters.add(precheck);
        }
        filters.add(probe);
        filters.add(admission);
        filters.add(publicBucket);
        filters.add(auth);
        if (withPrecheck && precheckAfterAuth) {
            filters.add(precheck);
        }
        this.mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler(renderer))
                .addFilters(filters.toArray(new Filter[0]))
                .build();
    }

    /** 순수요(시도에서 되돌림을 뺀 수). */
    long netDemand() {
        return progress.attempts() - progress.excluded();
    }
}
