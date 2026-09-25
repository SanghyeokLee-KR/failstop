package kr.exchange.api.ws.relay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.ws.MarketMirror;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 노드 사이 중계 봉투 한 건(API 설계 §5-1, S6 설계 4-3). STOMP SEND 한 건이고 본문은 클라이언트가 받는 JSON 바이트 그대로다.
 * 하트비트 봉투(hb)의 본문은 그 파티션 마켓마다 보낸 노드가 마지막으로 게시한 호가·티커의 [epoch, seq] 다.
 *
 * 받는 쪽은 목적지와 사용자 머리글의 모양을 대조한다. 발행 계정이 새어도 임의의 목적지에 메시지를 꽂지 못하게 하려는 것이다.
 */
public record RelayEnvelope(Kind kind, String origin, String dest, Long userId, String market, String partition,
                            long epoch, long seq, byte[] body) {

    public enum Kind {
        TOPIC("topic"), USER("user"), HB("hb");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        static Kind of(String wire) {
            for (Kind k : values()) {
                if (k.wire.equals(wire)) {
                    return k;
                }
            }
            return null;
        }
    }

    public static final String VERSION = "1";

    public static final String H_VERSION = "x-v";
    public static final String H_ORIGIN = "x-origin";
    public static final String H_KIND = "x-kind";
    public static final String H_DEST = "x-dest";
    public static final String H_USER = "x-user";
    public static final String H_MARKET = "x-market";
    public static final String H_PARTITION = "x-partition";
    public static final String H_EPOCH = "x-epoch";
    public static final String H_SEQ = "x-seq";
    public static final String H_SENT_AT = "x-sent-at";

    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9-]+");
    private static final Pattern PUBLIC_DEST = Pattern.compile("/topic/(orderbook|ticker|trade)\\.([A-Z0-9-]+)");
    private static final Pattern CANDLE_DEST = Pattern.compile("/topic/candle\\.([0-9a-z]+)\\.([A-Z0-9-]+)");
    private static final Set<String> USER_DESTS = Set.of("/queue/myOrder", "/queue/myAsset");
    private static final Pattern USER_ID = Pattern.compile("[0-9]{1,18}");
    private static final Pattern PARTITION = Pattern.compile("[a-z0-9-]{1,64}");

    public static RelayEnvelope topic(String origin, String dest, String market, long epoch, long seq, byte[] body) {
        return new RelayEnvelope(Kind.TOPIC, origin, dest, null, market, null, epoch, seq, body);
    }

    public static RelayEnvelope user(String origin, long userId, String dest, String market, long epoch, long seq,
                                     byte[] body) {
        return new RelayEnvelope(Kind.USER, origin, dest, userId, market, null, epoch, seq, body);
    }

    public static RelayEnvelope heartbeat(String origin, String partition, long epoch, long seq, byte[] body) {
        return new RelayEnvelope(Kind.HB, origin, null, null, null, partition, epoch, seq, body);
    }

    /** STOMP SEND 머리글. 목적지와 content-type 은 연결 쪽이 붙인다. */
    public Map<String, String> headers(long sentAtMillis) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put(H_VERSION, VERSION);
        h.put(H_ORIGIN, origin);
        h.put(H_KIND, kind.wire());
        if (dest != null) {
            h.put(H_DEST, dest);
        }
        if (userId != null) {
            h.put(H_USER, String.valueOf(userId));
        }
        if (market != null) {
            h.put(H_MARKET, market);
        }
        if (partition != null) {
            h.put(H_PARTITION, partition);
        }
        h.put(H_EPOCH, String.valueOf(epoch));
        h.put(H_SEQ, String.valueOf(seq));
        h.put(H_SENT_AT, String.valueOf(sentAtMillis));
        return h;
    }

    public boolean fromSelf(String holder) {
        return origin.equals(holder);
    }

    /** 호가·티커 봉투면 그 채널이다. 그 밖은 null. */
    public MarketMirror.Channel mirrorChannel() {
        if (kind != Kind.TOPIC) {
            return null;
        }
        if (dest.startsWith("/topic/orderbook.")) {
            return MarketMirror.Channel.ORDERBOOK;
        }
        if (dest.startsWith("/topic/ticker.")) {
            return MarketMirror.Channel.TICKER;
        }
        return null;
    }

    public boolean trade() {
        return kind == Kind.TOPIC && dest.startsWith("/topic/trade.");
    }

    /**
     * 받은 머리글과 본문을 봉투로 푼다. 모양이 허용 밖이면 Malformed 를 던진다. 하트비트 파티션이 계획에 있는지와 본문의 마켓이
     * 그 파티션 마켓인지는 계획을 아는 쪽(RelaySubscriber)이 본다.
     */
    public static RelayEnvelope decode(Map<String, String> headers, byte[] body) throws Malformed {
        if (!VERSION.equals(headers.get(H_VERSION))) {
            throw new Malformed("x-v " + headers.get(H_VERSION));
        }
        String origin = headers.get(H_ORIGIN);
        if (origin == null || origin.isBlank()) {
            throw new Malformed("x-origin 없음");
        }
        Kind kind = Kind.of(headers.get(H_KIND));
        if (kind == null) {
            throw new Malformed("x-kind " + headers.get(H_KIND));
        }
        long epoch = nonNegative(headers, H_EPOCH);
        long seq = nonNegative(headers, H_SEQ);
        nonNegative(headers, H_SENT_AT);
        byte[] payload = body == null ? new byte[0] : body;
        return switch (kind) {
            case TOPIC -> {
                String dest = headers.get(H_DEST);
                String market = headers.get(H_MARKET);
                String destMarket = topicMarket(dest);
                if (destMarket == null || !destMarket.equals(market)) {
                    throw new Malformed("topic 목적지 " + dest + " 마켓 " + market);
                }
                yield topic(origin, dest, market, epoch, seq, payload);
            }
            case USER -> {
                String dest = headers.get(H_DEST);
                String user = headers.get(H_USER);
                String market = headers.get(H_MARKET);
                if (!USER_DESTS.contains(dest) || user == null || !USER_ID.matcher(user).matches()
                        || market == null || !SYMBOL.matcher(market).matches()) {
                    throw new Malformed("user 목적지 " + dest + " 사용자 " + user + " 마켓 " + market);
                }
                yield user(origin, Long.parseLong(user), dest, market, epoch, seq, payload);
            }
            case HB -> {
                String partition = headers.get(H_PARTITION);
                if (partition == null || !PARTITION.matcher(partition).matches() || headers.containsKey(H_DEST)) {
                    throw new Malformed("hb 파티션 " + partition);
                }
                yield heartbeat(origin, partition, epoch, seq, payload);
            }
        };
    }

    /** 허용 모양의 공개 목적지면 그 마켓 심볼, 아니면 null. 캔들 단위는 계약의 열 가지다. */
    static String topicMarket(String dest) {
        if (dest == null) {
            return null;
        }
        Matcher pub = PUBLIC_DEST.matcher(dest);
        if (pub.matches()) {
            return pub.group(2);
        }
        Matcher candle = CANDLE_DEST.matcher(dest);
        if (candle.matches() && MarketDataService.isSupportedUnit(candle.group(1))) {
            return candle.group(2);
        }
        return null;
    }

    private static long nonNegative(Map<String, String> headers, String name) throws Malformed {
        String raw = headers.get(name);
        if (raw == null) {
            throw new Malformed(name + " 없음");
        }
        try {
            long v = Long.parseLong(raw);
            if (v < 0) {
                throw new Malformed(name + " 음수");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new Malformed(name + " " + raw);
        }
    }

    /** 하트비트 본문. 채널마다 마켓 심볼에서 [epoch, seq] 로 간다. 게시한 적이 없는 마켓은 없다. */
    public record Heartbeat(Map<String, long[]> orderbook, Map<String, long[]> ticker) {

        private static final TypeReference<Map<String, Map<String, long[]>>> SHAPE = new TypeReference<>() { };

        public byte[] encode(ObjectMapper json) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("orderbook", orderbook);
            body.put("ticker", ticker);
            try {
                return json.writeValueAsBytes(body);
            } catch (IOException e) {
                throw new IllegalStateException("하트비트 본문 직렬화 실패", e);
            }
        }

        public static Heartbeat decode(ObjectMapper json, byte[] body) throws Malformed {
            Map<String, Map<String, long[]>> raw;
            try {
                raw = json.readValue(body, SHAPE);
            } catch (IOException e) {
                throw new Malformed("hb 본문 " + e.getMessage());
            }
            Map<String, long[]> orderbook = raw.getOrDefault("orderbook", Map.of());
            Map<String, long[]> ticker = raw.getOrDefault("ticker", Map.of());
            for (Map<String, long[]> channel : java.util.List.of(orderbook, ticker)) {
                for (Map.Entry<String, long[]> e : channel.entrySet()) {
                    long[] v = e.getValue();
                    if (!SYMBOL.matcher(e.getKey()).matches() || v == null || v.length != 2 || v[0] < 0 || v[1] < 0) {
                        throw new Malformed("hb 항목 " + e.getKey());
                    }
                }
            }
            return new Heartbeat(orderbook, ticker);
        }

        public Map<String, long[]> channel(MarketMirror.Channel channel) {
            return channel == MarketMirror.Channel.ORDERBOOK ? orderbook : ticker;
        }
    }

    /** 허용 밖 모양. 받는 쪽은 버리고 dropped{reason=malformed} 로 센다. */
    public static final class Malformed extends Exception {
        public Malformed(String message) {
            super(message);
        }
    }
}
