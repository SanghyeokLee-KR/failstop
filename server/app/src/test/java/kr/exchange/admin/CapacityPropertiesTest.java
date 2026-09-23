package kr.exchange.admin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 용량 콘솔 계약면의 기본값과 로컬 카오스 스택 인벤토리(application.yml admin-chaos). */
class CapacityPropertiesTest {

    @Test
    void 값을_주지_않으면_계약_임계와_기본_노드_속성을_쓴다() {
        CapacityProperties props = new CapacityProperties();
        CapacityProperties.Node node = new CapacityProperties.Node();

        assertEquals(0.60, props.getScaleOutCpu());
        assertEquals(0.30, props.getScaleInCpu());
        assertEquals(Duration.ofSeconds(2), props.getProbeTimeout());
        assertTrue(props.getNodes().isEmpty());
        assertFalse(node.isEngine(), "노드는 명시하지 않으면 조회 전용이다");
        assertTrue(node.isScalable(), "조회 전용 노드는 명시하지 않으면 확장 대상이다");
    }

    @Test
    void 카오스_인벤토리는_compose_가_엔진을_켠_두_노드를_리더_후보로_적는다() throws Exception {
        CapacityProperties props = bind("admin-chaos");

        // harness/docker-compose.chaos.yml 에서 api-1·api-2 는 EXCHANGE_ENGINE_ENABLED=true, api-3 은 false 다.
        assertEquals(List.of("chaos-api-1", "chaos-api-2", "chaos-api-3"),
                props.getNodes().stream().map(CapacityProperties.Node::getId).toList());
        assertEquals(List.of(true, true, false),
                props.getNodes().stream().map(CapacityProperties.Node::isEngine).toList());
        assertEquals(List.of(false, false, true),
                props.getNodes().stream().map(CapacityProperties.Node::isScalable).toList());
        assertEquals(0.25, props.getScaleOutCpu());
        assertEquals(0.10, props.getScaleInCpu());
    }

    private static CapacityProperties bind(String profile) throws Exception {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        PropertySource<?> document = documents.stream()
                .filter(d -> profile.equals(d.getProperty("spring.config.activate.on-profile")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("application.yml 에 " + profile + " 문서가 없다"));
        return new Binder(ConfigurationPropertySources.from(document))
                .bind("exchange.capacity", CapacityProperties.class)
                .orElseThrow(() -> new AssertionError(profile + " 에 exchange.capacity 가 없다"));
    }
}
