package kr.exchange.engine;

/**
 * 접수 가용성 통지(백엔드 API §6-C, 복구 RTO §3-a). 승격 상태기계의 전이를 외부 readiness로
 * 승격시킨다. 구현(HaAvailability)이 AvailabilityChangeEvent를 발행해 LB 헬스체크가 보는
 * /actuator/health/readiness를 ACCEPTING_TRAFFIC·REFUSING_TRAFFIC로 전이시킨다.
 *
 * PromotionCoordinator가 Spring에 직접 결합하지 않도록 인터페이스로 분리한다. 테스트는 NOOP를
 * 주입해 배선 없이 상태기계만 검증한다.
 */
public interface AvailabilitySignal {

    /** 접수 개방. 재생 완료·리더 확정 후에만 호출한다. */
    void accepting();

    /** 접수 차단. 팔로워·승격 대기(PROMOTING)·강등 시 호출한다. */
    void refusing();

    AvailabilitySignal NOOP = new AvailabilitySignal() {
        @Override
        public void accepting() {
        }

        @Override
        public void refusing() {
        }
    };
}
