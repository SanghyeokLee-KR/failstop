package kr.exchange.admin.msp;

import java.util.Map;

/**
 * AI 통지 사건을 내보내는 자리(설계 7-12). 관제센터를 켠 배포의 구현은 TeamsNotifier 다. 통지 보관함에 쓰고 보고
 * 서비스로 넘기며, 보고 서비스 주소가 없으면 SKIPPED 로 남긴다. NONE 은 그 빈이 없는 조립(시험)에서만 쓴다.
 *
 * 사실 값(facts)에는 서버가 만든 값만 싣는다. 모델의 자유 문장은 싣지 않는다. 호출은 조치가 커밋된 뒤에 하고,
 * 구현은 호출 스레드를 붙잡지 않아야 한다.
 */
public interface AiNotifier {

    enum Kind {
        AUTO_EXECUTED, AUTO_REVERTED, MODE_CHANGED, CAP_REACHED, OBSERVE_FAILED, EXECUTION_UNKNOWN, MODEL_FAILING,
        STOPPED, RESUMED
    }

    record Notice(Kind kind, Map<String, Object> facts) {
    }

    void publish(Notice notice);

    AiNotifier NONE = notice -> { };
}
