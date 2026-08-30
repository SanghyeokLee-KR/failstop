package kr.exchange.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 복구 재생 결과를 recovery_runs에 적재한다(검증설계 C17 REPLAY_DETERMINISM 원천).
 *
 * C17은 "복구 재생이 결정론적인가"를 recovery_runs의 matched=false 행 유무로 판정한다.
 * 그런데 그 표에 쓰는 코드가 저장소에 없었다. 읽는 쪽(HaMonitoringController)과 판정
 * SQL만 있고 기록 주체가 없어 표가 늘 0행이었고, 판정이 빈 테이블 대조로 공허 통과했다.
 * 실측(2026-08-30): recovery_runs 0행에 C17 위반 0.
 *
 * 결정론 검증 자체는 이미 ReplayService.rebuild가 한다. 재생이 만든 이벤트를
 * event_journal 행과 하나씩 대조하고 어긋나면 ReplayMismatchException을 던진다.
 * 즉 재생이 예외 없이 끝났다는 사실이 곧 결정론 성립이다. 이 기록자는 그 사실과
 * 창(재생 구간)을 표로 남길 뿐 새 검증을 하지 않는다.
 *
 * 지문 컬럼(fp_replay·fp_live)의 한계를 명시한다. 성공 경로에서는 두 값을 같게 적으므로
 * 그 둘의 비교로는 matched가 항상 true다. 불일치 경로만 fp_live에 예외를 담아 갈린다. 즉 fp 비교는 판정이 아니다. 판정은
 * ReplayService가 이벤트 단위로 이미 끝냈고, matched는 그 결과(예외 없이 끝났는가)를
 * 옮긴 값이다. 별도 해시를 새로 계산해 두 벌 대조를 만들지 않는 이유는, 둘이 어긋날 때
 * 어느 쪽이 정본인지 규정이 없어지기 때문이다.
 *
 * 그래서 C17의 실질 판정력은 recordMismatch가 도달하는가에 달려 있다. 재생이 예외로
 * 끝나면 그 경로로 matched=false가 남는다. fp 값을 비교해 무언가를 검출한다고 읽으면
 * 안 된다. 지문은 재생 창의 식별자이지 대조 대상이 아니다.
 *
 * 재생 0건(tail이 비어 새로 적용한 명령이 없음)도 matched=true로 남는다. 그것은
 * "재생할 것이 없었다"이지 "결정론이 증명됐다"가 아니다. verified_events를 fp에 실어
 * 사후에 그 구분이 되게 한다. 0건 주행만으로 C17 통과를 주장하지 않는다.
 *
 * 실패를 기록하는 것이 더 중요하다. 재생이 예외로 끝나면 그 사실이 matched=false로
 * 남아야 C17이 검출한다. 예외를 삼키지 않고 기록만 남긴 뒤 다시 던진다.
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
