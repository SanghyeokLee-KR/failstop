package kr.exchange.config;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.lease.LeaseStoreProvider;
import kr.exchange.engine.lease.PartitionLeaseDb;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DynamoDB 리스 보관소의 파티션 모드 차단(S4 설계 4-20, DB 설계 §5.4-c 12, ADR 0034 결정한 값 18). DynamoDB 보관소는
 * matching-engine 하나만 다룬다. 설정이 파티션 자원을 참여·선호로 적었으면 기동을 멈추고, 공급자는 파티션 자원을 달라는
 * 요청을 거부한다. 모드 3 거부는 PublishGuardTest 가 본다.
 */
class DynamoDbPartitionGuardTest {

    @Test
    void partitionResourcesInSettingsAreRefused() {
        assertThrows(IllegalStateException.class, () -> LeaseStoreConfig.refusePartitionSettings(
                new LeaderPartitionProperties(List.of("matching-p0"), null, 0, null)));
        assertThrows(IllegalStateException.class, () -> LeaseStoreConfig.refusePartitionSettings(
                new LeaderPartitionProperties(null, List.of("matching-p1"), 0, null)));
        assertDoesNotThrow(() -> LeaseStoreConfig.refusePartitionSettings(
                new LeaderPartitionProperties(null, null, 0, null)));
        assertDoesNotThrow(() -> LeaseStoreConfig.refusePartitionSettings(
                new LeaderPartitionProperties(List.of("matching-engine"), List.of("matching-engine"), 0, null)));
    }

    @Test
    void beanRefusesPartitionSettingsAndProviderRefusesPartitionResources() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("pml_dynamo_guard");
        try (HikariDataSource lease = db.ownerPool(); DynamoDbClient client = unreachableDynamoDb()) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> new LeaseStoreConfig().dynamoDbLeaseStores(dynamoProps(),
                            new LeaderPartitionProperties(List.of("matching-p0"), null, 0, null), client, lease,
                            new SimpleMeterRegistry()));
            assertTrue(refused.getMessage().contains("matching-p0"), refused.getMessage());

            LeaseStoreProvider stores = new LeaseStoreConfig().dynamoDbLeaseStores(dynamoProps(),
                    new LeaderPartitionProperties(null, null, 0, null), client, lease, new SimpleMeterRegistry());
            assertNotNull(stores.store("matching-engine"));
            for (String resource : new String[] {"matching-p0", "matching-p1"}) {
                IllegalStateException partition = assertThrows(IllegalStateException.class, () -> stores.store(resource));
                assertTrue(partition.getMessage().contains(resource), partition.getMessage());
            }
        }
    }

    private static LeaderLeaseStoreProperties dynamoProps() {
        return new LeaderLeaseStoreProperties(LeaderLeaseStoreProperties.DYNAMODB, "s4-guard", "s4-guard",
                "http://127.0.0.1:9", "ap-northeast-2", false);
    }

    /** 닿지 않는 주소의 클라이언트. 빈이 DynamoDB 를 부르면 연결 오류가 나고 부르지 않으면 아무 일도 없다. */
    private static DynamoDbClient unreachableDynamoDb() {
        return DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .endpointOverride(URI.create("http://127.0.0.1:9"))
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }
}
