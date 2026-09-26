package kr.exchange.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * 봇 플릿의 거래 일시 중단 대기(Patroni 설계 6-3, API 설계 §2-5-e). 어느 마켓의 주문·취소가 423 trading_suspended 를 받으면 그
 * 마켓의 봇 발행을 멈추고, 상태 API 가 재개를 알릴 때까지 권장 간격마다 읽는다. 423 을 받은 client_order_id 는 각 봇이 버리지
 * 않고 재개 뒤 같은 키로 다시 내거나 재조회로 결과를 정한다.
 *
 * <p>상태 API 는 클러스터 전체의 상태 하나만 알리므로 재개는 멈춘 마켓을 모두 연다. 운영자의 일시정지(OpsGovernor)와는 따로 둔다.
 * 재개가 운영자의 정지를 풀면 안 된다.
 *
 * <p>마켓을 여는 일과 폴러를 비우는 일은 한 잠금 안에서 함께 한다. 따로 하면 그 사이에 도착한 423 이 마켓을 다시 닫고도 폴러를
 * 띄우지 못한다. 발행이 멈춘 봇은 새 423 을 받을 일이 없으므로 그 마켓은 영영 닫힌 채 남는다.
 */
public final class TradingSuspensionWatch {

    private static final Logger log = LoggerFactory.getLogger(TradingSuspensionWatch.class);
    static final String STATUS_PATH = "/api/v1/status";
    private static final Duration DEFAULT_POLL = Duration.ofSeconds(5);
    private static final int MIN_POLL_SECONDS = 1;
    private static final int MAX_POLL_SECONDS = 30;
    private static final long OPEN_CHECK_MS = 50;

    private final BotApiClient statusClient;
    private final Duration fallbackPoll;
    private final ObjectMapper mapper = new ObjectMapper();
    // 읽기는 봇 스레드마다 잦으므로 잠금 없이 한다. 바꾸는 일은 poller 와 함께 this 잠금 안에서만 한다.
    private final Set<String> suspended = ConcurrentHashMap.newKeySet();
    private Thread poller;
    private boolean closed;

    public TradingSuspensionWatch(BotApiClient statusClient) {
        this(statusClient, DEFAULT_POLL);
    }

    /** 시험용. 상태 응답이 권장 간격을 주지 않거나 읽히지 않을 때 기다리는 시간을 바꾼다. */
    TradingSuspensionWatch(BotApiClient statusClient, Duration fallbackPoll) {
        this.statusClient = statusClient;
        this.fallbackPoll = fallbackPoll;
    }

    /** 이 마켓의 주문 또는 취소가 423 을 받았다. 마켓을 멈추고, 재개를 확인하는 폴러가 없으면 하나 띄운다. */
    public synchronized void onSuspended(String market) {
        if (closed) {
            return;
        }
        if (suspended.add(market)) {
            log.warn("{} 주문이 423 trading_suspended 를 받았다. 재개를 확인할 때까지 이 마켓의 봇 발행을 멈춘다", market);
        }
        if (poller == null) {
            poller = Thread.ofVirtual().name("sim-trading-status").start(this::pollUntilResumed);
        }
    }

    public boolean suspended(String market) {
        return suspended.contains(market);
    }

    /**
     * 이 마켓이 열릴 때까지 기다린다. 열려 있으면 바로 true 다. 플릿이 멈추면 false 로 돌아가 봇 루프를 끝낸다. 토큰(OpsGovernor)을
     * 얻기 전에 부른다. 멈춘 마켓의 봇이 토큰을 쥐고 기다리면 다른 마켓의 발행까지 줄어든다.
     */
    public boolean awaitOpen(String market, BooleanSupplier running) {
        while (running.getAsBoolean()) {
            if (!suspended.contains(market)) {
                return true;
            }
            try {
                Thread.sleep(OPEN_CHECK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 플릿 정지. 폴러를 끝내고 모든 마켓을 연다. 다음 기동은 발행 가능 상태로 시작해 423 을 다시 받으면 다시 멈춘다. */
    public synchronized void close() {
        closed = true;
        if (poller != null) {
            poller.interrupt();
            poller = null;
        }
        suspended.clear();
    }

    /** 폴러가 떠 있는지. 시험용. */
    synchronized boolean polling() {
        return poller != null;
    }

    /**
     * 재개를 볼 때까지 멈추지 않는다. 응답이 5xx·시간 초과·형식 오류면 직전 상태(중단)를 유지하고 다시 읽는다(API 설계 §4-A-7
     * 클라이언트 규약). 첫 조회는 바로 한다. 재개 직전에 보낸 요청의 늦은 423 이 마켓을 닫았을 때 권장 간격만큼 멈추지 않게 한다.
     */
    private void pollUntilResumed() {
        Duration wait = Duration.ZERO;
        try {
            while (true) {
                if (!wait.isZero()) {
                    Thread.sleep(wait.toMillis());
                }
                JsonNode status = readStatus();
                if (status == null) {
                    wait = fallbackPoll;
                    continue;
                }
                if (!status.path("trading_suspended").asBoolean(true)) {
                    resume();
                    return;
                }
                JsonNode after = status.path("poll_after_seconds");
                wait = after.canConvertToInt()
                        ? Duration.ofSeconds(Math.max(MIN_POLL_SECONDS, Math.min(MAX_POLL_SECONDS, after.asInt())))
                        : fallbackPoll;
            }
        } catch (InterruptedException e) {
            // close() 가 끝냈다. 마켓은 close() 가 연다.
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void resume() {
        if (poller != Thread.currentThread()) {
            return;
        }
        log.info("거래 재개를 확인했다. 멈췄던 마켓 {} 의 봇 발행을 다시 연다", suspended);
        suspended.clear();
        poller = null;
    }

    private JsonNode readStatus() {
        try {
            BotApiClient.Response response = statusClient.getPublic(STATUS_PATH);
            if (response.status() != 200) {
                return null;
            }
            JsonNode status = mapper.readTree(response.body());
            return status == null || !status.isObject() ? null : status;
        } catch (Exception e) {
            log.debug("거래 상태를 읽지 못했다: {}", e.toString());
            return null;
        }
    }
}
