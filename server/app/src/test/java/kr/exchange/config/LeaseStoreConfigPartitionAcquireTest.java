package kr.exchange.config;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.lease.LeaseResources;
import kr.exchange.engine.lease.LeaseStoreProvider;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.journal.TransientDbErrors;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 운영 배선으로 P3 파티션 리스를 얻는다(ADR 0034 S7, S7 설계 4-2, DB 설계 §5.4-c 13). 다른 P3 시험은 PartitionNode 가 최고 모드 3 을
 * 직접 넘기므로 운영 배선이 넘기는 LeaseResources.MAX_PROTOCOL 을 거치지 않는다. 여기서는 LeaseStoreConfig.postgresLeaseStores 가
 * 만든 보관소를 앱 로그인 풀 위에 세워, 운영과 같은 전환 함수로 P3 에 올린 DB 에서 두 파티션을 얻고 matching-engine 은 거부되는
 * 것을 본다. 상수가 2 로 돌아가면 파티션 획득이 55000 으로 끝나 이 시험이 실패한다.
 */
class LeaseStoreConfigPartitionAcquireTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    @Test
    void productionWiringAcquiresBothPartitionsOnAP3Database() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("s7_wiring").withRuntimeRoles();
        db.seedMarkets(1, 2);
        db.exec("""
                INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
                SELECT 'SYSTEM', 0, 'FEE_INCOME', a.id, 0 FROM assets a
                ON CONFLICT (owner_type, owner_id, purpose, asset_id, shard) DO NOTHING
                """);
        assertEquals(3, db.switchProtocol(2, false));
        db.exec("SELECT latch_command_band_cutover()");
        assertEquals(3, db.switchProtocol(3, false));
        long retiredMax = db.ownerLong("SELECT max(epoch) FROM leader_lease");

        try (HikariDataSource appPool = db.appPool()) {
            LeaseStoreProvider stores = new LeaseStoreConfig().postgresLeaseStores(appPool,
                    new EngineProperties(null, null, null, 0, null, null, null), new SimpleMeterRegistry());

            Optional<Long> p0 = stores.store(LeaseResources.PARTITION_P0).acquire("N1", TTL);
            Optional<Long> p1 = stores.store(LeaseResources.PARTITION_P1).acquire("N2", TTL);
            assertTrue(p0.isPresent() && p1.isPresent(), "운영 배선이 P3 DB 에서 파티션 리스를 얻지 못했다");
            assertTrue(p0.get() > retiredMax && p1.get() > p0.get(), "P3 첫 획득이 마지막 퇴역 epoch 뒤가 아니다");
            assertEquals("N1:" + p0.get(),
                    db.ownerString("SELECT holder || ':' || epoch FROM leader_lease WHERE resource = 'matching-p0'"));
            assertEquals("N2:" + p1.get(),
                    db.ownerString("SELECT holder || ':' || epoch FROM leader_lease WHERE resource = 'matching-p1'"));
            assertEquals(2, db.ownerLong("""
                    SELECT count(*) FROM lease_acquisitions
                     WHERE origin = 'ACQUIRE' AND protocol = 3 AND resource IN ('matching-p0', 'matching-p1')
                    """), "획득 이력이 모드 3 의 ACQUIRE 두 행이 아니다");

            // P3 에서 matching-engine 은 어떤 바이너리도 얻지 못한다(단일 리더로 돌아가는 길은 3->2 전환뿐이다).
            DataAccessException refused = assertThrows(DataAccessException.class,
                    () -> stores.store(LeaseResources.MATCHING_ENGINE).acquire("N3", TTL));
            assertEquals("55000", TransientDbErrors.sqlState(refused), refused.getMessage());
            assertEquals("", db.ownerString("SELECT holder FROM leader_lease WHERE resource = 'matching-engine'"));
        }
    }
}
