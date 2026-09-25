package kr.exchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.WriteAvailability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 쓰기 게이트의 대상 경로(API 설계 §2-5-d). 대상은 필터 등록이 정하고, 컨테이너는 정규화한 경로로 등록과 서블릿 매핑을 함께
 * 고른다. 실제 Tomcat 에 운영과 같은 등록(WriteAvailabilityConfig.writeGateFilter)과 '/' 서블릿(DispatcherServlet 자리)을
 * 올리고 원시 요청 줄을 보낸다. 서블릿에 닿는 쓰기는 경로를 어떻게 적어도 게이트를 지나야 한다.
 */
class WriteGateContainerPathTest {

    private static final String REACHED = "servlet-reached";

    private WebServer server;

    @BeforeEach
    void startSuspendedGate() {
        WriteAvailability availability = WriteAvailability.enabled(WriteAvailability.Settings.DEFAULTS,
                () -> 1_000_000_000L, () -> 1_790_000_000_000L);
        availability.settingRead("*", false);
        assertTrue(availability.isSuspended());
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.setStatus(200);
                response.getWriter().write(REACHED + " " + request.getServletPath());
            }
        };
        server = new TomcatServletWebServerFactory(0).getWebServer(
                new WriteAvailabilityConfig().writeGateFilter(availability, new ErrorRenderer(new ObjectMapper()),
                        new LeaderWriteProgress(), new SimpleMeterRegistry()),
                new ServletRegistrationBean<>(servlet, "/"));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
        server.destroy();
    }

    @Test
    void writesThatReachTheServletAreGatedHoweverThePathIsWritten() throws IOException {
        String[][] writes = {
                {"POST", "/api/v1/orders"},
                {"POST", "/api/v1;x=1/orders"},
                {"POST", "/api/%761/orders"},
                {"POST", "//api/v1/orders"},
                {"POST", "/api/./v1/orders"},
                {"POST", "/x/../api/v1/orders"},
                {"POST", "/api/v1;x=1/auth/login"},
                {"POST", "/api/v1;x=1/community/boards/KRW-BTC/posts"},
                {"DELETE", "/api/%76%31/orders/abc"},
                {"PUT", "/admin/v1;a=b/sim/effects"},
                {"PATCH", "/admin/%76%31/members/1"},
        };
        for (String[] write : writes) {
            String response = send(write[0], write[1]);
            assertEquals(423, status(response), write[0] + " " + write[1] + " 가 게이트를 비껴갔다: " + response);
            assertTrue(response.contains("trading_suspended"), write[0] + " " + write[1]);
            assertFalse(response.contains(REACHED), write[0] + " " + write[1] + " 가 서블릿에 닿았다");
        }
    }

    @Test
    void pathsOutsideTheRegistrationAndSafeMethodsReachTheServlet() throws IOException {
        String[][] passing = {
                {"POST", "/harness/v1/invariants/run"},
                {"POST", "/actuator/partitions"},
                {"GET", "/api/v1;x=1/orders/open"},
                {"GET", "/api/v1/status"},
                {"OPTIONS", "/api/%761/orders"},
        };
        for (String[] request : passing) {
            String response = send(request[0], request[1]);
            assertEquals(200, status(response), request[0] + " " + request[1] + ": " + response);
            assertTrue(response.contains(REACHED), request[0] + " " + request[1]);
        }
        assertTrue(send("GET", "/api/%761/orders/open").contains(REACHED + " /api/v1/orders/open"),
                "컨테이너는 퍼센트 인코딩을 푼 경로로 서블릿을 고른다. 이 전제가 깨지면 위 423 이 공허하다");
    }

    /** 원시 요청 줄을 그대로 보낸다. HTTP 클라이언트는 경로를 다시 인코딩하거나 정규화할 수 있다. */
    private String send(String method, String rawPath) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + rawPath + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int status(String response) {
        return Integer.parseInt(response.substring("HTTP/1.1 ".length(), "HTTP/1.1 ".length() + 3));
    }
}
