package kr.exchange.api.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 재전송 안전 표식을 단 처리기 목록(API 설계 §2-5-c, S4b 설계 4-3). 컨트롤러 계층은 이 표식이 있으면 쓰기의 DB 연결 장애도 503
 * 으로 내고 인그레스는 그 POST 를 다른 노드로 다시 보낸다. 표식은 멱등 키가 DB 제약으로 재전송을 한 번의 효과로 모으는 주문과
 * 이체에만 단다. 목록이 바뀌면 그 처리기의 멱등 설계와 계약을 먼저 확인하고 이 시험을 고친다.
 */
class RetrySafeWriteInventoryTest {

    @Test
    void onlyOrderAndTransferControllersAreRetrySafe() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> controllers = new TreeSet<>();
        Set<String> marked = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("kr.exchange")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            // 시험 안의 중첩 컨트롤러는 운영 처리기가 아니다.
            if (type.getEnclosingClass() != null) {
                continue;
            }
            controllers.add(type.getName());
            if (type.isAnnotationPresent(RetrySafeWrite.class)) {
                marked.add(type.getName());
            }
        }

        assertTrue(controllers.contains("kr.exchange.api.community.CommunityController"),
                "스캔이 운영 컨트롤러를 찾지 못해 목록 대조가 공허하다: " + controllers);
        assertEquals(Set.of("kr.exchange.api.order.OrderController", "kr.exchange.api.transfer.TransferController"),
                marked);
    }
}
