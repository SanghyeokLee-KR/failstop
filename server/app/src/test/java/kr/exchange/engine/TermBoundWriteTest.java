package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.ReplayService;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 기간 결속 쓰기(S4 설계 4-5·6-2, DB 설계 §5.4-c 6). 쓰기 경로는 기간을 쥐고 쓰고, 재시도의 시도마다 그 기간이 여전히
 * 현재인지 본다. 강등 전 스레드의 늦은 쓰기는 같은 노드가 재승격해 얻은 새 epoch 를 빌리지 못한다.
 *
 * 재시도 대기에 넣는 방법. 정산의 트랜잭션 템플릿이 첫 호출에서 트랜잭션을 열기 전에 재승격을 끝내고 일시 오류(55P03)를
 * 던진다. 정산은 대기 뒤 다시 시도한다. 트랜잭션을 열기 전이라 잠금을 쥐고 있지 않아 재승격의 획득이 막히지 않는다.
 *
 * 대조군은 기간을 쥐지 않은 종전 경로(requireLeader)다. 같은 상황에서 옛 스레드의 재시도가 새 epoch 로 쓴다.
 */
class TermBoundWriteTest {

    private static final String RESOURCE = "matching-engine";

    /** 라이브 사례. 강등 전 루프의 정산이 재시도 대기에 있는 사이 같은 파티션을 재승격한다. */
    @Test
    void lateSettlementRetryOfADemotedTermWritesNothing() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_term_live", 2)) {
            long bob = f.user("bob", BigDecimal.ZERO);
            f.deposit(bob, BTC, new BigDecimal("10"));
            LeaderLeaseService lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, true);
            InterceptingTx tx = new InterceptingTx(f);
            SettlementService settlement = new SettlementService(f.app, tx, lease, new ObjectMapper(),
                    new MarketCatalog(f.app), f.mapper);
            MatchingEngineImpl engine = new MatchingEngineImpl();

            // 기간 결속 경로.
            LeaderTerm demoted = lease.currentTerm().orElseThrow();
            List<EngineEvent> events = f.journalAndApply(appender, engine,
                    f.limit(KRW_BTC, bob, OrderCommand.Side.SELL, "50000", "0.1"));
            long cmdSeq = events.get(0).sourceCmdSeq();
            AtomicReference<LeaderTerm> next = new AtomicReference<>();
            tx.beforeFirst(() -> {
                assertTrue(lease.demote(demoted, DemotionReason.WRITER_FATAL, "시험: 라이터 치명"));
                assertTrue(lease.rearm());
                expire(f);
                assertTrue(lease.tryAcquire(), "같은 노드의 재승격 실패");
                next.set(lease.currentTerm().orElseThrow());
            });
            assertThrows(NotLeaderException.class, () -> settlement.appendAndSettle(demoted, events),
                    "끝난 기간의 재시도가 거부되지 않았다");
            assertTrue(next.get().epoch() > demoted.epoch());
            assertEquals(1, settlement.transientRetries(), "재시도 대기에 들어가지 않았다");
            assertEquals(0, eventRows(f, cmdSeq), "끝난 기간의 늦은 정산이 행을 썼다");
            // 새 기간은 정상으로 쓴다.
            settlement.appendAndSettle(next.get(), events);
            assertEquals(next.get().epoch(), eventEpoch(f, cmdSeq), "새 기간의 정산이 그 epoch 로 쓰지 않았다");

            // 대조군: 기간 없는 종전 경로는 옛 스레드의 재시도가 새 epoch 로 통과한다.
            List<EngineEvent> legacy = f.journalAndApply(appender, engine,
                    f.limit(KRW_BTC, bob, OrderCommand.Side.SELL, "50001", "0.1"));
            long legacySeq = legacy.get(0).sourceCmdSeq();
            AtomicReference<LeaderTerm> legacyNext = new AtomicReference<>();
            tx.beforeFirst(() -> {
                lease.demote("시험: 라이터 치명(종전 경로)");
                assertTrue(lease.rearm());
                expire(f);
                assertTrue(lease.tryAcquire(), "같은 노드의 재승격 실패");
                legacyNext.set(lease.currentTerm().orElseThrow());
            });
            settlement.appendAndSettle(legacy);
            assertEquals(legacyNext.get().epoch(), eventEpoch(f, legacySeq),
                    "대조군: 종전 경로의 재시도가 새 epoch 로 쓰지 않았다(가정이 틀렸다)");
        }
    }

    /**
     * 재생 사례. 승격 재생 도중 갱신 거부로 회복 가능 반납을 일으킨 뒤 같은 노드가 다시 얻는다. 앞 재생의 늦은 재적재는
     * 한 행도 쓰지 못하고 새 재생은 정상으로 끝난다.
     */
    @Test
    void lateReinjectionOfAStoodDownReplayWritesNothing() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_term_replay", 2)) {
            long bob = f.user("bob", BigDecimal.ZERO);
            f.deposit(bob, BTC, new BigDecimal("10"));
            LeaderLeaseService lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, true);
            InterceptingTx tx = new InterceptingTx(f);
            SettlementService settlement = new SettlementService(f.app, tx, lease, new ObjectMapper(),
                    new MarketCatalog(f.app), f.mapper);
            ReplayService replay = new ReplayService(new CommandJournalReader(f.app), new EventJournalReader(f.app),
                    f.mapper, new ObjectMapper(), new MarketCatalog(f.app));

            // 크래시 창: 명령만 커밋하고 정산하지 않는다.
            long crashSeq = f.append(appender, f.limit(KRW_BTC, bob, OrderCommand.Side.SELL, "50000", "0.1")).seq();
            EngineRecovery recovery = new EngineRecovery(replay, settlement::appendAndSettle, null, n -> { });

            LeaderTerm first = lease.currentTerm().orElseThrow();
            long token = lease.beginPromotionReplay();
            AtomicReference<LeaderTerm> next = new AtomicReference<>();
            tx.beforeFirst(() -> {
                // 다른 노드가 리스를 가져간 것처럼 행을 바꿔 갱신을 거부시킨다. 재생 구간이라 회복 가능 반납이다.
                f.owner.sql("UPDATE leader_lease SET holder = 'intruder' WHERE resource = ?").param(RESOURCE).update();
                assertFalse(lease.renew(), "갱신이 거부되지 않았다");
                assertFalse(lease.isLeader());
                assertTrue(lease.isArmed(), "회복 가능 반납이 후보 표식을 내렸다");
                expire(f);
                assertTrue(lease.tryAcquire(), "같은 노드의 재획득 실패");
                next.set(lease.currentTerm().orElseThrow());
            });
            IllegalStateException failed = assertThrows(IllegalStateException.class,
                    () -> recovery.recover(new MatchingEngineImpl(), marketId -> true, first));
            assertTrue(failed.getCause() instanceof NotLeaderException, "재적재가 기간 종료로 끝나지 않았다: " + failed);
            lease.endPromotionReplay(token);
            assertEquals(0, eventRows(f, crashSeq), "반납한 재생의 늦은 재적재가 행을 썼다");

            // 새 기간의 재생은 정상으로 끝나고 그 epoch 로 쓴다.
            recovery.recover(new MatchingEngineImpl(), marketId -> true, next.get());
            assertEquals(next.get().epoch(), eventEpoch(f, crashSeq));

            // 대조군: 기간 없는 종전 재생은 옛 시도의 재시도가 새 epoch 로 쓴다.
            long legacySeq = f.append(appender, f.limit(KRW_BTC, bob, OrderCommand.Side.SELL, "50001", "0.1")).seq();
            EngineRecovery legacyRecovery = new EngineRecovery(replay, settlement::appendAndSettle, lease);
            long legacyToken = lease.beginPromotionReplay();
            AtomicReference<LeaderTerm> legacyNext = new AtomicReference<>();
            tx.beforeFirst(() -> {
                f.owner.sql("UPDATE leader_lease SET holder = 'intruder' WHERE resource = ?").param(RESOURCE).update();
                assertFalse(lease.renew());
                expire(f);
                assertTrue(lease.tryAcquire());
                legacyNext.set(lease.currentTerm().orElseThrow());
            });
            legacyRecovery.recover(new MatchingEngineImpl());
            lease.endPromotionReplay(legacyToken);
            assertEquals(legacyNext.get().epoch(), eventEpoch(f, legacySeq),
                    "대조군: 종전 재생의 재시도가 새 epoch 로 쓰지 않았다(가정이 틀렸다)");
        }
    }

    private static void expire(ReservationFixture f) {
        f.owner.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' WHERE resource = ?")
                .param(RESOURCE).update();
    }

    private static long eventRows(ReservationFixture f, long cmdSeq) {
        return f.count("SELECT count(*) FROM event_journal WHERE source_cmd_seq = " + cmdSeq);
    }

    private static long eventEpoch(ReservationFixture f, long cmdSeq) {
        return f.count("SELECT max(fencing_epoch) FROM event_journal WHERE source_cmd_seq = " + cmdSeq);
    }

    /**
     * 첫 호출에서 트랜잭션을 열기 전에 동작을 하나 돌리고 일시 오류를 던지는 템플릿. 정산의 재시도 루프가 그 오류를 받아
     * 대기한 뒤 다시 부른다. 두 번째부터는 그대로 트랜잭션을 연다.
     */
    private static final class InterceptingTx extends TransactionTemplate {
        private final AtomicReference<Runnable> pending = new AtomicReference<>();

        InterceptingTx(ReservationFixture f) {
            super(new JdbcTransactionManager(f.appPool));
        }

        void beforeFirst(Runnable action) {
            pending.set(action);
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            Runnable before = pending.getAndSet(null);
            if (before != null) {
                before.run();
                throw new CannotAcquireLockException("주입: 재시도 대기", new PSQLException(
                        new ServerErrorMessage("SERROR\u0000C55P03\u0000Mlock timeout\u0000")));
            }
            return super.execute(action);
        }
    }
}
