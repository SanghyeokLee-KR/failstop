package kr.exchange.api.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 다시 보내도 한 번의 효과로 모이는 쓰기만 받는 처리기 표식(API 설계 §2-5-c). 인그레스는 503 을 받은 POST 까지 다른 노드로
 * 다시 보내므로, 컨트롤러 계층의 DB 연결 장애를 503 으로 내는 쓰기는 이 표식을 단 처리기뿐이다. 표식이 없으면 500 이다.
 * 달 수 있는 처리기는 멱등 키가 DB 제약으로 재전송을 한 번의 효과로 모으는 곳뿐이고 목록은 RetrySafeWriteInventoryTest 가
 * 고정한다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RetrySafeWrite {
}
