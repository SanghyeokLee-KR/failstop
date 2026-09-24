package kr.exchange.admin.msp;

import kr.exchange.api.common.ApiFormats;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 온프레미스 AI 서버 상태(설계 7-18, API §6-F 'AI 서버 상태'). 관제센터 인프라 칸과 설정 화면의 원천이다. 서버 조회는
 * 30초 캐시이고 판단 주기와 별개로 화면이 부를 때만 한다. 호출 지연과 실패는 LLM 클라이언트가 직접 센 값이다.
 */
public class AiServerStatus {

    static final Duration TTL = Duration.ofSeconds(30);

    /** 서버 조회 한 번의 결과. failure 가 있으면 server 는 없다. */
    private record Probe(LlmClient.ServerStatus server, String failure) {
    }

    private final LlmClient llm;
    private final SourceCache cache;

    /** llm 은 LLM 설정이 없으면 null 이다. 그때는 연결 전으로 낸다. */
    public AiServerStatus(LlmClient llm, SourceCache cache) {
        this.llm = llm;
        this.cache = cache;
    }

    public Map<String, Object> view() {
        if (llm == null) {
            return MspSections.notConnected("llm");
        }
        SourceCache.Cached<Probe> probe = cache.get("llm:status", TTL, () -> {
            cache.called("llm", "status");
            try {
                return new Probe(llm.serverStatus(), null);
            } catch (LlmClient.LlmException e) {
                return new Probe(null, e.code());
            }
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", probe.value().failure() == null ? "READY" : "UNAVAILABLE");
        if (probe.value().failure() != null) {
            m.put("reason", probe.value().failure());
        }
        m.put("provider", AiViews.provider(llm));
        m.put("model", llm.model());
        m.put("transport", llm.transport().name());
        m.put("remote_allowed", llm.remoteAllowed());
        m.put("server", server(probe, cache.clock().instant()));
        m.put("calls", calls(llm.stats().summary()));
        return m;
    }

    private static Map<String, Object> server(SourceCache.Cached<Probe> probe, Instant now) {
        LlmClient.ServerStatus s = probe.value().server();
        if (s == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", s.version());
        m.put("model_loaded", s.modelLoaded());
        m.put("model_listed", s.modelListed());
        List<Map<String, Object>> loaded = new ArrayList<>();
        for (LlmClient.LoadedModel lm : s.loaded()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", lm.name());
            x.put("size_vram_bytes", lm.sizeVramBytes());
            x.put("expires_at", lm.expiresAt() == null ? null : ApiFormats.time(lm.expiresAt()));
            loaded.add(x);
        }
        m.put("loaded_models", loaded);
        m.put("checked_at", ApiFormats.time(probe.loadedAt()));
        m.put("age_seconds", Math.max(0, Duration.between(probe.loadedAt(), now).toSeconds()));
        return m;
    }

    private static Map<String, Object> calls(LlmStats.Summary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("window", LlmStats.WINDOW);
        m.put("count", s.count());
        m.put("failures", s.failures());
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("p50", s.p50Ms());
        latency.put("p95", s.p95Ms());
        m.put("latency_ms", latency);
        m.put("last_success_at", s.lastSuccessAt() == null ? null : ApiFormats.time(s.lastSuccessAt()));
        if (s.lastFailureAt() == null) {
            m.put("last_failure", null);
        } else {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("at", ApiFormats.time(s.lastFailureAt()));
            f.put("code", s.lastFailureCode());
            m.put("last_failure", f);
        }
        Map<String, Object> byPurpose = new LinkedHashMap<>();
        for (LlmClient.Purpose p : LlmClient.Purpose.values()) {
            byPurpose.put(p.name().toLowerCase(Locale.ROOT), s.byPurpose().getOrDefault(p, 0));
        }
        m.put("by_purpose", byPurpose);
        return m;
    }
}
