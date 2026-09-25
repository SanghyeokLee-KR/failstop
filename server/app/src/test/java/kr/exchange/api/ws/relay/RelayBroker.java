package kr.exchange.api.ws.relay;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 시험용 중계 브로커(S6 설계 6-1). 하네스 중계 오버레이와 같은 이미지와 설정 파일(harness/relay)로 일회용 RabbitMQ 를 띄운다.
 * 모듈 의존을 더하지 않고 GenericContainer 로 띄운다.
 *
 * CLI 는 rabbitmq 사용자로 부른다. root 로 부르면 쿠키 파일을 root 소유로 만들어 서버가 쿠키를 읽지 못한다(증거 pml-s6-probe-20260925).
 */
public final class RelayBroker implements AutoCloseable {

    public static final String IMAGE = "rabbitmq:4.3.6-alpine";
    public static final String LOGIN = "exch-relay";
    // 일회용 컨테이너의 시험 값이다. 운영 비밀 값이 아니다.
    public static final String PASSCODE = "s6-relay-test-only";
    private static final int STOMP = 61613;

    private final GenericContainer<?> container;

    private RelayBroker(GenericContainer<?> container) {
        this.container = container;
    }

    public static RelayBroker start() {
        Path relay = relayDir();
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withEnv("RABBITMQ_DEFAULT_USER", LOGIN)
                .withEnv("RABBITMQ_DEFAULT_PASS", PASSCODE)
                .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "+S 2:2")
                .withCopyFileToContainer(MountableFile.forHostPath(relay.resolve("enabled_plugins")),
                        "/etc/rabbitmq/enabled_plugins")
                .withCopyFileToContainer(MountableFile.forHostPath(relay.resolve("rabbitmq.conf")),
                        "/etc/rabbitmq/rabbitmq.conf")
                .withExposedPorts(STOMP)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
        c.start();
        return new RelayBroker(c);
    }

    private static Path relayDir() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("harness/relay/rabbitmq.conf"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("harness/relay/rabbitmq.conf 를 찾지 못했다");
        }
        return root.resolve("harness/relay");
    }

    public String host() {
        return container.getHost();
    }

    public int port() {
        return container.getMappedPort(STOMP);
    }

    /** 앱 문맥에 넣을 중계 설정. */
    public Map<String, String> springProperties() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("exchange.ws.relay.mode", "broker");
        p.put("exchange.ws.relay.broker.host", host());
        p.put("exchange.ws.relay.broker.port", String.valueOf(port()));
        p.put("exchange.ws.relay.broker.passcode", PASSCODE);
        return p;
    }

    /** 같은 브로커에 붙는 시험 쪽 연결(관측자, 원격 노드 흉내)의 설정. */
    public WsRelayProperties clientProperties() {
        return new WsRelayProperties(WsRelayProperties.Mode.BROKER,
                new WsRelayProperties.Broker(host(), port(), "exchange", LOGIN, PASSCODE, Duration.ofSeconds(5)),
                null, null, 0, null, null, null, null, null,
                new WsRelayProperties.Reconnect(Duration.ofMillis(300), Duration.ofSeconds(2)));
    }

    public String ctl(String... args) {
        String[] command = new String[args.length + 2];
        command[0] = "rabbitmqctl";
        command[1] = "-q";
        System.arraycopy(args, 0, command, 2, args.length);
        try {
            Container.ExecResult r = container.execInContainerWithUser("rabbitmq", command);
            if (r.getExitCode() != 0) {
                throw new IllegalStateException("rabbitmqctl " + String.join(" ", args) + " 실패: " + r.getStderr());
            }
            return r.getStdout();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 브로커 애플리케이션을 멈춘다. 모든 연결이 닫히고 STOMP 수신이 멈춘다. 컨테이너와 포트는 그대로다. */
    public void stopApp() {
        ctl("stop_app");
    }

    public void startApp() {
        ctl("start_app");
        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Container.ExecResult r = container.execInContainerWithUser("rabbitmq",
                        "rabbitmq-diagnostics", "-q", "check_port_listener", String.valueOf(STOMP));
                if (r.getExitCode() == 0) {
                    return;
                }
                Thread.sleep(500);
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("브로커 STOMP 수신이 다시 열리지 않았다");
    }

    /** 메모리 경보. 발행 연결의 읽기를 막고 구독 연결은 살려 둔다(증거 R8·R11a). */
    public void alarm(boolean on) {
        ctl("set_vm_memory_high_watermark", "absolute", on ? "1MiB" : "192MiB");
    }

    @Override
    public void close() {
        container.stop();
    }
}
