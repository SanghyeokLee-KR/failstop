package kr.exchange.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 리더 리스 보관소 설정(2026-09-08). 거래 데이터는 PostgreSQL 에 그대로 두고 리스만 옮긴다.
 *
 * 기본값이 postgres 인 이유가 있다. 옮기기 전과 옮긴 뒤를 같은 조건에서 비교해야 하고, 기존
 * 통합 검증 전량이 종전 경로를 전제한다. 옮긴 경로는 명시 설정으로만 켜진다.
 *
 * @param provider           postgres 또는 dynamodb
 * @param table              DynamoDB 테이블 이름
 * @param clusterId          파티션 키 값. 한 항목이 한 클러스터다
 * @param endpoint           비우면 AWS SDK 기본 해석 사슬을 쓴다. 로컬 DynamoDB 주소는 여기로만
 *                           들어오고 코드에는 남지 않는다
 * @param region             비우면 AWS SDK 기본 해석 사슬을 쓴다
 * @param createTableIfMissing 로컬 검증용. 운영은 거짓으로 두고 테이블을 미리 만든다.
 *                             참이면 앱 롤에 CreateTable 권한이 필요해 권한 경계가 넓어진다
 */
@ConfigurationProperties(prefix = "exchange.leader.lease")
public record LeaderLeaseStoreProperties(String provider, String table, String clusterId,
                                         String endpoint, String region,
                                         boolean createTableIfMissing) {

    public static final String POSTGRES = "postgres";
    public static final String DYNAMODB = "dynamodb";

    public LeaderLeaseStoreProperties {
        provider = provider == null || provider.isBlank() ? POSTGRES : provider.trim();
        table = table == null || table.isBlank() ? "exchange-leader-lease" : table.trim();
        clusterId = clusterId == null || clusterId.isBlank() ? "exchange-main" : clusterId.trim();
        endpoint = endpoint == null ? "" : endpoint.trim();
        region = region == null ? "" : region.trim();
        if (!POSTGRES.equals(provider) && !DYNAMODB.equals(provider)) {
            // 오타를 무음으로 두면 postgres 로 조용히 떨어져 옮긴 줄 알고 종전 경로를 잰다.
            throw new IllegalArgumentException(
                    "exchange.leader.lease.provider 는 " + POSTGRES + " 또는 " + DYNAMODB
                            + " 이어야 한다. 받은 값: " + provider);
        }
    }

    public boolean dynamoDb() {
        return DYNAMODB.equals(provider);
    }
}
