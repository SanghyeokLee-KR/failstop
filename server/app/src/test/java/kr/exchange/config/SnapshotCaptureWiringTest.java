package kr.exchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.WriterRouter;
import kr.exchange.snapshot.SnapshotPersister;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 운영 샤드 팩토리(EngineConfig.writerRouter)가 스냅샷을 켜면 샤드마다 SnapshotCapture 를 하나씩 건다(ADR 0022 7단계).
 * 캡처는 생성자에서 market 태그 미터를 등록하므로 마켓마다 태그가 하나씩 등록됐는지로 본다. 스냅샷 통합 시험은 샤드
 * 팩토리를 손으로 조립해 이 배선 줄이 빠져도 통과하므로 그 회귀를 여기서 잡는다.
 */
class SnapshotCaptureWiringTest {

    private static final List<String> NAMES = List.of("exchange.snapshot.captured", "exchange.snapshot.dropped",
            "exchange.snapshot.persist_failed", "exchange.snapshot.last_persisted_seq");

    @Test
    void enabledSnapshotsAttachOneCapturePerShard() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (SnapshotPersister persister = new SnapshotPersister(null)) {
            WriterRouter router = router(true, persister, registry);
            for (String name : NAMES) {
                assertTrue(registry.find(name).meters().isEmpty(), name + " 가 샤드 생성 전에 등록됐다");
            }

            router.shard(12);
            router.shard(1);
            router.shard(12);

            for (String name : NAMES) {
                List<String> markets = new ArrayList<>();
                for (Meter meter : registry.find(name).meters()) {
                    markets.add(meter.getId().getTag("market"));
                }
                assertEquals(List.of("1", "12"), markets.stream().sorted().toList(),
                        name + " 가 샤드마다 하나씩 market 태그와 함께 등록되지 않았다");
            }
        }
    }

    @Test
    void disabledSnapshotsAttachNoCapture() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (SnapshotPersister persister = new SnapshotPersister(null)) {
            router(false, persister, registry).shard(1);

            assertEquals(1, registry.find("exchange.writer.hook.nanos").meters().size(), "샤드가 만들어지지 않았다");
            for (String name : NAMES) {
                assertTrue(registry.find(name).meters().isEmpty(), name + " 가 스냅샷 비활성에서 등록됐다");
            }
        }
    }

    /** 라이터를 띄우지 않으므로 정산, 저널, 리스, 시세 협력자는 쓰이지 않는다. */
    private static WriterRouter router(boolean snapshots, SnapshotPersister persister, SimpleMeterRegistry registry) {
        return new EngineConfig().writerRouter(new EngineProperties(null, null, null, 16, null, null, null), null, null,
                new CommandPayloadMapper(new ObjectMapper()), null, null, null,
                new SnapshotProperties(snapshots, 0, null, 0, null, null), persister, registry);
    }
}
