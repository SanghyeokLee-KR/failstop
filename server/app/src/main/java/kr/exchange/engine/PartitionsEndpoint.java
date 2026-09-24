package kr.exchange.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 파티션 상태 조회와 운영자 조작(API 설계 §6-C /actuator/partitions, DB 설계 §5.4-c 9·10).
 *
 * 읽기는 파티션별 상태·epoch·참여·선호·강등 기록·차단기·마켓을 낸다. 쓰기는 참여 켜고 끄기와 차단기 해제다. 쓰기는 요청의
 * 원격 주소가 loopback 일 때만 받는다. 운영(prod)의 actuator 는 8081 관리 포트라 SG 가 막지만, 카오스 스택의 dev 프로파일은
 * actuator 가 8080 에 있어 LB 와 호스트 포트로도 닿는다. 원격 주소는 신뢰 프록시 규칙을 거친 값이라 LB 가 붙인 전달 헤더는
 * 믿지 않는다. 운영자는 노드 안에서 localhost 로 부른다.
 */
@Endpoint(id = "partitions")
public class PartitionsEndpoint {

    private static final Logger log = LoggerFactory.getLogger(PartitionsEndpoint.class);

    private final PartitionLeases leases;
    private final Supplier<String> remoteAddress;

    public PartitionsEndpoint(PartitionLeases leases) {
        this(leases, PartitionsEndpoint::currentRemoteAddress);
    }

    PartitionsEndpoint(PartitionLeases leases, Supplier<String> remoteAddress) {
        this.leases = leases;
        this.remoteAddress = remoteAddress;
    }

    @ReadOperation
    public Map<String, Object> partitions() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", leases.plan().partitions());
        out.put("partitioned", leases.plan().partitioned());
        // 엔진을 끈 노드는 수명주기가 없어 빈 목록이다.
        out.put("partitions", leases.runtimes().stream().map(PartitionRuntime::status).toList());
        return out;
    }

    /**
     * 참여를 켜고 끄거나(participate) 차단기를 푼다(reset_breaker). 원격 주소가 loopback 이 아니면 상태를 바꾸지 않고 403 이다.
     * 노드 치명(DEMOTED)은 풀지 않는다.
     */
    @WriteOperation
    public WebEndpointResponse<Map<String, Object>> update(String resource, @Nullable Boolean participate,
                                                           @Nullable Boolean reset_breaker) {
        String remote = remoteAddress.get();
        if (!loopback(remote)) {
            log.warn("파티션 조작 거부(loopback 아님): 원격={}, resource={}", remote, resource);
            return response(403, "loopback 에서만 조작할 수 있다", null);
        }
        if (participate == null && !Boolean.TRUE.equals(reset_breaker)) {
            return response(400, "participate 나 reset_breaker 가 필요하다", null);
        }
        Optional<PartitionRuntime> found = leases.runtime(resource);
        if (found.isEmpty()) {
            return response(400, "이 노드에 수명주기가 없는 자원: " + resource + " (계획 "
                    + leases.plan().partitions() + ")", null);
        }
        PartitionRuntime runtime = found.get();
        Map<String, Object> before = runtime.status();
        if (Boolean.TRUE.equals(reset_breaker) && !runtime.resetBreaker()) {
            log.warn("파티션 차단기 해제 거부(노드 치명): 원격={}, resource={}", remote, resource);
            return response(409, "노드 치명(DEMOTED)은 풀 수 없다. 재기동해야 한다", before);
        }
        if (participate != null) {
            runtime.setParticipate(participate);
        }
        Map<String, Object> after = runtime.status();
        log.warn("파티션 조작: 원격={}, resource={}, before={}, after={}", remote, resource,
                summary(before), summary(after));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("before", before);
        body.put("after", after);
        return new WebEndpointResponse<>(body, 200);
    }

    private static WebEndpointResponse<Map<String, Object>> response(int status, String message,
                                                                     Map<String, Object> state) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        if (state != null) {
            body.put("state", state);
        }
        return new WebEndpointResponse<>(body, status);
    }

    private static String summary(Map<String, Object> status) {
        return status.get("state") + "/participate=" + status.get("participate") + "/breaker="
                + status.get("breaker");
    }

    static boolean loopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            // 숫자 주소만 받는다. 이름이면 조회가 일어나므로 loopback 으로 보지 않는다.
            if (!address.matches("[0-9a-fA-F:.%]+")) {
                return false;
            }
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String currentRemoteAddress() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        return null;
    }
}
