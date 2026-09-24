package kr.exchange.engine.lease;

/**
 * 자원별 리스 보관소(DB 설계 §5.4-c 2·12). 파티션마다 한 번 부른다. PostgreSQL 공급자는 자원마다 보관소를 만들고, DynamoDB
 * 공급자는 matching-engine 하나만 다룬다.
 */
@FunctionalInterface
public interface LeaseStoreProvider {

    LeaderLeaseStore store(String resource);
}
