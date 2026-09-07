package kr.exchange.api.ws;

/**
 * WS 동시 연결 상한 유도(하네스 CICD·IaC §3-3 WS 동시연결 예산).
 *
 * 정본은 노드당 3,000 연결을 적었으나 그것을 강제하는 코드가 없었고, 힙 크기와도 무관한 고정값
 * 이었다. 2026-09-04 실측(증거 gateoff-20260903-2vcpu 31회차)에서 최대 힙 768MB(컨테이너 1GB,
 * MaxRAMPercentage 75) 노드가 2,000 연결까지는 정상이었고 2,702 연결에서
 * {@code OutOfMemoryError: Java heap space}로 죽었다. 매칭 엔진 쓰기 스레드까지 함께 죽어
 * 주문과 조회가 모두 멈췄다. 고정 3,000은 그 노드에서 죽는 지점 위에 있어 방어가 되지 않는다.
 *
 * 그래서 상한을 힙에서 유도한다. 실측의 정상 지점(768MB에 2,000 연결)을 비율로 옮겨
 * 힙 1MB당 2.6 연결로 둔다. 죽는 지점까지 26% 여유가 남는다. 카오스 스택과 t4g.small이
 * 컨테이너 메모리를 서로 다르게 잡으므로 유도가 곧 추종이다(AdmissionLimits와 같은 이유).
 *
 * 정본의 3,000은 천장으로 유지한다. 앱 2GB 실측에서 3,000 연결은 살아남았으나 첫 메시지 지연이
 * p50 4,668ms로 늘어 그 위는 살아 있어도 쓸모가 없다. nginx fd 예산도 그 값에 맞춰져 있다.
 *
 * 왜 연결 수를 세는가. 이 사고에서 힙을 채운 것은 요청이 아니라 붙잡고 있는 연결이다. 요청
 * 수용 제어는 요청 수명에 슬롯을 매어 두므로 핸드셰이크가 끝나는 순간 슬롯을 놓는다. 연결은
 * 그 뒤로도 몇 시간을 산다. 두 축은 같은 상한을 쓸 수 없다.
 */
public record WsConnectionLimit(int limit, long maxHeapBytes) {

    /** 실측에서 옮긴 비율. 힙 1MB당 연결 수를 10배 정수로 둔다(2.6 연결/MB). */
    private static final int CONNECTIONS_PER_MB_X10 = 26;

    /** 정본 천장(하네스 CICD·IaC §3-3). nginx fd 예산이 이 값에 맞춰져 있다. */
    static final int CANON_CEILING = 3_000;

    /**
     * 하한. 초소형 힙에서도 데모가 성립해야 한다. 브라우저 몇 개와 봇 한 대를 합쳐도 이 아래로
     * 내려가면 상한이 기능이 아니라 고장으로 보인다.
     */
    static final int FLOOR = 256;

    private static final long MB = 1024L * 1024L;

    /**
     * 0 이하 설정은 힙에서 유도한다. 명시값은 그대로 쓰되 정본 천장을 넘지 못한다. 천장을 넘는
     * 설정을 조용히 받아들이면 정본과 코드가 어긋난 채 돌고, 그 어긋남이 드러나는 순간이 노드가
     * 죽는 순간이다.
     */
    public static WsConnectionLimit resolve(int configured, long maxHeapBytes) {
        if (maxHeapBytes <= 0) {
            throw new IllegalArgumentException("최대 힙은 양수여야 한다: " + maxHeapBytes);
        }
        if (configured > CANON_CEILING) {
            throw new IllegalStateException(
                    "WS 동시 연결 상한 " + configured + " 이 정본 천장 " + CANON_CEILING
                            + " 을 넘는다. exchange.admission.ws-connections 를 낮추거나"
                            + " 정본(하네스 CICD·IaC §3-3 WS 동시연결 예산)을 먼저 고쳐라.");
        }
        if (configured > 0) {
            return new WsConnectionLimit(configured, maxHeapBytes);
        }
        long derived = (maxHeapBytes / MB) * CONNECTIONS_PER_MB_X10 / 10;
        int bounded = (int) Math.min(CANON_CEILING, Math.max(FLOOR, derived));
        return new WsConnectionLimit(bounded, maxHeapBytes);
    }

    /** 진단용. 유도 근거를 로그와 기동 배너에 그대로 싣는다. */
    public String describe() {
        return "WS 동시 연결 상한 " + limit + " (최대 힙 " + (maxHeapBytes / MB) + "MB)";
    }
}
