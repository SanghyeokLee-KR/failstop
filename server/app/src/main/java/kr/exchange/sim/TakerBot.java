package kr.exchange.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;

/**
 * 테이커 봇. 지수 분포 도착(파라미터 lambda)으로 호가를 소비한다. 도착률은 레짐 배수(r13)로
 * 스케일되고, 크기는 멱함수형 분포 + 고래 확률(r6)로 꼬리를 갖는다. 낮은 확률로 같은 방향
 * 연속 스윕(버스트)을 낸다. 방향 스칼라 d in [-1,1]는 directionSource가 산출한다(r7): STATIC은
 * effective bias, MOMENTUM은 공개 ticker 추세, MEAN_REVERT는 공개 trades 역추세. d와 확신
 * 게인 kDir이 매수 확률을 정한다. 확률·크기만 바꾸므로 총 OPS 상한(governor)·정밀도 표면은 불변.
 * 프리셋. TAKER kDir 0.8, TREND kDir 1.0(강한 방향), NOISE kDir 0(방향 무시)+고분산.
 * 모든 의사결정 난수는 시드 SplittableRandom을 고정 순서로 소비한다(재현성). 방향 소스 교체는
 * rng 소비 순서를 바꾸지 않아 페르소나 무관하게 rng 스트림이 동일하다.
 */
public class TakerBot implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TakerBot.class);
    private static final long PARK_MS = 400;
    // 크기 분포 파라미터(r6). sigma는 반정규 스케일, pWhale은 기본 고래 확률, whaleFactor는
    // 고래 배수, capMult는 baseCap 대비 절대 상한 배수. 관리자 조율 후보(#36).
    private static final double SIZE_SIGMA = 1.2;
    private static final double P_WHALE = 0.03;
    private static final double WHALE_FACTOR = 6.0;
    private static final int CAP_MULT = 8;
    // 버스트 파라미터(r13). 기본 확률과 최대 연속 수. 활성 레짐(lambdaMult)에서 확률이 커진다.
    private static final double P_BURST = 0.05;
    private static final int MAX_BURST = 3;
    private static final double MULT_PROB_CAP = 0.5;
    // 방향 신호 게인·창(r7). 관리자 조율 후보(#36). change_rate·편차가 작아 게인으로 확대한 뒤
    // [-1,1] 클램프한다. 확률 스칼라 전용이라 NUMERIC 산술에 유입되지 않는다.
    private static final double MOMENTUM_GAIN = 8.0;
    private static final double MEAN_REVERT_GAIN = 20.0;
    private static final int SMA_N = 20;
    // 신호 조회 주기(결정 N회당 1회). 매 결정 GET을 피해 읽기 부하를 억제한다(MM INV_READ_EVERY 동형).
    private static final int SIGNAL_READ_EVERY = 4;

    private final String botId;
    private final String runNonce;
    private final BotApiClient client;
    private final SplittableRandom rng;
    private final SimProperties props;
    private final double kDir;
    private final int stepMultiplier;
    private final BooleanSupplier running;
    private final OpsGovernor governor;
    private final BotFleetGovernor fleet;
    private final BotType type;
    private final int index;
    private final DirectionSource directionSource;
    private final double reactionFactor;
    private final ObjectMapper mapper = new ObjectMapper();

    private long coidSeq;
    private long signalCycle;
    private double cachedSignal;

    public TakerBot(String botId, String runNonce, BotApiClient client, long seed,
                    SimProperties props, double kDir, int stepMultiplier, BooleanSupplier running,
                    OpsGovernor governor, BotFleetGovernor fleet, BotType type, int index,
                    DirectionSource directionSource) {
        this.botId = botId;
        this.runNonce = runNonce;
        this.client = client;
        this.rng = new SplittableRandom(seed);
        this.props = props;
        this.kDir = kDir;
        this.stepMultiplier = Math.max(1, stepMultiplier);
        this.running = running;
        this.governor = governor;
        this.fleet = fleet;
        this.type = type;
        this.index = index;
        this.directionSource = directionSource == null ? DirectionSource.STATIC : directionSource;
        this.reactionFactor = BotReaction.reactionJitter(seed);
    }

    /**
     * 매수 확률. p_buy = 0.5 + 0.5 * clamp(bias) * kDir. bias=+1에서도 kDir<1이면 양면성을
     * 남겨 한쪽 호가만 소진하며 가드까지 밀어붙이는 극단을 피한다.
     * (bias, kDir) 순수 함수다. 벽시계·추가 난수원 없음.
     */
    public static double buyProbability(double bias, double kDir) {
        double clamped = Math.max(-1.0, Math.min(1.0, bias));
        double p = 0.5 + 0.5 * clamped * kDir;
        return Math.max(0.0, Math.min(1.0, p));
    }

    /**
     * 모멘텀 방향 스칼라(r7). change가 부호 권위(RISE +1 / FALL -1 / EVEN 0), change_rate가
     * 절대 크기다(§4-A-4 부호·크기 분리). d = clamp(sign * |rate| * gain, -1, 1). 순수 함수.
     */
    static double momentumScalar(int changeSign, BigDecimal changeRate, double gain) {
        if (changeSign == 0 || changeRate == null) {
            return 0.0;
        }
        double magnitude = Math.abs(changeRate.doubleValue());
        double d = Math.signum(changeSign) * magnitude * gain;
        return Math.max(-1.0, Math.min(1.0, d));
    }

    /**
     * 역추세 방향 스칼라(r7). SMA 대비 편차 dev=last/sma-1의 반대 부호. last>sma(고평가)면 d<0로
     * 페이드 매도. sma 비양수면 0. 순수 함수.
     */
    static double meanRevertScalar(BigDecimal last, BigDecimal sma, double gain) {
        if (last == null || sma == null || sma.signum() <= 0) {
            return 0.0;
        }
        double dev = last.doubleValue() / sma.doubleValue() - 1.0;
        double d = -dev * gain;
        return Math.max(-1.0, Math.min(1.0, d));
    }

    /**
     * 주문 크기 스텝(r6). 반정규 기본에 고래 확률로 배수를 얹어 우편향·두꺼운 꼬리를 만든다.
     * baseCap은 정상 크기 상한, cap은 고래 포함 절대 상한. 시드 rng를 고정 순서(가우시안 ->
     * 균등)로 소비하는 결정적 함수. 결과는 [1, cap] 정수 스텝.
     */
    static int sizeSteps(SplittableRandom rng, int baseCap, double sigma, double pWhale,
                         double whaleFactor, int cap) {
        double g = Math.abs(rng.nextGaussian());
        int base = 1 + (int) Math.floor(g * sigma);
        if (base > baseCap) {
            base = baseCap;
        }
        if (rng.nextDouble() < pWhale) {
            base = (int) Math.round(base * whaleFactor);
        }
        if (base < 1) {
            base = 1;
        }
        return Math.min(base, cap);
    }

    /**
     * 버스트 길이(r13). pBurst 확률로 [2, 1+maxBurst] 연속 스윕, 그 외 1(단발). 트리거 시 최소
     * 2라 실제 군집이 보장된다. 시드 rng를 고정 순서로 소비하는 결정적 함수.
     */
    static int burstCount(SplittableRandom rng, double pBurst, int maxBurst) {
        return rng.nextDouble() < pBurst ? 2 + rng.nextInt(maxBurst) : 1;
    }

    @Override
    public void run() {
        SimProperties.Taker taker = props.taker();
        int baseCap = taker.maxSteps() * stepMultiplier;
        int cap = baseCap * CAP_MULT;
        while (running.getAsBoolean()) {
            try {
                if (fleet != null && !fleet.active(props.market(), type, index)) {
                    Thread.sleep(PARK_MS);
                    continue;
                }
                // 버스트 확률은 레짐 단독 신호로 둔다. 상한이 0.5인데 국면 10배를 곱하면
                // 즉시 상한에 붙어 레짐의 동적 범위가 사라진다(레짐 최대치는 2.34).
                double burstMult = fleet == null ? 1.0 : fleet.regimeLambdaMult(props.market());
                // 도착률만 국면 배수를 태운다(레짐 x 국면). interarrivalMs는 λ 값과 무관하게
                // 난수를 1회 소비하므로 배수를 바꿔도 결정 열은 불변이다.
                double arrivalMult = fleet == null ? 1.0 : fleet.takerArrivalMult(props.market());
                Thread.sleep(interarrivalMs(taker.lambda() * arrivalMult));
                JsonNode best = bestOfBook();
                if (best == null) {
                    continue;
                }
                double d = directionScalar();
                boolean buy = rng.nextDouble() < buyProbability(d, kDir);
                double pWhale = Math.min(MULT_PROB_CAP,
                        P_WHALE * (fleet == null ? 1.0 : fleet.regimePWhaleMult(props.market())));
                double pBurst = Math.min(MULT_PROB_CAP, P_BURST * burstMult);
                int burst = burstCount(rng, pBurst, MAX_BURST);

                for (int b = 0; b < burst && running.getAsBoolean(); b++) {
                    if (b > 0) {
                        best = bestOfBook();
                        if (best == null) {
                            break;
                        }
                    }
                    int steps = sizeSteps(rng, baseCap, SIZE_SIGMA, pWhale, WHALE_FACTOR, cap);
                    BigDecimal qty =
                            new BigDecimal(taker.qtyStep()).multiply(BigDecimal.valueOf(steps));
                    if (!sweep(buy, best, qty, taker)) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.debug("{} 테이커 주기 실패, 계속: {}", botId, e.toString());
            }
        }
    }

    /** 한 방향 소비 1건. 호가 없으면 false로 버스트를 끊는다. sell 시장/지정 선택은 marketRatio. */
    private boolean sweep(boolean buy, JsonNode best, BigDecimal qty, SimProperties.Taker taker) {
        if (buy) {
            String askPrice = best.path("ask_price").asText(null);
            if (askPrice == null || askPrice.isEmpty()) {
                return false;
            }
            recordAggressor(true);
            submitLimit("bid", askPrice, qty);
            return true;
        }
        String bidPrice = best.path("bid_price").asText(null);
        if (bidPrice == null || bidPrice.isEmpty()) {
            return false;
        }
        recordAggressor(false);
        if (rng.nextDouble() < taker.marketRatio()) {
            submitMarketSell(qty);
        } else {
            submitLimit("ask", bidPrice, qty);
        }
        return true;
    }

    private long interarrivalMs(double lambda) {
        double u = 1.0 - rng.nextDouble();
        // reactionFactor는 rng 소비 후 결과 ms에만 곱하는 상수 배율이라 결정 열을 바꾸지 않는다.
        long ms = (long) (-Math.log(u) * 1000.0 / lambda * reactionFactor);
        return Math.min(Math.max(ms, 1), 5_000);
    }

    /**
     * 방향 스칼라 d in [-1,1]. STATIC은 effective bias, MOMENTUM/MEAN_REVERT는 공개 신호를
     * SIGNAL_READ_EVERY 주기마다 캐시한다. rng 미소비라 페르소나가 rng 스트림을 바꾸지 않는다.
     */
    private double directionScalar() {
        if (directionSource == DirectionSource.STATIC) {
            return fleet == null ? 0.0 : fleet.effectiveBias(props.market());
        }
        if (signalCycle++ % SIGNAL_READ_EVERY == 0) {
            cachedSignal = readSignal();
        }
        return cachedSignal;
    }

    /** 실패 시 직전 캐시값을 유지한다(신호 결측이 방향을 튀게 하지 않게). */
    private double readSignal() {
        return directionSource == DirectionSource.MOMENTUM ? readMomentum() : readMeanRevert();
    }

    private double readMomentum() {
        BotApiClient.Response r = client.getPublic("/api/v1/ticker?markets=" + props.market());
        if (r.status() != 200) {
            return cachedSignal;
        }
        try {
            JsonNode data = mapper.readTree(r.body()).path("data");
            if (!data.isArray() || data.isEmpty()) {
                return cachedSignal;
            }
            JsonNode t = data.get(0);
            int sign = switch (t.path("change").asText("EVEN")) {
                case "RISE" -> 1;
                case "FALL" -> -1;
                default -> 0;
            };
            BigDecimal rate = decimalOf(t.path("change_rate").asText(null));
            return momentumScalar(sign, rate, MOMENTUM_GAIN);
        } catch (Exception e) {
            return cachedSignal;
        }
    }

    private double readMeanRevert() {
        BotApiClient.Response r = client.getPublic(
                "/api/v1/trades?market=" + props.market() + "&count=" + SMA_N);
        if (r.status() != 200) {
            return cachedSignal;
        }
        try {
            JsonNode data = mapper.readTree(r.body()).path("data");
            if (!data.isArray() || data.isEmpty()) {
                return cachedSignal;
            }
            BigDecimal last = decimalOf(data.get(0).path("price").asText(null));
            BigDecimal sum = BigDecimal.ZERO;
            int n = 0;
            for (JsonNode row : data) {
                BigDecimal p = decimalOf(row.path("price").asText(null));
                if (p != null) {
                    sum = sum.add(p);
                    n++;
                }
            }
            if (n == 0 || last == null) {
                return cachedSignal;
            }
            BigDecimal sma = sum.divide(BigDecimal.valueOf(n), 18, RoundingMode.HALF_UP);
            return meanRevertScalar(last, sma, MEAN_REVERT_GAIN);
        } catch (Exception e) {
            return cachedSignal;
        }
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

    private JsonNode bestOfBook() {
        BotApiClient.Response response =
                client.getPublic("/api/v1/orderbook?market=" + props.market());
        if (response.status() != 200) {
            return null;
        }
        try {
            JsonNode units = mapper.readTree(response.body()).path("orderbook_units");
            return units.isArray() && units.size() > 0 ? units.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void submitLimit(String side, String price, BigDecimal qty) {
        if (!governor.acquire(running)) {
            return;
        }
        String body = "{\"market\":\"" + props.market() + "\",\"side\":\"" + side
                + "\",\"ord_type\":\"limit\",\"price\":\"" + price + "\",\"volume\":\""
                + qty.toPlainString() + "\",\"client_order_id\":\"" + coid() + "\"}";
        report(client.placeOrder(props.market(), body));
    }

    private void submitMarketSell(BigDecimal qty) {
        if (!governor.acquire(running)) {
            return;
        }
        String body = "{\"market\":\"" + props.market()
                + "\",\"side\":\"ask\",\"ord_type\":\"market\",\"volume\":\"" + qty.toPlainString()
                + "\",\"client_order_id\":\"" + coid() + "\"}";
        report(client.placeOrder(props.market(), body));
    }

    /**
     * client_order_id. run 단위 nonce 접두로 재기동 시 직전 run과의 coid 충돌을 막는다(멱등키
     * 재사용에 의한 무의미한 replay 회피). nonce·botId 고정, coidSeq 단조라 한 run 안에서는
     * 결정적이다.
     */
    private String coid() {
        return runNonce + "-" + botId + "-" + (++coidSeq);
    }

    private void recordAggressor(boolean buy) {
        if (fleet != null) {
            fleet.recordAggressor(props.market(), buy);
        }
    }

    private void report(BotApiClient.Response response) {
        if (response.status() == 201 || response.status() == 200) {
            if (fleet != null) {
                fleet.recordFill(props.market());
            }
            return;
        }
        log.debug("{} 주문 거부 {}: {}", botId, response.status(), response.body());
    }
}
