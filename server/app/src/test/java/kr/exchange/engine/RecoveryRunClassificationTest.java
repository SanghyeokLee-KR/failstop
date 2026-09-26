package kr.exchange.engine;

import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.harness.RecoveryRunRecorder;
import kr.exchange.journal.DbFailures;
import kr.exchange.journal.JournalCheckpoints;
import kr.exchange.journal.ReplayMismatchException;
import kr.exchange.journal.ReplayService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 재생 결과의 recovery_runs 기록 분류(S4b 설계 4-2, DB 설계 §5.5 v2.56). DB 없이 재생 서비스가 던질 예외를 고르고 기록자를
 * 가로챈다. 판정 없이 끝난 재생(DB 접근 실패, 일시 오류, 리더 자격 상실)은 불일치로 적지 않고 예외를 그대로 올린다. 진짜
 * 불일치와 분류하지 못한 예외는 matched=false 한 번이다.
 */
class RecoveryRunClassificationTest {

    @Test
    void replayEndedWithoutAVerdictIsNotRecorded() {
        Map<String, RuntimeException> noVerdict = new LinkedHashMap<>();
        noVerdict.put("획득 실패", DbFailures.acquireFailure());
        noVerdict.put("08006", DbFailures.connectionFailure());
        noVerdict.put("57P01", DbFailures.sql("57P01"));
        noVerdict.put("롤백 실패", DbFailures.rollbackFailed(DbFailures.connectionFailure()));
        noVerdict.put("53200", DbFailures.sql("53200"));
        noVerdict.put("57014", DbFailures.sql("57014"));
        noVerdict.put("리더 자격 상실", new NotLeaderException("크래시 창 마켓 [1] 재적재 불가: 리더 자격 없음"));
        noVerdict.forEach((name, failure) -> {
            Probe probe = new Probe(failure);
            RuntimeException thrown = assertThrows(RuntimeException.class, probe::recover, name);
            assertSame(failure, thrown, name + " 이 다른 예외로 바뀌었다");
            assertEquals(List.of(), probe.recorded, name + " 을 불일치로 적었다");
        });
    }

    @Test
    void mismatchAndUnclassifiedFailuresAreRecordedOnce() {
        Map<String, RuntimeException> verdict = new LinkedHashMap<>();
        verdict.put("재생 불일치", new ReplayMismatchException("주입: 이벤트 불일치"));
        verdict.put("엔진 예외", new IllegalStateException("주입: 엔진 계약 위반"));
        // 사슬에 불일치가 있으면 DB 불통 모양이어도 판정이 난 것이다.
        verdict.put("불일치를 원인에 둔 자원 실패", new org.springframework.dao.DataAccessResourceFailureException("주입",
                new ReplayMismatchException("불일치")));
        verdict.forEach((name, failure) -> {
            Probe probe = new Probe(failure);
            assertSame(failure, assertThrows(RuntimeException.class, probe::recover, name), name);
            assertEquals(List.of(false), probe.recorded, name + " 이 불일치 한 행으로 남지 않았다");
            assertTrue(probe.details.get(0).contains("주입"), name + " 의 원인이 fp_live 에 없다");
        });
    }

    /** 가짜 재생 서비스와 기록자. 팔로워 복구(재구성·기동)의 첫 저널 조회가 failure 를 던진다. */
    private static final class Probe {
        final List<Boolean> recorded = new ArrayList<>();
        final List<String> details = new ArrayList<>();
        final EngineRecovery recovery;

        Probe(RuntimeException failure) {
            ReplayService replay = new ReplayService(null, null, null, null, null) {
                @Override
                public JournalCheckpoints materializedThroughByMarket(MatchingEngineImpl engine) {
                    throw failure;
                }
            };
            recovery = new EngineRecovery(replay, (term, events) -> {
                throw new IllegalStateException("재적재할 크래시 창이 없다");
            }, null, n -> { });
            recovery.setRecoveryRecorder(new RecoveryRunRecorder(null) {
                @Override
                public void recordSuccess(long fromSeq, long toSeq, long verifiedEvents, long durationMs) {
                    recorded.add(true);
                }

                @Override
                public void recordMismatch(long fromSeq, long toSeq, String detail, long durationMs) {
                    recorded.add(false);
                    details.add(detail);
                }
            });
        }

        void recover() {
            recovery.recoverFollower(new MatchingEngineImpl(), marketId -> true);
        }
    }
}
