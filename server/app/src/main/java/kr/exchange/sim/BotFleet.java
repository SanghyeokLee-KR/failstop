package kr.exchange.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * MM·TAKER 플릿 수명주기. 시드(계정·자금·키) 후 봇을 가상 스레드로 기동한다.
 * 마켓 규칙(tick)은 내부 조회가 아니라 공개 API(GET /markets)에서 읽는다(봇=외부 관점).
 */
public class BotFleet {

    private static final Logger log = LoggerFactory.getLogger(BotFleet.class);
    private static final long STOP_JOIN_MS = 5_000;

    private final SimProperties props;
    private final BotSeeder seeder;
    private final OpsGovernor governor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Thread> threads = new ArrayList<>();

    private volatile boolean running;

    public BotFleet(SimProperties props, BotSeeder seeder, OpsGovernor governor) {
        this.props = props;
        this.seeder = seeder;
        this.governor = governor;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        BigDecimal quoteFunding = new BigDecimal(props.funding().quote());
        List<SimProperties.MarketSpec> specs = resolveMarkets();
        int totalMm = 0;
        int totalTaker = 0;
        int marketIndex = 0;
        for (SimProperties.MarketSpec spec : specs) {
            marketIndex++;
            SimProperties per = derive(spec);
            BigDecimal tick = fetchTick(per.market());
            BigDecimal baseFunding = new BigDecimal(spec.baseFunding() != null
                    ? spec.baseFunding() : props.funding().base());
            String suffix = shortName(per.market());
            // seed에 마켓 인덱스를 섞어 마켓 간 봇 의사결정이 서로 다른 열을 갖게 한다.
            long marketSalt = marketIndex * 100_000_019L;
            for (int i = 1; i <= per.marketMaker().count(); i++) {
                String botId = "mm-" + suffix + "-" + i;
                BotCredentials creds = seeder.seed(botId + "@bots.exchange.local", per.market(),
                        quoteFunding, baseFunding);
                MarketMakerBot bot = new MarketMakerBot(botId,
                        new BotApiClient(per.baseUrl(), creds, botId),
                        per.seed() * 1_000_003L + marketSalt + i, per, tick, () -> running, governor);
                threads.add(Thread.ofVirtual().name("sim-" + botId).start(bot));
                totalMm++;
            }
            for (int i = 1; i <= per.taker().count(); i++) {
                String botId = "tk-" + suffix + "-" + i;
                BotCredentials creds = seeder.seed(botId + "@bots.exchange.local", per.market(),
                        quoteFunding, baseFunding);
                TakerBot bot = new TakerBot(botId,
                        new BotApiClient(per.baseUrl(), creds, botId),
                        per.seed() * 2_000_003L + marketSalt + i, per, () -> running, governor);
                threads.add(Thread.ofVirtual().name("sim-" + botId).start(bot));
                totalTaker++;
            }
        }
        log.info("봇 플릿 기동: 마켓 {} MM {} TAKER {} targetOps {}",
                specs.size(), totalMm, totalTaker, governor.targetOps());
    }

    /** markets 미지정 시 top-level 단일 마켓으로 폴백한다(하위호환). */
    private List<SimProperties.MarketSpec> resolveMarkets() {
        if (props.markets() != null && !props.markets().isEmpty()) {
            return props.markets();
        }
        return List.of(new SimProperties.MarketSpec(props.market(), props.initialPrice(),
                props.marketMaker().count(), props.taker().count(),
                props.marketMaker().quoteSize(), props.taker().qtyStep(), props.funding().base()));
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
