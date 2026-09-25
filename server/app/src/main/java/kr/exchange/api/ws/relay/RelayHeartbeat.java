package kr.exchange.api.ws.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.ws.MarketMirror;
import kr.exchange.engine.LeaderTerm;
import kr.exchange.engine.PartitionLeases;
import kr.exchange.engine.PartitionRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * 소유 노드 하트비트(S6 설계 4-4). 이 노드가 접수 게이트를 연 파티션마다 설정 간격(기본 200ms, 2026-09-25 관문 주행 뒤 1초에서
 * 줄였다)으로 하트비트 봉투를 게시와 같은 발행 큐에
 * 넣는다. 본문은 그 파티션 마켓마다 이 기간에 마지막으로 게시한 호가·티커의 [epoch, seq] 다.
 *
 * 게시기가 마지막 게시를 넣기 시도 뒤에 적으므로, 하트비트가 알린 게시는 그 하트비트보다 먼저 큐에 들어갔거나 버려진 것이다.
 * 받는 쪽은 미러가 알린 seq 까지 따라잡지 못했으면 그 사이 게시를 잃었다고 본다. 게이트가 닫힌 파티션(승격 재생 중, 강등)은
 * 보내지 않는다.
 */
public final class RelayHeartbeat implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RelayHeartbeat.class);

    private final String holder;
    private final PartitionLeases leases;
    private final MarketDirectory markets;
    // (채널, 마켓) -> 이 노드의 마지막 게시 [epoch, seq]. 게시한 적이 없으면 null.
    private final BiFunction<MarketMirror.Channel, String, long[]> lastPublished;
    private final RelayPublisher relay;
    private final ObjectMapper json;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    // 파티션마다 지금 기간과 그 기간의 하트비트 번호. 하트비트 스레드만 쓴다.
    private final Map<String, LeaderTerm> terms = new HashMap<>();
    private final Map<String, Long> seqs = new HashMap<>();

    public RelayHeartbeat(String holder, PartitionLeases leases, MarketDirectory markets,
                          BiFunction<MarketMirror.Channel, String, long[]> lastPublished, RelayPublisher relay,
                          ObjectMapper json, Duration interval) {
        this.holder = holder;
        this.leases = leases;
        this.markets = markets;
        this.lastPublished = lastPublished;
        this.relay = relay;
        this.json = json;
        this.interval = interval;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-relay-hb");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                beat();
            } catch (RuntimeException e) {
                log.warn("중계 하트비트 실패: {}", e.getMessage());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 게이트를 연 파티션마다 하트비트 하나를 넣는다. 시험은 스케줄 없이 직접 부른다. */
    void beat() {
        for (PartitionRuntime runtime : leases.runtimes()) {
            String partition = runtime.resource();
            LeaderTerm term = runtime.coordinator().openTerm();
            if (term == null) {
                terms.remove(partition);
                continue;
            }
            if (terms.get(partition) != term) {
                terms.put(partition, term);
                seqs.put(partition, 0L);
            }
            long seq = seqs.merge(partition, 1L, Long::sum);
            relay.offer(RelayEnvelope.heartbeat(holder, partition, term.epoch(), seq,
                    body(partition, term.epoch()).encode(json)));
        }
    }

    private RelayEnvelope.Heartbeat body(String partition, long epoch) {
        Map<String, long[]> orderbook = new LinkedHashMap<>();
        Map<String, long[]> ticker = new LinkedHashMap<>();
        for (MarketDirectory.Market m : markets.all()) {
            if (!partition.equals(leases.plan().partitionOf(m.id()))) {
                continue;
            }
            long[] ob = lastPublished.apply(MarketMirror.Channel.ORDERBOOK, m.symbol());
            if (ob != null && ob[0] == epoch) {
                orderbook.put(m.symbol(), ob);
            }
            long[] tk = lastPublished.apply(MarketMirror.Channel.TICKER, m.symbol());
            if (tk != null && tk[0] == epoch) {
                ticker.put(m.symbol(), tk);
            }
        }
        return new RelayEnvelope.Heartbeat(orderbook, ticker);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
