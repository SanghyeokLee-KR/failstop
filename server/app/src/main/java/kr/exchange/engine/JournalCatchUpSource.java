package kr.exchange.engine;

import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.JournalChecksum;

import java.util.List;

/**
 * seq 갭 회수 어댑터. 저널 조회와 payload 복원을 묶어 단일 라이터 루프에 공급한다
 * (server/README.md M1-2 인계: 갭 감지 시 CommandJournalReader 기반 catch-up).
 */
public class JournalCatchUpSource implements SingleWriterLoop.CatchUpSource {

    private final CommandJournalReader reader;
    private final CommandPayloadMapper mapper;
    /** 음수면 전 마켓. 0 이상이면 그 마켓 명령만 공급한다(ADR 0021 5단계). */
    private final int marketId;

    public JournalCatchUpSource(CommandJournalReader reader, CommandPayloadMapper mapper) {
        this(reader, mapper, -1);
    }

    /**
     * 마켓별 라이터용. 그 마켓의 명령만 공급한다.
     *
     * 이것이 없으면 마켓별 루프의 갭 회수가 남의 마켓 명령을 자기 엔진에 먹인다.
     * 전역 seq 채번이라 한 마켓의 스트림에는 구멍이 뚫려 있고, 루프는 그 구멍을 갭으로
     * 보고 회수를 부른다. 회수가 전 마켓을 읽으면 그 자리에서 상태가 오염된다.
     */
    public JournalCatchUpSource(CommandJournalReader reader, CommandPayloadMapper mapper,
                                int marketId) {
        this.reader = reader;
        this.mapper = mapper;
        this.marketId = marketId;
    }

    @Override
    public List<OrderCommand> readAfter(long lastAppliedSeq, int limit) {
        // 라이브 루프 공급 전 체크섬 검증. 재생 경로와 단일 지점을 공유한다. 검증 없이
        // 적용하면 변조 행이 event_journal에 비가역 방출된다.
        List<kr.exchange.journal.JournalEntry> rows = marketId < 0
                ? reader.readAfter(lastAppliedSeq, limit)
                : reader.readAfterForMarket(lastAppliedSeq, marketId, -1, limit);
        return rows.stream()
                .map(entry -> {
                    JournalChecksum.verify(entry);
                    return mapper.toCommand(entry);
                })
                .toList();
    }
}
