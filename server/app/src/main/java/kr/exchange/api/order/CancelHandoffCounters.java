package kr.exchange.api.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 파티션 밖 취소 인계의 계수(DB 설계 §5.4-d 8, 하네스 CI/CD §5-2). 태그 값을 기동 때 모두 등록해 프로메테우스 increase() 가 첫
 * 증가를 센다.
 *
 * requests 는 요청자가 넣은 행의 결말 수다(행 단위). done 결과를 받음, pending 시작했거나 포기하지 못해 끝나지 않음, not_started
 * 소유 노드가 시작하지 않아 포기함, insert_failed 넣기가 DB 오류로 끝남. worker 는 작업자의 처리 수다. claimed 점유,
 * reclaimed 더 작은 epoch 의 점유를 다시 잡음, completed 끝냄, complete_lost 끝내기 0행, stopped 기간이 끝나 멈춤, resumed
 * 같은 기간 안에서 멈춘 주문을 이어 함, expired 만료로 닫은 행, error 주문 단위 server_error 와 행 단위 예외.
 */
public final class CancelHandoffCounters {

    public static final List<String> REQUEST_OUTCOMES = List.of("done", "pending", "not_started", "insert_failed");
    public static final List<String> WORKER_OUTCOMES = List.of("claimed", "reclaimed", "completed", "complete_lost",
            "stopped", "resumed", "expired", "error");

    private final Map<String, Counter> requests = new HashMap<>();
    private final Map<String, Counter> worker = new HashMap<>();

    public CancelHandoffCounters(MeterRegistry registry) {
        for (String outcome : REQUEST_OUTCOMES) {
            requests.put(outcome, Counter.builder("exchange.order.cancel_handoff.requests")
                    .tag("outcome", outcome)
                    .description("일괄 취소·회원 폐쇄가 넣은 인계 행의 결말 수(행 단위)")
                    .register(registry));
        }
        for (String outcome : WORKER_OUTCOMES) {
            worker.put(outcome, Counter.builder("exchange.order.cancel_handoff.worker")
                    .tag("outcome", outcome)
                    .description("소유 노드 인계 작업자의 처리 수")
                    .register(registry));
        }
    }

    /** 계수를 내보내지 않는 판. 배선하지 않은 시험이 쓴다. */
    public static CancelHandoffCounters untracked() {
        return new CancelHandoffCounters(new SimpleMeterRegistry());
    }

    void request(String outcome, int rows) {
        if (rows > 0) {
            requests.get(outcome).increment(rows);
        }
    }

    void worker(String outcome) {
        worker.get(outcome).increment();
    }

    void worker(String outcome, int n) {
        if (n > 0) {
            worker.get(outcome).increment(n);
        }
    }

    /** 요청자 행 결말 수. */
    public double requests(String outcome) {
        return requests.get(outcome).count();
    }

    /** 작업자 결말 수. */
    public double workerCount(String outcome) {
        return worker.get(outcome).count();
    }
}
