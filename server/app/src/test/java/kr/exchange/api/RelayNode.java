package kr.exchange.api;

import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.ExchangeApplication;
import kr.exchange.api.ws.WsGate;
import kr.exchange.api.ws.relay.RelayBroker;
import kr.exchange.engine.PartitionLeases;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 한 JVM 안에 띄운 앱 노드 하나(S6 설계 6-2 두 노드 문맥). 같은 PostgreSQL 과 브로커를 나눠 쓴다. 명령줄 인자로 넘겨 application.yml
 * 보다 우선하게 한다. 문맥을 닫으면 리스를 반납하지 않으므로 kill 흉내다.
 */
final class RelayNode implements AutoCloseable {

    static final String MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    final String name;
    final ConfigurableApplicationContext context;
    private final HttpClient http = HttpClient.newHttpClient();

    private RelayNode(String name, ConfigurableApplicationContext context) {
        this.name = name;
        this.context = context;
    }

    /** broker 가 null 이면 simple 모드다. */
    static RelayNode start(String name, String jdbcUrl, String user, String password, RelayBroker broker,
                           Duration leaseTtl, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + user,
                "--spring.datasource.password=" + password,
                "--spring.flyway.enabled=false",
                "--exchange.auth.master-key-base64=" + MASTER_KEY,
                "--exchange.order.rate-limit.capacity=100000",
                "--exchange.engine.lease-ttl=" + leaseTtl.toMillis() + "ms",
                "--exchange.engine.lease-tick-interval=500ms",
                "--exchange.engine.lease-acquire-lock-timeout=300ms",
                "--exchange.node.id=" + name));
        if (broker != null) {
            broker.springProperties().forEach((k, v) -> args.add("--" + k + "=" + v));
        }
        args.addAll(List.of(extra));
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ExchangeApplication.class)
                .run(args.toArray(String[]::new));
        return new RelayNode(name, ctx);
    }

    int port() {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    PartitionLeases leases() {
        return context.getBean(PartitionLeases.class);
    }

    boolean leader() {
        return leases().openTerm(1) != null;
    }

    WsGate gate() {
        return context.getBean(WsGate.class);
    }

    MeterRegistry meters() {
        return context.getBean(MeterRegistry.class);
    }

    double gauge(String name, String tag, String value) {
        var search = meters().find(name);
        var g = (tag == null ? search : search.tag(tag, value)).gauge();
        return g == null ? -1 : g.value();
    }

    String get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    int status(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port() + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    HttpResponse<String> post(String path, byte[] body, String authorization) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port() + path))
                        .header("Authorization", authorization).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        if (context.isActive()) {
            context.close();
        }
    }
}
