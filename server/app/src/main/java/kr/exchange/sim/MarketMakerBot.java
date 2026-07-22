package kr.exchange.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;

/**
 * 양방향 호가 공급 봇(설계 §2 Wave B realism). fair value는 시드 랜덤워크 + bias 오프셋에
 * 시장가 앵커(r1)를 더해 시장 last로 약하게 회귀한다. 재고 스큐(r11)로 호가 중심을 기울여
 * 초과 재고를 소진하고, 레짐 스프레드 배수(r12)로 반폭을 조절하며, 다단 사다리(r3)를 편다.
 * 재호가는 이탈·누락 레벨만 취소·발주하는 증분(r2)이라 호가창 플리커·공백이 없다.
 * 가격·수량은 틱·로트 정수 배수 + BigDecimal로만 구성한다. double는 스칼라 의사결정에만
 * 쓰고 Math.round로 long 양자화한 뒤에만 NUMERIC 산술에 진입한다.
 */
public class MarketMakerBot implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MarketMakerBot.class);
    private static final double K_MM = 0.5;
    // 앵커 회귀 게인. 랜덤워크·드리프트보다 약해(1 미만) 오버슈트·발진 없이 last로 끌어당긴다.
    private static final double K_ANCHOR = 0.2;
    // 호가 중심 이동이 이 틱 미만이면 주기 전체를 생략한다(레스팅 유지, OPS 미소비).
    private static final int REQUOTE_THRESHOLD_TICKS = 2;
    private static final int LEVEL_COUNT = 3;
    private static final int LEVEL_SPACING_TICKS = 1;
    // 미드에서 멀수록 레벨 수량 증가(전형적 MM 유동성 분포). §6-A "유동성" 취지. 수량은 quoteSize
    // 정수 배수라 lot_size(공개 API 미노출) 없이도 로트 정수 배수가 보장된다.
    private static final double K_DEPTH = 1.0;
    // 재고 스큐 게인. 초과 재고(imbalance=+1)에서 호가 중심을 최대 반스프레드만큼 아래로 민다.
    private static final double K_INV = 0.5;
    // 재고 조회 주기(재호가 N회당 1회). 매 주기 서명 GET을 피해 읽기 부하를 억제한다.
    private static final int INV_READ_EVERY = 4;
    private static final long PARK_MS = 400;

    private final String botId;
    private final BotApiClient client;
    private final SplittableRandom rng;
    private final SimProperties props;
    private final BigDecimal tick;
    private final BigDecimal targetBase;
    private final String baseSymbol;
    private final BooleanSupplier running;
    private final OpsGovernor governor;
    private final BotFleetGovernor fleet;
    private final int index;
    private final double reactionFactor;
    private final ObjectMapper mapper = new ObjectMapper();

    // 레벨 가격틱 -> 외부 주문 id. 공통 레벨은 유지, 이탈·누락만 교체(r2).
    private final Map<Long, String> resting = new HashMap<>();
    private long fairTicks;
    private long lastQuotedFairTicks = Long.MIN_VALUE;
    private double lastImbalance;
    private long cycle;
    private long coidSeq;

    public MarketMakerBot(String botId, BotApiClient client, long seed, SimProperties props,
                          BigDecimal tick, BigDecimal targetBase,
                          BooleanSupplier running, OpsGovernor governor, BotFleetGovernor fleet,
                          int index) {
        this.botId = botId;
        this.client = client;
        this.rng = new SplittableRandom(seed);
        this.props = props;
        this.tick = tick;
        this.targetBase = targetBase;
        this.baseSymbol = baseOf(props.market());
        this.running = running;
        this.governor = governor;
        this.fleet = fleet;
        this.index = index;
        this.reactionFactor = BotReaction.reactionJitter(seed);
        this.fairTicks = new BigDecimal(props.initialPrice())
                .divideToIntegralValue(tick).longValueExact();
    }

    /**
     * bias 유래 드리프트 오프셋(정수 틱). b = round(bias * driftTicks * K_MM). 미드가 bias
     * 방향으로 서서히 이동한다. (bias, driftTicks) 순수 함수이며 결과는 long이라 이후 가격
     * 산술은 틱 정수 배수를 유지한다.
     */
    public static long driftOffset(double bias, int driftTicks, double kMm) {
        double clamped = Math.max(-1.0, Math.min(1.0, bias));
        return Math.round(clamped * driftTicks * kMm);
    }

    /**
     * 앵커 회귀 오프셋(정수 틱, r1). fair를 시장 last(anchorTicks)로 kAnchor만큼 끌어당긴다.
     * (fairTicks, anchorTicks) 순수 함수. MM 자신의 호가로 경계된 last로 회귀하므로 발산 불가.
     */
    public static long anchorPull(long fairTicks, long anchorTicks, double kAnchor) {
        return Math.round(kAnchor * (anchorTicks - fairTicks));
    }

    /**
     * 재고 스큐 오프셋(정수 틱, r11). 초과 재고(imbalance>0)면 음의 스큐로 호가 중심을 낮춰
     * 매도를 유도한다. imbalance는 [-1,1] 클램프, 결과는 scaleTicks로 경계된다. 순수 함수.
     */
    public static long inventorySkewTicks(double invImbalance, double kInv, int scaleTicks) {
        double clamped = Math.max(-1.0, Math.min(1.0, invImbalance));
        return Math.round(-kInv * clamped * scaleTicks);
    }

    /**
     * 레짐 스프레드 배수 반영 반폭(정수 틱, r12). base 반폭에 배수를 곱한 뒤 양자화한다.
     * 최소 1틱. 순수 함수라 스칼라 배수가 NUMERIC 산술에 유입되지 않는다.
     */
    public static long spreadHalfTicks(int spreadTicks, double spreadMult) {
        long base = spreadTicks / 2L;
        return Math.max(1L, Math.round(base * spreadMult));
    }

    /**
     * desired 사다리(가격틱 -> quoteSize 배수). bid는 중심-반폭-j간격, ask는 중심+반폭+j간격.
     * 레벨 수량 배수는 round(1+kDepth*j)(정수). quoteSize 자체가 로트 배수라 그 정수 배수도 로트
     * 배수를 유지한다. 삽입 순서 결정적. 순수 함수.
     * 비양수 bid 틱은 제외한다(price>0 불변). run() 하한 가드가 정상 경로에선 이를 막지만,
     * 큰 halfTicks·낮은 fair 조합의 방어선으로 사다리 자체가 0 이하 가격을 발주하지 않게 한다.
     */
    static LinkedHashMap<Long, Long> buildLadder(long fairTicks, long halfTicks, int levelCount,
                                                 int spacingTicks, double kDepth) {
        LinkedHashMap<Long, Long> out = new LinkedHashMap<>();
        for (int j = 0; j < levelCount; j++) {
            long off = halfTicks + (long) j * spacingTicks;
            long mult = Math.max(1L, Math.round(1.0 + kDepth * j));
            long bid = fairTicks - off;
            if (bid > 0) {
                out.put(bid, mult);
            }
            out.put(fairTicks + off, mult);
        }
        return out;
    }

    public record LadderDiff(List<Long> toCancel, List<Long> toPlace) {
    }

    /**
     * 증분 재호가 diff(r2). live에만 있으면 취소, desired에만 있으면 발주, 공통은 유지.
     * 결정적 순서를 위해 양측을 정렬한다. 순수 함수.
     */
    static LadderDiff ladderDiff(Set<Long> desired, Set<Long> live) {
        List<Long> toCancel = new ArrayList<>();
        for (Long k : live) {
            if (!desired.contains(k)) {
                toCancel.add(k);
            }
        }
        List<Long> toPlace = new ArrayList<>();
        for (Long k : desired) {
            if (!live.contains(k)) {
                toPlace.add(k);
            }
        }
        toCancel.sort(Long::compareTo);
        toPlace.sort(Long::compareTo);
        return new LadderDiff(toCancel, toPlace);
    }

    @Override
    public void run() {
        SimProperties.MarketMaker mm = props.marketMaker();
        // 봇별 반응 지터(r17). requoteMs에 상수 배율만 적용, rng·의사결정에 미유입.
        long requoteMs = Math.max(1L, Math.round(mm.requoteMs() * reactionFactor));
        while (running.getAsBoolean()) {
            try {
                if (fleet != null && !fleet.active(props.market(), BotType.MARKET_MAKER, index)) {
                    Thread.sleep(PARK_MS);
                    continue;
                }
                cycle++;
                long anchorTicks = fetchAnchorTicks();
                drift(mm, driftMult(), anchorTicks);
                long halfTicks = spreadHalfTicks(mm.spreadTicks(), spreadMult());
                long effectiveFairTicks = fairTicks
                        + inventorySkewTicks(readImbalance(), K_INV, mm.spreadTicks());
                // 하한을 halfTicks 인지형으로 잡는다(r12 스프레드 배수·다단 사다리 반영). 최저 bid
                // 오프셋(halfTicks + (LEVEL_COUNT-1)*간격)보다 fair가 낮으면 bid가 0 이하로 내려가므로
                // 그 오프셋+1을 하한으로 강제한다.
                long lowestOffset = halfTicks + (long) (LEVEL_COUNT - 1) * LEVEL_SPACING_TICKS;
                effectiveFairTicks = Math.max(effectiveFairTicks, lowestOffset + 1L);

                if (!resting.isEmpty() && Math.abs(effectiveFairTicks - lastQuotedFairTicks)
                        < REQUOTE_THRESHOLD_TICKS) {
                    Thread.sleep(requoteMs);
                    continue;
                }

                LinkedHashMap<Long, Long> desired = buildLadder(effectiveFairTicks, halfTicks,
                        LEVEL_COUNT, LEVEL_SPACING_TICKS, K_DEPTH);
                reconcile(desired, effectiveFairTicks, mm.quoteSize());
                lastQuotedFairTicks = effectiveFairTicks;

                if (fleet != null) {
                    fleet.recordPrice(props.market(),
                            tick.multiply(BigDecimal.valueOf(effectiveFairTicks)));
                }
                Thread.sleep(requoteMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.debug("{} 재호가 실패, 다음 주기 계속: {}", botId, e.toString());
            }
        }
    }

    private double driftMult() {
        return fleet == null ? 1.0 : fleet.regimeDriftMult(props.market());
    }

    private double spreadMult() {
        return fleet == null ? 1.0 : fleet.regimeSpreadMult(props.market());
    }

    private void drift(SimProperties.MarketMaker mm, double driftMult, long anchorTicks) {
        double bias = fleet == null ? 0.0 : fleet.effectiveBias(props.market());
        long walk = rng.nextLong(-mm.driftTicks(), mm.driftTicks() + 1L);
        // 레짐 배수는 스칼라라 walk 크기만 키운 뒤 양자화한다(NUMERIC 미유입).
        long scaledWalk = Math.round(driftMult * walk);
        long anchor = anchorTicks >= 0 ? anchorPull(fairTicks, anchorTicks, K_ANCHOR) : 0L;
        fairTicks += scaledWalk + driftOffset(bias, mm.driftTicks(), K_MM) + anchor;
        // bid가 0 이하로 내려가지 않게 하한을 둔다. bias 음전·앵커 하강에서도 유지된다.
        fairTicks = Math.max(fairTicks, mm.spreadTicks() + 1L);
    }

    /** 시장 last 앵커(정수 틱). ticker trade_price -> orderbook 미드 -> 없으면 -1(앵커 무효). */
    private long fetchAnchorTicks() {
        long fromTicker = anchorFromTicker();
        if (fromTicker >= 0) {
            return fromTicker;
        }
        return anchorFromOrderbook();
    }

    private long anchorFromTicker() {
        BotApiClient.Response r =
                client.getPublic("/api/v1/ticker?markets=" + props.market());
        if (r.status() != 200) {
            return -1;
        }
        try {
            JsonNode data = mapper.readTree(r.body()).path("data");
            if (!data.isArray() || data.isEmpty()) {
                return -1;
            }
            return ticksOf(data.get(0).path("trade_price").asText(null));
        } catch (Exception e) {
            return -1;
        }
    }

    private long anchorFromOrderbook() {
        BotApiClient.Response r =
                client.getPublic("/api/v1/orderbook?market=" + props.market());
        if (r.status() != 200) {
            return -1;
        }
        try {
            JsonNode units = mapper.readTree(r.body()).path("orderbook_units");
            if (!units.isArray() || units.isEmpty()) {
                return -1;
            }
            JsonNode top = units.get(0);
            BigDecimal ask = decimalOf(top.path("ask_price").asText(null));
            BigDecimal bid = decimalOf(top.path("bid_price").asText(null));
            if (ask == null || bid == null) {
                return -1;
            }
            BigDecimal mid = ask.add(bid).divide(BigDecimal.valueOf(2), 18, RoundingMode.HALF_UP);
            return mid.divide(tick, 0, RoundingMode.HALF_UP).longValueExact();
        } catch (Exception e) {
            return -1;
        }
    }

    private long ticksOf(String raw) {
        BigDecimal price = decimalOf(raw);
        if (price == null || price.signum() <= 0) {
            return -1;
        }
        return price.divide(tick, 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static BigDecimal decimalOf(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 재고 불균형(r11). target 대비 (balance+locked) 초과분을 [-1,1]로 반환한다. INV_READ_EVERY
     * 주기마다만 서명 조회하고 그 외에는 직전값을 유지한다. target 미설정이면 스큐 비활성(0).
     */
    private double readImbalance() {
        if (targetBase == null || targetBase.signum() <= 0) {
            return 0.0;
        }
        if (cycle % INV_READ_EVERY != 1) {
            return lastImbalance;
        }
        BotApiClient.Response r = client.getSigned("/api/v1/accounts", Map.of());
        if (r.status() != 200) {
            return lastImbalance;
        }
        try {
            for (JsonNode row : mapper.readTree(r.body()).path("data")) {
                if (baseSymbol.equals(row.path("currency").asText())) {
                    BigDecimal bal = decimalOf(row.path("balance").asText("0"));
                    BigDecimal locked = decimalOf(row.path("locked").asText("0"));
                    BigDecimal held = (bal == null ? BigDecimal.ZERO : bal)
                            .add(locked == null ? BigDecimal.ZERO : locked);
                    double imb = held.doubleValue() / targetBase.doubleValue() - 1.0;
                    lastImbalance = Math.max(-1.0, Math.min(1.0, imb));
                    return lastImbalance;
                }
            }
        } catch (Exception e) {
            log.debug("{} 재고 조회 파싱 실패: {}", botId, e.toString());
        }
        return lastImbalance;
    }

    private void reconcile(Map<Long, Long> desired, long effectiveFairTicks, String quoteSize) {
        LadderDiff diff = ladderDiff(desired.keySet(), resting.keySet());
        for (Long priceTicks : diff.toCancel()) {
            if (!governor.acquire(running)) {
                return;
            }
            String orderId = resting.remove(priceTicks);
            if (orderId != null) {
                client.cancelOrder(orderId);
            }
        }
        for (Long priceTicks : diff.toPlace()) {
            if (!governor.acquire(running)) {
                return;
            }
            String side = priceTicks < effectiveFairTicks ? "bid" : "ask";
            place(side, priceTicks, desired.get(priceTicks), quoteSize);
        }
    }

    private void place(String side, long priceTicks, long quoteMult, String quoteSize) {
        String price = tick.multiply(BigDecimal.valueOf(priceTicks)).toPlainString();
        String volume = new BigDecimal(quoteSize)
                .multiply(BigDecimal.valueOf(quoteMult)).toPlainString();
        String body = "{\"market\":\"" + props.market() + "\",\"side\":\"" + side
                + "\",\"ord_type\":\"limit\",\"price\":\"" + price + "\",\"volume\":\"" + volume
                + "\",\"client_order_id\":\"" + botId + "-" + (++coidSeq) + "\"}";
        BotApiClient.Response response = client.placeOrder(body);
        if (response.status() == 201 || response.status() == 200) {
            try {
                JsonNode order = mapper.readTree(response.body());
                String status = order.path("status").asText();
                if ("OPEN".equals(status) || "PARTIAL".equals(status)) {
                    resting.put(priceTicks, order.path("id").asText());
                }
            } catch (Exception e) {
                log.debug("{} 주문 응답 파싱 실패: {}", botId, e.toString());
            }
        } else {
            log.debug("{} 호가 거부 {}: {}", botId, response.status(), response.body());
        }
    }

    private static String baseOf(String market) {
        int dash = market.indexOf('-');
        return dash >= 0 ? market.substring(dash + 1) : market.toUpperCase(Locale.ROOT);
    }
}
