package kr.exchange.config;

import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.api.common.AdmissionControl;
import kr.exchange.api.common.AdmissionLimits;
import kr.exchange.engine.LeaderLeaseService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 리스 전용 DataSource 배선 계약(§5.4). 기본 풀과 리스 풀이 서로 다른 인스턴스이고, 리스 풀이
 * 소형·짧은 타임아웃 격리 설정을 갖고 부팅되며, 리더 리스 서비스가 정상 승격함을 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaseDataSourceWiringIntegrationTest {

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
    }

    @Autowired
    @Qualifier("dataSource")
    HikariDataSource requestPool;
    @Autowired
    @Qualifier("leaseDataSource")
    HikariDataSource leasePool;
    @Autowired
    LeaderLeaseService appLease;
    @Autowired
    AdmissionLimits limits;
    @Autowired
    @Qualifier("orderAdmissionControl")
    AdmissionControl orderAdmission;
    @Autowired
    @Qualifier("queryAdmissionControl")
    AdmissionControl queryAdmission;

    @Test
    void leasePoolIsSeparateSmallAndIsolated() {
        assertNotSame(requestPool, leasePool, "리스 풀이 기본 풀과 같은 인스턴스다");
        assertEquals("exchange-lease", leasePool.getPoolName());
        assertNotEquals("exchange-lease", requestPool.getPoolName(),
                "기본 풀이 리스 풀 이름을 가졌다");
        assertEquals(2, leasePool.getMaximumPoolSize(), "리스 풀은 소형이어야 한다");
        assertEquals(2000, leasePool.getConnectionTimeout(), "리스 풀 커넥션 타임아웃 미적용");

        // 배선된 리스 서비스가 전용 풀로 정상 승격하는지 확인(단일 노드는 시드 만료 리스를 탈취).
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(appLease::isLeader);
    }

    /**
     * 수용 제한 상한 유도의 실배선(API 설계 §2-5-a). 이 컨텍스트는 상한 프로퍼티를 지정하지 않으므로
     * 두 버킷이 실제 요청 풀 크기에서 유도돼야 한다. 유도값은 코드에도 설정에도 숫자로 남지 않아
     * 배선이 끊겨도 조용히 굴러갈 수 있는 자리다. 기동 INFO 로그와 함께 이 단언이 그것을 막는다.
     */
    @Test
    void admissionLimitsAreDerivedFromRequestPool() {
        AdmissionLimits expected = AdmissionLimits.resolve(requestPool.getMaximumPoolSize(), 0, 0);
        assertEquals(expected.write(), limits.write(), "쓰기 상한이 풀에서 유도되지 않았다");
        assertEquals(expected.query(), limits.query(), "조회 상한이 풀에서 유도되지 않았다");
        assertEquals(expected.write(), orderAdmission.limit(), "쓰기 버킷이 유도 상한으로 뜨지 않았다");
        assertEquals(expected.query(), queryAdmission.limit(), "조회 버킷이 유도 상한으로 뜨지 않았다");
        // 예비분이 있어야 면제 경로·백그라운드가 굶지 않는다(AdmissionLimits 무대기 성질).
        assertTrue(limits.reserved() > 0, "면제 경로 몫이 남지 않았다");
    }
}
