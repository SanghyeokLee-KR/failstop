package kr.exchange.api.ws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.ws.relay.OwnerBeats;
import kr.exchange.engine.LeaderTerm;
import kr.exchange.engine.PartitionPlan;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/**
 * 호가·티커 REST 와 구독 즉시 스냅샷의 seq·epoch 선택(API 설계 §4-A-2·§4-A-4, S6 설계 4-6). 세 경로가 같은 선택을 쓴다.
 *
 * 그 마켓을 쥔 노드는 자기 채널 카운터와 그 기간의 epoch, 그 기간에 만든 엔진 호가를 낸다. 쥐지 않은 노드는 자기 카운터를 내지
 * 않는다. 옛 리더였던 노드의 카운터가 새 리더보다 크면 클라이언트가 그 값을 기준점으로 받고 새 리더의 메시지를 버린다. 중계 모드에서
 * 소유 노드의 하트비트가 이어지고 미러가 하트비트가 알린 마지막 게시를 따라잡았으면 미러를 내고, 아니면 DB 집계와 seq 0 이다.
 * seq 0 은 이 스냅샷이 기준점이 아니라는 표시다.
 */
public final class MarketViews {

    /** 미러를 쓰지 못한 원인. exchange.ws.mirror.fallback{reason} 의 값이다. */
    public enum Fallback {
        RELAY_DOWN("relay_down"), OWNER_SILENT("owner_silent"), BEHIND("behind"), ABSENT("absent"),
        EPOCH_MISMATCH("epoch_mismatch");

        private final String wire;

        Fallback(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** 중계 모드의 미러 입력. simple 모드면 없다. */
    public record Relay(MarketMirror mirror, OwnerBeats beats) {
    }

    private record Parsed(long epoch, long seq, Map<String, Object> body) {
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final MarketDataService data;
    private final ChannelSequences sequences;
    private final EpochSource epochs;
    private final PartitionPlan plan;
    private final IntFunction<LeaderTerm> openTerm;
    private final Relay relay;
    private final ObjectMapper json;
    private final Map<String, Parsed> parsedOrderbooks = new ConcurrentHashMap<>();
    private final AtomicLong[][] fallbacks = new AtomicLong[MarketMirror.Channel.values().length][Fallback.values().length];

    public MarketViews(MarketDataService data, ChannelSequences sequences, EpochSource epochs, PartitionPlan plan,
                       IntFunction<LeaderTerm> openTerm, Relay relay, ObjectMapper json) {
        this.data = data;
        this.sequences = sequences;
        this.epochs = epochs;
        this.plan = plan;
        this.openTerm = openTerm;
        this.relay = relay;
        this.json = json;
        for (AtomicLong[] row : fallbacks) {
            for (int i = 0; i < row.length; i++) {
                row[i] = new AtomicLong();
            }
        }
    }

    /** GET /api/v1/orderbook 본문과 구독 즉시 스냅샷 본문(stream·type 은 부르는 쪽이 붙인다). */
    public Map<String, Object> orderbook(MarketDirectory.Market m) {
        String now = ApiFormats.time(Instant.now());
        LeaderTerm term = openTerm.apply(m.id());
        if (term != null) {
            // seq 를 depth 보다 먼저 잡는다. 반대면 그 사이 게시가 seq 에만 반영돼 클라이언트가 그 갱신을 버린다(§5-2).
            long seq = sequences.current("orderbook." + m.symbol());
            return data.orderbookBody(m.symbol(), data.depthForTerm(m.id(), term.epoch()), term.epoch(), seq, now);
        }
        long epoch = epochs.epoch(m.id());
        if (relay != null) {
            Selection<Parsed> mirrored = mirroredOrderbook(m, epoch);
            if (mirrored.value() != null) {
                return new LinkedHashMap<>(mirrored.value().body());
            }
            count(MarketMirror.Channel.ORDERBOOK, mirrored.fallback());
        }
        return data.orderbookBody(m.symbol(), data.dbDepth(m.id()), epoch, 0, now);
    }

    /** GET /api/v1/ticker 원소들. 비소유 마켓의 epoch 는 한 요청 안에서 파티션마다 한 번만 읽는다. */
    public List<Map<String, Object>> tickers(List<MarketDirectory.Market> requested) {
        Map<String, Long> leaseEpochs = new HashMap<>();
        List<Map<String, Object>> rows = new java.util.ArrayList<>(requested.size());
        for (MarketDirectory.Market m : requested) {
            LeaderTerm term = openTerm.apply(m.id());
            if (term != null) {
                long seq = sequences.current("ticker." + m.symbol());
                rows.add(data.ticker(m, term.epoch(), seq, data.depthForTerm(m.id(), term.epoch())));
                continue;
            }
            String partition = plan.partitionOf(m.id());
            long epoch = leaseEpochs.computeIfAbsent(partition == null ? "" : partition, p -> epochs.epoch(m.id()));
            rows.add(foreignTicker(m, epoch));
        }
        return rows;
    }

    private Map<String, Object> foreignTicker(MarketDirectory.Market m, long epoch) {
        if (relay != null) {
            Selection<long[]> seq = mirroredTicker(m, epoch);
            if (seq.value() != null) {
                // 24시간 통계와 등락은 소유 노드와 같은 DB 경로로 계산한다. 체결 없이도 시간이 지나면 바뀐다.
                Selection<Parsed> book = mirroredOrderbook(m, epoch);
                if (book.value() != null) {
                    Map<String, String> best = best(book.value().body());
                    return data.ticker(m, epoch, seq.value()[1], best.get("bid"), best.get("ask"));
                }
                return data.ticker(m, epoch, seq.value()[1], data.dbDepth(m.id()));
            }
            count(MarketMirror.Channel.TICKER, seq.fallback());
        }
        return data.ticker(m, epoch, 0, data.dbDepth(m.id()));
    }

    private record Selection<T>(T value, Fallback fallback) {
        static <T> Selection<T> of(T value) {
            return new Selection<>(value, null);
        }

        static <T> Selection<T> no(Fallback reason) {
            return new Selection<>(null, reason);
        }
    }

    private Selection<Parsed> mirroredOrderbook(MarketDirectory.Market m, long epoch) {
        Selection<MarketMirror.Entry> entry = usableEntry(MarketMirror.Channel.ORDERBOOK, m, epoch);
        if (entry.value() == null) {
            return Selection.no(entry.fallback());
        }
        MarketMirror.Entry e = entry.value();
        Parsed cached = parsedOrderbooks.get(m.symbol());
        if (cached == null || cached.epoch() != e.epoch() || cached.seq() != e.seq()) {
            cached = new Parsed(e.epoch(), e.seq(), restShape(e.body()));
            parsedOrderbooks.put(m.symbol(), cached);
        }
        return Selection.of(cached);
    }

    private Selection<long[]> mirroredTicker(MarketDirectory.Market m, long epoch) {
        Selection<MarketMirror.Entry> entry = usableEntry(MarketMirror.Channel.TICKER, m, epoch);
        return entry.value() == null ? Selection.no(entry.fallback())
                : Selection.of(new long[] {entry.value().epoch(), entry.value().seq()});
    }

    /**
     * 미러 사용 조건 셋(S6 설계 4-6의 2). 구독 연결이 붙어 있고, 그 마켓 파티션 소유 노드가 닿으며(최근 5초 하트비트 셋), 미러가
     * 리스 epoch 이고 하트비트가 알린 마지막 게시 이상이어야 한다. 소유 노드의 발행이 막히면 구독 연결이 멀쩡해도 아무것도 오지
     * 않고, 끊긴 동안 잃은 게시는 epoch 비교로 알 수 없다. 하트비트가 둘을 가른다.
     */
    private Selection<MarketMirror.Entry> usableEntry(MarketMirror.Channel channel, MarketDirectory.Market m, long epoch) {
        if (!relay.beats().subscribed()) {
            return Selection.no(Fallback.RELAY_DOWN);
        }
        String partition = plan.partitionOf(m.id());
        if (partition == null || !relay.beats().reachable(partition, epoch)) {
            return Selection.no(Fallback.OWNER_SILENT);
        }
        MarketMirror.Entry e = relay.mirror().get(channel, m.symbol());
        if (e == null) {
            return Selection.no(Fallback.ABSENT);
        }
        if (e.epoch() != epoch) {
            return Selection.no(Fallback.EPOCH_MISMATCH);
        }
        long[] announced = relay.beats().announced(partition, epoch, channel, m.symbol());
        if (announced == null || announced[0] != epoch || e.seq() < announced[1]) {
            return Selection.no(Fallback.BEHIND);
        }
        return Selection.of(e);
    }

    /** 게시 본문을 REST 모양으로 바꾼다. WS 전용 필드(type, stream)를 뺀다. */
    private Map<String, Object> restShape(byte[] body) {
        try {
            Map<String, Object> map = json.readValue(body, MAP);
            map.remove("type");
            map.remove("stream");
            return java.util.Collections.unmodifiableMap(map);
        } catch (IOException e) {
            throw new IllegalStateException("호가 미러 본문을 풀지 못했다", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> best(Map<String, Object> orderbook) {
        Map<String, String> best = new HashMap<>();
        Object units = orderbook.get("orderbook_units");
        if (units instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> top) {
            best.put("bid", (String) ((Map<String, Object>) top).get("bid_price"));
            best.put("ask", (String) ((Map<String, Object>) top).get("ask_price"));
        }
        return best;
    }

    private void count(MarketMirror.Channel channel, Fallback reason) {
        fallbacks[channel.ordinal()][reason.ordinal()].incrementAndGet();
    }

    public long fallbacks(MarketMirror.Channel channel, Fallback reason) {
        return fallbacks[channel.ordinal()][reason.ordinal()].get();
    }
}
