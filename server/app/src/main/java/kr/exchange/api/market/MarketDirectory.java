package kr.exchange.api.market;

import kr.exchange.api.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 마켓 규칙·자산 구성 조회. 게이트웨이 검증(tick/lot/min·max/scale 가드, §1.4 앱 가드 목록)과
 * 시장 데이터 응답이 같은 행을 본다. 요율 박제(§5.2)의 원천이기도 하다.
 *
 * 카탈로그 전체를 스냅샷으로 들고 세 조회가 그것을 본다. 2026-09-06 실측
 * (pg_stat_statements, 주입 200/s 2분)에서 이 조회가 192,409 회로 DB 시간의 16.6% 였다.
 * 회당 0.052ms 라 계획이 아니라 횟수가 비용이다. MarketDataPublisher 가 엔진 이벤트마다
 * byId 를 부르는 것이 대부분을 만든다.
 *
 * 갱신은 전용 데몬 스레드가 1초 주기로 전량을 다시 읽어 통째로 갈아 끼운다. 요청 스레드는
 * DB 를 보지 않는다. 관리 평면이 별도 JVM 이라 프로세스 내 무효화가 닿지 않고, 이 저장소에
 * 메시지 버스도 LISTEN/NOTIFY 선례도 없어 주기 재적재가 가장 작은 수단이다. 리스 서비스가
 * 같은 관용구로 리더 상태를 JVM 사이에 공유한다.
 *
 * 주기 재적재가 성립하는 이유는 카탈로그가 작아서다. 2026-09-06 실측으로 12마켓 13자산이고
 * 전량 조회가 평균 0.1296ms 최대 0.1473ms 다. 초당 한 번이면 DB 시간이 초당 0.13ms 다.
 *
 * 갱신에 실패하면 직전 정상 스냅샷을 그대로 쓴다. 빈 스냅샷으로 갈아 끼우면 전 마켓이
 * 미등록이 되어 거래가 통째로 멎는다. 낡은 값이 그보다 낫다.
 */
public class MarketDirectory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MarketDirectory.class);

    public record Market(int id, String symbol, String status,
                         short baseAssetId, String baseSymbol, String baseName, int baseScale,
                         short quoteAssetId, String quoteSymbol, int quoteScale,
                         BigDecimal tickSize, BigDecimal lotSize,
                         BigDecimal minNotional, BigDecimal maxNotional, BigDecimal maxQty,
                         BigDecimal makerFeeRate, BigDecimal takerFeeRate) {
    }

    private static final String SELECT = """
            SELECT m.id, m.symbol, m.status,
                   m.base_asset_id, b.symbol AS base_symbol, b.name AS base_name,
                   b.scale AS base_scale,
                   m.quote_asset_id, q.symbol AS quote_symbol, q.scale AS quote_scale,
                   m.tick_size, m.lot_size, m.min_notional, m.max_notional, m.max_qty,
                   m.maker_fee_rate, m.taker_fee_rate
            FROM markets m
            JOIN assets b ON b.id = m.base_asset_id
            JOIN assets q ON q.id = m.quote_asset_id
            """;

    // 1초. 상한은 halt 반영 지연이 정한다. 정지는 긴급 통제이므로 초 단위 위로 늘리지 않는다.
    private static final long REFRESH_MS = 1_000;

    private record Snapshot(List<Market> ordered, Map<String, Market> bySymbol,
                            Map<Integer, Market> byId, long loadedAt) {
    }

    private final JdbcClient jdbc;
    // 요청 스레드는 이 참조만 읽는다. 교체가 단일 대입이라 부분 상태가 보이지 않는다.
    private volatile Snapshot snapshot;
    private volatile long lastSuccessAt;
    private final AtomicLong refreshFailures = new AtomicLong();
    private volatile ScheduledExecutorService refresher;
    // 미스 재적재를 한 스레드로 묶는다. 정상 경로는 이 잠금을 지나지 않는다.
    private final Object missLock = new Object();
    private volatile long lastMissReloadAt;

    public MarketDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 최초 적재와 주기 갱신 시작. 최초 적재는 기동을 막아야 하므로 예외를 그대로 올린다. */
    public void start() {
        if (refresher != null) {
            return;
        }
        snapshot = load();
        lastSuccessAt = snapshot.loadedAt();
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-catalog-refresh");
            t.setDaemon(true);
            return t;
        });
        s.scheduleWithFixedDelay(this::refresh, REFRESH_MS, REFRESH_MS, TimeUnit.MILLISECONDS);
        refresher = s;
    }

    @Override
    public void close() {
        ScheduledExecutorService s = refresher;
        refresher = null;
        if (s != null) {
            s.shutdownNow();
        }
    }

    private void refresh() {
        try {
            Snapshot fresh = load();
            snapshot = fresh;
            lastSuccessAt = fresh.loadedAt();
        } catch (RuntimeException e) {
            // 직전 스냅샷을 버리지 않는다. 낡은 카탈로그가 빈 카탈로그보다 안전하다.
            refreshFailures.incrementAndGet();
            log.warn("마켓 카탈로그 갱신 실패. 직전 스냅샷 유지: {}", e.toString());
        }
    }

    /** 관측용. 갱신 실패 누계와 마지막 성공 시각으로 스냅샷 나이를 판정한다. */
    public long refreshFailureCount() {
        return refreshFailures.get();
    }

    public long lastSuccessfulRefreshAt() {
        return lastSuccessAt;
    }

    public Market bySymbol(String symbol) {
        Snapshot s = current();
        Market m = s.bySymbol().get(symbol);
        if (m == null) {
            s = reloadOnMiss(s);
            m = s.bySymbol().get(symbol);
        }
        if (m == null) {
            throw new ApiException(400, "invalid_parameter", "존재하지 않는 마켓입니다: " + symbol);
        }
        return m;
    }

    /**
     * 핫패스 전용. 스냅샷만 읽고 어떤 경우에도 DB 를 보지 않는다.
     *
     * MarketDataPublisher 가 엔진 이벤트마다, EngineBridge 가 저널 커밋 직전에 부른다. 뒤엣것은
     * 그 마켓의 접수 락 안이라 여기서 DB 를 한 번이라도 왕복하면 그 시간만큼 그 마켓 접수가
     * 직렬로 멎는다. 실측에서 미스 재적재를 이 경로에 두었더니 TPS 가 123.6 에서 119.3 으로
     * 내려갔다. 심볼 조회와 목록 조회만 미스 재적재를 갖는다.
     */
    public Market byId(int marketId) {
        Market m = current().byId().get(marketId);
        if (m == null) {
            throw new IllegalStateException("미등록 마켓: " + marketId);
        }
        return m;
    }

    /**
     * 미스 한 건에만 도는 동기 재적재. 기동 뒤에 마켓이 추가되는 배치(시딩·신규 상장)에서
     * 다음 주기까지 기다리지 않게 한다.
     *
     * 빈도에 상한을 둔다. 없는 심볼을 반복해 보내면 미스가 계속 나므로, 상한이 없으면 그것이
     * 곧 DB 부하 주입 경로가 된다. 상한은 스냅샷 나이가 아니라 직전 미스 재적재 시각으로 잰다.
     * 나이로 재면 기동 직후 시딩된 마켓의 첫 조회가 갓 적재된 빈 스냅샷에 막힌다.
     * 정상 운영에서는 미스가 나지 않으므로 이 경로가 돌지 않는다.
     */
    private Snapshot reloadOnMiss(Snapshot stale) {
        long now = System.currentTimeMillis();
        if (now - lastMissReloadAt < REFRESH_MS) {
            return stale;
        }
        synchronized (missLock) {
            if (System.currentTimeMillis() - lastMissReloadAt < REFRESH_MS) {
                return snapshot;
            }
            lastMissReloadAt = System.currentTimeMillis();
            try {
                Snapshot fresh = load();
                snapshot = fresh;
                lastSuccessAt = fresh.loadedAt();
                return fresh;
            } catch (RuntimeException e) {
                refreshFailures.incrementAndGet();
                return stale;
            }
        }
    }

    public List<Market> all() {
        Snapshot s = current();
        if (s.ordered().isEmpty()) {
            // 빈 카탈로그는 이 서비스의 정상 상태가 아니다. 기동과 시딩이 겹친 창이므로
            // 미스와 같은 처리로 한 번 다시 읽는다.
            s = reloadOnMiss(s);
        }
        return s.ordered();
    }

    /** 요청 스레드 전용. DB 를 보지 않는다. */
    private Snapshot current() {
        Snapshot s = snapshot;
        if (s == null) {
            // start() 이전 호출이다. 배선 오류이므로 조용히 적재하지 않고 드러낸다.
            throw new IllegalStateException("마켓 카탈로그 미적재: start() 선행 필요");
        }
        return s;
    }

    private Snapshot load() {
        List<Market> rows = jdbc.sql(SELECT + " ORDER BY m.id").query(this::map).list();
        Map<String, Market> byName = new HashMap<>(rows.size() * 2);
        Map<Integer, Market> byKey = new HashMap<>(rows.size() * 2);
        for (Market m : rows) {
            byName.put(m.symbol(), m);
            byKey.put(m.id(), m);
        }
        return new Snapshot(List.copyOf(rows), Map.copyOf(byName), Map.copyOf(byKey),
                System.currentTimeMillis());
    }

    private Market map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Market(
                rs.getInt("id"), rs.getString("symbol"), rs.getString("status"),
                rs.getShort("base_asset_id"), rs.getString("base_symbol"),
                rs.getString("base_name"), rs.getInt("base_scale"),
                rs.getShort("quote_asset_id"), rs.getString("quote_symbol"),
                rs.getInt("quote_scale"),
                rs.getBigDecimal("tick_size"), rs.getBigDecimal("lot_size"),
                rs.getBigDecimal("min_notional"), rs.getBigDecimal("max_notional"),
                rs.getBigDecimal("max_qty"),
                rs.getBigDecimal("maker_fee_rate"), rs.getBigDecimal("taker_fee_rate"));
    }
}
