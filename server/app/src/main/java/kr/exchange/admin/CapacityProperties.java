package kr.exchange.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 앱 계층 노드 인벤토리의 계약면. "어떤 노드가 있어야 하는가" 를 설정에서 읽는다.
 *
 * 관측면과 갈라 둔 이유가 있다. 계약을 DB 에 적재하면 반드시 드리프트한다. 이 저장소가
 * 같은 실패를 여러 번 겪었다. 존재한 적 없는 사이드카가 주석으로 남아 있었고, 도면이
 * 타겟그룹을 1 개로 그리고 있었고, ADR 색인이 13 편에서 멈춰 있었다. 그래서 계약은 설정
 * 하나에 두고 상태는 매번 물어본다.
 *
 * 목록은 있을 수 있는 노드 전부다. 그중 몇 대가 떠 있어야 하는지는 capacity_plan 이 정하고,
 * 실제로 몇 대가 떠 있는지는 NodeProbe 가 잰다. 화면은 셋을 나란히 보여준다.
 *
 * 클라우드로 옮기면 이 구현을 Auto Scaling group 조회로 갈아 끼운다. 그때도 화면과 API 는
 * 바뀌지 않는다.
 */
@ConfigurationProperties(prefix = "exchange.capacity")
public class CapacityProperties {

    /** 노드 한 대를 재는 데 허용하는 시간. 넘으면 그 노드를 도달 불가로 본다. */
    private Duration probeTimeout = Duration.ofSeconds(2);

    /**
     * 증설·축소 권고 임계. 조회를 받는 노드의 평균 사용률 기준이다.
     *
     * 상수가 아니라 설정인 이유. 같은 코드가 도는 환경마다 닿을 수 있는 사용률이 다르다.
     * 로컬 카오스 스택은 조회 부하를 올려도 DB 풀 수용 제어가 먼저 걸려 조회 계층 평균이
     * 0.33 근처에서 멈춘다(2026-09-23 실측). 그 환경에서 0.60 을 기준으로 두면 권고가
     * 영원히 나오지 않고, 화면은 조치할 수 없는 상태를 계속 안정이라고 적는다.
     *
     * 기본값은 계약값이라 값을 주지 않는 환경은 종전과 같다. AWS 운영값은 이 기본값이다.
     */
    private double scaleOutCpu = 0.60;

    private double scaleInCpu = 0.30;

    private final List<Node> nodes = new ArrayList<>();

    public Duration getProbeTimeout() {
        return probeTimeout;
    }

    public void setProbeTimeout(Duration probeTimeout) {
        this.probeTimeout = probeTimeout;
    }

    public double getScaleOutCpu() {
        return scaleOutCpu;
    }

    public void setScaleOutCpu(double scaleOutCpu) {
        this.scaleOutCpu = scaleOutCpu;
    }

    public double getScaleInCpu() {
        return scaleInCpu;
    }

    public void setScaleInCpu(double scaleInCpu) {
        this.scaleInCpu = scaleInCpu;
    }

    public List<Node> getNodes() {
        return nodes;
    }

    /** 계약상의 노드 한 대. 값은 전부 설정이고 관측이 아니다. */
    public static class Node {

        /** 화면에 그대로 뜨는 이름. app-1 처럼 사람이 부르는 이름을 쓴다. */
        private String id;

        /** 가용영역. 이중화가 실제로 갈렸는지 화면이 이것으로 보인다. */
        private String az;

        /** 인스턴스 등급. 비용과 용량 판단의 근거라 화면에 함께 낸다. */
        private String instanceType;

        /** 이 노드의 액추에이터 기점. 관리 평면이 여기로 상태를 물어본다. */
        private String baseUrl;

        /**
         * 매칭엔진을 켜고 뜨는 노드인가. 켠 노드만 리더가 될 수 있다.
         * 조회 전용 노드는 꺼서 띄운다. 켠 채로 띄우면 기동 시 승격을 시도하고 15 초를
         * 소진한 뒤 거부당한다.
         */
        private boolean engine;

        /**
         * 확장 대상인가. 리더 후보는 대상이 아니다. 매칭엔진이 단일 라이터라 노드를
         * 늘려도 주문 천장이 오르지 않는다(2026-09-07 실측 56.8/s -> 54.3/s).
         */
        private boolean scalable = true;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getAz() {
            return az;
        }

        public void setAz(String az) {
            this.az = az;
        }

        public String getInstanceType() {
            return instanceType;
        }

        public void setInstanceType(String instanceType) {
            this.instanceType = instanceType;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public boolean isEngine() {
            return engine;
        }

        public void setEngine(boolean engine) {
            this.engine = engine;
        }

        public boolean isScalable() {
            return scalable;
        }

        public void setScalable(boolean scalable) {
            this.scalable = scalable;
        }
    }
}
