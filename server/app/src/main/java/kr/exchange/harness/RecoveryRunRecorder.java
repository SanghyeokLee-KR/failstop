package kr.exchange.harness;

import kr.exchange.engine.WriteAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 복구 재생 결과를 recovery_runs에 적재한다(검증설계 C17 REPLAY_DETERMINISM 원천).
 *
 * C17은 recovery_runs의 matched=false 행 유무로 판정한다. 기록 주체가 없으면 표가 0행이라
 * 판정이 빈 테이블 대조로 공허 통과한다. 이 기록자가 그 양성 증거 축이다.
 *
 * 결정론 검증 자체는 ReplayService.rebuild가 이벤트 단위로 끝낸다. matched는 그 결과를 옮긴
 * 값이고, 지문(fp_replay·fp_live)은 재생 창 식별자이지 대조 대상이 아니다. 재생 0건도
 * matched=true라 verified_events로 그 구분을 남긴다. 실패는 삼키지 않고 기록 후 다시 던진다.
 */
public class RecoveryRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(RecoveryRunRecorder.class);
    // 거래 일시 중단 동안 미룬 행의 상한. 기동·재구성 재생마다 한 행이라 중단 한 번에 몇 행을 넘지 않는다.
    static final int DEFERRED_MAX = 256;

    private final JdbcClient jdbc;
    private volatile WriteAvailability writes = WriteAvailability.disabled();
    private final Deque<Row> deferred = new ArrayDeque<>();
    private long droppedSuccesses;

    record Row(long fromSeq, long toSeq, String fpReplay, String fpLive, boolean matched, long durationMs) {
    }

    public RecoveryRunRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 거래 일시 중단 동안에는 성공 행을 적지 않고 메모리에 두었다가 재개 뒤 적는다(Patroni 설계 12절 '기동 중 쓰기'). 중단 동안
     * 재기동한 노드의 기동 재생이 이 INSERT 의 커밋 대기에 걸려 팔로워 기동과 조회 제공을 멈추지 않게 한다. 불일치 행은 미루지
     * 않는다. C17 이 잡는 대상이고, 불일치는 기동 실패로 이어져 미룬 행이 프로세스와 함께 사라지기 때문이다. 중단 동안의 불일치
     * 기록은 재개까지 커밋을 기다린다.
     */
    public void setWriteAvailability(WriteAvailability writes) {
        this.writes = writes;
        // 탐지 스레드를 막지 않게 다른 스레드에서 적는다.
        writes.onResume(() -> CompletableFuture.runAsync(this::flushDeferred));
    }

    /**
     * 재생 성공을 기록한다. market_id는 재생이 마켓 전역이라 0으로 둔다(단일 라이터가
     * 전 마켓을 한 저널로 재생하므로 마켓별 분해가 원천에 없다).
     *
     * 기록 실패가 기동을 막지 않는다. 이 표는 검증 증빙이지 거래 경로가 아니다.
     * 다만 조용히 넘기면 C17이 다시 공허 통과하므로 WARN으로 남긴다.
     */
    public void recordSuccess(long fromSeq, long toSeq, long verifiedEvents, long durationMs) {
        String fp = fingerprint(fromSeq, toSeq, verifiedEvents);
        Row row = new Row(fromSeq, toSeq, fp, fp, true, durationMs);
        if (!writes.isActive()) {
            defer(row);
            return;
        }
        write(row);
    }

    /** 재생 불일치를 기록한다. 이 행이 C17이 검출하는 대상이다. 거래 일시 중단이어도 미루지 않는다. */
    public void recordMismatch(long fromSeq, long toSeq, String detail, long durationMs) {
        write(new Row(fromSeq, toSeq, "MISMATCH", truncate(detail), false, durationMs));
    }

    private synchronized void defer(Row row) {
        if (deferred.size() >= DEFERRED_MAX) {
            droppedSuccesses++;
            return;
        }
        deferred.addLast(row);
        log.info("쓰기가 ACTIVE 가 아니라 recovery_runs 성공 기록을 재개 뒤로 미룬다: 미룬 행 {}", deferred.size());
    }

    /** 미룬 행을 적는다. 재개 콜백이 다른 스레드에서 부른다. 적는 사이 다시 중단되면 남은 행은 다음 재개에 적는다. */
    void flushDeferred() {
        List<Row> rows;
        synchronized (this) {
            if (deferred.isEmpty()) {
                return;
            }
            rows = new ArrayList<>(deferred);
            deferred.clear();
            if (droppedSuccesses > 0) {
                log.warn("recovery_runs 미룬 성공 행 {}건을 상한 때문에 버렸다", droppedSuccesses);
                droppedSuccesses = 0;
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            if (!writes.isActive()) {
                synchronized (this) {
                    for (int j = rows.size() - 1; j >= i; j--) {
                        deferred.addFirst(rows.get(j));
                    }
                }
                return;
            }
            write(rows.get(i));
        }
    }

    synchronized int deferredCount() {
        return deferred.size();
    }

    /** 한 행을 적는다. 시험이 가로챈다. */
    void write(Row row) {
        long fromSeq = row.fromSeq();
        long toSeq = row.toSeq();
        String fpReplay = row.fpReplay();
        String fpLive = row.fpLive();
        boolean matched = row.matched();
        long durationMs = row.durationMs();
        try {
            jdbc.sql("""
                            INSERT INTO recovery_runs
                                (market_id, snapshot_id, replayed_from_seq, replayed_to_seq,
                                 fp_replay, fp_live, matched, duration_ms)
                            VALUES (0, NULL, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(fromSeq, toSeq, fpReplay, fpLive, matched, (int) durationMs)
                    .update();
        } catch (RuntimeException e) {
            log.warn("recovery_runs 적재 실패. C17이 이 재생을 보지 못한다: {}", e.toString());
        }
    }

    /**
     * 재생 창의 요약값. 같은 창을 같은 저널로 재생하면 같은 값이 나온다. 이벤트 단위
     * 대조는 ReplayService가 이미 끝냈으므로 여기서 페이로드를 다시 해싱하지 않는다.
     */
    private static String fingerprint(long fromSeq, long toSeq, long verifiedEvents) {
        String v = "seq:" + fromSeq + "-" + toSeq + ":ev:" + verifiedEvents;
        // VARCHAR(64) 상한. seq가 커져도 넘지 않지만 계약을 코드가 지킨다.
        return v.length() <= 64 ? v : v.substring(0, 64);
    }

    /** fp_live는 VARCHAR(64)라 긴 예외 메시지가 들어가면 적재가 실패한다. */
    private static String truncate(String s) {
        String v = s == null ? "" : s;
        return v.length() <= 64 ? v : v.substring(0, 64);
    }
}
