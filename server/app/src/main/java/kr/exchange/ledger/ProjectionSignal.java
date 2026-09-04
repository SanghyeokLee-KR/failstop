package kr.exchange.ledger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 주문 프로젝션 반영 신호.
 *
 * 접수 경로는 저널에 명령을 넣고 프로젝션에 행이 생길 때까지 기다린다. 종전에는 20ms마다
 * DB를 다시 조회했다. 2026-09-04 실측(pg_stat_statements)에서 그 조회가 95초 창에 292830회로
 * 전체 쿼리 호출의 절반을 넘었다. 주문 30건/s 기준 주문 하나당 약 100회다.
 *
 * 정산은 같은 JVM에서 돈다. 그러니 폴링 대신 정산이 끝났다고 알려주면 된다. 접수 경로는
 * 제출 전에 주문 id로 자리를 잡아 두고, 정산은 커밋 성공 뒤 그 자리를 깨운다. 깨어난 접수는
 * DB를 한 번만 조회한다.
 *
 * 신호를 놓쳐도 정확성은 유지된다. 대기에 상한이 있고 상한이 지나면 호출자가 종전대로 폴링을
 * 이어간다. 신호는 빠른 길이고 폴링은 남아 있는 느린 길이다. 다중 노드에서 다른 노드의 엔진이
 * 처리한 경우가 그 느린 길에 해당한다.
 *
 * 자리는 반드시 회수한다. 대기가 끝나면 호출자가 {@link #forget(long)}을 부른다. 신호가 오지
 * 않아 자리가 남는 경우를 대비해 등록 수 상한을 두고, 넘으면 신규 등록을 받지 않는다.
 * 그때는 폴링만으로 동작하므로 기능은 유지되고 속도만 종전으로 돌아간다.
 */
public final class ProjectionSignal {

    /** 등록 상한. 넘으면 신규 등록을 거부하고 폴링으로만 동작한다. 누수의 상한을 고정한다. */
    private static final int MAX_WAITERS = 20_000;

    private final Map<Long, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();

    /**
     * 주문 id로 자리를 잡는다. 제출 전에 부른다. 제출 뒤에 부르면 정산이 먼저 끝나 신호를
     * 놓칠 수 있다.
     *
     * @return 상한 초과로 등록하지 못하면 false. 호출자는 폴링만으로 진행한다.
     */
    public boolean register(long orderId) {
        if (waiters.size() >= MAX_WAITERS) {
            return false;
        }
        waiters.putIfAbsent(orderId, new CompletableFuture<>());
        return true;
    }

    /**
     * 정산 커밋이 끝났음을 알린다. 등록되지 않은 id는 무시한다(그 주문을 기다리는 접수가
     * 이 노드에 없다는 뜻이다).
     */
    public void settled(long orderId) {
        CompletableFuture<Void> f = waiters.get(orderId);
        if (f != null) {
            f.complete(null);
        }
    }

    /**
     * 신호를 기다린다. 등록되지 않았거나 상한 안에 오지 않으면 false를 돌려주고, 호출자는
     * 종전 폴링으로 넘어간다.
     */
    public boolean await(long orderId, long timeoutMs) {
        CompletableFuture<Void> f = waiters.get(orderId);
        if (f == null) {
            return false;
        }
        try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 자리를 회수한다. 대기가 끝나면 성공 여부와 무관하게 부른다. */
    public void forget(long orderId) {
        waiters.remove(orderId);
    }

    /** 진단용. 현재 대기 자리 수다. */
    public int waiting() {
        return waiters.size();
    }
}
