package kr.exchange.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 앱 계층 노드 인벤토리의 관측면. "실제로 무엇이 떠 있고 어떤 상태인가" 를 매번 물어본다.
 *
 * 적재하지 않는 이유는 CapacityProperties 주석에 적었다. 계약을 저장하면 드리프트한다.
 * 여기서는 그 반대의 위험을 적는다. 관측을 저장하면 낡은 값이 현재로 읽힌다. 그래서 화면이
 * 열릴 때마다 실제로 물어보고, 못 물어보면 못 물어봤다고 적는다. 마지막 성공값을 대신 내지 않는다.
 *
 * 리더 판정을 별도 조회로 하지 않는다. readiness 가 그 답을 이미 준다. 리더만 200 이고
 * 팔로워와 승격 대기와 강등 노드는 503 이다. 조회 가능 여부는 read 그룹이 답한다. 팔로워는
 * readiness 503 이면서 read 200 이고, 그것이 정상이다.
 *
 * 노드를 순차로 물으면 한 대가 죽어 있을 때 그 타임아웃이 전체 응답에 그대로 더해진다.
 * 병렬로 던지고 각자 타임아웃을 건다.
 */
public class NodeProbe {

    private final HttpClient http;
    private final Duration timeout;

    public NodeProbe(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 관측 결과 한 대분.
     *
     * @param reachable       액추에이터가 응답했는가. false 면 나머지 값은 뜻이 없다
     * @param leader          readiness 200. 쓰기를 받는 노드다
     * @param servesReads     read 그룹 200. 조회 타겟그룹에 남을 수 있는 상태다
     * @param cpuRatio        할당 코어 대비 사용률 0~1. 코어 수로 이미 나눈 값이다
     * @param cpuCount        그 노드가 보는 코어 수
     * @param heapUsedBytes   힙 사용량
     * @param heapMaxBytes    힙 상한. 동시 접속 상한이 이 값에서 유도된다
     * @param httpRequests    누적 요청 수. 초당 값은 화면이 두 표본의 차로 낸다
     * @param wsClients       동시 WebSocket 연결 수. 미노출이면 null
     */
    public record Observed(boolean reachable, boolean leader, boolean servesReads,
                           Double cpuRatio, Integer cpuCount,
                           Long heapUsedBytes, Long heapMaxBytes,
                           Double httpRequests, Double wsClients) {

        static Observed unreachable() {
            return new Observed(false, false, false, null, null, null, null, null, null);
        }
    }

    /** 노드 전부를 동시에 재고 id 로 묶어 돌려준다. 실패한 노드는 unreachable 로 들어간다. */
    public Map<String, Observed> probeAll(List<CapacityProperties.Node> nodes) {
        Map<String, CompletableFuture<Observed>> pending = new LinkedHashMap<>();
        for (CapacityProperties.Node node : nodes) {
            pending.put(node.getId(), probe(node));
        }
        Map<String, Observed> out = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<Observed>> e : pending.entrySet()) {
            try {
                out.put(e.getKey(), e.getValue().get());
            } catch (Exception ex) {
                out.put(e.getKey(), Observed.unreachable());
            }
        }
        return out;
    }

    private CompletableFuture<Observed> probe(CapacityProperties.Node node) {
        String base = node.getBaseUrl();
        if (base == null || base.isBlank()) {
            return CompletableFuture.completedFuture(Observed.unreachable());
        }
        CompletableFuture<Integer> readiness = status(base + "/actuator/health/readiness");
        CompletableFuture<Integer> read = status(base + "/actuator/health/read");
        CompletableFuture<String> metrics = body(base + "/actuator/prometheus");
        return CompletableFuture.allOf(readiness, read, metrics)
                .thenApply(ignored -> {
                    String text = metrics.join();
                    if (text == null) {
                        // 지표를 못 읽으면 도달 불가로 본다. 헬스만 200 이고 지표가 없는 상태는
                        // 화면이 판단할 근거가 없다.
                        return Observed.unreachable();
                    }
                    return new Observed(true,
                            readiness.join() != null && readiness.join() == 200,
                            read.join() != null && read.join() == 200,
                            gauge(text, "process_cpu_usage"),
                            intGauge(text, "system_cpu_count"),
                            longSum(text, "jvm_memory_used_bytes", "area=\"heap\""),
                            longSum(text, "jvm_memory_max_bytes", "area=\"heap\""),
                            sum(text, "http_server_requests_seconds_count", null),
                            gauge(text, "exchange_ws_clients"));
                })
                .exceptionally(ex -> Observed.unreachable());
    }

    private CompletableFuture<Integer> status(String url) {
        return send(url).thenApply(r -> r == null ? null : r.statusCode()).exceptionally(ex -> null);
    }

    private CompletableFuture<String> body(String url) {
        return send(url).thenApply(r -> r == null || r.statusCode() != 200 ? null : r.body())
                .exceptionally(ex -> null);
    }

    private CompletableFuture<HttpResponse<String>> send(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .GET()
                    .build();
            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /* ===== Prometheus 텍스트 파싱 =====
     *
     * 클라이언트 라이브러리를 붙이지 않는다. 필요한 것이 값 몇 개이고, 노출 형식이
     * "이름{라벨} 값" 한 줄로 고정돼 있다. 파싱 실패는 null 로 두고 화면이 빈칸으로 낸다.
     * 지어낸 값을 내지 않는다. */

    /** 같은 이름의 마지막 표본 하나. 게이지처럼 라벨이 없거나 하나뿐인 지표에 쓴다. */
    static Double gauge(String text, String name) {
        Double last = null;
        for (String line : text.split("\n")) {
            if (!line.startsWith(name)) {
                continue;
            }
            int cut = name.length();
            if (line.length() > cut && line.charAt(cut) != ' ' && line.charAt(cut) != '{') {
                continue; // 접두사만 같은 다른 지표
            }
            Double v = tail(line);
            if (v != null) {
                last = v;
            }
        }
        return last;
    }

    static Integer intGauge(String text, String name) {
        Double v = gauge(text, name);
        return v == null ? null : (int) Math.round(v);
    }

    /** 같은 이름의 전 표본 합. 라벨로 쪼개진 지표를 합칠 때 쓴다. */
    static Double sum(String text, String name, String labelContains) {
        double total = 0;
        boolean any = false;
        for (String line : text.split("\n")) {
            if (!line.startsWith(name)) {
                continue;
            }
            int cut = name.length();
            if (line.length() > cut && line.charAt(cut) != ' ' && line.charAt(cut) != '{') {
                continue;
            }
            if (labelContains != null && !line.contains(labelContains)) {
                continue;
            }
            Double v = tail(line);
            if (v != null) {
                total += v;
                any = true;
            }
        }
        return any ? total : null;
    }

    static Long longSum(String text, String name, String labelContains) {
        Double v = sum(text, name, labelContains);
        return v == null ? null : (long) (double) v;
    }

    /** 한 줄의 마지막 공백 뒤 숫자. 값이 아니면 null 이다. */
    private static Double tail(String line) {
        int sp = line.lastIndexOf(' ');
        if (sp < 0 || sp == line.length() - 1) {
            return null;
        }
        try {
            double v = Double.parseDouble(line.substring(sp + 1).trim());
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
