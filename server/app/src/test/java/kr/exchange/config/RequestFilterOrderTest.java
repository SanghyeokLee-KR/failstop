package kr.exchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.auth.ApiAuthService;
import kr.exchange.api.common.AdmissionControl;
import kr.exchange.api.common.AdmissionGate;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.common.RequestSizeLimitFilter;
import kr.exchange.api.common.RoutingCounters;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.engine.AcceptGate;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.annotation.Order;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 요청 필터 순서(API 설계 §2-5-d v2.57, S5 설계 4-1·6-2). 바디 상한 4 < 소유권 선검사 5 < 수용 제한 6 < 공개 IP 버킷 7 < 인증 10
 * 이다. 선검사는 조회 역할 노드에 등록하지 않는다(조회 역할 필터가 쓰기를 먼저 막는다).
 */
class RequestFilterOrderTest {

    @Test
    void filtersRunInTheContractOrder() {
        ApiConfig config = new ApiConfig();
        ErrorRenderer renderer = new ErrorRenderer(new ObjectMapper());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        int sizeLimit = RequestSizeLimitFilter.class.getAnnotation(Order.class).value();
        int precheck = config.marketRoutingFilter(renderer, MarketDirectory.ofSnapshot(null, List.of()), bridge(),
                new ObjectMapper(), RoutingCounters.untracked()).getOrder();
        AdmissionControl order = new AdmissionControl("order", 4, AdmissionControl.Shed.CAPACITY, registry);
        AdmissionControl query = new AdmissionControl("query", 4, AdmissionControl.Shed.CAPACITY, registry);
        AdmissionControl requery = new AdmissionControl("requery", 2, AdmissionControl.Shed.REQUERY_DEFERRED, registry);
        int admission = config.admissionFilter(new AdmissionGate(true), order, query, requery, renderer).getOrder();
        int publicBucket = config.publicRateLimitFilter(config.publicIpRateLimiter(300), renderer).getOrder();
        int auth = config.apiAuthFilter(new ApiAuthService(null, null, null, null, null, null), renderer,
                new LeaderWriteProgress()).getOrder();
        assertArrayEquals(new int[] {4, 5, 6, 7, 10}, new int[] {sizeLimit, precheck, admission, publicBucket, auth});
    }

    @Test
    void precheckIsNotRegisteredOnReadRoleNodes() throws Exception {
        Method method = ApiConfig.class.getMethod("marketRoutingFilter", ErrorRenderer.class, MarketDirectory.class,
                EngineBridge.class, ObjectMapper.class, RoutingCounters.class);
        Conditional conditional = method.getAnnotation(Conditional.class);
        assertNotNull(conditional, "선검사 등록에 조회 역할 조건이 없다");
        assertArrayEquals(new Class<?>[] {ReadRoleConfig.NotReadRoleCondition.class}, conditional.value());
        ReadRoleConfig.NotReadRoleCondition condition = new ReadRoleConfig.NotReadRoleCondition();
        assertFalse(condition.matches(context(new MockEnvironment().withProperty("exchange.node.role", "read")), null));
        MockEnvironment readProfile = new MockEnvironment();
        readProfile.setActiveProfiles("prod", "read");
        assertFalse(condition.matches(context(readProfile), null));
        assertTrue(condition.matches(context(new MockEnvironment()), null), "일반 노드에 선검사가 없다");
    }

    private static ConditionContext context(MockEnvironment environment) {
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return context;
    }

    private static EngineBridge bridge() {
        return new EngineBridge(null, new SingleWriterLoop(16, new MatchingEngineImpl(), events -> { },
                (afterSeq, limit) -> List.of(), fatal -> { }), new CommandPayloadMapper(new ObjectMapper()),
                AcceptGate.ALWAYS_OPEN, EngineBridge.NEVER_HALTED);
    }
}
