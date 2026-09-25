package kr.exchange.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import kr.exchange.api.auth.ApiAuthService;
import kr.exchange.api.auth.ApiKeyDirectory;
import kr.exchange.api.auth.CanonicalRequests;
import kr.exchange.api.auth.NonceStore;
import kr.exchange.api.auth.SecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 봇이 주문 생성과 취소에 마켓 힌트를 싣는다(API 설계 §2-5-d, S5 설계 4-9·6-2). 힌트는 서명 밖이라 canonical 을 바꾸지 않는다.
 * 가짜 HTTP 서버가 받은 요청을 서버와 같은 규칙으로 검증하면, 힌트 값을 바꿔도 서명 검증이 통과한다.
 */
class BotApiClientHintTest {

    private record Captured(String method, String path, String hint, String authorization, byte[] body) {
    }

    private HttpServer server;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Exchange-Market"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestBody().readAllBytes()));
            byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void placeAndCancelCarryTheHintOutsideTheSignature() throws Exception {
        String secret = "bot-secret-0123456789";
        BotApiClient client = new BotApiClient("http://127.0.0.1:" + server.getAddress().getPort(),
                new BotCredentials(7, "bot@sim.test", "AKbot", secret), "hint");
        String body = "{\"market\":\"KRW-ETH\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                + "\"volume\":\"0.1\",\"client_order_id\":\"bot-1\"}";

        client.placeOrder("KRW-ETH", body);
        client.cancelOrder("KRW-ETH", "9f2b7a4e-3d1c-4b0e-9a77-5c2f8e1d6a3b");

        assertEquals(2, captured.size());
        Captured place = captured.get(0);
        Captured cancel = captured.get(1);
        assertEquals("KRW-ETH", place.hint());
        assertEquals("KRW-ETH", cancel.hint());
        ApiAuthService verifier = verifier(secret);
        // 서버는 힌트를 canonical 에 넣지 않는다. 힌트가 무엇이든 같은 canonical 로 검증된다.
        verifier.verify(place.authorization(), CanonicalRequests.forBody("POST", place.path(), place.body()), true);
        verifier.verify(cancel.authorization(),
                CanonicalRequests.forQuery("DELETE", cancel.path(), new TreeMap<>()), true);
    }

    /** 가짜 키 저장소로 서버와 같은 검증을 한다. 봇의 키는 TRADE 다. */
    private static ApiAuthService verifier(String secret) {
        SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        String encrypted = cipher.encrypt(secret);
        ApiKeyDirectory keys = new ApiKeyDirectory(null) {
            @Override
            public Optional<ApiKey> findActive(String accessKey) {
                return Optional.of(new ApiKey(7, accessKey, encrypted, "TRADE"));
            }
        };
        return new ApiAuthService(keys, cipher, new NonceStore(), new ObjectMapper(), null, null);
    }
}
