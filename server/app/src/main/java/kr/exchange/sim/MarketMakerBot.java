package kr.exchange.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
    // 직전 주기 발주가 전량 거부되면 다음 주기부터 지수 백오프로 파킹한다(2,4,...,64주기). 자금·재고
    // 고갈 봇이 죽은 주문으로 공유 OPS 버킷을 무한 소모하는 것을 막는다.
    private static final int DEAD_CYCLE_BACKOFF_MAX_CYCLES = 64;
    private static final int DEAD_CYCLE_SHIFT_CAP = 6;

    private final String botId;
    private final String runNonce;
    private final BotApiClient client;
    private final SplittableRandom rng;
    private final SimProperties props;
    private final BigDecimal tick;
    private final BigDecimal targetBase;
    private final String baseSymbol;
    private final BooleanSupplier running;
    private final OpsGovernor governor;
    private final BotFleetGovernor fleet;
    private final TradingSuspensionWatch suspension;
    private final int index;
    private final double reactionFactor;
    private final ObjectMapper mapper = new ObjectMapper();

    // 레벨 가격틱 -> 외부 주문 id. 공통 레벨은 유지, 이탈·누락만 교체(r2).
    private final Map<Long, String> resting = new HashMap<>();
    // 레벨 가격틱 -> 미확정 발주의 client_order_id. 모호·거부 응답 후 같은 레벨을 재발주할 때
    // 같은 coid를 재사용해 서버가 멱등 처리하게 한다(중복·고아 주문 방지). 접수 확정 시 제거한다.
    private final Map<Long, String> pendingCoid = new HashMap<>();
    // 423 을 받은 client_order_id -> 레벨 가격틱(Patroni 설계 6-3). 423 은 그 시도가 아무것도 쓰지 않았다는 뜻일 뿐이라 같은
    // 키의 앞선 시도가 커밋됐을 수 있다. 레벨이 사다리에 남아 있으면 pendingCoid 가 같은 키로 다시 내고, 빠지면 재개 뒤 재조회로
    // 결과를 정할 때까지 버리지 않는다.
    private final Map<String, Long> suspendedCoid = new HashMap<>();
    // 거래 일시 중단으로 재호가를 중간에 멈췄다. 다음 주기는 중심 이동이 작아도 사다리를 다시 맞춘다.
    private boolean ladderIncomplete;
    private long fairTicks;
    private long lastQuotedFairTicks = Long.MIN_VALUE;
    private double lastImbalance;
    private long cycle;
    private long coidSeq;
    private int consecutiveDeadCycles;
    private long parkUntilCycle;

    public MarketMakerBot(String botId, String runNonce, BotApiClient client, long seed,
                          SimProperties props, BigDecimal tick, BigDecimal targetBase,
                          BooleanSupplier running, OpsGovernor governor, BotFleetGovernor fleet,
                          TradingSuspensionWatch suspension, int index) {
        this.botId = botId;
        this.runNonce = runNonce;
        this.client = client;
        this.rng = new SplittableRandom(seed);
        this.props = props;
        this.tick = tick;
        this.targetBase = targetBase;
        this.baseSymbol = baseOf(props.market());
        this.running = running;
        this.governor = governor;
        this.fleet = fleet;
        this.suspension = suspension;
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
                // 전량 거부 백오프 파킹. acquire 소비 전에 검사해 죽은 주기가 OPS를 태우지 않게 한다.
                if (cycle < parkUntilCycle) {
                    Thread.sleep(requoteMs);
                    continue;
                }
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

                if (!ladderIncomplete && !resting.isEmpty()
                        && Math.abs(effectiveFairTicks - lastQuotedFairTicks) < REQUOTE_THRESHOLD_TICKS) {
                    Thread.sleep(requoteMs);
                    continue;
                }

                LinkedHashMap<Long, Long> desired = buildLadder(effectiveFairTicks, halfTicks,
                        LEVEL_COUNT, LEVEL_SPACING_TICKS, K_DEPTH);
                ReconcileOutcome outcome = reconcile(desired, effectiveFairTicks, mm.quoteSize());
                applyBackoff(outcome);
                ladderIncomplete = outcome.suspended();
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

    /** suspended 는 거래 일시 중단(423)으로 재호가를 중간에 멈췄다는 뜻이다. 거부가 아니므로 백오프에 세지 않는다. */
    record ReconcileOutcome(int placeAttempts, int placeOk, boolean suspended) {
    }

    private enum PlaceResult { ACCEPTED, REJECTED, SUSPENDED }

    /** 사다리 한 번 맞추기. 시험이 직접 부른다. */
    ReconcileOutcome reconcile(Map<Long, Long> desired, long effectiveFairTicks, String quoteSize) {
        if (!settleSuspended(desired.keySet())) {
            return new ReconcileOutcome(0, 0, true);
        }
        // 소멸 레벨의 pending coid를 정리해 맵을 유계로 유지한다(더는 발주하지 않는 레벨). 423 을 받은 키는 결과를 정할 때까지 둔다.
        pendingCoid.entrySet().removeIf(e -> !desired.containsKey(e.getKey())
                && !suspendedCoid.containsKey(e.getValue()));
        LadderDiff diff = ladderDiff(desired.keySet(), resting.keySet());
        for (Long priceTicks : diff.toCancel()) {
            if (!acquire()) {
                return new ReconcileOutcome(0, 0, false);
            }
            String orderId = resting.remove(priceTicks);
            if (orderId != null && !cancel(priceTicks, orderId)) {
                return new ReconcileOutcome(0, 0, true);
            }
        }
        int attempts = 0;
        int ok = 0;
        for (Long priceTicks : diff.toPlace()) {
            if (!acquire()) {
                break;
            }
            String side = priceTicks < effectiveFairTicks ? "bid" : "ask";
            PlaceResult result = place(side, priceTicks, desired.get(priceTicks), quoteSize);
            if (result == PlaceResult.SUSPENDED) {
                // 남은 레벨은 재개 뒤 새 사다리로 맞춘다. 멈춘 동안 중심이 움직였을 수 있다.
                return new ReconcileOutcome(attempts, ok, true);
            }
            attempts++;
            if (result == PlaceResult.ACCEPTED) {
                ok++;
            }
        }
        return new ReconcileOutcome(attempts, ok, false);
    }

    /** 이 마켓이 거래 일시 중단이면 재개까지 기다린 뒤 토큰을 얻는다. 플릿이 멈추면 false. */
    private boolean acquire() {
        return (suspension == null || suspension.awaitOpen(props.market(), running))
                && governor.acquire(running);
    }

    /**
     * 취소한다. 423 이면 취소가 쓰이지 않아 주문이 호가에 남아 있으므로 추적을 되살리고 false 를 돌려준다. 재개 뒤 다음 주기의
     * 사다리가 다시 취소한다. 그 밖의 응답은 종전처럼 보지 않는다.
     */
    private boolean cancel(long priceTicks, String orderId) {
        BotApiClient.Response response = client.cancelOrder(props.market(), orderId);
        if (response.status() != 423 || suspension == null) {
            return true;
        }
        resting.put(priceTicks, orderId);
        suspension.onSuspended(props.market());
        return false;
    }

    /**
     * 423 을 받은 키 가운데 이번 사다리에서 다시 내지 않을 키를 재조회로 정한다(Patroni 설계 6-3, API 설계 §2-5-e). 404 면 어느
     * 시도도 커밋되지 않았으니 버린다. 200 이면 앞선 시도가 커밋된 주문이다. 살아 있으면 추적에 넣어 사다리가 취소하게 하고, 그
     * 레벨에 이미 다른 주문이 있으면 바로 취소한다. 409(결과 확인 중)·5xx·시간 초과는 다음 주기에 다시 묻는다. 그 취소가 다시
     * 423 을 받으면 false.
     */
    private boolean settleSuspended(Set<Long> desired) {
        if (suspendedCoid.isEmpty() || (suspension != null && suspension.suspended(props.market()))) {
            // 재조회는 재개 뒤에 한다(설계 6-3). 중단 동안의 404 는 커밋 대기 중인 앞선 시도를 못 볼 수 있다.
            return true;
        }
        Iterator<Map.Entry<String, Long>> it = suspendedCoid.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> held = it.next();
            String coid = held.getKey();
            long priceTicks = held.getValue();
            if (desired.contains(priceTicks) && coid.equals(pendingCoid.get(priceTicks))) {
                continue;
            }
            BotApiClient.Response response =
                    client.getSigned("/api/v1/orders/by-client/" + coid, Map.of());
            if (response.status() == 404) {
                it.remove();
                pendingCoid.remove(priceTicks, coid);
                continue;
            }
            if (response.status() != 200) {
                continue;
            }
            JsonNode order;
            try {
                order = mapper.readTree(response.body());
            } catch (Exception e) {
                continue;
            }
            it.remove();
            pendingCoid.remove(priceTicks, coid);
            String status = order.path("status").asText();
            if (!"OPEN".equals(status) && !"PARTIAL".equals(status)) {
                continue;
            }
            String orderId = order.path("id").asText();
            if (!resting.containsKey(priceTicks)) {
                resting.put(priceTicks, orderId);
                continue;
            }
            if (!acquire()) {
                return true;
            }
            BotApiClient.Response cancelled = client.cancelOrder(props.market(), orderId);
            if (cancelled.status() == 423 && suspension != null) {
                suspendedCoid.put(coid, priceTicks);
                suspension.onSuspended(props.market());
                return false;
            }
        }
        return true;
    }

    /**
     * 발주 결과로 백오프 상태를 갱신한다. 승인 발주가 하나라도 있으면 리셋하고, 발주를 시도했는데
     * 전량 거부면 지수 백오프로 다음 주기부터 파킹한다(2,4,...,64주기 상한). 발주 시도 자체가
     * 없었던 주기(유지·취소만)는 상태를 바꾸지 않는다.
     */
    private void applyBackoff(ReconcileOutcome outcome) {
        if (outcome.placeOk() > 0) {
            consecutiveDeadCycles = 0;
        } else if (outcome.placeAttempts() > 0 && !outcome.suspended()) {
            consecutiveDeadCycles = Math.min(consecutiveDeadCycles + 1, DEAD_CYCLE_SHIFT_CAP);
            long backoff = Math.min(DEAD_CYCLE_BACKOFF_MAX_CYCLES, 1L << consecutiveDeadCycles);
            parkUntilCycle = cycle + backoff;
        }
    }

    /**
     * client_order_id. run 단위 nonce 접두로 재기동 시 직전 run과의 coid 충돌을 막는다(멱등키
     * 재사용에 의한 무의미한 replay 회피). nonce·botId 고정, coidSeq 단조라 한 run 안에서는
     * 결정적이다.
     */
    private String coid() {
        return runNonce + "-" + botId + "-" + (++coidSeq);
    }

    /**
     * 발주하고 유효 접수 여부를 반환한다. 2xx라도 주문 status가 REJECTED면(거부 주문 replay)
     * 유동성 공급 실패이므로 false로 집계한다. 그렇지 않으면 replay-거부 반복이 매 주기 백오프를
     * 리셋해 파킹이 성립하지 않는다. 모호 응답(타임아웃·5xx)은 pendingCoid를 유지해 재발주가 멱등.
     */
    private PlaceResult place(String side, long priceTicks, long quoteMult, String quoteSize) {
        String price = tick.multiply(BigDecimal.valueOf(priceTicks)).toPlainString();
        String volume = new BigDecimal(quoteSize)
                .multiply(BigDecimal.valueOf(quoteMult)).toPlainString();
        // 같은 레벨의 미확정 발주는 같은 coid를 재사용한다. 타임아웃(-1)·5xx 등 모호 응답에서 서버가
        // 실제로 접수했어도 새 coid로 재발주하면 (user, client_order_id) 멱등키가 달라 중복 주문과
        // 추적 불가 고아가 생긴다(CLAUDE.md: 재시도는 같은 키를 재사용한다).
        String coid = pendingCoid.computeIfAbsent(priceTicks, k -> coid());
        String body = "{\"market\":\"" + props.market() + "\",\"side\":\"" + side
                + "\",\"ord_type\":\"limit\",\"price\":\"" + price + "\",\"volume\":\"" + volume
                + "\",\"client_order_id\":\"" + coid + "\"}";
        BotApiClient.Response response = client.placeOrder(props.market(), body);
        if (response.status() == 201 || response.status() == 200) {
            // 접수 확정. coid 소비. 이후 같은 레벨 재발주는 새 주문이라 새 coid를 쓴다.
            pendingCoid.remove(priceTicks);
            suspendedCoid.remove(coid);
            boolean rejected = false;
            try {
                JsonNode order = mapper.readTree(response.body());
                String status = order.path("status").asText();
                rejected = "REJECTED".equals(status);
                if ("OPEN".equals(status) || "PARTIAL".equals(status)) {
                    resting.put(priceTicks, order.path("id").asText());
                }
            } catch (Exception e) {
                log.debug("{} 주문 응답 파싱 실패: {}", botId, e.toString());
            }
            return rejected ? PlaceResult.REJECTED : PlaceResult.ACCEPTED;
        }
        if (response.status() == 423 && suspension != null) {
            // 이 레벨의 coid 는 남긴다. 재개 뒤 같은 키로 다시 내거나 재조회로 결과를 정한다(API 설계 §2-5-e).
            suspendedCoid.put(coid, priceTicks);
            suspension.onSuspended(props.market());
            return PlaceResult.SUSPENDED;
        }
        if (response.status() == 409 && "duplicate_client_order_id".equals(errorName(response))) {
            // 서버 멱등 계약(requireSameContent)상 이 coid는 다른 내용의 주문에 이미 소비돼
            // 어떤 재시도도 성공할 수 없는 죽은 키다. 버리고 다음 시도는 새 coid로 낸다.
            // 같은 409라도 order_in_progress는 재시도가 replay로 수렴하므로 유지한다.
            // 423 을 받았던 키면 suspendedCoid 에 남아 재조회가 그 주문을 찾는다.
            pendingCoid.remove(priceTicks);
        }
        log.debug("{} 호가 거부 {}: {}", botId, response.status(), response.body());
        return PlaceResult.REJECTED;
    }

    /** 표준 에러 포맷(API 설계 §2-3)의 error.name. 파싱 불가면 빈 문자열. */
    private String errorName(BotApiClient.Response response) {
        try {
            return mapper.readTree(response.body()).path("error").path("name").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String baseOf(String market) {
        int dash = market.indexOf('-');
        return dash >= 0 ? market.substring(dash + 1) : market.toUpperCase(Locale.ROOT);
    }
}
