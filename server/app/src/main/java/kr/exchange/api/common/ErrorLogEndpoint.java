package kr.exchange.api.common;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.Map;

/**
 * 이 노드의 WARN·ERROR 로그 묶음(API 설계 §6-F '오류 로그'). 관리 JVM 의 관제센터가 1분마다 읽는다. actuator 에 두는
 * 이유는 AdmissionEndpoint 와 같다. 운영 프로파일에서 actuator 는 내부 포트(8081)에 있고 공개 경로는 Nginx 가 막는다.
 * 문장은 내보낼 때 가리므로 원문 로그를 그대로 내지 않는다.
 */
@Endpoint(id = "errorlog")
public class ErrorLogEndpoint {

    private final ErrorLogBook book;

    public ErrorLogEndpoint(ErrorLogBook book) {
        this.book = book;
    }

    @ReadOperation
    public Map<String, Object> groups() {
        return book.view();
    }
}
