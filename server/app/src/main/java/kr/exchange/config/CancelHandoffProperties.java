package kr.exchange.config;

import kr.exchange.api.order.OrderCancelHandoff;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;

/**
 * 파티션 밖 취소 인계 설정(DB 설계 §5.4-d, S5 설계 4-4). 값은 application.yml 의 exchange.order.cancel-handoff.* 이고 빠진 값은
 * 기본값이다. 범위를 벗어나면(chunk 1~500 밖 등) 기동을 멈춘다.
 *
 * @param waitFor        요청자가 넣은 행을 기다리는 상한(설정 키 wait). 기본 5초. Object.wait 와 겹쳐 이름을 바꿨다
 * @param poll           작업자가 빈 표나 닫힌 게이트를 다시 보는 간격. 기본 200밀리초
 * @param chunk          인계 행 하나의 주문 수. 기본 100, 1~500(V54 chk_ocr_order_ids)
 * @param workers        파티션당 작업자 스레드 수. 기본 1. 요청 풀의 예비분을 쓰므로 늘리기 전에 연결 예산을 본다
 * @param expireInterval 작업자가 만료 문장을 도는 간격. 기본 60초
 */
@ConfigurationProperties(prefix = "exchange.order.cancel-handoff")
public record CancelHandoffProperties(@Name("wait") Duration waitFor, Duration poll, Integer chunk, Integer workers,
                                      Duration expireInterval) {

    public OrderCancelHandoff.Settings settings() {
        OrderCancelHandoff.Settings defaults = OrderCancelHandoff.Settings.defaults();
        return new OrderCancelHandoff.Settings(
                waitFor == null ? defaults.waitFor() : waitFor,
                poll == null ? defaults.poll() : poll,
                chunk == null ? defaults.chunk() : chunk,
                workers == null ? defaults.workers() : workers,
                expireInterval == null ? defaults.expireInterval() : expireInterval);
    }
}
