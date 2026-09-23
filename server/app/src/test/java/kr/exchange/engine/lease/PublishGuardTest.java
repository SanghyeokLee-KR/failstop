package kr.exchange.engine.lease;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.config.LeaderLeaseStoreProperties;
import kr.exchange.config.LeaseStoreConfig;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

import static kr.exchange.engine.lease.EpochAllocationTest.acquisitions;
import static kr.exchange.engine.lease.PartitionLeaseDb.queryString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 외부 보관소 게시(ADR 0034 결정한 값 18, S2 설계 4-4·4-8). DB 게시 함수가 P3 에서 거부하는 것이 권위이고, DynamoDB 보관소
 * 빈의 기동 거부는 빨리 실패하게 하는 보조다. P1 에서는 게시가 V33 과 같은 행 변화에 이력 한 행을 더한다.
 */
class PublishGuardTest {

    @Test
    void p3RefusesPublishAndLeavesRowUntouched() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("publish_p3");
        db.seedLease("matching-engine", "old-node", 41, 3600);
        db.setProtocol(3);
        try (Connection c = db.owner()) {
            SQLException refused = assertThrows(SQLException.class,
                    () -> queryString(c, "SELECT publish_leader_epoch('node-q', 999)"));
            assertEquals("55000", refused.getSQLState(), refused.getMessage());
        }
        assertEquals("matching-engine:old-node:41,matching-p0::0,matching-p1::0", EpochAllocationTest.leaseRows(db));
        assertEquals("", acquisitions(db));
    }

    /** 앱 로그인 풀로 만든 게시자. 반환값과 행 변화는 V33 과 같고 이력은 PUBLISH 한 행이다. */
    @Test
    void appRolePublisherPublishesLikeV33AndRecordsOnce() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("publish_app").withRuntimeRoles();
        try (HikariDataSource app = db.appPool()) {
            PostgresFencingEpochPublisher publisher = new PostgresFencingEpochPublisher(JdbcClient.create(app));
            assertTrue(publisher.publish("node-d", 50));
            assertTrue(publisher.publish("node-d", 50), "같은 보유자의 재게시가 거부됐다");
            assertFalse(publisher.publish("node-e", 49), "작은 epoch 게시가 성립했다");
            assertFalse(publisher.publish("node-e", 50), "다른 보유자의 같은 epoch 게시가 성립했다");
        }
        assertEquals("matching-engine:node-d:50,matching-p0::0,matching-p1::0", EpochAllocationTest.leaseRows(db));
        assertEquals("true", db.ownerString("""
                SELECT (lease_until > clock_timestamp() + interval '99 years')::text
                FROM leader_lease WHERE resource = 'matching-engine'
                """));
        assertEquals("50:matching-engine:node-d:1:PUBLISH", acquisitions(db));
    }

    /**
     * 보관소 전환 순서. PostgreSQL 획득 X, 만료, FencingTokenAlignment 가 읽는 X 를 바닥으로 한 게시 X + 1, 만료, PostgreSQL
     * 재획득 X + 2 까지 이력 PK 가 부딪히지 않는다.
     */
    @Test
    void storeSwitchingKeepsEpochsUnique() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("publish_switch").withRuntimeRoles();
        try (HikariDataSource app = db.appPool()) {
            JdbcClient jdbc = JdbcClient.create(app);
            PostgresLeaderLeaseStore store = new PostgresLeaderLeaseStore(jdbc);
            PostgresFencingEpochPublisher publisher = new PostgresFencingEpochPublisher(jdbc);

            long x = store.acquire("pg-a", Duration.ofMinutes(1)).orElseThrow();
            expireNow(jdbc);
            long floor = jdbc.sql("SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'")
                    .query(Long.class).single();
            assertEquals(x, floor);
            assertTrue(publisher.publish("ddb-b", floor + 1));
            expireNow(jdbc);
            assertEquals(x + 2, store.acquire("pg-c", Duration.ofMinutes(1)).orElseThrow());
            assertEquals("%d:matching-engine:pg-a:1:ACQUIRE,%d:matching-engine:ddb-b:1:PUBLISH,%d:matching-engine:pg-c:1:ACQUIRE"
                    .formatted(x, x + 1, x + 2), acquisitions(db));
        }
    }

    /** DynamoDB 보관소 빈은 프로토콜 3 이면 기동을 멈춘다. 게시 전에 멈추므로 DynamoDB 에 닿지 않는다. */
    @Test
    void dynamoDbStoreBeanRefusesPartitionProtocol() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("publish_bean");
        db.setProtocol(3);
        try (HikariDataSource lease = db.ownerPool(); DynamoDbClient client = unreachableDynamoDb()) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> new LeaseStoreConfig().dynamoDbLeaderLeaseStore(dynamoProps(), client, lease,
                            new SimpleMeterRegistry()));
            assertTrue(refused.getMessage().contains("leader_protocol"), refused.getMessage());

            db.setProtocol(1);
            assertNotNull(new LeaseStoreConfig().dynamoDbLeaderLeaseStore(dynamoProps(), client, lease,
                    new SimpleMeterRegistry()), "P1 에서 DynamoDB 보관소를 만들지 못했다");
        }
    }

    /** 프로토콜을 읽지 못하면(V48 전 DB) 경고만 남기고 만든다. 그때도 DB 게시 함수가 권위로 남는다. */
    @Test
    void dynamoDbStoreBeanToleratesMissingProtocolTable() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.createAt("publish_v47", "47");
        try (HikariDataSource lease = db.ownerPool(); DynamoDbClient client = unreachableDynamoDb()) {
            assertNotNull(new LeaseStoreConfig().dynamoDbLeaderLeaseStore(dynamoProps(), client, lease,
                    new SimpleMeterRegistry()));
        }
    }

    /** 보관소를 PostgreSQL 로 되돌릴 때 부르는 V33 함수. 앱 역할에 EXECUTE 가 있다. */
    private static void expireNow(JdbcClient jdbc) {
        jdbc.sql("SELECT expire_leader_lease_now()").query((rs, rowNum) -> 0).list();
    }

    private static LeaderLeaseStoreProperties dynamoProps() {
        return new LeaderLeaseStoreProperties(LeaderLeaseStoreProperties.DYNAMODB, "s2-guard", "s2-guard",
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
