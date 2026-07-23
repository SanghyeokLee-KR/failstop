package kr.exchange.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * MM·TAKER·TREND·NOISE 플릿 수명주기. 시드(계정·자금·키) 후 봇을 가상 스레드로 기동한다.
 * 대수 스케일은 파킹 게이트로 구현한다. 타입별 최대 풀(poolPerType)을 기동하되 각 봇은
 * 인덱스 <= 현재 목표일 때만 활성이다. 목표 초과 봇은 파킹(OPS 미소비)하므로 봇 수 증가가
 * 처리량을 못 올린다(백프레셔 무해). TREND·NOISE는 테이커 프리셋으로 실현한다(설계 §2-1).
 * 마켓 규칙(tick)은 내부 조회가 아니라 공개 API(GET /markets)에서 읽는다(봇=외부 관점).
 */
public class BotFleet {

    private static final Logger log = LoggerFactory.getLogger(BotFleet.class);
    private static final long STOP_JOIN_MS = 5_000;

    private final SimProperties props;
    private final BotSeeder seeder;
    private final OpsGovernor governor;
    private final BotFleetGovernor fleet;
    private final RunOrdinalSource ordinalSource;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Thread> threads = new ArrayList<>();
    private Thread balancer;
    private String runNonce;

    private volatile boolean running;

    public BotFleet(SimProperties props, BotSeeder seeder, OpsGovernor governor,
                    BotFleetGovernor fleet, RunOrdinalSource ordinalSource) {
        this.props = props;
        this.seeder = seeder;
        this.governor = governor;
        this.fleet = fleet;
        this.ordinalSource = ordinalSource;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        // 채번이 던지면 running이 참으로 굳어 이후 start()가 조기 반환한다. 플래그 앞에서 뽑는다.
        String nonce = nextRunNonce(props.seed(), ordinalSource.next());
        running = true;
        runNonce = nonce;
        BigDecimal quoteFunding = new BigDecimal(props.funding().quote());
        List<SimProperties.MarketSpec> specs = resolveMarkets();
        int pool = fleet.poolPerType();
        int marketIndex = 0;
        for (SimProperties.MarketSpec spec : specs) {
            marketIndex++;
            SimProperties per = derive(spec);
            BigDecimal tick = fetchTick(per.market());
            BigDecimal baseFunding = new BigDecimal(spec.baseFunding() != null
                    ? spec.baseFunding() : props.funding().base());
            String suffix = shortName(per.market());
            long marketSalt = marketIndex * 100_000_019L;

            // TREND·NOISE 초기 목표(r4). 0 파킹을 탈피해 페르소나가 상시 구동한다. 명시 없으면
            // taker 수에서 파생한다. governor가 typeCap·marketCap·globalCap으로 클램프한다.
            int trendInit = spec.trendCount() != null ? spec.trendCount() : per.taker().count();
            int noiseInit = spec.noiseCount() != null ? spec.noiseCount() : per.taker().count();
            Map<BotType, Integer> initialTargets = new EnumMap<>(BotType.class);
            initialTargets.put(BotType.MARKET_MAKER, per.marketMaker().count());
            initialTargets.put(BotType.TAKER, per.taker().count());
            initialTargets.put(BotType.TREND, Math.max(0, trendInit));
            initialTargets.put(BotType.NOISE, Math.max(0, noiseInit));
            fleet.registerMarket(per.market(), new BigDecimal(per.initialPrice()), initialTargets,
                    BotFleetGovernor.RegimeProfile.parse(spec.regimeProfile()));

            for (int i = 1; i <= pool; i++) {
                String botId = "mm-" + suffix + "-" + i;
                BotCredentials creds = seeder.seed(botId + "@bots.exchange.local", per.market(),
                        quoteFunding, baseFunding);
                MarketMakerBot bot = new MarketMakerBot(botId, runNonce,
                        new BotApiClient(per.baseUrl(), creds, botId),
                        per.seed() * 1_000_003L + marketSalt + i, per, tick, baseFunding,
                        () -> running, governor, fleet, i);
                threads.add(Thread.ofVirtual().name("sim-" + botId).start(bot));
            }
            spawnTakers(BotType.TAKER, "tk-" + suffix, per, marketSalt, 2_000_003L,
                    0.8, 1, quoteFunding, baseFunding, pool);
            spawnTakers(BotType.TREND, "tr-" + suffix, per, marketSalt, 3_000_003L,
                    1.0, 1, quoteFunding, baseFunding, pool);
            spawnTakers(BotType.NOISE, "ns-" + suffix, per, marketSalt, 4_000_003L,
                    0.0, 2, quoteFunding, baseFunding, pool);
        }
        startBalancer();
        log.info("봇 플릿 기동: 마켓 {} 타입당 풀 {} 캡(타입 {}) 균형주기 {}ms targetOps {}",
                specs.size(), pool, fleet.typeCap(), BotFleetGovernor.CONTROL_PERIOD_MS,
                governor.targetOps());
    }

    private void spawnTakers(BotType type, String idPrefix, SimProperties per, long marketSalt,
                             long seedMul, double kDir, int stepMul, BigDecimal quoteFunding,
                             BigDecimal baseFunding, int pool) {
        for (int i = 1; i <= pool; i++) {
            String botId = idPrefix + "-" + i;
            BotCredentials creds = seeder.seed(botId + "@bots.exchange.local", per.market(),
                    quoteFunding, baseFunding);
            // TREND는 index 결정론으로 momentum/mean-revert 이분(r7), TAKER·NOISE는 STATIC.
            DirectionSource ds = DirectionSource.of(type, i);
            TakerBot bot = new TakerBot(botId, runNonce,
                    new BotApiClient(per.baseUrl(), creds, botId),
                    per.seed() * seedMul + marketSalt + i, per, kDir, stepMul, () -> running,
                    governor, fleet, type, i, ds);
            threads.add(Thread.ofVirtual().name("sim-" + botId).start(bot));
        }
    }

    /** 자동균형 외부 제어 루프. 느린 고정 주기로 measured 집계·effective 수렴을 돌린다(§4). */
    private void startBalancer() {
        balancer = Thread.ofVirtual().name("sim-balancer").start(() -> {
            while (running) {
                try {
                    Thread.sleep(BotFleetGovernor.CONTROL_PERIOD_MS);
                    fleet.controlStep();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.debug("균형 루프 스텝 실패, 계속: {}", e.toString());
                }
            }
        });
    }

    /** markets 미지정 시 top-level 단일 마켓으로 폴백한다(하위호환). */
    private List<SimProperties.MarketSpec> resolveMarkets() {
        if (props.markets() != null && !props.markets().isEmpty()) {
            return props.markets();
        }
        return List.of(new SimProperties.MarketSpec(props.market(), props.initialPrice(),
                props.marketMaker().count(), props.taker().count(),
                props.marketMaker().quoteSize(), props.taker().qtyStep(), props.funding().base(),
                null, null, null));
    }

    /** MarketSpec을 마켓별 SimProperties 뷰로 투영한다. 지정 안 된 필드는 top-level 값 유지. */
    private SimProperties derive(SimProperties.MarketSpec spec) {
        SimProperties.MarketMaker mm = props.marketMaker();
        SimProperties.Taker tk = props.taker();
        SimProperties.MarketMaker perMm = new SimProperties.MarketMaker(
                spec.mmCount() != null ? spec.mmCount() : mm.count(),
                mm.spreadTicks(), mm.driftTicks(),
                spec.quoteSize() != null ? spec.quoteSize() : mm.quoteSize(),
                mm.requoteMs());
        SimProperties.Taker perTk = new SimProperties.Taker(
                spec.takerCount() != null ? spec.takerCount() : tk.count(),
                tk.lambda(),
                spec.qtyStep() != null ? spec.qtyStep() : tk.qtyStep(),
                tk.maxSteps(), tk.marketRatio());
        return new SimProperties(props.baseUrl(), props.seed(), props.targetOps(),
                spec.symbol(), spec.initialPrice(), props.funding(), perMm, perTk, List.of());
    }

    /**
     * run 단위 client_order_id nonce. 같은 (seed, ordinal)이면 재현 동일이라 한 run 수명 동안
     * coid 접두가 고정된다. splitmix64 finalizer가 64비트 전단사라 서로 다른 ordinal은 확률이
     * 아니라 항상 서로 다른 nonce를 낸다. ordinal의 유일성 요건은 {@link RunOrdinalSource} 참조.
     * coid는 봇 클라이언트 산물이고 재생 정본은 command_journal이라 run별 nonce 차이는 엔진
     * 재생 결정론에 무관하다.
     */
    static String nextRunNonce(long seed, long runOrdinal) {
        long z = seed * 0x9E3779B97F4A7C15L + runOrdinal;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return Long.toUnsignedString(z, 36);
    }

    private static String shortName(String symbol) {
        int dash = symbol.indexOf('-');
        return (dash >= 0 ? symbol.substring(dash + 1) : symbol).toLowerCase(java.util.Locale.ROOT);
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (balancer != null) {
            balancer.interrupt();
        }
        for (Thread thread : threads) {
            thread.interrupt();
        }
        long deadline = System.currentTimeMillis() + STOP_JOIN_MS;
        for (Thread thread : threads) {
            try {
                thread.join(Math.max(1, deadline - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        boolean alive = threads.stream().anyMatch(Thread::isAlive);
        threads.clear();
        balancer = null;
        if (alive) {
            log.warn("봇 스레드 일부가 종료 대기 시간 내에 끝나지 않았다");
        } else {
            log.info("봇 플릿 정지 완료");
        }
    }

    private BigDecimal fetchTick(String symbol) {
        BotApiClient publicClient = new BotApiClient(props.baseUrl(),
                new BotCredentials(0, "", "", ""), "meta");
        BotApiClient.Response response = publicClient.getPublic("/api/v1/markets");
        if (response.status() != 200) {
            throw new IllegalStateException("마켓 메타 조회 실패: " + response.status());
        }
        try {
            for (JsonNode market : mapper.readTree(response.body()).path("data")) {
                if (symbol.equals(market.path("market").asText())) {
                    return new BigDecimal(market.path("tick_size").asText());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("마켓 메타 파싱 실패", e);
        }
        throw new IllegalStateException("미등록 마켓: " + symbol);
    }
}
