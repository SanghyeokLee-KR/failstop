package kr.exchange.engine;

import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.CommandJournalReader;

import java.util.List;

/**
 * seq 갭 회수 어댑터. 저널 조회와 payload 복원을 묶어 단일 라이터 루프에 공급한다
 * (server/README.md M1-2 인계: 갭 감지 시 CommandJournalReader 기반 catch-up).
 */
public class JournalCatchUpSource implements SingleWriterLoop.CatchUpSource {

    private final CommandJournalReader reader;
    private final CommandPayloadMapper mapper;

    public JournalCatchUpSource(CommandJournalReader reader, CommandPayloadMapper mapper) {
        this.reader = reader;
        this.mapper = mapper;
    }

    @Override
    public List<OrderCommand> readAfter(long lastAppliedSeq, int limit) {
        return reader.readAfter(lastAppliedSeq, limit).stream()
                .map(mapper::toCommand)
                .toList();
    }
}
