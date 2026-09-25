package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static kr.exchange.admin.msp.AiFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 근거의 조치 카탈로그(설계 7-5). 방향 전제가 서지 않는 조치와 대상은 모델에게 보이지 않는다. 카탈로그는 안내라서
 * 정책 엔진의 거부 규칙을 대신하지 않고, 여기서는 둘이 같은 전제를 쓰는지만 본다.
 */
class AiEvidenceCollectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration SUSTAIN = Duration.ofMinutes(10);
    private static final String FP = "b000000000000001";

    private static JsonNode catalog(AiEvidence ev) {
        return MAPPER.valueToTree(AiEvidenceCollector.catalog(ev, NOW, SUSTAIN));
    }

    private static List<String> actions(AiEvidence ev) {
        List<String> out = new ArrayList<>();
        catalog(ev).forEach(e -> out.add(e.path("action").asText()));
        return out;
    }

    private static List<String> targets(AiEvidence ev, AiAction.Verb verb) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : catalog(ev)) {
            if (e.path("action").asText().equals(verb.name())) {
                e.path("targets").forEach(t -> out.add(t.has("key") ? t.path("key").asText()
                        : t.path("fingerprint").asText()));
            }
        }
        return out;
    }

    @Test
    void 증설은_조회_CPU가_증설_임계_이상일_때만_보인다() {
        assertFalse(actions(new AiFixtures.Ev().cpu(0.5, null).build()).contains("READ_NODE_ADD_ONE"));
        assertTrue(actions(new AiFixtures.Ev().cpu(0.6, null).build()).contains("READ_NODE_ADD_ONE"));
        assertFalse(actions(new AiFixtures.Ev().cpu(0.9, null).nodes(2, 4, 4).build()).contains("READ_NODE_ADD_ONE"),
                "최대 대수면 보이지 않는다");
        assertFalse(actions(new AiFixtures.Ev().cpu(null, null).build()).contains("READ_NODE_ADD_ONE"),
                "조회 CPU 를 모르면 보이지 않는다");
    }

    @Test
    void 축소는_축소_임계_아래가_지속_시간을_채운_뒤에만_보인다() {
        assertFalse(actions(new AiFixtures.Ev().cpu(0.1, NOW.minus(Duration.ofMinutes(3))).build())
                .contains("READ_NODE_REMOVE_ONE"));
        assertTrue(actions(new AiFixtures.Ev().cpu(0.1, NOW.minus(SUSTAIN)).build()).contains("READ_NODE_REMOVE_ONE"));
        assertFalse(actions(new AiFixtures.Ev().cpu(0.1, NOW.minus(Duration.ofMinutes(30))).nodes(2, 2, 4).build())
                .contains("READ_NODE_REMOVE_ONE"), "최소 대수면 보이지 않는다");
    }

    @Test
    void 임계값_대상은_울리는_경보의_임계를_둔하게_하는_쪽과_간격을_깨는_쪽을_뺀다() {
        AiFixtures.Ev quiet = new AiFixtures.Ev().cpu(0.44, null).threshold("read.scale_in_cpu", "0.45", "0.05",
                "0.50", "0.05", true);
        assertEquals(List.of("read.scale_out_cpu", "host.disk_free_alert"),
                targets(quiet.build(), AiAction.Verb.THRESHOLD_RAISE), "축소 임계를 올리면 간격이 0.15 아래가 된다");
        assertEquals(List.of("read.scale_in_cpu"), targets(quiet.build(), AiAction.Verb.THRESHOLD_LOWER),
                "증설 임계를 내리면 간격이 0.15 아래가 되고, 디스크 여유를 모르면 디스크 임계를 둔하게 하지 않는다");

        AiFixtures.Ev over = new AiFixtures.Ev().cpu(0.7, null);
        assertFalse(targets(over.build(), AiAction.Verb.THRESHOLD_RAISE).contains("read.scale_out_cpu"),
                "이미 넘은 증설 임계는 올려 가리지 않는다");

        AiFixtures.Ev disk = new AiFixtures.Ev().metric(AiEvidence.HOST_ROOT_FREE_MIN, 0.12, NOW);
        disk.alerts.add(new AiEvidence.Alert(FP, "LinuxDiskSpaceLow", "warning", Map.of("component", "storage"),
                NOW.minus(Duration.ofMinutes(5)), false, "디스크 여유 부족", "host.disk_free_alert"));
        assertFalse(targets(disk.build(), AiAction.Verb.THRESHOLD_LOWER).contains("host.disk_free_alert"),
                "울리는 디스크 경보의 임계를 낮춰 가리지 않는다");
        assertTrue(targets(disk.build(), AiAction.Verb.THRESHOLD_RAISE).contains("host.disk_free_alert"),
                "민감하게 바꾸는 쪽은 남는다");
    }

    @Test
    void 경보_목록이_60초보다_오래되면_보류_대상을_보이지_않고_근거에_STALE_로_싣는다() {
        AiFixtures.Ev ev = new AiFixtures.Ev();
        ev.alerts.add(AiFixtures.alert(FP, "OrderRejectSpike", "warning", "order-gateway"));
        ev.windows.add(AiFixtures.window("OrderRejectSpike", Map.of(), NOW.minus(Duration.ofMinutes(10)),
                NOW.plus(Duration.ofMinutes(40))));
        assertEquals(List.of(FP), targets(ev.build(), AiAction.Verb.ALERT_SILENCE));
        assertEquals("OK", ev.build().toJson().get("alerts_collect"));

        ev.polledAt = NOW.minus(Duration.ofSeconds(61));
        assertEquals(List.of(), targets(ev.build(), AiAction.Verb.ALERT_SILENCE));
        assertEquals("STALE", ev.build().toJson().get("alerts_collect"));

        ev.polledAt = null;
        assertEquals("MISSING", ev.build().toJson().get("alerts_collect"));
    }

    @Test
    void 봇_부하_줄이기는_봇_호스트_CPU가_용량_보호_임계_이상이고_신선할_때만_보인다() {
        AiFixtures.Ev ev = new AiFixtures.Ev().metric(AiEvidence.BOT_HOST_CPU, 0.93, NOW);
        assertFalse(actions(ev.build()).contains("BOT_LOAD_REDUCE"), "용량 보호 임계가 미설정이면 기준이 없다");

        ev.threshold(AiPolicy.BOT_GUARD, "0.85", "0.50", "0.90", "0.05", false);
        assertTrue(actions(ev.build()).contains("BOT_LOAD_REDUCE"));
        ev.metric(AiEvidence.BOT_HOST_CPU, 0.28, NOW);
        assertFalse(actions(ev.build()).contains("BOT_LOAD_REDUCE"), "봇 호스트가 한가하면 뜻이 없다");
        ev.metric(AiEvidence.BOT_HOST_CPU, 0.96, NOW.minus(Duration.ofSeconds(240)));
        assertFalse(actions(ev.build()).contains("BOT_LOAD_REDUCE"), "오래된 표본으로는 줄이지 않는다");
    }
}
