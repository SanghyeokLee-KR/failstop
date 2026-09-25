package kr.exchange.api;

import org.springframework.web.socket.CloseStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** WS 수용 판정을 밖에서 보는 도구(S6 설계 6-2). 핸드셰이크 응답의 상태·머리글과 열린 세션의 닫힘 코드를 잰다. */
public final class WsProbe {

    private WsProbe() {
    }

    public record Handshake(int status, Map<String, String> headers) {
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    /** /ws/v1 업그레이드 요청을 보내고 응답 머리만 읽는다. origin 이 null 이면 Origin 머리글을 싣지 않는다. */
    public static Handshake handshake(int port, String origin) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            byte[] nonce = new byte[16];
            new java.security.SecureRandom().nextBytes(nonce);
            String request = "GET /ws/v1 HTTP/1.1\r\nHost: localhost:" + port + "\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Key: " + Base64.getEncoder().encodeToString(nonce) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n" + (origin == null ? "" : "Origin: " + origin + "\r\n") + "\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = in.readLine();
            Map<String, String> headers = new LinkedHashMap<>();
            for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                }
            }
            return new Handshake(Integer.parseInt(statusLine.split(" ")[1]), headers);
        }
    }

    /** 연 WS 세션 하나. 서버가 닫으면 닫힘 코드와 이유가 남는다. */
    static final class Watched {
        final WebSocket socket;
        final AtomicReference<CloseStatus> closed;

        Watched(WebSocket socket, AtomicReference<CloseStatus> closed) {
            this.socket = socket;
            this.closed = closed;
        }
    }

    /**
     * JDK 클라이언트로 연다. Tomcat 의 JSR-356 클라이언트는 받은 1012·1013 을 1002 로 바꿔 알린다(RFC 6455 뒤에 등록된 코드).
     * 브라우저와 k6 는 1013 을 그대로 받는다.
     */
    static Watched open(int port) throws Exception {
        AtomicReference<CloseStatus> closed = new AtomicReference<>();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/v1"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        closed.set(new CloseStatus(statusCode, reason));
                        return null;
                    }
                }).get(10, TimeUnit.SECONDS);
        return new Watched(socket, closed);
    }
}
