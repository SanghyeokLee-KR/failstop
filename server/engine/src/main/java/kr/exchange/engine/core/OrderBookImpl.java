package kr.exchange.engine.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 단일 마켓 오더북. 가격·시간 우선(가격 레벨 정렬 맵 + 레벨 내 FIFO).
 *
 * 결정론 규약(API 설계 v2.6 §7-2): 시각은 command.engineTs만, 순서는 seq 전순서만 사용한다.
 * 자료구조는 순회 순서가 정의된 것(TreeMap, ArrayDeque)만 쓴다. HashMap 계열 금지(하네스 T3-A).
 *
 * 지원 범위: LIMIT(GTC/IOC/FOK/POST_ONLY), MARKET(GTC/IOC/FOK), BEST(IOC/FOK, 매도 전용).
 * 비-GTC와 BEST는 매칭 루프 진입 전 사전판정으로 처리한다. POST_ONLY는 반대편 best 1회 비교로
 * 즉시체결이면 거부(post_only_would_take), 아니면 전량 게시한다. FOK는 read-only 사전스캔으로
 * 전량 충족을 판정해 불가면 접수 없이 거부(fok_not_filled)한다. IOC와 BEST 잔량은 rest하지
 * 않고 취소로 종단한다. 어떤 신규 타입도 새 영속 주문 상태를 만들지 않아 state hash 표현이
 * 불변이다(POST_ONLY 게시는 일반 LIMIT resting과 동일 표현).
 */
public final class OrderBookImpl implements OrderBook {

    /** NUMERIC(40,18) 계약과 동일한 소수 자릿수. 엔진 내부 수치는 전부 이 scale로 정규화한다. */
    static final int SCALE = 18;

    public static final String REASON_SELF_TRADE_PREVENTED = "self_trade_prevented";
    public static final String REASON_NO_LIQUIDITY = "no_liquidity";
    public static final String REASON_ORDER_NOT_FOUND = "order_not_found";
    public static final String REASON_INVALID_PARAMETERS = "invalid_parameters";
    public static final String REASON_DUPLICATE_ORDER_ID = "duplicate_order_id";
    public static final String REASON_POST_ONLY_WOULD_TAKE = "post_only_would_take";
    public static final String REASON_FOK_NOT_FILLED = "fok_not_filled";
    public static final String REASON_IOC_PARTIAL_CANCELED = "ioc_partial_canceled";

    private final int marketId;
    // 매수는 높은 가격 우선, 매도는 낮은 가격 우선. TreeMap 순회가 곧 우선순위다.
    private final TreeMap<BigDecimal, ArrayDeque<RestingOrder>> bids =
            new TreeMap<>(java.util.Comparator.reverseOrder());
    private final TreeMap<BigDecimal, ArrayDeque<RestingOrder>> asks = new TreeMap<>();
    /*
     * 가격 단별 잔량 합. 위 두 TreeMap 과 같은 키를 갖고 같은 순서로 돈다.
     *
     * 왜 따로 두는가. 호가 응답은 단별 합을 원하는데, 그것을 필요할 때 세면 그 단에 걸린 주문
     * 수만큼 덧셈이 든다. 2026-09-07 실측에서 가격 단이 17 개인데 미체결이 73073 건이었다.
     * 배치마다 그것을 세면 단일 라이터가 배치당 7 만 번 덧셈을 한다. 실제로 그렇게 만들었다가
     * 천장이 98.6/s 에서 88.7/s 로 내려갔다.
     *
     * 그래서 잔량이 드나드는 자리에서 함께 갱신한다. 갱신 지점은 셋뿐이다. 신규 안착(rest,
     * 스냅샷 복원), 제거(removeResting), 체결·STP 감액이다. 그 셋 밖에서 remaining 을 바꾸면
     * 이 맵이 어긋나므로 새 경로를 만들 때 반드시 여기를 함께 본다.
     */
    private final TreeMap<BigDecimal, BigDecimal> bidSizes =
            new TreeMap<>(java.util.Comparator.reverseOrder());
    private final TreeMap<BigDecimal, BigDecimal> askSizes = new TreeMap<>();

    // 취소 조회용 색인. HashMap은 순회 비결정 우려로 금지 대상이라 TreeMap을 쓴다.
    private final TreeMap<Long, RestingOrder> ordersById = new TreeMap<>();

    public OrderBookImpl(int marketId) {
        this.marketId = marketId;
    }

    @Override
    public int marketId() {
        return marketId;
    }

    @Override
    public List<EngineEvent> apply(OrderCommand command) {
        Emitter emitter = new Emitter(command);
        switch (command.type()) {
            case NEW -> applyNew(command, emitter);
            case CANCEL -> applyCancel(command, emitter);
        }
        return emitter.events;
    }

    @Override
    public BigDecimal bestBid() {
        Map.Entry<BigDecimal, ArrayDeque<RestingOrder>> e = bids.firstEntry();
        return e == null ? null : e.getKey();
    }

    @Override
    public BigDecimal bestAsk() {
        Map.Entry<BigDecimal, ArrayDeque<RestingOrder>> e = asks.firstEntry();
        return e == null ? null : e.getKey();
    }

    /**
     * 정준 직렬화(하네스 T3-A: 마켓 → 가격 레벨 → FIFO (order_id, remaining_qty),
     * setScale(18).toPlainString(), UTF-8)를 SHA-256으로 접어 상위 8바이트를 취한다.
     */
    @Override
    public long stateChecksum() {
        StringBuilder sb = new StringBuilder();
        sb.append(marketId).append('\n');
        appendSide(sb, 'B', bids);
        appendSide(sb, 'A', asks);
        return StateHash.sha256ToLong(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 열린 주문의 잔여 수량. 없으면 null. 검증(수량 보존 속성)용 조회 경로다. */
    public BigDecimal openQuantity(long orderId) {
        RestingOrder o = ordersById.get(orderId);
        return o == null ? null : o.remaining;
    }

    public int openOrderCount() {
        return ordersById.size();
    }

    /** 호가 한 단. 가격과 그 가격의 잔량 합이다. */
    public record DepthLevel(BigDecimal price, BigDecimal size) {
    }

    /** 한 면의 상위 단과 그 면 전체 잔량. levels 순서는 우선순위 순서다. */
    public record SideDepth(List<DepthLevel> levels, BigDecimal total) {
    }

    /**
     * 매수 상위 limit 단과 매수 전체 잔량.
     *
     * 이 메서드는 책을 읽기만 한다. 엔진 결정론에 영향이 없다.
     *
     * 왜 엔진에 두는가. 종전에는 같은 값을 DB 집계로 다시 만들었다. 2026-09-07 실측에서 그
     * 두 쿼리가 DB 실행 시간 166445ms 로 같은 창 쓰기 경로의 3 배였고, 미체결이 8 만 건일 때
     * 총 잔량 한 번이 25ms 였다. 엔진은 같은 사실을 이미 메모리에 들고 있으므로 다시 셀 이유가
     * 없다. 여기서 읽으면 가격 단 수에 비례할 뿐 주문 수와 무관하다.
     *
     * 호출은 단일 라이터 스레드에서만 해야 한다. TreeMap 은 동시 수정에 안전하지 않다.
     * 다른 스레드가 쓰려면 라이터가 만든 결과를 불변 객체로 건네받아야 한다.
     */
    public SideDepth bidDepth(int limit) {
        return depthOf(bidSizes, limit);
    }

    /** 매도 상위 limit 단과 매도 전체 잔량. 규약은 {@link #bidDepth(int)} 와 같다. */
    public SideDepth askDepth(int limit) {
        return depthOf(askSizes, limit);
    }

    private static SideDepth depthOf(TreeMap<BigDecimal, BigDecimal> sizes, int limit) {
        List<DepthLevel> levels = new ArrayList<>(Math.max(limit, 0));
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<BigDecimal, BigDecimal> e : sizes.entrySet()) {
            // 총 잔량은 상위 단으로 끊지 않는다. 끊으면 total_ask_size 가 15 단 합이 되어
            // 종전 DB 집계와 다른 값이 된다.
            total = total.add(e.getValue());
            if (levels.size() < limit) {
                levels.add(new DepthLevel(e.getKey(), e.getValue()));
            }
        }
        return new SideDepth(List.copyOf(levels), total);
    }

    // ------------------------------------------------------------------
    // 스냅샷 export/import (복구 RTO 설계 §3-b)
    // ------------------------------------------------------------------

    /**
     * 레스팅 주문을 정준 순서(bids 우선순위·레벨 내 FIFO, 이어서 asks)로 내보낸다.
     * stateChecksum의 순회 순서와 동일해 재적재 후 체크섬이 보존된다. SnapshotCodec 전용.
     */
    List<BookEntry> exportEntries() {
        List<BookEntry> out = new ArrayList<>(ordersById.size());
        exportSide(out, bids);
        exportSide(out, asks);
        return out;
    }

    private static void exportSide(List<BookEntry> out,
                                   TreeMap<BigDecimal, ArrayDeque<RestingOrder>> side) {
        for (Map.Entry<BigDecimal, ArrayDeque<RestingOrder>> level : side.entrySet()) {
            for (RestingOrder o : level.getValue()) {
                out.add(new BookEntry(o.orderId, o.userId, o.side, o.price, o.remaining));
            }
        }
    }

    /**
     * export 순서 그대로 재적재해 북을 재구성한다. addLast가 레벨 내 FIFO를, TreeMap 비교자가
     * 레벨 정렬을 복원하므로 원본과 비트동일한 구조가 나온다. 매칭을 돌리지 않는다(스냅샷
     * 불변식상 bids·asks는 비교차, C19).
     */
    static OrderBookImpl fromEntries(int marketId, List<BookEntry> entries) {
        OrderBookImpl book = new OrderBookImpl(marketId);
        for (BookEntry e : entries) {
            RestingOrder o = new RestingOrder(e.orderId(), e.userId(), e.side(), e.price(), e.remaining());
            TreeMap<BigDecimal, ArrayDeque<RestingOrder>> side =
                    e.side() == OrderCommand.Side.BUY ? book.bids : book.asks;
            side.computeIfAbsent(e.price(), p -> new ArrayDeque<>()).addLast(o);
            book.addSize(e.side(), e.price(), e.remaining());
            book.ordersById.put(o.orderId, o);
        }
        return book;
    }

    /** 스냅샷 직렬화 단위. 레스팅 주문 복원에 필요한 전 필드를 담는다. */
    record BookEntry(long orderId, long userId, OrderCommand.Side side,
                     BigDecimal price, BigDecimal remaining) {
    }

    // ------------------------------------------------------------------
    // NEW
    // ------------------------------------------------------------------

    private void applyNew(OrderCommand command, Emitter em) {
        String invalid = validateNew(command);
        if (invalid != null) {
            em.rejected(command.assignedOrderId(), invalid);
            return;
        }

        OrderCommand.Side side = command.side();
        OrderCommand.OrdType ordType = command.ordType();
        OrderCommand.TimeInForce tif = command.timeInForce() == null
                ? OrderCommand.TimeInForce.GTC : command.timeInForce();
        boolean market = ordType == OrderCommand.OrdType.MARKET;
        boolean best = ordType == OrderCommand.OrdType.BEST;
        boolean notional = market && side == OrderCommand.Side.BUY;
        TreeMap<BigDecimal, ArrayDeque<RestingOrder>> opposite =
                side == OrderCommand.Side.BUY ? asks : bids;

        BigDecimal limitPrice = ordType == OrderCommand.OrdType.LIMIT
                ? normalize(command.price()) : null;
        // notional 주문은 잔여를 quote 금액으로, 그 외는 base 수량으로 추적한다.
        BigDecimal remaining = notional
                ? normalize(command.quoteOrderQty())
                : normalize(command.quantity());

        // POST_ONLY(LIMIT 전용): 반대편 best와 1회 비교로 종결. 크로스면 접수 없이 거부하고,
        // 아니면 체결 0이 보장되므로 매칭 루프 없이 전량 게시한다(새 영속 상태 없음).
        if (tif == OrderCommand.TimeInForce.POST_ONLY) {
            BigDecimal bestOpp = side == OrderCommand.Side.BUY ? bestAsk() : bestBid();
            if (bestOpp != null && crosses(side, limitPrice, bestOpp)) {
                em.rejected(command.assignedOrderId(), REASON_POST_ONLY_WOULD_TAKE);
                return;
            }
            em.accepted(command.assignedOrderId());
            rest(command, limitPrice, remaining);
            return;
        }

        // FOK: 북 변경 전 read-only 사전스캔. 전량(금액기반은 경제적) 충족 불가면 접수 없이 거부.
        if (tif == OrderCommand.TimeInForce.FOK) {
            if (!wouldFullyFill(side, market, notional, best, limitPrice, opposite, remaining)) {
                em.rejected(command.assignedOrderId(), REASON_FOK_NOT_FILLED);
                return;
            }
        } else if (market && opposite.isEmpty()) {
            // 빈 반대편 시장가는 체결 불가 확정이므로 접수 없이 거부한다(비 FOK 경로).
            em.rejected(command.assignedOrderId(), REASON_NO_LIQUIDITY);
            return;
        }

        em.accepted(command.assignedOrderId());

        // BEST(최유리)는 접수 시점 반대편 최우선 가격 한 레벨만 소비한다(API §4-B-1).
        BigDecimal bestPx = null;
        if (best) {
            Map.Entry<BigDecimal, ArrayDeque<RestingOrder>> first = opposite.firstEntry();
            bestPx = first == null ? null : first.getKey();
        }

        while (remaining.signum() > 0) {
            Map.Entry<BigDecimal, ArrayDeque<RestingOrder>> level = opposite.firstEntry();
            if (level == null) {
                break;
            }
            if (best) {
                if (bestPx == null || level.getKey().compareTo(bestPx) != 0) {
                    break;
                }
            } else if (!market && !crosses(side, limitPrice, level.getKey())) {
                break;
            }
            RestingOrder maker = level.getValue().peekFirst();
            BigDecimal takerCapacity = notional
                    ? remaining.divide(maker.price, SCALE, RoundingMode.DOWN)
                    : remaining;
            BigDecimal fill = takerCapacity.min(maker.remaining);
            if (fill.signum() == 0) {
                // notional 잔액이 이 레벨 최소 단위에도 못 미친다. 아래 잔여 취소로 종결.
                break;
            }
            // notional 소진액은 이벤트에 보고되는 양자화 값과 동일한 값으로만 차감한다.
            // 비양자화 차감은 보고 합계와 실소진액을 dust만큼 갈라놓아, 이벤트를 진실 원천으로
            // 삼는 정산(README M1-3 인계)이 재구성 불가능한 잔차를 갖게 된다.
            BigDecimal delta = notional ? quantizeDown(fill.multiply(maker.price)) : fill;
            if (notional && delta.signum() == 0) {
                // 소진액이 최소 단위(1e-18) 미만이면 0금액 체결만 남는다. dust는 잔여 취소로 종결.
                break;
            }
            if (maker.userId == command.userId()) {
                // STP Decrement & Cancel(API §7-2): 양쪽을 min 수량만큼 감액하고 0이 된 쪽을
                // 취소한다. 체결 없이 소멸한 수량은 CANCELED 이벤트로 남겨 정산·프로젝션이
                // 이벤트만으로 잔량과 잠금 해제를 재구성할 수 있게 한다. maker 먼저 방출.
                maker.remaining = maker.remaining.subtract(fill);
                addSize(maker.side, maker.price, fill.negate());
                em.canceled(maker.orderId, fill, REASON_SELF_TRADE_PREVENTED);
                if (maker.remaining.signum() == 0) {
                    removeResting(maker);
                }
                em.canceled(command.assignedOrderId(), delta, REASON_SELF_TRADE_PREVENTED);
                remaining = remaining.subtract(delta);
                continue;
            }
            // 체결가는 항상 maker 지정가(price improvement는 taker 차익, API §7-2, 하네스 C18).
            em.trade(command, maker, fill);
            maker.remaining = maker.remaining.subtract(fill);
            addSize(maker.side, maker.price, fill.negate());
            if (maker.remaining.signum() == 0) {
                removeResting(maker);
            }
            remaining = remaining.subtract(delta);
        }

        if (remaining.signum() > 0) {
            if (market) {
                // 시장가는 잔량을 게시하지 않는다. 유동성 소진 잔여는 취소로 종결(IOC 의미론).
                // 양자화 차감 규약상 remaining은 항상 scale 18 그대로라 0수량 이벤트가 없다.
                // FOK 금액기반은 사전스캔 통과 후 남는 dust만 이 경로로 온다.
                em.canceled(command.assignedOrderId(), remaining, REASON_NO_LIQUIDITY);
            } else if (best || tif == OrderCommand.TimeInForce.IOC) {
                // IOC·BEST 잔량은 게시하지 않고 취소로 종단한다(정보성 사유, API §4-B-1).
                // FOK 수량기반은 사전스캔이 잔여 0을 보장하므로 이 분기에 오지 않는다.
                em.canceled(command.assignedOrderId(), remaining, REASON_IOC_PARTIAL_CANCELED);
            } else {
                rest(command, limitPrice, remaining);
            }
        }
    }

    /**
     * FOK 사전 유동성 스캔. 실제 매칭 루프와 동일한 순회(레벨 우선순위 + 레벨 내 FIFO)를 가상
     * 잔량 위에서 재생하되 북을 변경하지 않는다. STP-DC 소비도 실제 루프처럼 잔량을 감액해
     * 등가로 판정한다(자기 유동성 제외 시 스캔과 실행 결정이 갈려 결정론이 깨진다).
     *
     * 반환: 잔량이 0에 도달하거나(수량기반) 유효 잔량이 dust로 절사되면(금액기반) true.
     * 크로스 유동성이 먼저 소진되어 유효 잔량이 남으면 false.
     */
    private boolean wouldFullyFill(OrderCommand.Side side, boolean market, boolean notional,
                                   boolean best, BigDecimal limitPrice,
                                   TreeMap<BigDecimal, ArrayDeque<RestingOrder>> opposite,
                                   BigDecimal remaining) {
        BigDecimal rem = remaining;
        BigDecimal bestPx = null;
        for (Map.Entry<BigDecimal, ArrayDeque<RestingOrder>> level : opposite.entrySet()) {
            BigDecimal levelPrice = level.getKey();
            if (best) {
                if (bestPx == null) {
                    bestPx = levelPrice;
                } else if (levelPrice.compareTo(bestPx) != 0) {
                    break;
                }
            } else if (!market && !crosses(side, limitPrice, levelPrice)) {
                break;
            }
            for (RestingOrder maker : level.getValue()) {
                if (rem.signum() == 0) {
                    return true;
                }
                BigDecimal takerCapacity = notional
                        ? rem.divide(maker.price, SCALE, RoundingMode.DOWN)
                        : rem;
                BigDecimal fill = takerCapacity.min(maker.remaining);
                if (fill.signum() == 0) {
                    // 금액기반 dust(다음 단위도 못 사는 잔액). 경제적 전량 충족.
                    return true;
                }
                BigDecimal delta = notional ? quantizeDown(fill.multiply(maker.price)) : fill;
                if (delta.signum() == 0) {
                    return true;
                }
                if (fill.compareTo(maker.remaining) < 0) {
                    // taker 한정: 이 maker가 잔량을 전부 흡수한다(수량기반=정확히 0, 금액기반=dust만 잔여).
                    return true;
                }
                rem = rem.subtract(delta);
            }
        }
        return rem.signum() == 0;
    }

    private String validateNew(OrderCommand c) {
        if (c.ordType() == null || c.side() == null) {
            return REASON_INVALID_PARAMETERS;
        }
        OrderCommand.TimeInForce tif =
                c.timeInForce() == null ? OrderCommand.TimeInForce.GTC : c.timeInForce();
        // 게이트웨이(§4-B-1)가 이미 강제하는 조합 규칙의 엔진 이중검증. 불법 조합의 방어적 거부는
        // 단일 사유로 일원화한다. 특히 BEST 매수는 정적 예약 산식이 없어 접수 시 정산이 성립하지
        // 않으므로 ACCEPTED로 넘기지 않는다.
        if (tif == OrderCommand.TimeInForce.POST_ONLY
                && c.ordType() != OrderCommand.OrdType.LIMIT) {
            return REASON_INVALID_PARAMETERS;
        }
        if (c.ordType() == OrderCommand.OrdType.BEST) {
            if (c.side() == OrderCommand.Side.BUY) {
                return REASON_INVALID_PARAMETERS;
            }
            if (tif != OrderCommand.TimeInForce.IOC && tif != OrderCommand.TimeInForce.FOK) {
                return REASON_INVALID_PARAMETERS;
            }
        }
        if (ordersById.containsKey(c.assignedOrderId())) {
            return REASON_DUPLICATE_ORDER_ID;
        }
        // 형태 검증(§4-B-1 상호배타). LIMIT=가격+수량, BEST=수량(가격·notional 금지),
        // MARKET 매수=notional(수량 금지)·매도=수량(notional 금지).
        switch (c.ordType()) {
            case LIMIT -> {
                if (!positiveWithinScale(c.price()) || !positiveWithinScale(c.quantity())
                        || c.quoteOrderQty() != null) {
                    return REASON_INVALID_PARAMETERS;
                }
            }
            case BEST -> {
                if (c.price() != null || c.quoteOrderQty() != null
                        || !positiveWithinScale(c.quantity())) {
                    return REASON_INVALID_PARAMETERS;
                }
            }
            case MARKET -> {
                if (c.side() == OrderCommand.Side.BUY) {
                    if (!positiveWithinScale(c.quoteOrderQty()) || c.quantity() != null) {
                        return REASON_INVALID_PARAMETERS;
                    }
                } else {
                    if (!positiveWithinScale(c.quantity()) || c.quoteOrderQty() != null) {
                        return REASON_INVALID_PARAMETERS;
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // CANCEL
    // ------------------------------------------------------------------

    private void applyCancel(OrderCommand command, Emitter em) {
        RestingOrder target = ordersById.get(command.assignedOrderId());
        if (target == null || target.userId != command.userId()) {
            // 소유자 불일치도 존재 비노출 원칙으로 동일 사유를 쓴다.
            em.rejected(command.assignedOrderId(), REASON_ORDER_NOT_FOUND);
            return;
        }
        BigDecimal released = target.remaining;
        removeResting(target);
        em.canceled(target.orderId, released, null);
    }

    // ------------------------------------------------------------------
    // 내부
    // ------------------------------------------------------------------

    private static boolean crosses(OrderCommand.Side side, BigDecimal limitPrice, BigDecimal levelPrice) {
        return side == OrderCommand.Side.BUY
                ? levelPrice.compareTo(limitPrice) <= 0
                : levelPrice.compareTo(limitPrice) >= 0;
    }

    private void rest(OrderCommand command, BigDecimal price, BigDecimal remaining) {
        RestingOrder order = new RestingOrder(
                command.assignedOrderId(), command.userId(), command.side(), price, remaining);
        TreeMap<BigDecimal, ArrayDeque<RestingOrder>> side =
                command.side() == OrderCommand.Side.BUY ? bids : asks;
        side.computeIfAbsent(price, p -> new ArrayDeque<>()).addLast(order);
        addSize(command.side(), price, remaining);
        ordersById.put(order.orderId, order);
    }

    /**
     * 단별 잔량을 더하거나 뺀다. 0 이 되면 그 단을 지운다.
     *
     * 지우는 이유는 호가 응답에 잔량 0 인 빈 벽이 서지 않게 하려는 것이고, 그것이 곧 위 두
     * 호가 TreeMap 의 키 집합과 이 맵의 키 집합을 같게 유지한다.
     */
    private void addSize(OrderCommand.Side side, BigDecimal price, BigDecimal delta) {
        TreeMap<BigDecimal, BigDecimal> sizes = side == OrderCommand.Side.BUY ? bidSizes : askSizes;
        BigDecimal next = sizes.getOrDefault(price, BigDecimal.ZERO).add(delta);
        if (next.signum() == 0) {
            sizes.remove(price);
        } else {
            sizes.put(price, next);
        }
    }

    private void removeResting(RestingOrder order) {
        TreeMap<BigDecimal, ArrayDeque<RestingOrder>> side =
                order.side == OrderCommand.Side.BUY ? bids : asks;
        ArrayDeque<RestingOrder> level = side.get(order.price);
        if (level != null && level.remove(order)) {
            // 제거 시점에 남아 있던 잔량만 뺀다. 체결로 줄어든 몫은 그때 이미 뺐다.
            addSize(order.side, order.price, order.remaining.negate());
            if (level.isEmpty()) {
                side.remove(order.price);
            }
        }
        ordersById.remove(order.orderId);
    }

    private static BigDecimal normalize(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal quantizeDown(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.DOWN);
    }

    private static boolean positiveWithinScale(BigDecimal v) {
        return v != null && v.signum() > 0 && v.stripTrailingZeros().scale() <= SCALE;
    }

    private static void appendSide(StringBuilder sb, char tag,
                                   TreeMap<BigDecimal, ArrayDeque<RestingOrder>> side) {
        for (Map.Entry<BigDecimal, ArrayDeque<RestingOrder>> level : side.entrySet()) {
            for (RestingOrder o : level.getValue()) {
                sb.append(tag).append('|')
                        .append(level.getKey().setScale(SCALE, RoundingMode.UNNECESSARY).toPlainString())
                        .append('|').append(o.orderId)
                        .append('|').append(o.remaining.toPlainString())
                        .append('\n');
            }
        }
    }

    private static final class RestingOrder {
        final long orderId;
        final long userId;
        final OrderCommand.Side side;
        final BigDecimal price;
        BigDecimal remaining;

        RestingOrder(long orderId, long userId, OrderCommand.Side side,
                     BigDecimal price, BigDecimal remaining) {
            this.orderId = orderId;
            this.userId = userId;
            this.side = side;
            this.price = price;
            this.remaining = remaining;
        }
    }

    /** 명령 단위 이벤트 축적기. ordinal 0부터 단조 부여가 여기서만 일어난다. */
    private final class Emitter {
        final List<EngineEvent> events = new ArrayList<>();
        private final OrderCommand command;
        private int ordinal;

        Emitter(OrderCommand command) {
            this.command = command;
        }

        void accepted(long orderId) {
            add(EngineEvent.EventType.ACCEPTED, orderId, null, null, null, null, null, null, null);
        }

        void rejected(long orderId, String reason) {
            add(EngineEvent.EventType.REJECTED, orderId, null, null, null, null, null, null, reason);
        }

        void canceled(long orderId, BigDecimal quantity, String reason) {
            add(EngineEvent.EventType.CANCELED, orderId, null, null, null, null, null, quantity, reason);
        }

        void trade(OrderCommand taker, RestingOrder maker, BigDecimal quantity) {
            add(EngineEvent.EventType.TRADE, taker.assignedOrderId(), maker.orderId,
                    maker.userId, taker.userId(), taker.side(), maker.price, quantity, null);
        }

        private void add(EngineEvent.EventType type, long orderId, Long counterOrderId,
                         Long makerUserId, Long takerUserId, OrderCommand.Side takerSide,
                         BigDecimal price, BigDecimal quantity, String reason) {
            events.add(new EngineEvent(0, command.seq(), ordinal++, marketId, type,
                    command.engineTs(), orderId, counterOrderId, makerUserId, takerUserId,
                    takerSide, price, quantity, reason));
        }
    }
}
