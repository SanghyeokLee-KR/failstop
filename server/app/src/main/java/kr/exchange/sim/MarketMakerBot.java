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
 * fair value는 시드 PRNG의 틱 단위 랜덤워크다. 가격·수량 산술은 틱·로트 정수 배수로만
 * 구성해 BigDecimal 외 수치 표현이 자금 경로에 끼지 않는다.
 */
public class MarketMakerBot implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MarketMakerBot.class);

    private final String botId;
    private final BotApiClient client;
    private final SplittableRandom rng;
    private final SimProperties props;
    private final BigDecimal tick;
    private final BooleanSupplier running;
    private final OpsGovernor governor;
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<String> openOrders = new ArrayList<>();
    private long fairTicks;
    private long coidSeq;

    public MarketMakerBot(String botId, BotApiClient client, long seed, SimProperties props,
                          BigDecimal tick, BooleanSupplier running, OpsGovernor governor) {
        this.botId = botId;
        this.client = client;
        this.rng = new SplittableRandom(seed);
        this.props = props;
        this.tick = tick;
        this.running = running;
        this.governor = governor;
        this.fairTicks = new BigDecimal(props.initialPrice())
                .divideToIntegralValue(tick).longValueExact();
    }

    @Override
    public void run() {
        SimProperties.MarketMaker mm = props.marketMaker();
        while (running.getAsBoolean()) {
            try {
                cancelOpenOrders();
                drift(mm);
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
        fairTicks += rng.nextLong(-mm.driftTicks(), mm.driftTicks() + 1L);
        // bid가 0 이하로 내려가지 않게 하한을 둔다. 상한은 잔고 가드가 자연 제약한다.
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
