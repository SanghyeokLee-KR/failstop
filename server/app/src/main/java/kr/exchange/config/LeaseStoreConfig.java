package kr.exchange.config;

import kr.exchange.engine.lease.DynamoDbLeaderLeaseStore;
import kr.exchange.engine.lease.DynamoDbLeaseTable;
import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.engine.lease.FencingTokenAlignment;
import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.lease.LeaseResources;
import kr.exchange.engine.lease.LeaseStoreProvider;
import kr.exchange.engine.lease.MeteredLeaderLeaseStore;
import kr.exchange.engine.lease.PostgresFencingEpochPublisher;
import kr.exchange.engine.lease.PostgresLeaderLeaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Clock;
import java.util.List;

/**
 * 리더 리스 보관소 배선(2026-09-08). 거래 데이터는 PostgreSQL 에 그대로 두고 리스만 가른다.
 *
 * 가른 이유는 실측이다. 리스가 PostgreSQL 행이면 갱신이 곧 커밋이고, 동기 복제 대상 standby 를
 * 전부 잃으면 갱신이 멈춘다. 그 사이 리스가 만료되고 커밋이 풀리는 순간 fencing 이 리더를
 * 끌어내렸다. 근거는 `harness/evidence/replication-fault-20260908/` 이다.
 *
 * 두 빈 모두 보관소 설정 하나로 짝이 맞게 갈린다. postgres 보관소는 승격 UPDATE 가 이미
 * fencing 검증이 읽는 행을 올리므로 게시가 필요 없고, dynamodb 보관소는 그 행이 갱신되지
 * 않으므로 승격 시 한 번 게시한다. 짝을 어긋나게 하면 쓰기 경로가 낡은 epoch 을 본다.
 *
 * 보관소는 자원마다 하나다(DB 설계 §5.4-c 2). DynamoDB 보관소는 파티션 리스를 지원하지 않는다(§5.4-c 12).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LeaderLeaseStoreProperties.class, LeaderPartitionProperties.class})
public class LeaseStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(LeaseStoreConfig.class);

    /**
     * 리스 전용 DynamoDB 클라이언트. 보관소가 dynamodb 일 때만 만든다.
     *
     * endpoint 와 region 이 비면 AWS SDK 기본 해석 사슬을 쓴다. 로컬 주소는 설정으로만 들어오고
     * 코드에는 남지 않는다. 자격 증명도 SDK 기본 사슬이며 여기서 잡지 않는다.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "exchange.leader.lease", name = "provider",
            havingValue = LeaderLeaseStoreProperties.DYNAMODB)
    public DynamoDbClient leaseDynamoDbClient(LeaderLeaseStoreProperties props) {
        // 전송을 명시한다. SDK는 클래스패스에서 동기 전송을 찾는데, 그 탐색에 기대면 의존성이
        // 하나 더 들어오는 것만으로 전송이 조용히 바뀐다.
        var builder = DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        if (!props.endpoint().isEmpty()) {
            builder = builder.endpointOverride(URI.create(props.endpoint()));
        }
        if (!props.region().isEmpty()) {
            builder = builder.region(Region.of(props.region()));
        }
        DynamoDbClient client = builder.build();
        if (props.createTableIfMissing()) {
            DynamoDbLeaseTable.ensure(client, props.table(), "clusterId");
        }
        return client;
    }

    /**
     * 보관소. dynamodb 이면 DynamoDB 항목 하나(matching-engine)다.
     *
     * DynamoDbClient 를 필수 인자로 받는 이유는 배선 오류를 기동에서 잡기 위해서다. 선택 인자로
     * 두고 없으면 postgres 로 떨어지게 하면, 클라이언트 생성이 실패한 배포가 조용히 종전 경로로
     * 돌아 옮긴 줄 알고 종전 성질을 그대로 안게 된다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "exchange.leader.lease", name = "provider",
            havingValue = LeaderLeaseStoreProperties.DYNAMODB)
    public LeaseStoreProvider dynamoDbLeaseStores(LeaderLeaseStoreProperties props,
                                                  LeaderPartitionProperties partitionProps,
                                                  DynamoDbClient leaseDynamoDbClient,
                                                  @Qualifier("leaseDataSource") DataSource leaseDataSource,
                                                  MeterRegistry registry) {
        JdbcClient leaseJdbc = JdbcClient.create(leaseDataSource);
        refuseInPartitionMode(leaseJdbc);
        refusePartitionSettings(partitionProps);
        // 두 보관소의 카운터가 서로 다른 값에서 시작하므로 전환 첫 회차에 하한을 맞춘다.
        // 맞추지 않으면 게시 단조 조건에 걸려 승격이 영구 실패한다.
        FencingTokenAlignment.align(leaseDynamoDbClient, props.table(), props.clusterId(), leaseJdbc);
        LeaderLeaseStore store = new MeteredLeaderLeaseStore(
                new DynamoDbLeaderLeaseStore(leaseDynamoDbClient, props.table(), props.clusterId(),
                        Clock.systemUTC()),
                registry, Clock.systemUTC(), LeaseResources.MATCHING_ENGINE);
        return resource -> {
            if (!LeaseResources.MATCHING_ENGINE.equals(resource)) {
                throw new IllegalStateException("DynamoDB 리스 보관소는 파티션 리스를 지원하지 않는다: " + resource
                        + " (ADR 0034 결정한 값 18)");
            }
            return store;
        };
    }

    /**
     * 파티션 모드(leader_protocol 3)에서는 DynamoDB 보관소로 뜨지 않는다(ADR 0034 결정한 값 18). 권위는 DB 게시 함수가
     * P3 게시를 거부하는 것이고 이 검사는 기동에서 빨리 드러내는 보조다. 읽지 못하면(V48 전 DB, DB 불통) 막지 않는다.
     */
    static void refuseInPartitionMode(JdbcClient leaseJdbc) {
        Integer protocol;
        try {
            protocol = leaseJdbc.sql("SELECT protocol FROM leader_protocol")
                    .query(Integer.class)
                    .optional()
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("leader_protocol 을 읽지 못해 DynamoDB 보관소의 파티션 모드 검사를 건너뛴다. "
                    + "P3 게시는 DB 가 거부한다: {}", e.toString());
            return;
        }
        if (protocol != null && protocol == LeaseResources.PARTITION_PROTOCOL) {
            throw new IllegalStateException("leader_protocol 이 3(파티션 모드)이라 DynamoDB 리스 보관소로 뜰 수 없다. "
                    + "파티션 모드의 리스는 PostgreSQL 파티션 행이다(ADR 0034 결정한 값 18)");
        }
    }

    /**
     * 설정이 파티션 자원을 참여·선호로 적었으면 DynamoDB 보관소로 뜨지 않는다(DB 설계 §5.4-c 12). 그 설정은 P3 을 뜻하는데
     * DynamoDB 보관소는 matching-engine 하나만 다룬다.
     */
    static void refusePartitionSettings(LeaderPartitionProperties partitionProps) {
        for (String resource : List.of(LeaseResources.PARTITION_P0, LeaseResources.PARTITION_P1)) {
            if (partitionProps.preferred().contains(resource) || partitionProps.participate().contains(resource)) {
                throw new IllegalStateException("exchange.leader.partition 설정이 파티션 자원(" + resource
                        + ")을 적었는데 리스 보관소가 DynamoDB 다. 파티션 리스는 PostgreSQL 행만 지원한다"
                        + "(ADR 0034 결정한 값 18)");
            }
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "exchange.leader.lease", name = "provider",
            havingValue = LeaderLeaseStoreProperties.POSTGRES, matchIfMissing = true)
    public LeaseStoreProvider postgresLeaseStores(@Qualifier("leaseDataSource") DataSource leaseDataSource,
                                                  EngineProperties engineProps, MeterRegistry registry) {
        // 계측은 두 보관소에 똑같이 건다. 한쪽만 계측하면 옮기기 전후를 같은 축으로 비교할 수 없다.
        // 획득에는 이 바이너리가 아는 최고 모드 LeaseResources.MAX_PROTOCOL 을 넘긴다(S7 부터 3, DB 설계 §5.4-c 13). 그래서 P3 DB 에서
        // 파티션 리스를 얻는다. 모드의 권위는 DB 라서 P1·P2 DB 에서는 결과가 같다. 최고 모드 2 인 S6 이전 바이너리는 P3 DB 에서
        // 어떤 파티션도 얻지 못하고 팔로워로 남는다.
        return resource -> new MeteredLeaderLeaseStore(
                new PostgresLeaderLeaseStore(leaseDataSource, resource, LeaseResources.MAX_PROTOCOL,
                        engineProps.leaseAcquireLockTimeout()),
                registry, Clock.systemUTC(), resource);
    }

    /**
     * fencing epoch 게시자. 게시도 리스 전용 풀을 탄다. 요청 풀이 포화된 구간에도 승격이
     * 성립해야 하고, 그 이유는 리스 갱신을 그 풀로 격리한 이유와 같다. 획득과 같은 잠금 대기 상한을 건다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "exchange.leader.lease", name = "provider",
            havingValue = LeaderLeaseStoreProperties.DYNAMODB)
    public FencingEpochPublisher postgresFencingEpochPublisher(
            @Qualifier("leaseDataSource") DataSource leaseDataSource, EngineProperties engineProps) {
        return new PostgresFencingEpochPublisher(leaseDataSource, engineProps.leaseAcquireLockTimeout());
    }

    @Bean
    @ConditionalOnProperty(prefix = "exchange.leader.lease", name = "provider",
            havingValue = LeaderLeaseStoreProperties.POSTGRES, matchIfMissing = true)
    public FencingEpochPublisher noopFencingEpochPublisher() {
        return FencingEpochPublisher.NONE;
    }
}
