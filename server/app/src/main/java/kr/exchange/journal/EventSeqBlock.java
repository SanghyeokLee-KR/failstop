package kr.exchange.journal;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 명령 하나의 방출 배치에 배정한 번호의 시작. 마켓 안 번호와 파생 event_seq 가 같은 폭으로 이어져 배치의 i 번째 이벤트는 둘에 i 를 더한다.
 * 파생은 DB 가 한다(V46 next_market_event_seq). 대역 폭을 앱에 두면 명세를 넓힌 뒤 이전 앱이 event_seq 를 되돌린다(ADR 0022 9단계).
 */
public record EventSeqBlock(long marketSeq, long eventSeq) {

    /** 호출자의 트랜잭션에서 채번한다. 카운터 행 잠금이 커밋까지 유지된다. */
    public static EventSeqBlock allocate(JdbcClient jdbc, int marketId, int count) {
        return jdbc.sql("SELECT out_market_seq, out_event_seq FROM next_market_event_seq(?::smallint, ?)")
                .params(marketId, count)
                .query((rs, rowNum) -> new EventSeqBlock(rs.getLong("out_market_seq"), rs.getLong("out_event_seq")))
                .single();
    }
}
