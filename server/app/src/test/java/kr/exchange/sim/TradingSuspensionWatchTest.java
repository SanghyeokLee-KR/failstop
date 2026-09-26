package kr.exchange.sim;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 봇 플릿의 거래 일시 중단 대기(Patroni 설계 6-3). 423 을 받은 마켓만 멈추고, 상태 API 가 재개를 알리기 전에는 열지 않으며,
 * 재개 직후의 늦은 423 이나 플릿 정지에도 마켓이 닫힌 채 남지 않는지 본다. 상태 응답은 시험이 넣어 줄 때까지 폴러가 기다리므로
 * 순서가 시각에 기대지 않는다.
 */
class TradingSuspensionWatchTest {

    static final String SUSPENDED =
            "{\"schema_version\":1,\"trading_suspended\":true,\"trading_state\":\"SUSPENDED\"}";
    static final String ACTIVE =
            "{\"schema_version\":1,\"trading_suspended\":false,\"trading_state\":\"ACTIVE\"}";
    private static final Duration FAST = Duration.ofMillis(20);

    /** 상태 API. 시험이 feed 로 넣은 응답을 하나씩 돌려주고, 없으면 들어올 때까지 기다린다. */
    static final class FedStatus extends BotApiClient {
        private final LinkedBlockingDeque<Response> feed = new LinkedBlockingDeque<>();
        private final AtomicInteger entered = new AtomicInteger();
        private final List<Long> servedNanos = new CopyOnWriteArrayList<>();

        FedStatus() {
            super("http://127.0.0.1:9", new BotCredentials(0, "", "", ""), "status-test");
        }

        @Override
        public Response getPublic(String pathWithQuery) {
            if (!TradingSuspensionWatch.STATUS_PATH.equals(pathWithQuery)) {
                return new Response(404, "");
            }
            entered.incrementAndGet();
            try {
                Response response = feed.take();
                servedNanos.add(System.nanoTime());
                return response;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Response(-1, "interrupted");
            }
        }

        void feed(Response... responses) {
            for (Response response : responses) {
                feed.add(response);
            }
        }

        int entered() {
            return entered.get();
        }

        int served() {
            return servedNanos.size();
        }

        long nanosBetween(int first, int second) {
            return servedNanos.get(second) - servedNanos.get(first);
        }
    }

    static BotApiClient.Response ok(String body) {
        return new BotApiClient.Response(200, body);
    }

    static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("시간 안에 이뤄지지 않음: " + what);
            }
            Thread.sleep(5);
        }
    }

    @Test
    void stopsOnlyTheMarketThatGot423AndResumeOpensEveryStoppedMarket() throws Exception {
        FedStatus status = new FedStatus();
        TradingSuspensionWatch watch = new TradingSuspensionWatch(status, FAST);
        try {
            watch.onSuspended("KRW-BTC");
            assertTrue(watch.suspended("KRW-BTC"));
            assertFalse(watch.suspended("KRW-ETH"), "423 을 받지 않은 마켓은 계속 발행한다");
            assertTrue(watch.awaitOpen("KRW-ETH", () -> true));

            watch.onSuspended("KRW-ETH");
            status.feed(ok(SUSPENDED), ok(SUSPENDED));
            await(() -> status.entered() >= 3, "중단을 두 번 읽고 세 번째를 기다림");
            assertTrue(watch.suspended("KRW-BTC") && watch.suspended("KRW-ETH"), "재개 응답 전에는 열지 않는다");

            status.feed(ok(ACTIVE));
            await(() -> !watch.suspended("KRW-BTC") && !watch.suspended("KRW-ETH"), "재개 뒤 두 마켓이 열림");
            await(() -> !watch.polling(), "재개 뒤 폴러 종료");
            assertEquals(3, status.served(), "폴러는 하나뿐이다");
        } finally {
            watch.close();
        }
    }

    @Test
    void errorsAndMalformedStatusKeepTheMarketStopped() throws Exception {
        FedStatus status = new FedStatus();
        TradingSuspensionWatch watch = new TradingSuspensionWatch(status, FAST);
        try {
            status.feed(
                    new BotApiClient.Response(503, "{\"error\":{\"name\":\"database_unavailable\"}}"),
                    new BotApiClient.Response(-1, "io_error"),
                    ok("<html>not json</html>"),
                    ok("[]"),
                    ok("{}"),
                    ok("{\"trading_suspended\":\"no\"}"));
            watch.onSuspended("KRW-BTC");
            await(() -> status.entered() >= 7, "여섯 응답을 모두 읽고 다음을 기다림");
            assertTrue(watch.suspended("KRW-BTC"), "오류·형식 오류·필드 없음은 중단 유지로 읽는다");

            status.feed(ok(ACTIVE));
            await(() -> !watch.suspended("KRW-BTC"), "명시적인 재개 응답 뒤에만 열림");
            assertEquals(7, status.served());
        } finally {
            watch.close();
        }
    }

    @Test
    void late423AfterResumeClosesTheMarketAgainAndPollsAtOnce() throws Exception {
        FedStatus status = new FedStatus();
        // 대체 간격을 길게 둔다. 첫 조회를 기다렸다 하면 아래 await 가 시간 안에 끝나지 않는다.
        TradingSuspensionWatch watch = new TradingSuspensionWatch(status, Duration.ofSeconds(30));
        try {
            status.feed(ok(ACTIVE));
            watch.onSuspended("KRW-BTC");
            await(() -> !watch.suspended("KRW-BTC") && !watch.polling(), "첫 재개");

            // 재개 직전에 보낸 요청의 423 이 늦게 도착했다. 마켓을 닫되 폴러를 새로 띄워야 영영 닫히지 않는다.
            watch.onSuspended("KRW-BTC");
            assertTrue(watch.suspended("KRW-BTC"));
            assertTrue(watch.polling());
            status.feed(ok(ACTIVE));
            await(() -> !watch.suspended("KRW-BTC") && !watch.polling(), "늦은 423 뒤 재개");
            assertEquals(2, status.served());
        } finally {
            watch.close();
        }
    }

    @Test
    void pollAfterSecondsHintIsClampedToAtLeastOneSecond() throws Exception {
        FedStatus status = new FedStatus();
        TradingSuspensionWatch watch = new TradingSuspensionWatch(status, FAST);
        try {
            status.feed(ok("{\"trading_suspended\":true,\"poll_after_seconds\":0}"), ok(ACTIVE));
            watch.onSuspended("KRW-BTC");
            await(() -> !watch.suspended("KRW-BTC"), "재개");
            assertEquals(2, status.served());
            assertTrue(status.nanosBetween(0, 1) >= Duration.ofMillis(900).toNanos(),
                    "권장 간격 0 은 1초로 올려 읽는다");
        } finally {
            watch.close();
        }
    }

    @Test
    void closeOpensEveryMarketAndIgnoresLaterSuspensions() throws Exception {
        FedStatus status = new FedStatus();
        TradingSuspensionWatch watch = new TradingSuspensionWatch(status, FAST);

        status.feed(ok(SUSPENDED));
        watch.onSuspended("KRW-BTC");
        await(() -> status.entered() >= 2, "중단을 읽고 다음 응답을 기다림");
        watch.close();
        assertFalse(watch.suspended("KRW-BTC"), "정지한 플릿은 닫힌 마켓을 다음 기동에 넘기지 않는다");
        assertFalse(watch.polling());

        watch.onSuspended("KRW-BTC");
        assertFalse(watch.suspended("KRW-BTC"), "닫은 뒤 도착한 423 은 무시한다");
        assertFalse(watch.polling());
        int entered = status.entered();
        Thread.sleep(100);
        assertEquals(entered, status.entered(), "폴러가 끝났다");
    }

    @Test
    void awaitOpenReturnsFalseWhenTheFleetStops() {
        FedStatus status = new FedStatus();
        TradingSuspensionWatch watch = new TradingSuspensionWatch(status, FAST);
        try {
            watch.onSuspended("KRW-BTC");
            AtomicInteger checks = new AtomicInteger();
            assertFalse(watch.awaitOpen("KRW-BTC", () -> checks.getAndIncrement() < 3));
            assertTrue(watch.suspended("KRW-BTC"));
        } finally {
            watch.close();
        }
    }
}
