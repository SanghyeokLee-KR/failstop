package kr.exchange.api.common;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 수용 게이트의 런타임 스위치(API 설계 §2-5-a v2.41).
 *
 * 기본은 켜짐이다. 끄는 경우는 하나뿐이다. 수용 제어가 없는 상태(보통의 Spring 서비스,
 * 곧 고객 기본값)를 재현해 탐지·판단·복구 절차를 검증하는 실험이다. 기록된 사고는 이 상태에서
 * 났고(ADR 0007), 이 스위치는 그 상태를 재기동 없이 오가게 한다.
 *
 * 값을 바꾸면 반드시 WARN 한 줄을 남긴다. 누가·언제·이전값·이후값. 운영에서 이 스위치가 꺼진
 * 채 발견되면 그 로그가 유일한 단서다.
 */
public final class AdmissionGate {

    private static final Logger log = LoggerFactory.getLogger(AdmissionGate.class);

    private final AtomicBoolean enabled;

    public AdmissionGate(boolean initiallyEnabled) {
        this.enabled = new AtomicBoolean(initiallyEnabled);
        if (!initiallyEnabled) {
            log.warn("수용 게이트가 꺼진 채 기동. 초과 요청이 거절되지 않고 커넥션을 기다린다(실험 전용)");
        }
    }

    public boolean enabled() {
        return enabled.get();
    }

    /** 이전값을 돌려준다. 같은 값이면 로그를 남기지 않는다. */
    public boolean set(boolean value, String actor) {
        boolean before = enabled.getAndSet(value);
        if (before != value) {
            log.warn("수용 게이트 전환 actor={} before={} after={}", actor, before, value);
        }
        return before;
    }
}
