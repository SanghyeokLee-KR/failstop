package kr.exchange.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;

/**
 * 양방향 호가 공급 봇. 주기마다 직전 호가를 취소하고 fair value 주변 스프레드로 재호가한다.
 * fair value는 시드 PRNG의 틱 단위 랜덤워크에 bias 유래 틱 오프셋을 더한 것이다(설계 §2-3).
 * 오프셋은 정수 틱(long)으로 양자화한 뒤에만 가격에 반영되므로 bias 분수는 NUMERIC 산술에
 * 곱해지지 않는다. 가격·수량은 틱·로트 정수 배수 + BigDecimal로만 구성한다.
 */
public class MarketMakerBot implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MarketMakerBot.class);
    private static final double K_MM = 0.5;
    private static final long PARK_MS = 400;

    private final String botId;
    private final BotApiClient client;
    private final SplittableRandom rng;
    private final SimProperties props;
    private final BigDecimal tick;
    private final BooleanSupplier running;
    private final OpsGovernor governor;
    private final BotFleetGovernor fleet;
    private final int index;
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<String> openOrders = new ArrayList<>();
    private long fairTicks;
    private long coidSeq;

    public MarketMakerBot(String botId, BotApiClient client, long seed, SimProperties props,
                          BigDecimal tick, BooleanSupplier running, OpsGovernor governor,
                          BotFleetGovernor fleet, int index) {
        this.botId = botId;
        this.client = client;
        this.rng = new SplittableRandom(seed);
        this.props = props;
        this.tick = tick;
        this.running = running;
        this.governor = governor;
        this.fleet = fleet;
        this.index = index;
        this.fairTicks = new BigDecimal(props.initialPrice())
                .divideToIntegralValue(tick).longValueExact();
    }

    /**
     * bias 유래 드리프트 오프셋(정수 틱). b = round(bias * driftTicks * K_MM). 미드가 bias
     * 방향으로 서서히 이동한다. (bias, driftTicks) 순수 함수이며 결과는 long이라 이후 가격
     * 산술은 틱 정수 배수를 유지한다(설계 §2-2, §2-3).
     */
    public static long driftOffset(double bias, int driftTicks, double kMm) {
        double clamped = Math.max(-1.0, Math.min(1.0, bias));
        return Math.round(clamped * driftTicks * kMm);
    }

    @Override
    public void run() {
        SimProperties.MarketMaker mm = props.marketMaker();
        while (running.getAsBoolean()) {
            try {
                if (fleet != null && !fleet.active(props.market(), BotType.MARKET_MAKER, index)) {
                    Thread.sleep(PARK_MS);
                    continue;
                }
                cancelOpenOrders();
                drift(mm);
                if (fleet != null) {
                    fleet.recordPrice(props.market(), tick.multiply(BigDecimal.valueOf(fairTicks)));
                }
                long half = mm.spreadTicks() / 2;
                quote("bid", fairTicks - half, mm.quoteSize());
                quote("ask", fairTicks + Math.max(half, 1), mm.quoteSize());
                Thread.sleep(mm.requoteMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.debug("{} 재호가 실패, 다음 주기 계속: {}", botId, e.toString());
            }
        }
    }

    private void drift(SimProperties.MarketMaker mm) {
        double bias = fleet == null ? 0.0 : fleet.effectiveBias(props.market());
        fairTicks += rng.nextLong(-mm.driftTicks(), mm.driftTicks() + 1L)
                + driftOffset(bias, mm.driftTicks(), K_MM);
        // bid가 0 이하로 내려가지 않게 하한을 둔다. bias 음전에서도 이 가드가 유지된다.
        fairTicks = Math.max(fairTicks, mm.spreadTicks() + 1L);
    }

    private void quote(String side, long priceTicks, String volume) {
        if (!governor.acquire(running)) {
            return;
        }
        String price = tick.multiply(BigDecimal.valueOf(priceTicks)).toPlainString();
        String body = "{\"market\":\"" + props.market() + "\",\"side\":\"" + side
                + "\",\"ord_type\":\"limit\",\"price\":\"" + price + "\",\"volume\":\"" + volume
                + "\",\"client_order_id\":\"" + botId + "-" + (++coidSeq) + "\"}";
        BotApiClient.Response response = client.placeOrder(body);
        if (response.status() == 201 || response.status() == 200) {
            try {
                JsonNode order = mapper.readTree(response.body());
                String status = order.path("status").asText();
                if ("OPEN".equals(status) || "PARTIAL".equals(status)) {
                    openOrders.add(order.path("id").asText());
                }
            } catch (Exception e) {
                log.debug("{} 주문 응답 파싱 실패: {}", botId, e.toString());
            }
        } else {
            log.debug("{} 호가 거부 {}: {}", botId, response.status(), response.body());
        }
    }

    private void cancelOpenOrders() {
        List<String> stale = new ArrayList<>(openOrders);
        openOrders.clear();
        for (String orderId : stale) {
            if (!governor.acquire(running)) {
                return;
            }
            client.cancelOrder(orderId);
        }
    }
}
