package kr.exchange.api.ws.relay;

import org.awaitility.Awaitility;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 시험 쪽 버스 연결. 관측자는 봉투 토픽을 구독해 받은 봉투를 모으고, 원격 노드 흉내는 봉투를 보낸다. 앱과 같은 연결 코드
 * (BrokerLink)를 쓰지만 앱 문맥 밖의 별도 연결이다.
 */
public final class BusClient implements AutoCloseable {

    /** 받은 봉투 하나. 머리글과 본문 그대로다. */
    public record Received(Map<String, String> headers, byte[] body, long receivedAtMillis) {
        public String h(String name) {
            return headers.get(name);
        }
    }

    private final BrokerLink link;
    public final List<Received> received = new CopyOnWriteArrayList<>();

    private BusClient(String name, BrokerLink.Role role, WsRelayProperties props) {
        this.link = new BrokerLink(name, role, props, new BrokerLink.Listener() {
            @Override
            public void onMessage(Map<String, String> headers, byte[] body, String ackId) {
                received.add(new Received(headers, body, System.currentTimeMillis()));
                // 누적 확인을 건마다 보낸다. 관측자가 선반입 상한에 막히지 않게 한다.
                BusClient.this.link.ack(ackId);
            }
        });
    }

    public static BusClient observer(RelayBroker broker) {
        BusClient c = new BusClient("test-sub", BrokerLink.Role.SUBSCRIBE, broker.clientProperties());
        c.link.start();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(c.link::connected);
        return c;
    }

    public static BusClient publisher(RelayBroker broker) {
        BusClient c = new BusClient("test-pub", BrokerLink.Role.PUBLISH, broker.clientProperties());
        c.link.start();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(c.link::connected);
        return c;
    }

    public boolean connected() {
        return link.connected();
    }

    /** 쓰기 완료까지 기다린다. */
    public void send(RelayEnvelope envelope) {
        try {
            CompletableFuture<Void> f = link.send(envelope);
            f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("시험 봉투 발행 실패", e);
        }
    }

    public List<Received> matching(Predicate<Received> filter) {
        return received.stream().filter(filter).toList();
    }

    @Override
    public void close() {
        link.close();
    }
}
