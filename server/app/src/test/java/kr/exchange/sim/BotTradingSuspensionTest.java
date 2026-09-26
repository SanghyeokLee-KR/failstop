package kr.exchange.sim;

import kr.exchange.sim.TradingSuspensionWatchTest.FedStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static kr.exchange.sim.TradingSuspensionWatchTest.ACTIVE;
import static kr.exchange.sim.TradingSuspensionWatchTest.await;
import static kr.exchange.sim.TradingSuspensionWatchTest.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 봇의 423 처리(Patroni 설계 6-3, API 설계 §2-5-e). 423 을 받은 마켓은 재개까지 새 주문·취소를 내지 않고, 423 을 받은
 * client_order_id 는 버리지 않고 재개 뒤 같은 키로 다시 내거나 재조회로 결과를 정한다. 새 키로 바꿔 다시 내는 일이 없어야 한다.
 */
class BotTradingSuspensionTest {

    private static final String MARKET = "KRW-BTC";
    private static final Pattern COID = Pattern.compile("\"client_order_id\":\"([^\"]+)\"");
    private static final Pattern PRICE = Pattern.compile("\"price\":\"([^\"]+)\"");
    private static final BotApiClient.Response SUSPENDED_423 = new BotApiClient.Response(423,
            "{\"error\":{\"name\":\"trading_suspended\",\"message\":\"거래 일시 중단\"}}");

    /** 거래 API. 응답은 시험이 차례로 정하고, 부른 순서와 그때 마켓이 멈춰 있었는지를 적는다. */
    private static final class FakeExchange extends BotApiClient {
        final List<String> placed = new CopyOnWriteArrayList<>();
        final List<String> cancelled = new CopyOnWriteArrayList<>();
        final List<String> requeried = new CopyOnWriteArrayList<>();
        final List<Boolean> suspendedAtWrite = new CopyOnWriteArrayList<>();
        final Deque<Response> placeScript = new ArrayDeque<>();
        final Deque<Response> cancelScript = new ArrayDeque<>();
        final Map<String, Deque<Response>> requeryScript = new HashMap<>();
        TradingSuspensionWatch watch;
        int orderSeq;

        FakeExchange() {
            super("http://127.0.0.1:9", new BotCredentials(1, "ak", "sk", "bot"), "exchange-test");
        }

        @Override
        public synchronized Response placeOrder(String market, String jsonBody) {
            placed.add(jsonBody);
            suspendedAtWrite.add(watch.suspended(MARKET));
            Response scripted = placeScript.poll();
            return scripted != null ? scripted
                    : new Response(201, "{\"id\":\"auto-" + (++orderSeq) + "\",\"status\":\"FILLED\"}");
        }

        @Override
        public synchronized Response cancelOrder(String market, String externalOrderId) {
            cancelled.add(externalOrderId);
            suspendedAtWrite.add(watch.suspended(MARKET));
            Response scripted = cancelScript.poll();
            return scripted != null ? scripted : new Response(200, "{}");
        }

        @Override
        public synchronized Response getSigned(String path, Map<String, String> params) {
            requeried.add(path);
            Deque<Response> script = requeryScript.get(path);
            Response scripted = script == null ? null : script.poll();
            return scripted != null ? scripted : new Response(404, "{\"error\":{\"name\":\"order_not_found\"}}");
        }

        @Override
        public Response getPublic(String pathWithQuery) {
            if (pathWithQuery.startsWith("/api/v1/orderbook")) {
                return new Response(200, "{\"market\":\"KRW-BTC\",\"orderbook_units\":"
                        + "[{\"ask_price\":\"101\",\"bid_price\":\"99\",\"ask_size\":\"1\",\"bid_size\":\"1\"}]}");
            }
            return new Response(404, "");
        }

        synchronized void script(Deque<Response> queue, Response... responses) {
            queue.addAll(List.of(responses));
        }

        synchronized void scriptRequery(String coid, Response... responses) {
            requeryScript.computeIfAbsent("/api/v1/orders/by-client/" + coid, k -> new ArrayDeque<>())
                    .addAll(List.of(responses));
        }
    }

    private static BotApiClient.Response open(String id) {
        return new BotApiClient.Response(201, "{\"id\":\"" + id + "\",\"status\":\"OPEN\"}");
    }

    private static String coid(String body) {
        Matcher m = COID.matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    private static String price(String body) {
        Matcher m = PRICE.matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    private static MarketMakerBot marketMaker(FakeExchange exchange, TradingSuspensionWatch watch) {
        SimProperties props = new SimProperties("http://127.0.0.1:9", 7, 1000, MARKET, "100", null,
                new SimProperties.MarketMaker(1, 10, 3, "0.01", 500), null, null);
        exchange.watch = watch;
        return new MarketMakerBot("mm-btc-1", "run", exchange, 11, props, BigDecimal.ONE, BigDecimal.ZERO,
                () -> true, new OpsGovernor(100_000), null, watch, 1);
    }

    private static void resume(FedStatus status, TradingSuspensionWatch watch) throws InterruptedException {
        status.feed(ok(ACTIVE));
        await(() -> !watch.suspended(MARKET), "재개");
    }

    @Test
    void takerHoldsThe423BodyAndResendsTheSameKeyAfterResume() throws Exception {
        FakeExchange exchange = new FakeExchange();
        FedStatus status = new FedStatus();
        TradingSuspensionWatch watch = new TradingSuspensionWatch(status, Duration.ofMillis(20));
        exchange.watch = watch;
        exchange.script(exchange.placeScript, SUSPENDED_423);
        SimProperties props = new SimProperties("http://127.0.0.1:9", 7, 1000, MARKET, "100", null, null,
                new SimProperties.Taker(1, 200, "0.001", 1, 0.0), null);
        AtomicBoolean running = new AtomicBoolean(true);
        TakerBot taker = new TakerBot("tk-btc-1", "run", exchange, 5, props, 0.8, 1, running::get,
                new OpsGovernor(100_000), null, BotType.TAKER, 1, DirectionSource.STATIC, watch);
        Thread thread = Thread.ofVirtual().start(taker);
        try {
            await(() -> exchange.placed.size() == 1 && watch.suspended(MARKET), "첫 주문이 423 을 받아 마켓 정지");
            Thread.sleep(200);
            assertEquals(1, exchange.placed.size(), "중단 동안 새 주문을 내지 않는다");

            resume(status, watch);
            await(() -> exchange.placed.size() >= 3, "재개 뒤 재전송과 새 주문");
            assertEquals(exchange.placed.get(0), exchange.placed.get(1), "423 을 받은 주문을 같은 키·같은 내용으로 다시 낸다");
            assertNotEquals(coid(exchange.placed.get(0)), coid(exchange.placed.get(2)), "그 뒤는 새 주문이다");
            // 봇이 계속 내는 중이다. 부분 목록은 원본이 바뀌면 예외를 내므로 복사본을 본다.
            List<Boolean> atWrite = List.copyOf(exchange.suspendedAtWrite);
            assertFalse(atWrite.subList(1, atWrite.size()).contains(true), "멈춘 마켓에 쓰기를 보내지 않았다");
        } finally {
            running.set(false);
            thread.interrupt();
            thread.join(5_000);
            watch.close();
        }
    }

    @Test
    void marketMakerResendsThe423KeyForTheSameLevelAndDoesNotBackOff() throws Exception {
        FakeExchange exchange = new FakeExchange();
        FedStatus status = new FedStatus();
        TradingSuspensionWatch watch = new TradingSuspensionWatch(status, Duration.ofMillis(20));
        MarketMakerBot mm = marketMaker(exchange, watch);
        exchange.script(exchange.placeScript, SUSPENDED_423, open("o-1"), open("o-2"));
        Map<Long, Long> ladder = Map.of(100L, 1L, 110L, 1L);

        MarketMakerBot.ReconcileOutcome first = mm.reconcile(ladder, 105, "0.01");
        assertTrue(first.suspended());
        assertEquals(0, first.placeAttempts(), "423 은 거부가 아니라 백오프에 세지 않는다");
        assertEquals(1, exchange.placed.size(), "남은 레벨은 재개 뒤에 맞춘다");
        assertTrue(watch.suspended(MARKET));

        resume(status, watch);
        MarketMakerBot.ReconcileOutcome second = mm.reconcile(ladder, 105, "0.01");
        assertEquals(new MarketMakerBot.ReconcileOutcome(2, 2, false), second);
        assertEquals(3, exchange.placed.size());
        assertEquals("100", price(exchange.placed.get(1)));
        assertEquals(coid(exchange.placed.get(0)), coid(exchange.placed.get(1)), "같은 레벨은 같은 키로 다시 낸다");
        assertNotEquals(coid(exchange.placed.get(0)), coid(exchange.placed.get(2)));
        assertTrue(exchange.requeried.isEmpty(), "다시 낸 키는 재조회하지 않는다");
        watch.close();
    }

    @Test
    void marketMakerKeepsTrackingAnOrderWhoseCancelGot423() throws Exception {
        FakeExchange exchange = new FakeExchange();
        FedStatus status = new FedStatus();
        TradingSuspensionWatch watch = new TradingSuspensionWatch(status, Duration.ofMillis(20));
        MarketMakerBot mm = marketMaker(exchange, watch);
        exchange.script(exchange.placeScript, open("o-1"), open("o-2"));
        exchange.script(exchange.cancelScript, SUSPENDED_423);

        mm.reconcile(Map.of(100L, 1L), 105, "0.01");
        MarketMakerBot.ReconcileOutcome cut = mm.reconcile(Map.of(110L, 1L), 105, "0.01");
        assertTrue(cut.suspended());
        assertEquals(List.of("o-1"), exchange.cancelled);
        assertEquals(1, exchange.placed.size(), "취소가 멈추면 새 레벨도 내지 않는다");

        resume(status, watch);
        mm.reconcile(Map.of(110L, 1L), 105, "0.01");
        assertEquals(List.of("o-1", "o-1"), exchange.cancelled, "423 을 받은 취소는 재개 뒤 다시 낸다");
        assertEquals(2, exchange.placed.size());
        watch.close();
    }

    @Test
    void marketMakerRequeriesA423KeyWhoseLevelLeftTheLadder() throws Exception {
        FakeExchange exchange = new FakeExchange();
        FedStatus status = new FedStatus();
        TradingSuspensionWatch watch = new TradingSuspensionWatch(status, Duration.ofMillis(20));
        MarketMakerBot mm = marketMaker(exchange, watch);
        exchange.script(exchange.placeScript, SUSPENDED_423, open("o-2"), open("o-3"));

        mm.reconcile(Map.of(100L, 1L), 105, "0.01");
        String held = coid(exchange.placed.get(0));
        exchange.scriptRequery(held, new BotApiClient.Response(409,
                "{\"error\":{\"name\":\"order_in_progress\"}}"));
        resume(status, watch);

        // 결과 확인 중(409)이면 키를 두고 다음 주기에 다시 묻는다.
        assertEquals(new MarketMakerBot.ReconcileOutcome(1, 1, false), mm.reconcile(Map.of(120L, 1L), 105, "0.01"));
        assertEquals(List.of("/api/v1/orders/by-client/" + held), exchange.requeried);
        assertNotEquals(held, coid(exchange.placed.get(1)), "다른 레벨의 주문은 새 키다");

        // 404 면 어느 시도도 커밋되지 않았다. 버리고 더 묻지 않는다.
        mm.reconcile(Map.of(120L, 1L), 105, "0.01");
        mm.reconcile(Map.of(120L, 1L), 105, "0.01");
        assertEquals(2, exchange.requeried.size());

        // 버린 뒤 그 레벨을 다시 내면 새 키다. 옛 키의 결과가 정해졌기 때문이다.
        mm.reconcile(Map.of(100L, 1L, 120L, 1L), 105, "0.01");
        assertEquals("100", price(exchange.placed.get(2)));
        assertNotEquals(held, coid(exchange.placed.get(2)));
        watch.close();
    }

    @Test
    void marketMakerDoesNotRequeryWhileTheMarketIsStillSuspended() throws Exception {
        FakeExchange exchange = new FakeExchange();
        FedStatus status = new FedStatus();
        TradingSuspensionWatch watch = new TradingSuspensionWatch(status, Duration.ofMillis(20));
        MarketMakerBot mm = marketMaker(exchange, watch);
        exchange.script(exchange.placeScript, SUSPENDED_423, open("o-2"));

        mm.reconcile(Map.of(100L, 1L), 105, "0.01");
        assertTrue(watch.suspended(MARKET));
        // 중단 중에 사다리가 옮겨 갔다. 재조회는 재개 뒤에 해야 한다. 발주는 재개까지 기다린다.
        Thread worker = Thread.ofVirtual().start(() -> mm.reconcile(Map.of(120L, 1L), 105, "0.01"));
        Thread.sleep(200);
        assertTrue(exchange.requeried.isEmpty(), "중단 동안 재조회하지 않는다");
        assertEquals(1, exchange.placed.size());

        resume(status, watch);
        worker.join(5_000);
        assertFalse(worker.isAlive());
        assertEquals(2, exchange.placed.size(), "재개 뒤 새 레벨을 낸다");
        mm.reconcile(Map.of(120L, 1L), 105, "0.01");
        assertEquals(List.of("/api/v1/orders/by-client/" + coid(exchange.placed.get(0))), exchange.requeried,
                "다음 주기에 423 키를 재조회한다");
        watch.close();
    }

    @Test
    void marketMakerCancelsAnEarlierAttemptThatTurnsOutCommitted() throws Exception {
        FakeExchange exchange = new FakeExchange();
        FedStatus status = new FedStatus();
        TradingSuspensionWatch watch = new TradingSuspensionWatch(status, Duration.ofMillis(20));
        MarketMakerBot mm = marketMaker(exchange, watch);
        exchange.script(exchange.placeScript, SUSPENDED_423, open("o-2"));

        mm.reconcile(Map.of(100L, 1L), 105, "0.01");
        String held = coid(exchange.placed.get(0));
        // 같은 키의 앞선 시도가 커밋돼 있었다. 재조회가 살아 있는 주문을 돌려준다.
        exchange.scriptRequery(held, new BotApiClient.Response(200,
                "{\"id\":\"o-9\",\"status\":\"OPEN\",\"client_order_id\":\"" + held + "\"}"));
        resume(status, watch);

        mm.reconcile(Map.of(120L, 1L), 105, "0.01");
        assertEquals(List.of("o-9"), exchange.cancelled, "사다리에서 빠진 레벨의 주문을 추적에 넣어 취소한다");
        assertEquals("120", price(exchange.placed.get(1)));
        watch.close();
    }

    @Test
    void marketMakerCancelsACommittedDuplicateAtALevelThatAlreadyRests() throws Exception {
        FakeExchange exchange = new FakeExchange();
        FedStatus status = new FedStatus();
        TradingSuspensionWatch watch = new TradingSuspensionWatch(status, Duration.ofMillis(20));
        MarketMakerBot mm = marketMaker(exchange, watch);
        exchange.script(exchange.placeScript, SUSPENDED_423,
                new BotApiClient.Response(409, "{\"error\":{\"name\":\"duplicate_client_order_id\"}}"),
                open("o-2"));

        mm.reconcile(Map.of(100L, 1L), 105, "0.01");
        String held = coid(exchange.placed.get(0));
        exchange.scriptRequery(held,
                new BotApiClient.Response(409, "{\"error\":{\"name\":\"order_in_progress\"}}"),
                new BotApiClient.Response(200, "{\"id\":\"o-1\",\"status\":\"PARTIAL\"}"));
        resume(status, watch);

        // 같은 키가 다른 내용으로 이미 쓰였다(409 duplicate). 레벨은 새 키로 내고, 옛 키는 재조회가 정한다.
        mm.reconcile(Map.of(100L, 1L), 105, "0.01");
        mm.reconcile(Map.of(100L, 1L), 105, "0.01");
        assertEquals(3, exchange.placed.size());
        assertNotEquals(held, coid(exchange.placed.get(2)));
        assertTrue(exchange.cancelled.isEmpty());

        // 재조회가 그 키의 주문을 찾았고 레벨에는 이미 새 주문이 있다. 찾은 주문을 바로 취소한다.
        mm.reconcile(Map.of(100L, 1L), 105, "0.01");
        assertEquals(List.of("o-1"), exchange.cancelled);
        assertEquals(3, exchange.placed.size(), "레벨의 새 주문은 그대로 둔다");
        watch.close();
    }
}
