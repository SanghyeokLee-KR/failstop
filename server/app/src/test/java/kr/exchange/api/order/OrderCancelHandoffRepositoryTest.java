package kr.exchange.api.order;

import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 인계 표의 문장(DB 설계 §3.9·§5.4-d 2~6, S5 설계 6-2). V54 를 올린 일회용 DB 에서 배포와 같은 앱 로그인(app_role)으로 넣기·
 * 점유·포기·재점유·끝내기·유효 시간·만료를 본다. 리스 행은 소유자가 바꿔 흉내 낸다(폐기용 확인 P5·P6 와 같은 방법).
 */
class OrderCancelHandoffRepositoryTest {

    private static final String P0 = "matching-p0";
    private static final String P1 = "matching-p1";
    private static final AtomicInteger EPOCHS = new AtomicInteger(100);

    static PartitionLeaseDb db;
    static HikariDataSource ownerPool;
    static HikariDataSource appPool;
    static JdbcClient owner;
    static OrderCancelHandoffRepository repo;
    static long user;

    @BeforeAll
    static void setUp() {
        db = PartitionLeaseDb.create("pml_ocr_repo").withRuntimeRoles();
        ownerPool = db.ownerPool();
        appPool = db.appPool();
        owner = JdbcClient.create(ownerPool);
        user = new AccountBootstrap(owner).user("handoff@repo.test");
        repo = new OrderCancelHandoffRepository(JdbcClient.create(appPool),
                new TransactionTemplate(new JdbcTransactionManager(appPool)));
    }

    @AfterAll
    static void tearDown() {
        appPool.close();
        ownerPool.close();
    }

    @Test
    void insertedRowsAreReadBackInOrder() {
        UUID request = UUID.randomUUID();
        List<UUID> p0 = ids(3);
        List<UUID> p1 = ids(2);
        List<Long> inserted = repo.insert(request, user, OrderCancelHandoff.ORIGIN_CANCEL_ALL,
                List.of(new OrderCancelHandoffRepository.NewRow(P0, p0), new OrderCancelHandoffRepository.NewRow(P1, p1)));
        List<OrderCancelHandoffRepository.Row> rows = repo.byRequest(request);
        assertEquals(inserted, rows.stream().map(OrderCancelHandoffRepository.Row::id).toList());
        assertEquals(p0, rows.get(0).orderIds());
        assertEquals(P1, rows.get(1).resource());
        assertTrue(rows.stream().allMatch(r -> r.state() == OrderCancelHandoffRepository.State.PENDING));
    }

    /** 점유와 끝내기는 리스 행이 그 holder·epoch 로 유효할 때만 된다(R1). 결과는 DONE 행에만 있다. */
    @Test
    void claimAndCompleteRequireTheCallersValidLease() throws Exception {
        Lease lease = freshLease(P0, "holder-a");
        UUID request = insert(P0, 2);
        assertTrue(repo.claim(P0, "holder-b", lease.epoch).isEmpty(), "다른 holder 가 점유했다");
        assertTrue(repo.claim(P0, "holder-a", lease.epoch + 1).isEmpty(), "리스 행과 다른 epoch 가 점유했다");
        OrderCancelHandoffRepository.Claimed claimed = repo.claim(P0, "holder-a", lease.epoch).orElseThrow();
        assertFalse(claimed.reclaimed());
        assertEquals(user, claimed.userId());
        assertEquals(1, repo.complete(claimed.id(), "holder-a", lease.epoch, "{\"canceled\":[]}"));
        assertEquals(OrderCancelHandoffRepository.State.DONE, repo.byRequest(request).get(0).state());
        assertEquals("{\"canceled\": []}", repo.byRequest(request).get(0).result());
        // 만료된 리스로는 점유하지 못한다.
        db.expire(P0);
        insert(P0, 1);
        assertTrue(repo.claim(P0, "holder-a", lease.epoch).isEmpty(), "만료된 리스로 점유했다");
    }

    /** 같은 epoch 는 끝나지 않은 점유를 다시 집지 않고 더 큰 epoch 만 다시 집는다. 옛 점유자의 끝내기는 0행이다(P5-5). */
    @Test
    void onlyALargerEpochReclaimsAndTheOldClaimerCannotComplete() throws Exception {
        Lease first = freshLease(P1, "holder-a");
        insert(P1, 1);
        OrderCancelHandoffRepository.Claimed claimed = repo.claim(P1, "holder-a", first.epoch).orElseThrow();
        assertTrue(repo.claim(P1, "holder-a", first.epoch).isEmpty(), "같은 epoch 가 끝나지 않은 점유를 다시 집었다");
        Lease second = lease(P1, "holder-b");
        OrderCancelHandoffRepository.Claimed again = repo.claim(P1, "holder-b", second.epoch).orElseThrow();
        assertEquals(claimed.id(), again.id());
        assertTrue(again.reclaimed(), "재점유 표식이 없다");
        assertEquals(0, repo.complete(claimed.id(), "holder-a", first.epoch, "{}"), "옛 점유자가 끝냈다");
        assertEquals(1, repo.complete(again.id(), "holder-b", second.epoch, "{}"));
    }

    /**
     * 리스를 잃은 점유자(R1·R5). 리스가 다른 holder·epoch 로 넘어간 뒤 옛 점유자의 끝내기(점유자·epoch 는 맞음)와 새 점유가 모두
     * 0행이고, 새 소유자가 그 행을 다시 점유해 끝낸다.
     */
    @Test
    void aZombieClaimerCanNeitherClaimNorComplete() throws Exception {
        Lease old = freshLease(P0, "zombie");
        insert(P0, 1);
        OrderCancelHandoffRepository.Claimed claimed = repo.claim(P0, "zombie", old.epoch).orElseThrow();
        UUID pending = insert(P0, 1);
        Lease next = lease(P0, "owner");
        assertEquals(0, repo.complete(claimed.id(), "zombie", old.epoch, "{}"), "리스를 잃은 점유자가 끝냈다");
        assertTrue(repo.claim(P0, "zombie", old.epoch).isEmpty(), "리스를 잃은 점유자가 새 행을 점유했다");
        Set<Long> retaken = new HashSet<>();
        Optional<OrderCancelHandoffRepository.Claimed> c;
        while ((c = repo.claim(P0, "owner", next.epoch)).isPresent()) {
            retaken.add(c.get().id());
            assertEquals(1, repo.complete(c.get().id(), "owner", next.epoch, "{}"));
        }
        assertTrue(retaken.contains(claimed.id()), "새 소유자가 옛 점유를 다시 점유하지 않았다");
        assertEquals(OrderCancelHandoffRepository.State.DONE, repo.byRequest(pending).get(0).state());
    }

    /** 요청자의 포기는 시작한 파티션의 몫을 포기하지 않는다(R5·R7). 두 파티션 요청은 시작하지 않은 쪽만 포기된다(R4b). */
    @Test
    void abandonSkipsPartitionsTheOwnerAlreadyStarted() throws Exception {
        Lease lease = freshLease(P1, "holder-a");
        freshLease(P0, "holder-z");
        UUID request = UUID.randomUUID();
        repo.insert(request, user, OrderCancelHandoff.ORIGIN_MEMBER_CLOSE, List.of(
                new OrderCancelHandoffRepository.NewRow(P0, ids(2)),
                new OrderCancelHandoffRepository.NewRow(P1, ids(2)),
                new OrderCancelHandoffRepository.NewRow(P1, ids(2)),
                new OrderCancelHandoffRepository.NewRow(P1, ids(2))));
        repo.claim(P1, "holder-a", lease.epoch).orElseThrow();
        assertEquals(1, repo.abandon(request), "시작한 p1 몫까지 포기했거나 p0 을 포기하지 않았다");
        List<OrderCancelHandoffRepository.State> states = repo.byRequest(request).stream()
                .map(OrderCancelHandoffRepository.Row::state).toList();
        assertEquals(List.of(OrderCancelHandoffRepository.State.ABANDONED, OrderCancelHandoffRepository.State.CLAIMED,
                OrderCancelHandoffRepository.State.PENDING, OrderCancelHandoffRepository.State.PENDING), states);
        // 아무도 가져가지 않은 요청은 모두 포기되고, 포기한 행은 뒤에 점유되지 않는다.
        Lease p0 = lease(P0, "holder-c");
        UUID untouched = insert(P0, 2);
        assertEquals(1, repo.abandon(untouched));
        assertTrue(claimAll(P0, "holder-c", p0.epoch).stream()
                .noneMatch(id -> id == repo.byRequest(untouched).get(0).id()), "포기한 행을 점유했다");
    }

    /** 포기와 점유는 행 잠금으로 줄을 서 한 행에서 둘 가운데 하나만 된다(P5-4·R4c). */
    @Test
    void abandonWaitsForAnInFlightClaimAndSkipsTheClaimedRow() throws Exception {
        Lease lease = lease(P0, "holder-a");
        UUID request = insert(P0, 1);
        long rowId = repo.byRequest(request).get(0).id();
        try (Connection claimer = db.owner()) {
            claimer.setAutoCommit(false);
            try (PreparedStatement ps = claimer.prepareStatement("""
                    UPDATE order_cancel_requests SET state = 'CLAIMED', claimed_by = 'holder-a', claimed_epoch = ?,
                           claimed_at = now()
                    WHERE id = ? AND state = 'PENDING'
                    """)) {
                ps.setLong(1, lease.epoch);
                ps.setLong(2, rowId);
                assertEquals(1, ps.executeUpdate());
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> abandoned = pool.submit(() -> repo.abandon(request));
                Thread.sleep(300);
                assertFalse(abandoned.isDone(), "포기가 점유 커밋을 기다리지 않았다");
                claimer.commit();
                assertEquals(0, abandoned.get(10, TimeUnit.SECONDS), "점유된 행을 포기했다");
            } finally {
                pool.shutdownNow();
            }
        }
        assertEquals(OrderCancelHandoffRepository.State.CLAIMED, repo.byRequest(request).get(0).state());
    }

    /** 같은 기간의 두 스레드와 여러 스레드는 SKIP LOCKED 로 같은 행을 함께 점유하지 않는다(P5-3·R3). */
    @Test
    void concurrentClaimsNeverOverlap() throws Exception {
        Lease lease = freshLease(P1, "holder-a");
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID request = insert(P1, 1);
            expected.add(repo.byRequest(request).get(0).id());
        }
        List<Long> claimed = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    Optional<OrderCancelHandoffRepository.Claimed> c;
                    while ((c = repo.claim(P1, "holder-a", lease.epoch)).isPresent()) {
                        claimed.add(c.get().id());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(new HashSet<>(claimed).size(), claimed.size(), "같은 행을 두 번 점유했다: " + claimed);
        assertEquals(new HashSet<>(expected), new HashSet<>(claimed));
    }

    /** 넣은 지 5분이 지난 행은 유효한 리스로도 점유하지 않는다(P5-6·R6). */
    @Test
    void rowsOlderThanFiveMinutesAreNotClaimed() throws Exception {
        Lease lease = freshLease(P0, "holder-a");
        UUID old = insert(P0, 1);
        owner.sql("UPDATE order_cancel_requests SET created_at = now() - interval '6 minutes' WHERE request_id = ?")
                .param(old).update();
        assertTrue(repo.claim(P0, "holder-a", lease.epoch).isEmpty(), "6분 된 행을 점유했다");
        // 뒤 시험의 만료 수를 흔들지 않게 요청자가 포기한 것처럼 닫는다.
        assertEquals(1, repo.abandon(old));
    }

    /** 만료는 넣은 지 5분 지난 PENDING 을 ABANDONED 로, 점유한 지 5분 지난 CLAIMED 를 EXPIRED 로 닫는다(R8·R10). */
    @Test
    void expiryClosesStaleOpenRowsOnly() throws Exception {
        Lease lease = freshLease(P1, "holder-a");
        UUID stalePending = insert(P1, 1);
        UUID staleClaimed = insert(P1, 1);
        UUID freshClaimed = insert(P1, 1);
        UUID done = insert(P1, 1);
        UUID fresh = insert(P1, 1);
        for (UUID request : List.of(staleClaimed, freshClaimed, done)) {
            long id = repo.byRequest(request).get(0).id();
            owner.sql("""
                    UPDATE order_cancel_requests SET state = 'CLAIMED', claimed_by = 'holder-a', claimed_epoch = ?,
                           claimed_at = now() WHERE id = ?
                    """).params(lease.epoch, id).update();
        }
        repo.complete(repo.byRequest(done).get(0).id(), "holder-a", lease.epoch, "{}");
        owner.sql("UPDATE order_cancel_requests SET created_at = now() - interval '6 minutes' WHERE request_id IN (?, ?, ?)")
                .params(stalePending, staleClaimed, done).update();
        owner.sql("UPDATE order_cancel_requests SET claimed_at = now() - interval '6 minutes' WHERE request_id = ?")
                .param(staleClaimed).update();

        assertEquals(2, repo.expire(), "닫은 행 수");

        assertEquals(OrderCancelHandoffRepository.State.ABANDONED, repo.byRequest(stalePending).get(0).state());
        assertEquals(OrderCancelHandoffRepository.State.EXPIRED, repo.byRequest(staleClaimed).get(0).state());
        assertEquals(OrderCancelHandoffRepository.State.CLAIMED, repo.byRequest(freshClaimed).get(0).state());
        assertEquals(OrderCancelHandoffRepository.State.DONE, repo.byRequest(done).get(0).state());
        assertEquals(OrderCancelHandoffRepository.State.PENDING, repo.byRequest(fresh).get(0).state());
        // 늦게 끝난 점유자의 끝내기는 state = 'CLAIMED' 조건으로 0행이다.
        assertEquals(0, repo.complete(repo.byRequest(staleClaimed).get(0).id(), "holder-a", lease.epoch, "{}"));
        assertEquals(0, repo.expire(), "닫을 행이 없는데 닫았다");
    }

    /** 앱 로그인은 행을 지우지 못한다(V54 권한). */
    @Test
    void appLoginCannotDelete() throws Exception {
        UUID request = insert(P0, 1);
        try (Connection app = db.app(); PreparedStatement ps = app.prepareStatement(
                "DELETE FROM order_cancel_requests WHERE request_id = ?")) {
            ps.setObject(1, request);
            java.sql.SQLException denied = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                    ps::executeUpdate);
            assertEquals("42501", denied.getSQLState());
        }
    }

    // ------------------------------------------------------------------ 도우미

    private record Lease(String resource, String holder, long epoch) {
    }

    /** 리스를 새로 두고 앞 시험이 남긴 점유할 수 있는 행을 모두 끝낸다. 뒤 점유가 이 시험의 행만 보게 한다. */
    private static Lease freshLease(String resource, String holder) throws Exception {
        Lease lease = lease(resource, holder);
        claimAll(resource, holder, lease.epoch);
        return lease;
    }

    /** 그 자원의 리스를 이 holder 와 새 epoch 로 60초 유효하게 둔다. */
    private static Lease lease(String resource, String holder) throws Exception {
        long epoch = EPOCHS.incrementAndGet();
        db.seedLease(resource, holder, epoch, 60);
        return new Lease(resource, holder, epoch);
    }

    private static UUID insert(String resource, int orders) {
        UUID request = UUID.randomUUID();
        repo.insert(request, user, OrderCancelHandoff.ORIGIN_CANCEL_ALL,
                List.of(new OrderCancelHandoffRepository.NewRow(resource, ids(orders))));
        return request;
    }

    /** 점유할 수 있는 행을 모두 점유해 끝낸다. 앞 시험이 남긴 행이 뒤 시험의 점유 순서를 흔들지 않게 한다. */
    private static List<Long> claimAll(String resource, String holder, long epoch) {
        List<Long> ids = new ArrayList<>();
        Optional<OrderCancelHandoffRepository.Claimed> c;
        while ((c = repo.claim(resource, holder, epoch)).isPresent()) {
            ids.add(c.get().id());
            repo.complete(c.get().id(), holder, epoch, "{}");
        }
        return ids;
    }

    private static List<UUID> ids(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }
}
