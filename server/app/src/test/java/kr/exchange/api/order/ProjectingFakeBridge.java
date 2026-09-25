package kr.exchange.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.AcceptGate;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.NotReadyException;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.SubmitResult;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.CommandDraft;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 저널과 엔진 없이 접수를 흉내 내는 브리지(S5 설계 6-2 시험 배선). 게이트는 시험이 연다·닫는다. 열려 있으면 새 주문은 OPEN
 * 주문 행을, 취소는 CANCELED 로 바꾼 주문 행을 곧바로 남겨 게이트웨이의 반영 대기가 끝나게 한다. 닫혀 있으면 운영의 게이트처럼
 * 저널 전에 거부한다. 멱등 키·계정·주문 행은 실제 DB 다.
 */
public final class ProjectingFakeBridge extends EngineBridge {

    private final JdbcClient jdbc;
    private final AtomicLong seq = new AtomicLong(1);
    public final AtomicInteger submits = new AtomicInteger();
    public volatile boolean open = true;

    public ProjectingFakeBridge(JdbcClient jdbc) {
        super(null, new SingleWriterLoop(16, new MatchingEngineImpl(), events -> { }, (afterSeq, limit) -> List.of(),
                fatal -> { }), new CommandPayloadMapper(new ObjectMapper()), AcceptGate.ALWAYS_OPEN, marketId -> false);
        this.jdbc = jdbc;
    }

    @Override
    public boolean accepting(int marketId) {
        return open;
    }

    @Override
    public Boolean acceptingIfKnown(int marketId) {
        return open;
    }

    @Override
    public SubmitResult submit(OrderIntake intake) {
        submits.incrementAndGet();
        if (!open) {
            throw new NotReadyException("시험: 게이트 닫힘");
        }
        if (intake.type() == CommandDraft.CommandType.NEW) {
            jdbc.sql("""
                            INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type,
                                                price, quantity, remaining_qty, status)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')
                            """)
                    .params(intake.assignedOrderId(), intake.externalId(), intake.userId(), intake.marketId(),
                            intake.clientOrderId(), intake.side().name(), intake.ordType().name(), intake.price(),
                            intake.quantity(), intake.quantity())
                    .update();
        } else {
            jdbc.sql("UPDATE orders SET status = 'CANCELED', remaining_qty = 0 WHERE id = ?")
                    .params(intake.assignedOrderId())
                    .update();
        }
        return new SubmitResult(seq.getAndIncrement(), 1, false, true, true);
    }
}
