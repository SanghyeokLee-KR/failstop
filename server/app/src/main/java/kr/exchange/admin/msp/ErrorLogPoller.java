package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.admin.CapacityProperties;
import kr.exchange.api.common.ErrorLogBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 오류 로그 묶음 수집(설계 7-20, API 설계 §6-F '오류 로그'). 1분마다 앱 노드의 /actuator/errorlog 와 관리 JVM 자신의 묶음을
 * 읽어 같은 id 끼리 합친다. 노드마다 병렬로 부르고 시간 상한을 걸어, 죽은 노드 하나가 다른 노드의 수집을 붙들지 않게 한다.
 * 결정적 계산만 하고 모델은 부르지 않는다. 모델의 요약은 판단 주기가 이 결과를 보고 붙인다(AiAnalysis).
 */
public class ErrorLogPoller {

    private static final Logger log = LoggerFactory.getLogger(ErrorLogPoller.class);

    static final String SELF = "admin";
    // 노드 응답 한 건의 상한. 노드의 끝점은 응답을 ErrorLogBook.MAX_VIEW_BYTES(192 KiB) 안으로 줄여 보내므로 이 상한에 걸리지
    // 않는다. 걸리면 그 노드는 response_limit 로 빠진다.
    static final int MAX_RESPONSE = 256 * 1024;
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{16}$");
    // 로거, 예외, 프레임은 클래스와 메서드의 이름이라 이 모양만 받는다. 원인 분석 묶음과 상담 근거가 로그 문장 대신 싣는
    // 필드라서, 노드가 문장이나 다른 모양을 보내면 그 필드를 버린다(설계 7-20 신뢰 경계).
    private static final Pattern NAME = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$.<>]{0,199}$");
    private static final int MAX_TEXT = 300;

    /** 노드 여럿을 합친 묶음 하나. logger 와 exception 은 클래스의 짧은 이름이다. */
    record Group(String id, String level, String logger, String template, String exception, String exceptionMessage,
                 String frame, String message, long count, long total, Instant firstSeen, Instant lastSeen,
                 List<String> nodes) {
    }

    record Source(String node, String state, String reason) {
    }

    /**
     * 한 번의 수집. polledAt 이 null 이면 아직 수집하지 않았다. failure 는 모든 원천이 실패했을 때의 분류다. omitted 는 노드들이
     * 응답 상한 때문에 싣지 않은 묶음 수의 합이다. 그 묶음의 건수는 count 에 들지 않는다.
     */
    record Digest(Instant polledAt, List<Source> sources, List<Group> groups, String failure, long omitted) {

        static final Digest NOT_POLLED = new Digest(null, List.of(), List.of(), "not_polled");

        Digest(Instant polledAt, List<Source> sources, List<Group> groups, String failure) {
            this(polledAt, sources, groups, failure, 0);
        }

        long count(String level) {
            return groups.stream().filter(g -> g.level().equals(level)).mapToLong(Group::count).sum();
        }

        /**
         * 모델에게 다시 물을지 가르는 지문. 15분 건수가 있는 묶음의 id 와 건수의 2의 거듭제곱 구간이다. 오류가 같은 빠르기로
         * 이어지는 동안 1분마다 다시 묻지 않고, 새 묶음이 생기거나 건수가 한 구간을 넘으면 다시 묻는다.
         */
        String signature() {
            List<String> parts = new ArrayList<>();
            for (Group g : groups) {
                if (g.count() > 0) {
                    parts.add(g.id() + ":" + (63 - Long.numberOfLeadingZeros(g.count())));
                }
            }
            parts.sort(String::compareTo);
            return String.join(",", parts);
        }
    }

    /**
     * 원천 하나. 자기 JVM 은 묶음을 바로 읽고, 앱 노드는 액추에이터를 부른다. optional 은 켜고 끄는 조회 전용 노드(용량
     * 설정의 scalable, app-3)다. 꺼져 있으면 닿지 않는 것이 정상이라 실패로 세지 않는다.
     */
    private record Node(String name, boolean optional, Supplier<byte[]> fetch) {
    }

    private final List<Node> nodes;
    private final ObjectMapper mapper;
    private final ExecutorService pool;
    private final Duration timeout;
    private final Clock clock;
    // 관리 JVM 자신의 묶음 id. 노드 목록에 관리 JVM 이 함께 올라 있으면(한 JVM 이 앱과 관리를 겸하는 로컬 기동) 그 노드의
    // 응답은 자기 묶음과 같으므로 합치지 않는다.
    private final String selfInstance;
    private volatile Digest last = Digest.NOT_POLLED;

    /** self 는 이 관리 JVM 의 묶음이다. 없으면 앱 노드만 읽는다. */
    public ErrorLogPoller(List<CapacityProperties.Node> appNodes, ErrorLogBook self, ObjectMapper mapper,
                          ExecutorService pool, Duration timeout, Clock clock) {
        this.mapper = mapper;
        this.pool = pool;
        this.timeout = timeout;
        this.clock = clock;
        this.selfInstance = self == null ? null : self.instance();
        SourceHttp http = new SourceHttp(timeout, timeout, MAX_RESPONSE, null);
        List<Node> list = new ArrayList<>();
        if (self != null) {
            list.add(new Node(SELF, false, () -> json(self.view())));
        }
        for (CapacityProperties.Node n : appNodes) {
            if (n.getBaseUrl() == null || n.getBaseUrl().isBlank()) {
                continue;
            }
            URI uri = URI.create(MspProperties.sourceUri("capacity.nodes.base-url", n.getBaseUrl())
                    + "/actuator/errorlog");
            list.add(new Node(n.getId(), n.isScalable(), () -> {
                SourceHttp.Response r = http.get(uri);
                if (r.status() != 200) {
                    throw new SourceException("http_status", false);
                }
                return r.body();
            }));
        }
        this.nodes = List.copyOf(list);
    }

    public void start(ScheduledExecutorService scheduler, Duration interval) {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                poll();
            } catch (RuntimeException e) {
                log.warn("오류 로그 수집 실패: {}", e.toString());
            }
        }, 10, interval.toSeconds(), TimeUnit.SECONDS);
    }

    public Digest last() {
        return last;
    }

    /**
     * 원천을 모두 읽어 합친다. 한 원천의 실패는 그 원천만 UNAVAILABLE 로 적는다. 조회 전용 노드가 닿지 않으면(unreachable)
     * 꺼진 것으로 보고 OFF 로 적는다. 켜져 있어야 할 노드가 죽은 것은 인프라 칸과 경보가 알린다.
     */
    public Digest poll() {
        Instant now = clock.instant();
        Map<Node, Future<byte[]>> pending = new LinkedHashMap<>();
        for (Node n : nodes) {
            pending.put(n, pool.submit(() -> n.fetch().get()));
        }
        List<Source> sources = new ArrayList<>();
        Map<String, Merge> merged = new LinkedHashMap<>();
        long deadline = System.nanoTime() + timeout.multipliedBy(2).toNanos();
        String failure = null;
        int ok = 0;
        long omitted = 0;
        for (Map.Entry<Node, Future<byte[]>> e : pending.entrySet()) {
            String name = e.getKey().name();
            String reason;
            try {
                byte[] body = e.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                Read r = read(name, body, merged);
                sources.add(new Source(name, "READY", r.sameJvm() ? "same_jvm" : null));
                omitted += r.omitted();
                ok++;
                continue;
            } catch (TimeoutException ex) {
                e.getValue().cancel(true);
                reason = "timeout";
            } catch (ExecutionException ex) {
                reason = ex.getCause() instanceof SourceException s ? s.reason() : "bad_response";
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                reason = "timeout";
            } catch (IOException ex) {
                reason = "bad_response";
            }
            if (e.getKey().optional() && "unreachable".equals(reason)) {
                sources.add(new Source(name, "OFF", reason));
                continue;
            }
            sources.add(new Source(name, "UNAVAILABLE", reason));
            failure = reason;
        }
        List<Group> groups = new ArrayList<>();
        merged.values().forEach(m -> groups.add(m.group()));
        groups.sort(ORDER);
        Digest d = new Digest(now, List.copyOf(sources), List.copyOf(groups), ok == 0 && !nodes.isEmpty() ? failure
                : null, omitted);
        last = d;
        return d;
    }

    /** 한 원천을 읽은 결과. sameJvm 이면 관리 JVM 자신이라 합치지 않았다. omitted 는 노드가 상한 때문에 싣지 않은 묶음 수다. */
    private record Read(boolean sameJvm, long omitted) {
    }

    /** ERROR 를 먼저, 그다음 15분 건수가 많고 최근인 순. 한 판의 여덟 묶음이 이 순서다. */
    static final Comparator<Group> ORDER = Comparator.comparing((Group g) -> !"ERROR".equals(g.level()))
            .thenComparing(Group::count, Comparator.reverseOrder())
            .thenComparing(Group::lastSeen, Comparator.reverseOrder());

    /** 한 원천의 묶음을 합친다. 관리 JVM 자신을 노드로 한 번 더 읽은 응답이면 합치지 않는다. */
    private Read read(String node, byte[] body, Map<String, Merge> merged) throws IOException {
        JsonNode root = mapper.readTree(body);
        if (root == null || !"errorlog/1".equals(root.path("schema").asText()) || !root.path("groups").isArray()) {
            throw new IOException("bad_response");
        }
        if (!SELF.equals(node) && selfInstance != null && selfInstance.equals(root.path("instance").asText(null))) {
            return new Read(true, 0);
        }
        for (JsonNode g : root.path("groups")) {
            String id = g.path("id").asText("");
            String level = g.path("level").asText("");
            if (!ID.matcher(id).matches() || !(level.equals("WARN") || level.equals("ERROR"))) {
                continue;
            }
            Instant first = time(g.path("first_seen").asText(null));
            Instant lastSeen = time(g.path("last_seen").asText(null));
            long count = g.path("count").asLong(0);
            if (first == null || lastSeen == null || count <= 0) {
                continue;
            }
            merged.computeIfAbsent(id, k -> new Merge(id, level)).add(node, g, count, g.path("total").asLong(count),
                    first, lastSeen);
        }
        return new Read(false, Math.max(0, root.path("omitted").asLong(0)));
    }

    private static Instant time(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private byte[] json(Map<String, Object> view) {
        try {
            return mapper.writeValueAsBytes(view);
        } catch (IOException e) {
            throw new IllegalStateException("오류 로그 묶음 직렬화 실패", e);
        }
    }

    /** 같은 id 의 노드별 묶음을 합친다. 대표 문장은 마지막으로 본 노드의 것이다. */
    private static final class Merge {

        private final String id;
        private final String level;
        private String logger;
        private String template;
        private String exception;
        private String exceptionMessage;
        private String frame;
        private String message;
        private long count;
        private long total;
        private Instant firstSeen;
        private Instant lastSeen;
        private final TreeSet<String> nodes = new TreeSet<>();

        Merge(String id, String level) {
            this.id = id;
            this.level = level;
        }

        void add(String node, JsonNode g, long count, long total, Instant first, Instant last) {
            this.count += count;
            this.total += Math.max(total, count);
            this.nodes.add(node);
            this.firstSeen = firstSeen == null || first.isBefore(firstSeen) ? first : firstSeen;
            if (lastSeen == null || last.isAfter(lastSeen)) {
                lastSeen = last;
                logger = shortName(name(g, "logger"));
                template = text(g, "template");
                exception = shortName(name(g, "exception"));
                exceptionMessage = text(g, "exception_message");
                frame = name(g, "frame");
                message = text(g, "message");
            }
        }

        Group group() {
            return new Group(id, level, logger, template, exception, exceptionMessage, frame, message, count, total,
                    firstSeen, lastSeen, List.copyOf(nodes));
        }

        /**
         * 노드가 보낸 문장. 제어 문자를 지우고 가리기를 한 번 더 건 뒤 자른다. 노드가 이미 가렸지만, 가리기가 없거나 약한 이전
         * 판의 노드가 섞여 있어도 가리지 않은 문장이 화면과 모델로 가지 않게 한다.
         */
        private static String text(JsonNode g, String field) {
            String v = g.path(field).asText(null);
            if (v == null || v.isBlank()) {
                return null;
            }
            return ErrorLogBook.clean(AiOutputValidator.clean(v), MAX_TEXT, MAX_TEXT * 3);
        }

        private static String name(JsonNode g, String field) {
            String v = g.path(field).asText(null);
            return v != null && NAME.matcher(v).matches() ? v : null;
        }

        private static String shortName(String className) {
            return className == null ? null : className.substring(className.lastIndexOf('.') + 1);
        }
    }
}
