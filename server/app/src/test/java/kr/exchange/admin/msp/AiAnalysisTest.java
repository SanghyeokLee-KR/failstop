package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.admin.AdminAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 원인 분석과 로그 분석(설계 7-19·7-20). 모델 출력의 모양·길이·링크를 검사하고, 가설은 묶음에 있는 인용만 남기며, 같은 상황과
 * 같은 로그 묶음에는 모델을 다시 부르지 않고, 정지 중에는 부르지 않는다. 모델은 로컬 스텁이다.
 */
class AiAnalysisTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-28T03:15:05Z");
    private static final String FP = "a000000000000001";
    private static final String GROUP = "3f9a0c21b7e4d5a6";
    private static final AlertMeanings MEANINGS = AlertMeanings.load(MAPPER);

    private static final String CAUSE = "{\"load_reason_ko\":\"앱 계층 CPU가 기준 이상으로 4분 이어졌습니다.\","
            + "\"load_pattern\":\"SUSTAINED\",\"hypotheses\":["
            + "{\"cause_ko\":\"앱 계층 부하 경보가 울린 때에 부하가 이어졌습니다.\",\"cited_ids\":[\"alert." + FP + "\",\"load\"]},"
            + "{\"cause_ko\":\"묶음에 없는 근거만 댄 가설입니다.\",\"cited_ids\":[\"signal.made_up\"]},"
            + "{\"cause_ko\":\"DB 연결 오류가 함께 늘었습니다.\",\"cited_ids\":[\"log." + GROUP + "\",\"signal.made_up\"]}],"
            + "\"insufficient_ko\":\"요청률 지표가 없어 부하 원인을 좁히기 어렵습니다.\",\"summary_ko\":\"앱 계층 부하가 지속됩니다.\"}";
    private static final String LOGS = "{\"findings\":[{\"group_id\":\"" + GROUP + "\",\"summary_ko\":\"주문 접수가 DB에 "
            + "닿지 못했습니다.\",\"cause_ko\":\"DB 연결 거부로 보입니다.\"},{\"group_id\":\"ffffffffffffffff\","
            + "\"summary_ko\":\"없는 묶음\",\"cause_ko\":\"없음\"}],\"summary_ko\":\"DB 연결 오류가 이어집니다.\"}";

    private StubSource llmSource;
    private Function<StubSource.Request, StubSource.Response> reply;
    private volatile String causeContent = CAUSE;
    private volatile String logsContent = LOGS;
    private LlmClient llm;
    private AiControl control;
    private AdminAuditService audit;
    private ErrorLogPoller poller;
    private AiAnalysis analysis;

    @BeforeEach
    void start() throws Exception {
        llmSource = new StubSource();
        reply = r -> {
            String content = r.body().contains("원인 분석 보조") ? causeContent : logsContent;
            try {
                return StubSource.Response.json("{\"choices\":[{\"message\":{\"content\":"
                        + MAPPER.writeValueAsString(content) + "}}]}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        llmSource.handler = reply;
        MspProperties.Llm props = new MspProperties.Llm();
        props.setProvider(MspProperties.Llm.Provider.OPENAI_COMPATIBLE);
        props.setBaseUrl(llmSource.uri().toString());
        props.setModel("test-model");
        props.setTimeout(Duration.ofSeconds(2));
        llm = new LlmClient(props, MAPPER, new SimpleMeterRegistry(), Clock.systemUTC());
        control = mock(AiControl.class);
        when(control.mode()).thenReturn(AiFixtures.manual());
        audit = mock(AdminAuditService.class);
        poller = mock(ErrorLogPoller.class);
        when(poller.last()).thenReturn(digest(12));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AiCause cause = new AiCause(new MspProperties(), null, since -> List.of(), poller::last, MEANINGS, MAPPER, clock);
        analysis = new AiAnalysis(llm, cause, poller, control, MEANINGS, audit, MAPPER, clock, Duration.ofMinutes(10));
    }

    @AfterEach
    void stop() {
        llmSource.close();
    }

    private static ErrorLogPoller.Digest digest(long count) {
        return new ErrorLogPoller.Digest(NOW, List.of(new ErrorLogPoller.Source("admin", "READY", null)),
                List.of(new ErrorLogPoller.Group(GROUP, "ERROR", "OrderGateway", "주문 접수 실패: {}", "PSQLException",
                        "Connection to [주소] refused.", "OrderGateway.submit", "주문 접수 실패: 저널 제출 예외", count, count,
                        NOW.minusSeconds(300), NOW.minusSeconds(5), List.of("chaos-api-1"))), null);
    }

    private static AiEvidence evidence() {
        AiFixtures.Ev ev = new AiFixtures.Ev().cpu(0.72, null);
        ev.alerts.add(new AiEvidence.Alert(FP, "AppTierCpuHigh", "warning", Map.of(), NOW.minusSeconds(240), false,
                null, List.of()));
        return ev.load(new LoadPattern(LoadPattern.Kind.SUSTAINED, 0.72, new BigDecimal("0.5"), 240, 180, 240, 1, 900,
                0.02, 0.9, 1, NOW, NOW.minusSeconds(240))).build();
    }

    private int calls() {
        return llmSource.requests.size();
    }

    @Test
    void 가설은_묶음에_있는_인용만_남기고_서버가_값과_시각을_붙인다() {
        analysis.afterJudge(evidence(), "a@1,load:SUSTAINED");
        AiAnalysis.Cause c = analysis.lastCause();

        assertEquals("READY", c.state());
        assertEquals("SUSTAINED", c.aiPattern());
        assertEquals(2, c.hypotheses().size(), "근거 없는 가설 하나는 버린다");
        assertEquals(1, c.dropped());
        assertEquals(List.of(1, 2), c.hypotheses().stream().map(AiAnalysis.Hypothesis::rank).toList());
        Map<String, Object> alert = c.hypotheses().get(0).citations().get(0);
        assertEquals("alert." + FP, alert.get("id"));
        assertEquals("warning", alert.get("value"));
        assertEquals("SUSTAINED", c.hypotheses().get(0).citations().get(1).get("value"));
        assertEquals(List.of("log." + GROUP), c.hypotheses().get(1).citedIds(), "없는 id 는 인용에서 뺀다");
        assertEquals("12", c.hypotheses().get(1).citations().get(0).get("value"));
        assertEquals("요청률 지표가 없어 부하 원인을 좁히기 어렵습니다.", c.insufficient());

        Map<String, Object> view = analysis.causeView();
        assertEquals("msp-analysis/1", view.get("prompt_version"));
        @SuppressWarnings("unchecked")
        Map<String, Object> load = (Map<String, Object>) view.get("load");
        assertEquals("SUSTAINED", load.get("pattern"));
        assertEquals("SUSTAINED", load.get("ai_pattern"));
        assertEquals(240L, load.get("above_for_seconds"));
        verify(audit).record(eq(AiControl.AI_OPERATOR), eq("ops.ai.analysis"), eq("cause"), any(), any(), eq("OK"),
                any());
    }

    @Test
    void 같은_상황과_같은_로그_묶음에는_다시_묻지_않고_바뀌면_묻는다() {
        analysis.afterJudge(evidence(), "a@1,load:TRANSIENT");
        assertEquals(2, calls(), "원인 분석과 로그 분석 한 번씩");
        analysis.afterJudge(evidence(), "a@1,load:TRANSIENT");
        assertEquals(2, calls());

        analysis.afterJudge(evidence(), "a@1,load:SUSTAINED");
        assertEquals(3, calls(), "부하 판정이 바뀌면 원인 분석을 다시 한다");

        when(poller.last()).thenReturn(digest(20));
        analysis.afterJudge(null, null);
        assertEquals(4, calls(), "평온해도 로그 묶음의 건수 구간이 바뀌면 로그 분석을 한다");
        verify(audit, times(2)).record(eq(AiControl.AI_OPERATOR), eq("ops.ai.analysis"), eq("logs"), any(), any(),
                eq("OK"), any());
    }

    @Test
    void 경보도_넘은_임계도_없으면_원인_분석을_하지_않는다() {
        // 열린 조치 요청 때문에 근거를 모은 주기다. 상황 문자열에는 부하 판정 이름이 늘 있지만 설명할 문제가 없다.
        AiEvidence calm = new AiFixtures.Ev().cpu(0.45, null).load(AiFixtures.transientLoad()).build();
        analysis.afterJudge(calm, "load:TRANSIENT");

        assertNull(analysis.lastCause());
        assertEquals(1, calls(), "로그 분석만 한다");
    }

    @Test
    void 원인_분석_호출_중에_정지되면_결과를_남기지_않고_로그_분석도_시작하지_않는다() {
        // 운영자가 모델 호출 도중 AI 즉시 정지를 누른다(ADR 0035). 모델은 정지 뒤에 답을 돌려준다.
        AtomicBoolean stop = new AtomicBoolean();
        when(control.mode()).thenAnswer(inv -> stop.get() ? AiFixtures.stopped() : AiFixtures.manual());
        llmSource.handler = r -> {
            stop.set(true);
            return reply.apply(r);
        };

        analysis.afterJudge(evidence(), "a@1,load:SUSTAINED");

        assertEquals(1, calls(), "원인 분석 한 번만 나가고 로그 분석은 시작하지 않는다");
        assertTrue(llmSource.requests.get(0).body().contains("원인 분석 보조"));
        assertNull(analysis.lastCause());
        assertNull(analysis.lastLogs());
        verify(audit, never()).record(any(), eq("ops.ai.analysis"), any(), any(), any(), any(), any());
    }

    @Test
    void 로그_분석_호출_중에_정지되면_결과를_남기지_않는다() {
        AtomicBoolean stop = new AtomicBoolean();
        when(control.mode()).thenAnswer(inv -> stop.get() ? AiFixtures.stopped() : AiFixtures.manual());
        llmSource.handler = r -> {
            stop.set(true);
            return reply.apply(r);
        };

        analysis.afterJudge(null, null);

        assertEquals(1, calls());
        assertNull(analysis.lastLogs());
        verify(audit, never()).record(any(), eq("ops.ai.analysis"), any(), any(), any(), any(), any());
    }

    @Test
    void AI_정지_중에는_부르지_않는다() {
        when(control.mode()).thenReturn(AiFixtures.stopped());
        analysis.afterJudge(evidence(), "a@1");
        assertEquals(0, calls());
        assertNull(analysis.lastCause());
    }

    @Test
    void 로그_분석은_보낸_묶음의_발견만_남기고_로그_문장을_블록_안에_둔다() {
        analysis.afterJudge(null, null);
        AiAnalysis.Logs logs = analysis.lastLogs();

        assertEquals("READY", logs.state());
        assertEquals(1, logs.findings().size());
        assertEquals(GROUP, logs.findings().get(0).groupId());
        String body = llmSource.requests.get(0).body();
        assertTrue(body.contains("\\u003c로그\\u003e") || body.contains("<로그>"), body);
        assertTrue(body.contains("주문 접수 실패: 저널 제출 예외"), "로그 분석에는 대표 문장이 간다");
        assertEquals(List.of(Map.of("group_id", GROUP, "summary", "주문 접수가 DB에 닿지 못했습니다.",
                "cause", "DB 연결 거부로 보입니다.")), analysis.logsView().get("findings"));
    }

    @Test
    void 모양이_틀린_원인_분석은_통째로_버리고_사유를_남긴다() {
        AiCause.Bundle bundle = new AiCause(new MspProperties(), null, since -> List.of(), () -> digest(1), MEANINGS,
                MAPPER, Clock.fixed(NOW, ZoneOffset.UTC)).build(evidence());
        assertEquals("empty", parseCause("", bundle).reason());
        assertEquals("not_json", parseCause("원인은 부하입니다", bundle).reason());
        assertEquals("shape", parseCause(CAUSE.replace("\"summary_ko\"", "\"extra\":1,\"summary_ko\""), bundle).reason());
        assertEquals("pattern", parseCause(CAUSE.replace("\"SUSTAINED\"", "\"HIGH\""), bundle).reason());
        assertEquals("link", parseCause(CAUSE.replace("부하가 이어졌습니다.", "http://x.example 를 보십시오."), bundle)
                .reason());
        assertEquals("text_length", parseCause(CAUSE.replace("\"앱 계층 부하가 지속됩니다.\"", "\"\""), bundle).reason());
        String four = CAUSE.replace("],\"insufficient_ko\"", ",{\"cause_ko\":\"넷째\",\"cited_ids\":[\"load\"]}],"
                + "\"insufficient_ko\"");
        assertEquals("shape", parseCause(four, bundle).reason());
        AiAnalysis.Cause bad = parseCause("{}", bundle);
        assertEquals("INVALID_OUTPUT", bad.state());
        assertTrue(bad.hypotheses().isEmpty());
    }

    @Test
    void 모양이_틀린_로그_분석은_통째로_버린다() {
        List<ErrorLogPoller.Group> groups = digest(3).groups();
        assertEquals("link", AiAnalysis.parseLogs(LOGS.replace("DB 연결 거부로 보입니다.", "@ops 확인"), groups, "s", NOW,
                "m", 1, MAPPER).reason());
        assertEquals("shape", AiAnalysis.parseLogs("{\"findings\":[],\"summary_ko\":\"요약\",\"x\":1}", groups, "s", NOW,
                "m", 1, MAPPER).reason());
        assertEquals("READY", AiAnalysis.parseLogs("{\"findings\":[],\"summary_ko\":\"오류가 없습니다.\"}", groups, "s",
                NOW, "m", 1, MAPPER).state());
    }

    private static AiAnalysis.Cause parseCause(String raw, AiCause.Bundle bundle) {
        return AiAnalysis.parseCause(raw, bundle, NOW, "m", 1, MAPPER);
    }
}
