package kr.exchange.api.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * 수용 게이트 조작용 actuator 엔드포인트(API 설계 §2-5-a v2.41).
 *
 * 관리 평면(/admin/v1, 8443·mTLS)이 아니라 actuator에 두는 이유. 이 조작은 운영자가 SSM 으로
 * 노드 안에서 localhost 로 치는 조치이고, actuator 는 이미 내부 포트(prod 8081)로 분리돼 있다.
 * 인터넷에 열리지 않는 것은 SG 가, 사람의 접근은 SSM IAM 이 막는다.
 *
 * 읽기는 상태와 버킷별 in-flight 를 함께 돌려준다. 게이트를 켠 직후 in-flight 가 상한에 붙는
 * 것을 같은 응답에서 확인할 수 있게 하기 위해서다.
 */
@Endpoint(id = "admission")
public class AdmissionEndpoint {

    private final AdmissionGate gate;
    private final AdmissionControl order;
    private final AdmissionControl query;
    private final AdmissionControl requery;

    public AdmissionEndpoint(AdmissionGate gate, AdmissionControl order,
                             AdmissionControl query, AdmissionControl requery) {
        this.gate = gate;
        this.order = order;
        this.query = query;
        this.requery = requery;
    }

    @ReadOperation
    public Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", gate.enabled());
        out.put("buckets", Map.of(
                "order", bucket(order),
                "query", bucket(query),
                "requery", bucket(requery)));
        return out;
    }

    @WriteOperation
    public Map<String, Object> toggle(boolean enabled) {
        boolean before = gate.set(enabled, "actuator");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("before", before);
        out.put("after", gate.enabled());
        return out;
    }

    private static Map<String, Object> bucket(AdmissionControl c) {
        return Map.of("limit", c.limit(), "inFlight", c.inFlight());
    }
}
