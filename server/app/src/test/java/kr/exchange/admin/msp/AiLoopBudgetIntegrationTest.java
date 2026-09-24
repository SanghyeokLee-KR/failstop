package kr.exchange.admin.msp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 판단 주기의 자원 예산(설계 4-6·7-1). 근거가 같고 경보가 없으면 모델을 부르지 않고, 평온하면 근거도 모으지 않으며,
 * 여러 관리 JVM 가운데 임대를 가진 한 곳에서만 돈다. 실제 모델은 부르지 않는다.
 */
@Testcontainers
class AiLoopBudgetIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final String NONE = AiRig.out("조회 CPU가 임계를 넘었지만 지켜봅니다.", "지금은 조치가 필요 없습니다.",
            "NO_ACTION", "", "", "0.9");

    private AiRig.Sources src;
    private AiRig rig;

    @BeforeAll
    static void migrate() {
        AiRig.migrate(POSTGRES);
    }

    @BeforeEach
    void start() throws IOException {
        src = new AiRig.Sources();
        rig = new AiRig(POSTGRES, src, MspProperties.Llm.Provider.OPENAI_COMPATIBLE, p -> { });
    }

    @AfterEach
    void stop() {
        rig.close();
        src.close();
    }

    @Test
    void 근거가_직전과_같고_경보가_없으면_모델을_부르지_않고_지표로만_센다() {
        src.cpu = 0.82;
        src.llmContent = NONE;

        rig.ticks(1, MINUTE);
        assertEquals(1, src.chatCalls());
        rig.ticks(1, MINUTE);

        assertEquals(1, src.chatCalls(), "같은 근거는 다시 묻지 않는다");
        assertEquals(1.0, rig.skips("unchanged"));
        assertEquals(1, rig.count("SELECT count(*) FROM ops_ai_decisions"), "생략한 판단은 기록하지 않는다");

        rig.ticks(1, MINUTE);
        assertEquals(1.0, rig.skips("calm_wait"), "생략 뒤에는 평온 주기 동안 근거도 모으지 않는다");

        src.cpu = 0.9;
        rig.advance(Duration.ofMinutes(4));
        rig.loop.tick();
        assertEquals(2, src.chatCalls(), "평온 주기가 지난 뒤 근거가 바뀌었으면 다시 묻는다");
        assertEquals(2, rig.count("SELECT count(*) FROM ops_ai_decisions"));
    }

    @Test
    void 표본의_작은_흔들림은_지문을_바꾸지_않고_실제_움직임은_바꾼다() {
        src.cpu = 0.821;
        String a = AiLoop.fingerprint(rig.collector.collect(), rig.mapper);
        src.cpu = 0.824;
        String b = AiLoop.fingerprint(rig.collector.collect(), rig.mapper);
        rig.advance(Duration.ofSeconds(40));
        String c = AiLoop.fingerprint(rig.collector.collect(), rig.mapper);
        src.cpu = 0.9;
        String d = AiLoop.fingerprint(rig.collector.collect(), rig.mapper);

        assertEquals(a, b, "유효 숫자 두 자리 안의 흔들림");
        assertEquals(b, c, "수집 시각과 표본 나이는 지문에 들지 않는다");
        assertFalse(c.equals(d));
    }

    @Test
    void 경보가_있으면_근거가_같아도_생략하지_않는다() {
        src.cpu = 0.82;
        src.llmContent = NONE;
        src.alerts = List.of(AiRig.alert("a000000000000001", "BotOrderLatencyHigh", "warning", "bot"));
        rig.poller.pollOnce();

        rig.ticks(2, MINUTE);

        assertEquals(2, src.chatCalls(), "경보가 있으면 재판단 간격마다 다시 묻는다");
        assertEquals(0.0, rig.skips("unchanged"));
    }

    @Test
    void 평온하면_평온_주기_동안_원천을_읽지_않고_경보가_나타나면_곧바로_판단한다() {
        src.cpu = 0.45;
        src.llmContent = NONE;

        rig.ticks(1, MINUTE);
        assertEquals(1.0, rig.skips("calm"));
        int nodeCalls = src.node.requests("/actuator/prometheus").size();
        int promCalls = src.prometheus.requests("/api/v1/query").size();

        rig.ticks(3, MINUTE);
        assertEquals(3.0, rig.skips("calm_wait"));
        assertEquals(nodeCalls, src.node.requests("/actuator/prometheus").size(), "평온 주기에는 노드를 부르지 않는다");
        assertEquals(promCalls, src.prometheus.requests("/api/v1/query").size());
        assertEquals(0, src.chatCalls());

        src.alerts = List.of(AiRig.alert("a000000000000002", "BotOrderLatencyHigh", "warning", "bot"));
        rig.ticks(1, MINUTE);
        assertEquals(1, src.chatCalls(), "폴러가 본 경보가 있으면 평온 주기를 기다리지 않는다");
    }

    @Test
    void 판단_주기는_임대를_가진_한_곳에서만_돌고_보유자가_사라지면_넘어간다() {
        src.cpu = 0.82;
        src.llmContent = NONE;
        RunnerLease otherLease = new RunnerLease(rig.jdbc);
        AiLoop other = new AiLoop(rig.collector, rig.llm, rig.store, rig.control, rig.executor, rig.incidents,
                rig.audit, rig.tx, rig.mapper, rig.props, rig.registry, rig.clock, otherLease);

        rig.advance(MINUTE);
        rig.loop.tick();
        other.tick();
        other.observeTick();

        assertEquals(1, src.chatCalls(), "두 관리 JVM 이 같은 근거로 모델을 두 번 부르지 않는다");
        assertEquals(2.0, rig.skips("not_runner"));
        assertEquals(rig.lease.holder(), rig.text("SELECT holder FROM ops_runner_lease"));

        rig.jdbc.sql("UPDATE ops_runner_lease SET expires_at = now() - interval '1 second'").update();
        src.cpu = 0.9;
        rig.advance(Duration.ofMinutes(6));
        other.tick();
        assertEquals(2, src.chatCalls(), "만료된 임대는 다른 JVM 이 가져간다");
        assertEquals(otherLease.holder(), rig.text("SELECT holder FROM ops_runner_lease"));
        assertFalse(rig.lease.claim(), "넘겨준 쪽은 다시 가져가지 못한다");

        otherLease.release();
        assertTrue(rig.lease.claim(), "놓은 임대는 곧바로 가져갈 수 있다");
    }
}
