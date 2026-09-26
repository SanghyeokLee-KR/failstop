package kr.exchange.engine.lease;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 기동 때 한 번 읽은 리더 모드(leader_protocol, ADR 0034 결정 6·S3 설계 4-9).
 *
 * 모드는 DB 가 정하고 앱은 기동 때 한 번만 읽는다. 전환(set_leader_protocol)은 정지 창에서 앱을 멈춘 채 하고 앱을 다시
 * 띄우므로 실행 중에 다시 읽을 필요가 없다. 2 이상이면 게이트웨이가 in-flight 예약 누계를 끄고, append 는 DB 가 예약하지
 * 않은 주문 생성 명령을 커밋하지 않는다. 읽지 못하면(DB 불통, V48 전 DB) 1 로 본다. 1 로 뜬 노드는 P2 DB 에서도 안전하다.
 * in-flight 누계와 DB 예약이 함께 돌아 거부 쪽으로만 틀리고, DB 가 예약한 몫은 게이트웨이가 바로 푼다.
 */
public final class LeaderProtocolBoot {

    private static final Logger log = LoggerFactory.getLogger(LeaderProtocolBoot.class);

    /** 접수 시점 예약(P2)이 시작되는 모드. */
    public static final int RESERVATION_PROTOCOL = 2;

    private final int protocol;
    private final boolean fromDatabase;

    public LeaderProtocolBoot(int protocol) {
        this(protocol, false);
    }

    private LeaderProtocolBoot(int protocol, boolean fromDatabase) {
        this.protocol = protocol;
        this.fromDatabase = fromDatabase;
    }

    /** 기동 모드를 읽는다. 실패는 1 이다. 결과를 로그 한 줄로 남긴다. */
    public static LeaderProtocolBoot read(JdbcClient jdbc) {
        int protocol;
        boolean fromDatabase;
        try {
            Integer row = jdbc.sql("SELECT protocol FROM leader_protocol")
                    .query(Integer.class)
                    .optional()
                    .orElse(null);
            fromDatabase = row != null;
            protocol = row == null ? 1 : row;
        } catch (RuntimeException e) {
            log.warn("leader_protocol 을 읽지 못해 기동 모드를 1(P1)로 둔다: {}", e.toString());
            protocol = 1;
            fromDatabase = false;
        }
        LeaderProtocolBoot boot = new LeaderProtocolBoot(protocol, fromDatabase);
        log.info("기동 리더 모드: P{} (접수 시점 DB 예약 {})", protocol, boot.reservesAtIntake() ? "켬" : "끔");
        return boot;
    }

    /**
     * 모드를 읽지 않는 노드의 값. 조회 역할 노드는 접수와 append 를 하지 않고(ReadRoleFilter 가 쓰기 경로를 막고 엔진이 꺼져
     * 있다) 그 로그인은 leader_protocol 을 읽을 권한이 없다(deploy/provision-runtime-roles.sql). 읽으려 하면 기동마다 권한
     * 오류 경고가 남으므로 읽지 않고 배선에 필요한 1 만 둔다(S3 설계 4-15).
     */
    public static LeaderProtocolBoot notRead(String reason) {
        log.info("기동 리더 모드를 읽지 않고 1(P1)로 둔다: {}. 이 노드는 접수·append 를 하지 않는다", reason);
        return new LeaderProtocolBoot(1, false);
    }

    public int protocol() {
        return protocol;
    }

    /** 값이 leader_protocol 행에서 왔는가. 읽지 않았거나 읽지 못해 1 로 둔 경우는 거짓이다. */
    public boolean fromDatabase() {
        return fromDatabase;
    }

    /** 이 노드가 주문 생성 명령의 자금을 저널 append 트랜잭션에서 DB 로 예약하는 모드로 떴는가. */
    public boolean reservesAtIntake() {
        return protocol >= RESERVATION_PROTOCOL;
    }
}
