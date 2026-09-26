package kr.exchange.admin.msp;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 원천(Prometheus, Alertmanager)을 대신하는 로컬 HTTP 스텁. 받은 요청을 기록하고, 응답은 시험이 바꿔 끼운다.
 * 실제 원천을 부르지 않는다.
 */
final class StubSource implements AutoCloseable {

    record Request(String method, String path, String query, String body, Map<String, String> headers) {

        /** query 파라미터 하나를 풀어 돌려준다. */
        String param(String name) {
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }

    record Response(int status, String body, long delayMs, String location) {

        static Response json(String body) {
            return new Response(200, body, 0, null);
        }

        static Response status(int status, String body) {
            return new Response(status, body, 0, null);
        }
    }

    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "stub-source");
        t.setDaemon(true);
        return t;
    });
    final List<Request> requests = new CopyOnWriteArrayList<>();
    volatile Function<Request, Response> handler = r -> Response.status(404, "{}");

    StubSource() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            URI uri = exchange.getRequestURI();
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
            Request request = new Request(exchange.getRequestMethod(), uri.getPath(), uri.getRawQuery(), body,
                    Map.copyOf(headers));
            requests.add(request);
            Response response;
            try {
                response = handler.apply(request);
            } catch (RuntimeException e) {
                response = Response.status(500, "stub failure");
            }
            if (response.delayMs() > 0) {
                try {
                    Thread.sleep(response.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            if (response.location() != null) {
                exchange.getResponseHeaders().add("Location", response.location());
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } catch (IOException ignored) {
                // 시간 초과 시험에서는 클라이언트가 먼저 끊는다.
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    List<Request> requests(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
