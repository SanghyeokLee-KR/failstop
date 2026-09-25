package kr.exchange.api.ws.relay;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 발행 연결의 끊기(S6 설계 4-4). 읽지 않는 브로커(메모리 경보 흉내) 앞에서 쓰기 대기가 1초를 넘으면 발행 연결을 SO_LINGER 0 으로
 * 끊는다. 받는 쪽은 보통 닫기(FIN)면 남은 바이트를 다 읽은 뒤 끝을 보고, SO_LINGER 0 이면 연결 재설정(RST)을 본다. 보통 닫기로 끊으면
 * 브로커가 경보가 풀린 뒤 옛 연결의 메시지를 늦게 전달한다(증거 pml-s6-probe-20260925 R11b).
 *
 * 가짜 STOMP 서버를 같은 JVM 의 루프백에 둔다. Docker 포트 전달을 거치면 앱의 RST 가 전달 프록시에서 끝나 브로커에 닿지 않으므로
 * 이 성질은 여기서 본다.
 */
class BrokerLinkAbortTest {

    @Test
    void writeTimeoutAbortsThePublishLinkWithRstAndReconnects() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            server.setReceiveBufferSize(16 * 1024);
            CountDownLatch stalled = new CountDownLatch(1);
            CountDownLatch drain = new CountDownLatch(1);
            CompletableFuture<String> outcome = new CompletableFuture<>();
            AtomicInteger accepted = new AtomicInteger();
            Thread broker = new Thread(() -> {
                try {
                    Socket first = server.accept();
                    accepted.incrementAndGet();
                    readFrame(first.getInputStream());
                    OutputStream out = first.getOutputStream();
                    out.write("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    stalled.countDown();
                    drain.await(30, TimeUnit.SECONDS);
                    outcome.complete(readToEnd(first.getInputStream()));
                    while (!Thread.currentThread().isInterrupted()) {
                        Socket next = server.accept();
                        accepted.incrementAndGet();
                        next.close();
                    }
                } catch (Exception e) {
                    outcome.completeExceptionally(e);
                }
            }, "fake-broker");
            broker.setDaemon(true);
            broker.start();

            WsRelayProperties props = new WsRelayProperties(WsRelayProperties.Mode.BROKER,
                    new WsRelayProperties.Broker("127.0.0.1", server.getLocalPort(), "exchange", "u", "p", Duration.ZERO),
                    null, null, 0, null, null, null, null, null,
                    new WsRelayProperties.Reconnect(Duration.ofMillis(200), Duration.ofMillis(500)), null);
            BrokerLink link = new BrokerLink("abort-test", BrokerLink.Role.PUBLISH, props, new BrokerLink.Listener() { });
            RelayPublisher publisher = new RelayPublisher(link, 100, Duration.ofSeconds(1), Duration.ofSeconds(60),
                    () -> System.nanoTime() / 1_000_000);
            try {
                link.start();
                assertTrue(stalled.await(20, TimeUnit.SECONDS));
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(link::connected);
                publisher.start();
                byte[] big = new byte[32 * 1024];
                java.util.Arrays.fill(big, (byte) 'x');
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (publisher.writeTimeouts() == 0 && System.nanoTime() < deadline) {
                    publisher.offer(RelayEnvelope.topic("node-a", "/topic/trade.KRW-BTC", "KRW-BTC", 1, 1, big));
                    Thread.sleep(2);
                }
                assertEquals(1, publisher.writeTimeouts(), "읽지 않는 브로커 앞에서 쓰기 대기 상한이 걸리지 않았다");
                drain.countDown();
                assertEquals("reset", outcome.get(20, TimeUnit.SECONDS),
                        "발행 연결을 SO_LINGER 0(RST)이 아니라 보통 닫기로 끊었다");
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> accepted.get() >= 2);
            } finally {
                publisher.close();
                link.close();
                broker.interrupt();
            }
        }
    }

    /**
     * TCP 는 받지만 CONNECTED 전에 닫는 상대(브로커 앞의 포트 전달·TCP 로드밸런서, 기동 중인 브로커). 그런 시도는 끊김으로 알리지 않는다.
     * 알리면 구독 쪽의 끊긴 시각이 시도마다 새로 적혀, 재연결 간격이 5초보다 짧을 때 link_down 이 켜지지 않는다. 대조군으로 CONNECTED 까지
     * 받은 연결이 닫히면 한 번 알린다.
     */
    @Test
    void closeBeforeConnectedIsNotReportedAsADisconnect() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger connected = new AtomicInteger();
            AtomicInteger disconnected = new AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean answer = new java.util.concurrent.atomic.AtomicBoolean();
            Thread broker = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Socket s = server.accept();
                        accepted.incrementAndGet();
                        if (answer.get()) {
                            readFrame(s.getInputStream());
                            OutputStream out = s.getOutputStream();
                            out.write("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            Thread.sleep(300);
                        }
                        s.close();
                    }
                } catch (Exception e) {
                    // 시험이 끝나 닫혔다.
                }
            }, "fake-proxy");
            broker.setDaemon(true);
            broker.start();

            WsRelayProperties props = new WsRelayProperties(WsRelayProperties.Mode.BROKER,
                    new WsRelayProperties.Broker("127.0.0.1", server.getLocalPort(), "exchange", "u", "p", Duration.ZERO),
                    null, null, 0, null, null, null, null, null,
                    new WsRelayProperties.Reconnect(Duration.ofMillis(100), Duration.ofMillis(200)), null);
            BrokerLink link = new BrokerLink("proxy-test", BrokerLink.Role.SUBSCRIBE, props, new BrokerLink.Listener() {
                @Override
                public void onConnected() {
                    connected.incrementAndGet();
                }

                @Override
                public void onDisconnected() {
                    disconnected.incrementAndGet();
                }
            });
            try {
                link.start();
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> accepted.get() >= 5);
                assertEquals(0, connected.get());
                assertEquals(0, disconnected.get(), "CONNECTED 전에 닫힌 시도를 끊김으로 알렸다");

                answer.set(true);
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> connected.get() >= 1 && disconnected.get() >= 1);
                assertEquals(connected.get(), disconnected.get(), "붙었던 연결의 닫힘은 한 번씩 알려야 한다");
            } finally {
                link.close();
                broker.interrupt();
            }
        }
    }

    /** 대조군: 판별기가 보통 닫기를 fin 으로 본다. 판별기가 늘 reset 을 내면 위 시험이 공허하게 통과한다. */
    @Test
    void ordinaryCloseIsSeenAsFin() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> outcome = CompletableFuture.supplyAsync(() -> {
                try (Socket s = server.accept()) {
                    return readToEnd(s.getInputStream());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.getOutputStream().write(new byte[4096]);
            }
            assertEquals("fin", outcome.get(10, TimeUnit.SECONDS));
        }
    }

    private static void readFrame(InputStream in) throws IOException {
        for (int b = in.read(); b != 0; b = in.read()) {
            if (b < 0) {
                throw new IOException("CONNECT 전에 끊겼다");
            }
        }
    }

    /** 끝(FIN)이면 "fin", 재설정(RST)이면 "reset". */
    private static String readToEnd(InputStream in) throws IOException {
        byte[] buf = new byte[64 * 1024];
        try {
            while (in.read(buf) >= 0) {
                // 남은 바이트를 버린다.
            }
            return "fin";
        } catch (SocketException e) {
            return "reset";
        }
    }
}
