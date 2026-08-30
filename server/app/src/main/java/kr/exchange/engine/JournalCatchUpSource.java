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

    public JournalCatchUpSource(CommandJournalReader reader, CommandPayloadMapper mapper) {
        this.reader = reader;
        this.mapper = mapper;
    }

    @Override
    public List<OrderCommand> readAfter(long lastAppliedSeq, int limit) {
        // 라이브 루프 공급 전 체크섬 검증. 재생 경로와 단일 지점을 공유한다. 검증 없이
        // 적용하면 변조 행이 event_journal에 비가역 방출된다.
        return reader.readAfter(lastAppliedSeq, limit).stream()
                .map(entry -> {
                    JournalChecksum.verify(entry);
                    return mapper.toCommand(entry);
                })
                .toList();
    }
}
