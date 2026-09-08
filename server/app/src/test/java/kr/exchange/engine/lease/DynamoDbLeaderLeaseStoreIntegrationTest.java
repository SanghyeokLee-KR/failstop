package kr.exchange.engine.lease;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DynamoDB 리스 보관소의 조건부 쓰기 계약(2026-09-08).
 *
 * 이 검증이 있어야 보관소 교체가 무증거 변경이 아니게 된다. 특히 경쟁 조건은 코드를 읽어서는
 * 알 수 없다. 조건부 갱신이 실제로 원자적인지는 돌려 봐야 한다.
 *
 * 시계를 주입하는 이유는 만료를 기다리지 않기 위해서다. TTL 을 실제로 흘려보내면 회차가 느려지고
 * 느린 회차는 결국 꺼진다.
 */
class DynamoDbLeaderLeaseStoreIntegrationTest {

    private static final String TABLE = "exchange-leader-lease-test";
    private static final String CLUSTER = "exchange-test";
    private static final Duration TTL = Duration.ofSeconds(10);

    static final GenericContainer<?> DYNAMO =
            new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:2.5.2"))
                    .withExposedPorts(8000)
                    .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    static DynamoDbClient client;

    static {
        DYNAMO.start();
        client = DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .endpointOverride(URI.create(
                        "http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000)))
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();
    }

    /** 밀리초를 손으로 미는 시계. 만료를 기다리지 않고 만든다. */
    private static final class MovableClock extends Clock {
        private volatile long millis = Instant.parse("2026-09-08T00:00:00Z").toEpochMilli();

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }

        void advance(Duration d) {
            millis += d.toMillis();
        }
    }

    private MovableClock clock;
    private DynamoDbLeaderLeaseStore store;

    @BeforeEach
    void setUp() {
        try {
            client.deleteTable(DeleteTableRequest.builder().tableName(TABLE).build());
        } catch (RuntimeException e) {
            // 첫 회차에는 테이블이 없다.
        }
        DynamoDbLeaseTable.ensure(client, TABLE, "clusterId");
        clock = new MovableClock();
        store = new DynamoDbLeaderLeaseStore(client, TABLE, CLUSTER, clock);
    }

    @AfterAll
    static void tearDown() {
        client.close();
        DYNAMO.stop();
    }

    @Test
    void 최초_승격은_항목이_없어도_성립하고_토큰은_1이다() {
        Optional<Long> acquired = store.acquire("node-a", TTL);

        assertTrue(acquired.isPresent(), "빈 테이블에서 최초 승격이 실패했다");
        assertEquals(1L, acquired.get(), "최초 fencing 토큰이 1이 아니다");

        LeaseSnapshot snap = store.current().orElseThrow();
        assertEquals("node-a", snap.holder());
        assertEquals(1L, snap.epoch());
        assertFalse(snap.expiredAt(clock.instant()), "방금 얻은 리스가 만료로 보인다");
    }

    /**
     * 전환 이음매. 다른 보관소에서 넘어올 때 토큰 하한만 심은 항목이 만들어질 수 있고, 그 항목에는
     * 만료 시각이 없다. 없는 속성과의 비교는 거짓이라 조건을 잘못 쓰면 승격이 영원히 실패한다.
     * 2026-09-08 전환에서 실제로 막혔던 자리다.
     */
    @Test
    void 만료_시각이_없는_항목에서도_승격한다() {
        client.updateItem(software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.builder()
                .tableName(TABLE)
                .key(java.util.Map.of("clusterId",
                        software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                .s(CLUSTER).build()))
                .updateExpression("SET fencingToken = :floor")
                .expressionAttributeValues(java.util.Map.of(":floor",
                        software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                .n("9").build()))
                .build());

        assertEquals(10L, store.acquire("node-a", TTL).orElseThrow(),
                "토큰만 심긴 항목에서 승격이 실패했거나 하한을 잇지 않았다");
    }

    @Test
    void 유효한_리스는_탈취되지_않는다() {
        store.acquire("node-a", TTL);

        assertTrue(store.acquire("node-b", TTL).isEmpty(), "유효한 타 리더의 리스를 탈취했다");
        assertEquals("node-a", store.current().orElseThrow().holder());
    }

    @Test
    void 갱신은_보유자와_토큰이_모두_맞아야_성립한다() {
        long epoch = store.acquire("node-a", TTL).orElseThrow();

        assertTrue(store.renew("node-a", epoch, TTL), "정당한 보유자의 갱신이 거부됐다");
        assertFalse(store.renew("node-b", epoch, TTL), "남의 리스를 갱신했다");
        assertFalse(store.renew("node-a", epoch + 1, TTL), "틀린 토큰으로 갱신했다");
        assertEquals(epoch, store.current().orElseThrow().epoch(), "갱신이 토큰을 바꿨다");
    }

    @Test
    void 만료된_뒤에는_갱신이_불가능하고_탈취만_가능하다() {
        long epoch = store.acquire("node-a", TTL).orElseThrow();
        clock.advance(TTL.plusSeconds(1));

        assertFalse(store.renew("node-a", epoch, TTL), "만료 뒤 갱신이 성립했다");

        long taken = store.acquire("node-b", TTL).orElseThrow();
        assertEquals(epoch + 1, taken, "탈취가 fencing 토큰을 올리지 않았다");
        assertEquals("node-b", store.current().orElseThrow().holder());
    }

    @Test
    void 반납하면_만료를_기다리지_않고_다음_리더가_잡는다() {
        long epoch = store.acquire("node-a", TTL).orElseThrow();

        store.release("node-a", epoch);

        assertEquals(epoch + 1, store.acquire("node-b", TTL).orElseThrow(),
                "반납 뒤 승격이 토큰을 올리지 않았다");
    }

    /**
     * 이 회차가 이 파일의 존재 이유다. 조건부 쓰기가 아니라 읽고 쓰기였다면 여기서 두 노드가
     * 함께 승격한다. 같은 만료를 동시에 보기 때문이다.
     */
    @Test
    void 동시_승격_경쟁에서_정확히_한_노드만_리더가_된다() throws Exception {
        int nodes = 12;
        CountDownLatch ready = new CountDownLatch(nodes);
        CountDownLatch go = new CountDownLatch(1);
        Set<Long> tokens = ConcurrentHashMap.newKeySet();
        AtomicInteger winners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(nodes);
        try {
            for (int i = 0; i < nodes; i++) {
                String node = "node-" + i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    store.acquire(node, TTL).ifPresent(token -> {
                        winners.incrementAndGet();
                        tokens.add(token);
                    });
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "경쟁 스레드가 준비되지 않았다");
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "경쟁 회차가 끝나지 않았다");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, winners.get(), "동시 승격에서 리더가 하나가 아니다");
        assertEquals(List.of(1L), List.copyOf(tokens), "승격 토큰이 1이 아니거나 중복 발급됐다");
        assertEquals(1L, store.current().orElseThrow().epoch());
    }

    /**
     * 연속 페일오버에서 토큰이 단조 증가하는지. 같은 토큰이 두 번 나오면 fencing 이 무력화된다.
     * 게시된 epoch 으로 stale 리더를 가르는 구조라 이 성질이 안전성의 뿌리다.
     */
    @Test
    void 연속_페일오버에서_토큰이_단조_증가한다() {
        long prev = 0;
        for (int i = 0; i < 5; i++) {
            long token = store.acquire("node-" + i, TTL).orElseThrow();
            assertTrue(token > prev, "토큰이 증가하지 않았다: 이전=" + prev + ", 지금=" + token);
            prev = token;
            clock.advance(TTL.plusSeconds(1));
        }
        assertEquals(5L, prev, "다섯 번 승격했는데 토큰이 5가 아니다");
    }
}
