package kr.exchange.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.AccountRateLimiter;
import kr.exchange.api.common.ApiExceptionHandler;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.common.RoutingCounters;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.config.ApiConfig;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.engine.TestPartitions;
import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.journal.DbReadRetry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 인증을 흉내 낸 주문 API(S5 설계 6-2 MarketHintMismatchTest·BatchSingleMarketTest). 인증 컨텍스트만 심는 필터와 실제 컨트롤러·
 * 게이트웨이·예외 처리기, 실제 DB, 저널 없이 주문 행을 남기는 브리지다.
 */
final class HintedOrderApi {

    static final int ORDER_CAPACITY = 100;

    final ProjectingFakeBridge bridge;
    final OrderGateway gateway;
    final RoutingCounters counters = RoutingCounters.untracked();
    final MockMvc mvc;

    HintedOrderApi(JdbcClient jdbc, MarketDirectory markets, long user) {
        this.bridge = new ProjectingFakeBridge(jdbc);
        this.gateway = new ApiConfig().orderGateway(jdbc, bridge, markets, new OrderQueryService(jdbc),
                new SimpleMeterRegistry(), new LeaderProtocolBoot(2), PartitionPlan.single());
        gateway.attachRouting(counters, DbReadRetry.untracked());
        ObjectMapper mapper = new ObjectMapper();
        OrderController controller = new OrderController(gateway, new OrderQueryService(jdbc),
                new EpochSource(TestPartitions.unstarted(PartitionPlan.single()), jdbc), mapper,
                new AccountRepository(jdbc), new OrderRejectionMetrics(new SimpleMeterRegistry()), markets,
                new AccountRateLimiter(10_000, 10_000, "account", "query"), ORDER_CAPACITY);
        OncePerRequestFilter principal = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                request.setAttribute(AuthPrincipal.REQUEST_ATTRIBUTE,
                        new AuthPrincipal(user, "ak", "TRADE", AuthPrincipal.KIND_API_KEY));
                chain.doFilter(request, response);
            }
        };
        this.mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler(new ErrorRenderer(mapper)))
                .addFilters(principal)
                .build();
    }
}
