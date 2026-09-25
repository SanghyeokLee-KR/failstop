package kr.exchange.api.order;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/** 다른 패키지의 시험이 게이트웨이의 V47 판정 이음매(패키지 전용)에 실패를 넣게 한다. */
public final class OrderGatewayTestAccess {

    private OrderGatewayTestAccess() {
    }

    /**
     * V47 판정 호출을 감싼다. failureForCall 이 n 번째 호출(1부터)에 예외를 주면 그것을 던지고, null 을 주면 실제 판정을 부른다.
     *
     * @return 감싼 뒤의 판정 호출 수
     */
    public static AtomicInteger interceptResolutions(OrderGateway gateway,
                                                     IntFunction<RuntimeException> failureForCall) {
        AtomicInteger calls = new AtomicInteger();
        gateway.interceptResolver(real -> (userId, clientOrderId, requestHash, assignedId, discardKey) -> {
            RuntimeException failure = failureForCall.apply(calls.incrementAndGet());
            if (failure != null) {
                throw failure;
            }
            return real.resolve(userId, clientOrderId, requestHash, assignedId, discardKey);
        });
        return calls;
    }

    /** 게이트웨이의 주문 id 채번 호출 수(S5 설계 6-2). 선검사·힌트 거절이 채번 전에 끝났는지 본다. */
    public static long orderIdAssignments(OrderGateway gateway) {
        return gateway.orderIdAssignments();
    }

    /** 인계 작업자가 주문 하나를 처리하기 전에 부를 이음매(래치로 멈추기 등). */
    public interface WorkerOrderHook {
        void before(OrderCancelHandoffRepository.Claimed row, int index) throws InterruptedException;
    }

    public static void beforeHandoffOrder(CancelHandoffWorker worker, WorkerOrderHook hook) {
        worker.beforeOrder = hook::before;
    }

    /** 인계 작업자가 끝내기 직전에 부를 이음매. 던지면 행 처리 밖으로 새는 예외다. */
    public static void beforeHandoffComplete(CancelHandoffWorker worker,
                                             java.util.function.Consumer<OrderCancelHandoffRepository.Claimed> hook) {
        worker.beforeComplete = hook::accept;
    }

    /** 살아 있는 작업자 스레드 수. */
    public static int aliveWorkerThreads(CancelHandoffWorker worker) {
        return worker.aliveThreads();
    }

    /**
     * V47 판정 호출을 감싸 실제 판정을 부른 뒤 afterRealCall 에 호출 번호(1부터)와 키를 지우라고 했는지를 넘긴다. afterRealCall 이
     * 예외를 주면 그것을 던진다. 판정은 DB 에서 끝났는데 응답만 잃은 모양이다.
     *
     * @return 감싼 뒤 판정 호출마다 키를 지우라고 했는지
     */
    public static List<Boolean> interceptAfterResolutions(OrderGateway gateway,
                                                          BiFunction<Integer, Boolean, RuntimeException> afterRealCall) {
        List<Boolean> discards = new CopyOnWriteArrayList<>();
        gateway.interceptResolver(real -> (userId, clientOrderId, requestHash, assignedId, discardKey) -> {
            discards.add(discardKey);
            OrderGateway.IntakeResolution resolution =
                    real.resolve(userId, clientOrderId, requestHash, assignedId, discardKey);
            RuntimeException lost = afterRealCall.apply(discards.size(), discardKey);
            if (lost != null) {
                throw lost;
            }
            return resolution;
        });
        return discards;
    }
}
