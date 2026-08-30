package kr.exchange.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

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

    private final JdbcClient jdbc;

    public RecoveryRunRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
        insert(fromSeq, toSeq, fp, fp, true, durationMs);
    }

    /** 재생 불일치를 기록한다. 이 행이 C17이 검출하는 대상이다. */
    public void recordMismatch(long fromSeq, long toSeq, String detail, long durationMs) {
        insert(fromSeq, toSeq, "MISMATCH", truncate(detail), false, durationMs);
    }

    private void insert(long fromSeq, long toSeq, String fpReplay, String fpLive,
                        boolean matched, long durationMs) {
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
