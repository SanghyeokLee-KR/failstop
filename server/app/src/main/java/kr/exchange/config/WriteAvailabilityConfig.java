package kr.exchange.config;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.common.WriteGateFilter;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.transfer.TransferService;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.WriteAvailability;
import kr.exchange.engine.WriteProbe;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 쓰기 가능 탐지와 쓰기 게이트 배선(DB 설계 §5.4-d 4, API 설계 §2-5-d). 탐지를 끈 노드도 상태 빈과 게이트를 가진다. 그때 상태는
 * 늘 ACTIVE 라 게이트는 그대로 통과시키고 세대 대조와 확정 응답 규칙은 종전 동작과 같다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WriteAvailabilityProperties.class)
public class WriteAvailabilityConfig {

    @Bean
    public WriteAvailability writeAvailability(WriteAvailabilityProperties props, MeterRegistry registry) {
        WriteAvailability availability = props.enabled()
                ? WriteAvailability.enabled(props.settings()) : WriteAvailability.disabled();
        for (WriteAvailability.State state : WriteAvailability.State.values()) {
            Gauge.builder("exchange.write.availability.state", availability,
                            a -> a.view().state() == state ? 1 : 0)
                    .tag("state", state.name())
                    .description("쓰기 가능 상태(해당 상태면 1). ACTIVE 가 아니면 거래 일시 중단이다")
                    .register(registry);
        }
        Gauge.builder("exchange.write.generation", availability, WriteAvailability::generation)
                .description("ACTIVE 를 떠난 수. 게이트를 지난 요청이 쓰기 직전에 대조한다")
                .register(registry);
        Gauge.builder("exchange.write.probe.outstanding", availability, WriteAvailability::outstandingSeconds)
                .baseUnit("seconds")
                .description("끝나지 않은 쓰기 가능 탐지가 기다린 시간")
                .register(registry);
        FunctionCounter.builder("exchange.write.probe.unconfirmed", availability, WriteAvailability::unconfirmedSlow)
                .description("탐지가 느렸으나 DB 쪽 확인이 없어 ACTIVE 로 둔 수(이 노드만의 느림 후보)")
                .register(registry);
        for (String reason : List.of(WriteAvailability.REASON_REPLICATION, WriteAvailability.REASON_DATABASE)) {
            FunctionCounter.builder("exchange.trading.suspended", availability, a -> a.suspensionEntries(reason))
                    .tag("reason", reason)
                    .description("거래 일시 중단에 들어간 수(이유별)")
                    .register(registry);
        }
        for (String path : List.of("requery", "v47")) {
            FunctionCounter.builder("exchange.write.definitive.deferred", availability, a -> a.deferred(path))
                    .tag("path", path)
                    .description("ACTIVE 가 아니거나 재확인 전이라 확정 응답을 409 로 미룬 수")
                    .register(registry);
        }
        return availability;
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "exchange.write-availability", name = "enabled", havingValue = "true")
    public WriteProbe writeProbe(WriteAvailability availability, WriteAvailabilityProperties props,
                                 DataSourceProperties dataSource, MeterRegistry registry) {
        Timer probeTimer = Timer.builder("exchange.write.probe")
                .description("쓰기 가능 탐지의 커밋 시간")
                .register(registry);
        return new WriteProbe(availability,
                WriteProbe.driverManager(dataSource.determineUrl(), dataSource.determineUsername(),
                        dataSource.determinePassword(), props.connectTimeout()),
                props.probeInterval(), props.confirmInterval(), props.probeSocketTimeout(),
                props.checkSocketTimeout(), probeTimer);
    }

    /**
     * 게이트웨이와 이체 서비스에 쓰기 가능 상태를 붙인다(세대 재확인, 재조회·V47 규칙). 생성자와 빈 메서드 시그니처를 늘리지 않는
     * 이유는 프로젝션 신호 배선(EngineConfig.projectionSignalWiring)과 같다. 두 빈을 직접 만드는 시험이 여럿이다.
     */
    @Bean
    public static BeanPostProcessor writeAvailabilityWiring(ObjectProvider<WriteAvailability> availability) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof OrderGateway gateway) {
                    gateway.attachWriteAvailability(availability.getObject());
                } else if (bean instanceof TransferService transfers) {
                    transfers.attachWriteAvailability(availability.getObject());
                } else if (bean instanceof kr.exchange.api.community.CommunityService community) {
                    community.attachWriteAvailability(availability.getObject());
                }
                return bean;
            }
        };
    }

    /** 게이트는 수용 제한(6)과 바디 상한(5) 앞이다(WriteGateFilter 주석). 관리 평면의 변경도 같은 게이트를 지난다. */
    @Bean
    public FilterRegistrationBean<WriteGateFilter> writeGateFilter(WriteAvailability availability,
                                                                  ErrorRenderer errorRenderer,
                                                                  LeaderWriteProgress writeProgress,
                                                                  MeterRegistry registry) {
        WriteGateFilter filter = new WriteGateFilter(availability, errorRenderer, writeProgress);
        FunctionCounter.builder("exchange.write.gate.rejected", filter, WriteGateFilter::suspendedRejections)
                .tag("response", "423")
                .description("쓰기 게이트가 거절한 요청 수")
                .register(registry);
        FunctionCounter.builder("exchange.write.gate.rejected", filter, WriteGateFilter::unreachableRejections)
                .tag("response", "503")
                .description("쓰기 게이트가 거절한 요청 수")
                .register(registry);
        FilterRegistrationBean<WriteGateFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/v1/*", "/admin/v1/*");
        registration.setOrder(4);
        return registration;
    }
}
