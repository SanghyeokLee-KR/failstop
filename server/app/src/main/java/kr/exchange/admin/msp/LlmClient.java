package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 판단 모델 호출(설계 7-3). 두 공급자 형식을 쓴다. openai-compatible 은 /v1/chat/completions 에 response_format
 * json_schema 를 싣고, ollama-native 는 /api/chat 의 format 에 JSON 스키마를 싣는다. Ollama 의 /v1 호환 경로는
 * json_schema 강제가 불완전하다는 보고가 있어(ollama/ollama#10001) 네이티브 경로를 따로 둔다.
 *
 * 프롬프트가 운영 상태를 담으므로 주소를 좁힌다. 루프백과 사설 주소만 받고, 그 밖의 주소와 Ollama 클라우드 모델(로컬
 * Ollama 가 프롬프트를 외부로 넘김)은 allow-remote 를 명시해야 받는다. 루프백이 아닌 평문 http 는 allow-insecure-http 일 때만 받고
 * 그때는 수동 모드 전용이다(정책 M8). 비밀 값(API 키)은 파일에서 읽어 헤더로만 보낸다.
 *
 * 판단과 상담의 최근 호출(지연, 실패)을 직접 세어 AI 서버 상태로 낸다(설계 7-18).
 */
public class LlmClient {

    public enum Transport { LOOPBACK, TLS, PLAINTEXT }

    /** 모델을 부르는 까닭. 상태와 지표에서 가른다. ANALYSIS 는 원인 분석, LOGS 는 오류 로그 분석이다(설계 7-19·7-20). */
    public enum Purpose { JUDGE, CHAT, ANALYSIS, LOGS }

    /** 대화 한 칸. role 은 user 나 assistant 다. */
    public record Turn(String role, String content) {
    }

    /** 적재된 모델 하나(Ollama /api/ps). */
    public record LoadedModel(String name, Long sizeVramBytes, Instant expiresAt) {
    }

    /**
     * 서버 상태. ollama-native 는 버전과 적재 모델을 알고, openai-compatible 은 모델 목록만 안다. 모르는 칸은 null 이다.
     */
    public record ServerStatus(String version, Boolean modelLoaded, Boolean modelListed, List<LoadedModel> loaded) {
    }

    public static final String PROMPT_VERSION = "msp-ai/4";
    static final String SCHEMA_RESOURCE = "/msp/ai-output.schema.json";
    static final String PROMPT_RESOURCE = "/msp/ai-system-prompt.txt";
    static final int MAX_RESPONSE = 64 * 1024;
    // 추론 토큰이 출력 상한에 들어가는 서버가 있어 넉넉히 둔다. 스키마가 강제하는 출력 자체는 1 KiB 안팎이다.
    static final int MAX_TOKENS = 2048;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(5);

    public record Reply(String content, long latencyMs) {
    }

    /** 분류 코드만 싣는다. 주소, 헤더, 응답 본문은 싣지 않는다. */
    public static final class LlmException extends RuntimeException {

        private final String code;

        LlmException(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final MspProperties.Llm props;
    private final URI base;
    private final Transport transport;
    private final String apiKey;
    private final SourceHttp http;
    private final SourceHttp statusHttp;
    private final SourceHttp loadHttp;
    private final ObjectMapper mapper;
    private final JsonNode schema;
    private final String systemPrompt;
    private final LlmStats stats;
    private final Clock clock;
    // 판단·상담의 마지막 실패. 한 판의 AI 칸과 LLM 원천 상태가 이 값을 본다. 원인·로그 분석의 실패는 따로 둔다. 긴 분석
    // 프롬프트 하나가 시간을 넘겨도 판단은 멀쩡할 수 있어, 분석 실패로 AI 칸이 UNAVAILABLE 이 되지 않게 하려는 것이다.
    private volatile String lastError;
    private volatile Instant lastErrorAt;
    private volatile String lastAnalysisError;
    private volatile Instant lastAnalysisErrorAt;
    private volatile Instant lastSuccessAt;

    public LlmClient(MspProperties.Llm props, ObjectMapper mapper, MeterRegistry registry, Clock clock) {
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
        this.stats = new LlmStats(registry);
        this.base = MspProperties.sourceUri("llm.base-url", props.getBaseUrl());
        if (cloudModel(props.getModel()) && !props.isAllowRemote()) {
            throw new IllegalStateException("exchange.msp.llm.model 이 Ollama 클라우드 모델이라 프롬프트가 외부로 나간다."
                    + " 원격 모델은 exchange.msp.llm.allow-remote=true 로 명시해야 한다");
        }
        List<InetAddress> resolved = resolve(base.getHost());
        if (resolved != null && !props.isAllowRemote() && !resolved.stream().allMatch(LlmClient::privateOrLoopback)) {
            throw new IllegalStateException("exchange.msp.llm.base-url 이 사설·루프백 주소가 아니다."
                    + " 원격 주소는 exchange.msp.llm.allow-remote=true 로 명시해야 한다");
        }
        this.transport = transport(base, resolved, props.isAllowInsecureHttp());
        if (props.getCaFile() != null && !props.getCaFile().isBlank() && transport != Transport.TLS) {
            throw new IllegalStateException("exchange.msp.llm.ca-file 은 https 주소에서만 쓴다");
        }
        String keyFile = props.getApiKeyFile();
        if (keyFile != null && !keyFile.isBlank() && transport == Transport.PLAINTEXT) {
            throw new IllegalStateException("평문 http 로 LLM API 키를 보낼 수 없다. https 를 쓰거나 키 파일을 빼야 한다");
        }
        this.apiKey = keyFile == null || keyFile.isBlank() ? null : readKey(keyFile);
        SSLContext ssl = transport == Transport.TLS ? ssl(props.getCaFile()) : null;
        this.http = new SourceHttp(CONNECT_TIMEOUT, props.getTimeout(), MAX_RESPONSE, ssl);
        this.statusHttp = new SourceHttp(CONNECT_TIMEOUT, STATUS_TIMEOUT, MAX_RESPONSE, ssl);
        this.loadHttp = new SourceHttp(CONNECT_TIMEOUT, props.getLoadTimeout(), MAX_RESPONSE, ssl);
        this.schema = resourceJson(mapper, SCHEMA_RESOURCE);
        this.systemPrompt = systemPrompt();
    }

    public Transport transport() {
        return transport;
    }

    public MspProperties.Llm.Provider provider() {
        return props.getProvider();
    }

    public String model() {
        return props.getModel();
    }

    public String lastError() {
        return lastError;
    }

    public Instant lastErrorAt() {
        return lastErrorAt;
    }

    /** 원인·로그 분석 호출의 마지막 실패. AI 칸 상태에는 쓰지 않는다. */
    public String lastAnalysisError() {
        return lastAnalysisError;
    }

    public Instant lastAnalysisErrorAt() {
        return lastAnalysisErrorAt;
    }

    public Instant lastSuccessAt() {
        return lastSuccessAt;
    }

    public LlmStats stats() {
        return stats;
    }

    public boolean remoteAllowed() {
        return props.isAllowRemote();
    }

    String systemPromptText() {
        return systemPrompt;
    }

    JsonNode schema() {
        return schema;
    }

    /** 판단 한 번. 사용자 메시지는 근거 JSON 원문이다. 한 주기 안에서 다시 시도하지 않는다. */
    public Reply complete(String evidenceJson) {
        return call(Purpose.JUDGE, systemPrompt, List.of(new Turn("user", evidenceJson)), schema, "msp_ai_decision");
    }

    /** 상담 한 번(설계 7-17). 대화와 근거는 호출부가 turns 로 만든다. 출력 스키마는 상담 스키마다. */
    public Reply chat(String system, List<Turn> turns, JsonNode chatSchema) {
        return call(Purpose.CHAT, system, turns, chatSchema, "msp_ai_chat");
    }

    /** 원인 분석·로그 분석 한 번(설계 7-19·7-20). 조회 전용 호출이고 한 주기 안에서 다시 시도하지 않는다. */
    public Reply analyze(Purpose purpose, String system, String user, JsonNode outputSchema, String schemaName) {
        return call(purpose, system, List.of(new Turn("user", user)), outputSchema, schemaName);
    }

    private Reply call(Purpose purpose, String system, List<Turn> turns, JsonNode outputSchema, String schemaName) {
        long started = System.nanoTime();
        boolean analysis = purpose == Purpose.ANALYSIS || purpose == Purpose.LOGS;
        try {
            checkAddress();
            ObjectNode body = props.getProvider() == MspProperties.Llm.Provider.OLLAMA_NATIVE
                    ? ollamaBody(system, turns, outputSchema) : openAiBody(system, turns, outputSchema, schemaName);
            SourceHttp.Response response = send(http, chatUri(), body);
            if (response.status() != 200) {
                throw new LlmException("http_status");
            }
            JsonNode root = readTree(response.body());
            JsonNode content = props.getProvider() == MspProperties.Llm.Provider.OLLAMA_NATIVE
                    ? root.path("message").path("content")
                    : root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw new LlmException("bad_response");
            }
            long latency = (System.nanoTime() - started) / 1_000_000;
            lastSuccessAt = clock.instant();
            if (analysis) {
                lastAnalysisError = null;
            } else {
                lastError = null;
            }
            stats.record(purpose, null, latency, lastSuccessAt);
            return new Reply(content.asText(), latency);
        } catch (LlmException e) {
            Instant at = clock.instant();
            if (analysis) {
                lastAnalysisError = e.code();
                lastAnalysisErrorAt = at;
            } else {
                lastError = e.code();
                lastErrorAt = at;
            }
            stats.record(purpose, e.code(), (System.nanoTime() - started) / 1_000_000, at);
            throw e;
        }
    }

    /**
     * 서버 상태(설계 7-18). ollama-native 는 /api/version 과 /api/ps, openai-compatible 은 /v1/models 를 부른다. 판단
     * 주기와 별개이고 호출부(AiServerStatus)가 캐시한다.
     */
    public ServerStatus serverStatus() {
        checkAddress();
        if (props.getProvider() == MspProperties.Llm.Provider.OLLAMA_NATIVE) {
            JsonNode version = ok(get(statusHttp, URI.create(base + "/api/version")));
            JsonNode ps = ok(get(statusHttp, URI.create(base + "/api/ps")));
            List<LoadedModel> loaded = new ArrayList<>();
            boolean mine = false;
            for (JsonNode m : ps.path("models")) {
                String name = m.path("name").asText(m.path("model").asText(""));
                mine |= model().equals(name) || model().equals(m.path("model").asText());
                loaded.add(new LoadedModel(AiStore.clip(name, 128),
                        m.path("size_vram").isNumber() ? m.path("size_vram").asLong() : null,
                        parseTime(m.path("expires_at").asText(null))));
                if (loaded.size() >= 10) {
                    break;
                }
            }
            String v = version.path("version").asText(null);
            return new ServerStatus(v == null ? null : AiStore.clip(v, 64), mine, null, List.copyOf(loaded));
        }
        JsonNode models = ok(get(statusHttp, URI.create(base + (base.getPath().endsWith("/v1") ? "" : "/v1")
                + "/models")));
        boolean listed = false;
        for (JsonNode m : models.path("data")) {
            listed |= model().equals(m.path("id").asText());
        }
        return new ServerStatus(null, null, listed, List.of());
    }

    private JsonNode ok(SourceHttp.Response response) {
        if (response.status() != 200) {
            throw new LlmException("http_status");
        }
        return readTree(response.body());
    }

    private static Instant parseTime(String text) {
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 모델이 메모리에 올라 있는가. ollama-native 는 /api/ps 로 본다. 첫 호출의 적재는 수십 초 걸리므로(로컬 실측
     * 약 39초) 적재 중에는 판단하지 않고 다음 주기로 넘긴다. openai-compatible 은 적재 상태를 묻는 경로가 없다.
     */
    public boolean loaded() {
        if (props.getProvider() != MspProperties.Llm.Provider.OLLAMA_NATIVE) {
            return true;
        }
        checkAddress();
        SourceHttp.Response response = get(statusHttp, URI.create(base + "/api/ps"));
        if (response.status() != 200) {
            throw new LlmException("http_status");
        }
        for (JsonNode m : readTree(response.body()).path("models")) {
            if (model().equals(m.path("name").asText()) || model().equals(m.path("model").asText())) {
                return true;
            }
        }
        return false;
    }

    /** 모델을 메모리에 올린다. 판단 없이 적재만 하므로 적재 시간 상한(load-timeout)을 따로 쓴다. */
    public void load() {
        if (props.getProvider() != MspProperties.Llm.Provider.OLLAMA_NATIVE) {
            return;
        }
        checkAddress();
        ObjectNode body = mapper.createObjectNode().put("model", model());
        SourceHttp.Response response = send(loadHttp, URI.create(base + "/api/generate"), body);
        if (response.status() != 200) {
            throw new LlmException("http_status");
        }
    }

    private URI chatUri() {
        if (props.getProvider() == MspProperties.Llm.Provider.OLLAMA_NATIVE) {
            return URI.create(base + "/api/chat");
        }
        return URI.create(base + (base.getPath().endsWith("/v1") ? "" : "/v1") + "/chat/completions");
    }

    private ObjectNode openAiBody(String system, List<Turn> turns, JsonNode outputSchema, String schemaName) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model());
        messages(body, system, turns);
        body.put("temperature", 0);
        body.put("max_tokens", MAX_TOKENS);
        ObjectNode format = body.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode js = format.putObject("json_schema");
        js.put("name", schemaName);
        js.put("strict", true);
        js.set("schema", outputSchema);
        return body;
    }

    private ObjectNode ollamaBody(String system, List<Turn> turns, JsonNode outputSchema) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model());
        messages(body, system, turns);
        body.put("stream", false);
        body.set("format", outputSchema);
        String think = props.getThink();
        if (think != null) {
            if ("true".equals(think) || "false".equals(think)) {
                body.put("think", Boolean.parseBoolean(think));
            } else {
                body.put("think", think);
            }
        }
        body.putObject("options").put("temperature", 0);
        return body;
    }

    private void messages(ObjectNode body, String system, List<Turn> turns) {
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        for (Turn t : turns) {
            messages.addObject().put("role", t.role()).put("content", t.content());
        }
    }

    private SourceHttp.Response send(SourceHttp client, URI uri, ObjectNode body) {
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new IllegalStateException("LLM 요청 직렬화 실패", e);
        }
        try {
            return client.post(uri, bytes, headers());
        } catch (SourceException e) {
            throw new LlmException(e.reason());
        }
    }

    private SourceHttp.Response get(SourceHttp client, URI uri) {
        try {
            return client.get(uri, headers());
        } catch (SourceException e) {
            throw new LlmException(e.reason());
        }
    }

    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        if (apiKey != null) {
            h.put("Authorization", "Bearer " + apiKey);
        }
        return h;
    }

    private JsonNode readTree(byte[] body) {
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw new LlmException("bad_response");
            }
            return node;
        } catch (IOException e) {
            throw new LlmException("bad_response");
        }
    }

    /**
     * 부를 때마다 이름을 다시 풀어 주소가 허용 범위인지 본다. 기동 뒤 DNS 가 공인 주소로 바뀌어도 프롬프트를 내보내지
     * 않는다. 루프백으로 기동한 주소는 계속 루프백이어야 한다.
     */
    private void checkAddress() {
        List<InetAddress> addresses = resolve(base.getHost());
        if (addresses == null) {
            throw new LlmException("unreachable");
        }
        boolean ok = transport == Transport.LOOPBACK
                ? addresses.stream().allMatch(InetAddress::isLoopbackAddress)
                : props.isAllowRemote() || addresses.stream().allMatch(LlmClient::privateOrLoopback);
        if (!ok) {
            throw new LlmException("remote_address");
        }
    }

    static Transport transport(URI base, List<InetAddress> resolved, boolean allowInsecureHttp) {
        if ("https".equals(base.getScheme())) {
            return Transport.TLS;
        }
        boolean loopback = "localhost".equalsIgnoreCase(base.getHost())
                || (resolved != null && !resolved.isEmpty() && resolved.stream().allMatch(InetAddress::isLoopbackAddress));
        if (loopback) {
            return Transport.LOOPBACK;
        }
        if (allowInsecureHttp) {
            return Transport.PLAINTEXT;
        }
        throw new IllegalStateException("exchange.msp.llm.base-url 이 루프백이 아닌 평문 http 다. https 를 쓰거나"
                + " exchange.msp.llm.allow-insecure-http=true 로 명시해야 한다(수동 모드 전용)");
    }

    /**
     * Ollama 클라우드 모델인가. 태그가 cloud 이거나(glm-4.6:cloud) -cloud 로 끝나는(gpt-oss:120b-cloud) 이름이다. 이름으로만
     * 가르므로 클라우드 모델을 원본으로 만든 로컬 별칭은 가르지 못한다(설계 3절).
     */
    static boolean cloudModel(String model) {
        String name = model.toLowerCase(Locale.ROOT);
        return name.endsWith(":cloud") || name.endsWith("-cloud");
    }

    /** 루프백, 사설(10/8, 172.16/12, 192.168/16), IPv6 고유 로컬(fc00::/7)만 사설로 본다. */
    static boolean privateOrLoopback(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress()) {
            return true;
        }
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc;
    }

    /** 풀리지 않으면 null. 기동을 막지 않고 부를 때 다시 본다. */
    static List<InetAddress> resolve(String host) {
        try {
            return List.of(InetAddress.getAllByName(host));
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static String readKey(String file) {
        try {
            String key = Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
            if (key.isEmpty()) {
                throw new IllegalStateException("exchange.msp.llm.api-key-file 이 비어 있다");
            }
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("exchange.msp.llm.api-key-file 을 읽을 수 없다");
        }
    }

    /** PEM 인증서 파일의 CA 만 믿는 TLS 문맥. 파일이 없으면 JDK 기본 신뢰 저장소를 쓴다. */
    private static SSLContext ssl(String caFile) {
        try {
            if (caFile == null || caFile.isBlank()) {
                return SSLContext.getDefault();
            }
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            try (InputStream in = Files.newInputStream(Path.of(caFile))) {
                int i = 0;
                for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    store.setCertificateEntry("llm-ca-" + i++, cert);
                }
                if (i == 0) {
                    throw new IllegalStateException("exchange.msp.llm.ca-file 에 인증서가 없다");
                }
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("exchange.msp.llm.ca-file 을 읽을 수 없다", e);
        }
    }

    /** 리소스 파일의 문자열. 상담 프롬프트처럼 치환 없이 쓰는 문서에 쓴다. */
    static String resourceText(String resource) {
        try (InputStream in = LlmClient.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("리소스가 없다: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("리소스를 읽을 수 없다: " + resource, e);
        }
    }

    static JsonNode resourceJson(ObjectMapper mapper, String resource) {
        try (InputStream in = LlmClient.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("리소스가 없다: " + resource);
            }
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("리소스를 읽을 수 없다: " + resource, e);
        }
    }

    /** 시스템 프롬프트. 조치 설명은 카탈로그의 설명을 그대로 싣는다(설계 7-13). */
    static String systemPrompt() {
        String template = resourceText(PROMPT_RESOURCE);
        String actions = java.util.Arrays.stream(AiAction.Verb.values())
                .map(v -> "- " + v.name() + ": " + v.description())
                .collect(Collectors.joining("\n"));
        return template.replace("{{ACTIONS}}", actions);
    }
}
