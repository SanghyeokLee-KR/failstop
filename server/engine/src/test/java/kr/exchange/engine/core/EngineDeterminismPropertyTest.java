package kr.exchange.engine.core;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 무작위 주문·취소 시퀀스 속성 검증(하네스 T3-B 축).
 *
 * (i) 재생 결정론: 동일 명령열 재적용 = 이벤트열·상태 체크섬 완전 동일
 * (ii) 오더북 비교차: 모든 시점에서 bestBid < bestAsk
 * (iii) 수량 보존: 주문별 체결 + 취소 + 잔량 = 원 수량(notional 주문은 금액 기준)
 *
 * 생성기는 price scale 2 × qty scale 17로 곱 scale 19를 만들어 notional 절사 경로를
 * 강제로 지난다. 보존 판정은 소진 규약(quote 소진액 = quantizeDown(qty * price))과 같은
 * 양자화로 계산하므로 절사 도메인에서도 등호가 성립해야 한다.
 */
class EngineDeterminismPropertyTest {

    private static final long BASE_TS = 1_721_600_000_000L;

    @Property(tries = 200)
    void replayIsDeterministic(@ForAll("commandSequences") List<OrderCommand> commands) {
        MatchingEngineImpl live = new MatchingEngineImpl();
        MatchingEngineImpl replay = new MatchingEngineImpl();

        List<EngineEvent> liveEvents = new ArrayList<>();
        for (OrderCommand c : commands) {
            liveEvents.addAll(live.apply(c));
        }
        List<EngineEvent> replayEvents = new ArrayList<>();
        for (OrderCommand c : commands) {
            replayEvents.addAll(replay.apply(c));
        }

        // BigDecimal.equals는 scale까지 보므로 레코드 동등성이 곧 바이트 동일 판정이다.
        assertEquals(liveEvents, replayEvents);
        assertEquals(live.stateChecksum(), replay.stateChecksum());
        assertEquals(live.lastAppliedSeq(), replay.lastAppliedSeq());
    }

    @Property(tries = 200)
    void bestBidStaysBelowBestAsk(@ForAll("commandSequences") List<OrderCommand> commands) {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        for (OrderCommand c : commands) {
            engine.apply(c);
            OrderBookImpl book = engine.orderBook(1);
            if (book == null) {
                continue;
            }
            BigDecimal bid = book.bestBid();
            BigDecimal ask = book.bestAsk();
            if (bid != null && ask != null) {
                assertTrue(bid.compareTo(ask) < 0,
                        "오더북 교차: bestBid=" + bid + ", bestAsk=" + ask);
            }
        }
    }

    @Property(tries = 200)
    void quantityIsConserved(@ForAll("commandSequences") List<OrderCommand> commands) {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        List<EngineEvent> events = new ArrayList<>();
        for (OrderCommand c : commands) {
            events.addAll(engine.apply(c));
        }
        OrderBookImpl book = engine.orderBook(1);

        for (OrderCommand c : commands) {
            if (c.type() != OrderCommand.CommandType.NEW) {
                continue;
            }
            long id = c.assignedOrderId();
            boolean accepted = events.stream().anyMatch(e ->
                    e.type() == EngineEvent.EventType.ACCEPTED && e.orderId() == id);
            boolean notional = c.ordType() == OrderCommand.OrdType.MARKET
                    && c.side() == OrderCommand.Side.BUY;
            if (!accepted) {
                // 거부 주문은 어떤 체결·취소에도 관여하지 않아야 한다.
                assertTrue(events.stream().noneMatch(e ->
                                (e.type() == EngineEvent.EventType.TRADE
                                        && (e.orderId() == id || e.counterOrderId() == id))
                                        || (e.type() == EngineEvent.EventType.CANCELED && e.orderId() == id)),
                        "거부 주문 " + id + "이 체결·취소에 관여했다");
                continue;
            }

            BigDecimal traded = BigDecimal.ZERO;
            BigDecimal canceled = BigDecimal.ZERO;
            for (EngineEvent e : events) {
                if (e.type() == EngineEvent.EventType.TRADE) {
                    if (notional && e.orderId() == id) {
                        // 소진 규약과 동일 양자화. 절사 도메인에서도 등호 보존이 성립해야 한다.
                        traded = traded.add(quantizeDown(e.quantity().multiply(e.price())));
                    } else if (!notional && (e.orderId() == id
                            || (e.counterOrderId() != null && e.counterOrderId() == id))) {
                        traded = traded.add(e.quantity());
                    }
                } else if (e.type() == EngineEvent.EventType.CANCELED && e.orderId() == id) {
                    canceled = canceled.add(e.quantity());
                }
            }
            BigDecimal resting = book == null ? null : book.openQuantity(id);
            BigDecimal remaining = resting == null ? BigDecimal.ZERO : resting;
            BigDecimal original = notional ? c.quoteOrderQty() : c.quantity();
            // notional 주문은 게시되지 않으므로 잔량 항은 0이다.
            assertEquals(0, original.compareTo(traded.add(canceled).add(remaining)),
                    "보존 위반: order=" + id + ", 원=" + original + ", 체결=" + traded
                            + ", 취소=" + canceled + ", 잔량=" + remaining);
        }
    }

    @Property(tries = 300)
    void typedOrdersPreserveDeterminismConservationAndNonResting(
            @ForAll("typedCommandSequences") List<OrderCommand> commands) {
        MatchingEngineImpl live = new MatchingEngineImpl();
        MatchingEngineImpl replay = new MatchingEngineImpl();
        List<EngineEvent> liveEvents = new ArrayList<>();
        List<EngineEvent> replayEvents = new ArrayList<>();
        for (OrderCommand c : commands) {
            liveEvents.addAll(live.apply(c));
        }
        for (OrderCommand c : commands) {
            replayEvents.addAll(replay.apply(c));
        }
        // (i) 재생 결정론: 이벤트열·체크섬 바이트 동일.
        assertEquals(liveEvents, replayEvents);
        assertEquals(live.stateChecksum(), replay.stateChecksum());

        OrderBookImpl book = live.orderBook(1);
        for (OrderCommand c : commands) {
            if (c.type() != OrderCommand.CommandType.NEW) {
                continue;
            }
            long id = c.assignedOrderId();
            OrderCommand.TimeInForce tif = c.timeInForce() == null
                    ? OrderCommand.TimeInForce.GTC : c.timeInForce();
            boolean canRest = c.ordType() == OrderCommand.OrdType.LIMIT
                    && (tif == OrderCommand.TimeInForce.GTC
                        || tif == OrderCommand.TimeInForce.POST_ONLY);
            BigDecimal resting = book == null ? null : book.openQuantity(id);
            // (ii) 비게시 종단: IOC/FOK/BEST/MARKET는 어떤 경우에도 레스팅으로 남지 않는다.
            if (!canRest) {
                assertNull(resting, "비게시 타입이 게시됨: order=" + id + ", type=" + c.ordType()
                        + ", tif=" + tif);
            }
            assertConserved(c, id, liveEvents, resting);
        }
    }

    private void assertConserved(OrderCommand c, long id, List<EngineEvent> events,
                                 BigDecimal resting) {
        boolean accepted = events.stream().anyMatch(e ->
                e.type() == EngineEvent.EventType.ACCEPTED && e.orderId() == id);
        boolean notional = c.ordType() == OrderCommand.OrdType.MARKET
                && c.side() == OrderCommand.Side.BUY;
        if (!accepted) {
            // 거부 주문은 어떤 체결·취소에도 관여하지 않는다.
            assertTrue(events.stream().noneMatch(e ->
                            (e.type() == EngineEvent.EventType.TRADE
                                    && (e.orderId() == id || (e.counterOrderId() != null
                                        && e.counterOrderId() == id)))
                                    || (e.type() == EngineEvent.EventType.CANCELED
                                        && e.orderId() == id)),
                    "거부 주문 " + id + "이 체결·취소에 관여했다");
            return;
        }
        BigDecimal traded = BigDecimal.ZERO;
        BigDecimal canceled = BigDecimal.ZERO;
        for (EngineEvent e : events) {
            if (e.type() == EngineEvent.EventType.TRADE) {
                if (notional && e.orderId() == id) {
                    traded = traded.add(quantizeDown(e.quantity().multiply(e.price())));
                } else if (!notional && (e.orderId() == id
                        || (e.counterOrderId() != null && e.counterOrderId() == id))) {
                    traded = traded.add(e.quantity());
                }
            } else if (e.type() == EngineEvent.EventType.CANCELED && e.orderId() == id) {
                canceled = canceled.add(e.quantity());
            }
        }
        BigDecimal remaining = resting == null ? BigDecimal.ZERO : resting;
        BigDecimal original = notional ? c.quoteOrderQty() : c.quantity();
        assertEquals(0, original.compareTo(traded.add(canceled).add(remaining)),
                "보존 위반: order=" + id + ", 원=" + original + ", 체결=" + traded
                        + ", 취소=" + canceled + ", 잔량=" + remaining);
    }

    @Provide
    Arbitrary<List<OrderCommand>> typedCommandSequences() {
        Arbitrary<int[]> raw = Combinators.combine(
                Arbitraries.integers().between(0, 99),        // 명령 종류 선택자
                Arbitraries.integers().between(1, 3),         // 사용자
                Arbitraries.integers().between(0, 1),         // 방향
                Arbitraries.integers().between(9000, 11000),  // 가격(scale 2)
                Arbitraries.integers().between(1, 5000),      // 수량(scale 17)
                Arbitraries.integers().between(0, 99)         // 변형(tif/취소 대상)
        ).as((kind, user, side, price, qtyUnits, variant) ->
                new int[]{kind, user, side, price, qtyUnits, variant});
        return raw.list().ofMinSize(1).ofMaxSize(80).map(this::toTypedCommands);
    }

    private List<OrderCommand> toTypedCommands(List<int[]> actions) {
        List<OrderCommand> commands = new ArrayList<>(actions.size());
        TreeMap<Integer, Long> issued = new TreeMap<>();
        for (int i = 0; i < actions.size(); i++) {
            int[] a = actions.get(i);
            long seq = i + 1;
            long user = a[1];
            OrderCommand.Side side = a[2] == 0 ? OrderCommand.Side.BUY : OrderCommand.Side.SELL;
            BigDecimal price = BigDecimal.valueOf(a[3], 2);
            BigDecimal qty = BigDecimal.valueOf(a[4], 17);
            int variant = a[5];
            int kind = a[0];
            if (kind < 40) {
                // 유동성 공급용 LIMIT GTC. 게시 가능하므로 취소 대상 후보로 등록.
                issued.put(issued.size() + 1, seq);
                commands.add(newCmd(seq, user, side, OrderCommand.OrdType.LIMIT,
                        OrderCommand.TimeInForce.GTC, price, qty, null));
            } else if (kind < 55) {
                OrderCommand.TimeInForce tif = switch (variant % 3) {
                    case 0 -> OrderCommand.TimeInForce.POST_ONLY;
                    case 1 -> OrderCommand.TimeInForce.IOC;
                    default -> OrderCommand.TimeInForce.FOK;
                };
                if (tif == OrderCommand.TimeInForce.POST_ONLY) {
                    issued.put(issued.size() + 1, seq);
                }
                commands.add(newCmd(seq, user, side, OrderCommand.OrdType.LIMIT,
                        tif, price, qty, null));
            } else if (kind < 70) {
                boolean buy = side == OrderCommand.Side.BUY;
                OrderCommand.TimeInForce tif = variant % 2 == 0
                        ? OrderCommand.TimeInForce.GTC : OrderCommand.TimeInForce.FOK;
                commands.add(newCmd(seq, user, side, OrderCommand.OrdType.MARKET, tif,
                        null, buy ? null : qty, buy ? quantizeDown(price.multiply(qty)) : null));
            } else if (kind < 85) {
                // BEST는 매도 전용 + IOC/FOK. 가격·notional 없음.
                OrderCommand.TimeInForce tif = variant % 2 == 0
                        ? OrderCommand.TimeInForce.IOC : OrderCommand.TimeInForce.FOK;
                commands.add(newCmd(seq, user, OrderCommand.Side.SELL, OrderCommand.OrdType.BEST,
                        tif, null, qty, null));
            } else {
                Long targetSeq = issued.get(((a[5] - 1) % Math.max(1, issued.size())) + 1);
                long target = targetSeq == null ? 999_999L : targetSeq;
                commands.add(new OrderCommand(seq, 1, OrderCommand.CommandType.CANCEL, user,
                        null, target, BASE_TS + seq, seq, null, null, null, null, null, null, 1));
            }
        }
        return commands;
    }

    private OrderCommand newCmd(long seq, long user, OrderCommand.Side side,
                                OrderCommand.OrdType ordType, OrderCommand.TimeInForce tif,
                                BigDecimal price, BigDecimal qty, BigDecimal notional) {
        return new OrderCommand(seq, 1, OrderCommand.CommandType.NEW, user, "c-" + seq, seq,
                BASE_TS + seq, seq, side, ordType, tif, price, qty, notional, 1);
    }

    @Provide
    Arbitrary<List<OrderCommand>> commandSequences() {
        Arbitrary<int[]> raw = Combinators.combine(
                Arbitraries.integers().between(0, 99),      // 명령 종류 선택자
                Arbitraries.integers().between(1, 3),       // 사용자
                Arbitraries.integers().between(0, 1),       // 방향
                Arbitraries.integers().between(9000, 11000), // 가격(1/100 단위, scale 2)
                Arbitraries.integers().between(1, 5000),    // 수량(1e-17 단위, scale 17)
                Arbitraries.integers().between(1, 80)       // 취소 대상 후보
        ).as((kind, user, side, price, qtyUnits, target) ->
                new int[]{kind, user, side, price, qtyUnits, target});
        return raw.list().ofMinSize(1).ofMaxSize(80).map(this::toCommands);
    }

    private List<OrderCommand> toCommands(List<int[]> actions) {
        List<OrderCommand> commands = new ArrayList<>(actions.size());
        // 이전에 발급된 주문 id만 취소 대상으로 고른다(없는 id는 거부 경로 검증이 된다).
        TreeMap<Integer, Long> issued = new TreeMap<>();
        for (int i = 0; i < actions.size(); i++) {
            int[] a = actions.get(i);
            long seq = i + 1;
            long user = a[1];
            OrderCommand.Side side = a[2] == 0 ? OrderCommand.Side.BUY : OrderCommand.Side.SELL;
            BigDecimal price = BigDecimal.valueOf(a[3], 2);
            BigDecimal qty = BigDecimal.valueOf(a[4], 17);
            if (a[0] < 70) {
                issued.put(issued.size() + 1, seq);
                commands.add(new OrderCommand(seq, 1, OrderCommand.CommandType.NEW, user,
                        "c-" + seq, seq, BASE_TS + seq, seq, side, OrderCommand.OrdType.LIMIT,
                        OrderCommand.TimeInForce.GTC, price, qty, null, 1));
            } else if (a[0] < 85) {
                boolean buy = side == OrderCommand.Side.BUY;
                // scale 19 곱은 검증 거부 대상이라 유효한 scale 18 notional로 절사해 공급한다.
                commands.add(new OrderCommand(seq, 1, OrderCommand.CommandType.NEW, user,
                        "c-" + seq, seq, BASE_TS + seq, seq, side, OrderCommand.OrdType.MARKET,
                        null, null, buy ? null : qty,
                        buy ? quantizeDown(price.multiply(qty)) : null, 1));
            } else {
                Long targetSeq = issued.get(((a[5] - 1) % Math.max(1, issued.size())) + 1);
                long target = targetSeq == null ? 999_999L : targetSeq;
                commands.add(new OrderCommand(seq, 1, OrderCommand.CommandType.CANCEL, user,
                        null, target, BASE_TS + seq, seq, null, null, null, null, null, null, 1));
            }
        }
        return commands;
    }

    private static BigDecimal quantizeDown(BigDecimal v) {
        return v.setScale(OrderBookImpl.SCALE, java.math.RoundingMode.DOWN);
    }
}
