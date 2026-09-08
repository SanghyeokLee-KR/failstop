package kr.exchange.config;

import kr.exchange.engine.FollowerCatchUp;
import kr.exchange.engine.PromotionCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 엔진을 끈 노드가 기동하는지 본다(2026-09-08).
 *
 * 봇 전용 노드는 `exchange.engine.enabled=false` 로 뜬다. 리더 경쟁에 끼면 봇이 죽인 리더로
 * 승격 주체가 오염되기 때문이다(`deploy/compose.node-bot.yml`).
 *
 * 이 검증이 있는 이유는 실제로 깨졌기 때문이다. 체크포인트 상한 작업에서 `FollowerCatchUp` 을
 * 넣으면서 `promotionCoordinator` 가 그것을 필수 인자로 받았는데, 그 빈은 엔진과 같은 조건에서만
 * 뜬다. 그래서 봇 컨테이너가 "required a bean of type FollowerCatchUp" 으로 기동에 실패하고
 * 재시작을 반복했다. 통합 검증 전량이 엔진을 켠 채로만 돌아 아무도 잡지 못했다.
 *
 * 판정 대상은 하나다. 엔진이 없어도 컨텍스트가 뜬다. 따라잡기 빈은 없어야 맞다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EngineDisabledContextTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("exchange.auth.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("exchange.engine.enabled", () -> "false");
    }

    @Autowired ApplicationContext context;

    @Test
    void 엔진을_끈_노드도_기동한다() {
        assertNotNull(context.getBean(PromotionCoordinator.class),
                "엔진이 꺼진 노드에서 승격 조율 빈이 뜨지 않았다");
        assertEquals(0, context.getBeanNamesForType(FollowerCatchUp.class).length,
                "엔진이 꺼졌는데 따라잡기 빈이 떴다. 재생할 엔진이 없으므로 있으면 안 된다");
    }
}
