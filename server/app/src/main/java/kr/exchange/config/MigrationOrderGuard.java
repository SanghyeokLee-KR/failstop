package kr.exchange.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;

import java.util.Arrays;
import java.util.List;

/**
 * 적용 순서 점검(DB 설계 §10.6). 관제센터 마이그레이션(V70 이후)을 마켓별 리더의 V48~V69 보다 먼저
 * 계속 쓰는 DB 에 넣지 않는다. out-of-order 를 켜지 않았으므로 먼저 넣으면 그 DB 에는 V48 이후를 영영 넣을
 * 수 없다. 빈 DB 는 통과하고, 두 작업을 병합해 V48~V69 가 실행 파일에 들어오면 점검이 저절로 풀린다.
 */
public final class MigrationOrderGuard {

    private static final MigrationVersion LEADER_FIRST = MigrationVersion.fromVersion("48");
    private static final MigrationVersion MSP_FIRST = MigrationVersion.fromVersion("70");

    private MigrationOrderGuard() {
    }

    /** migrate() 전에 부른다. 거부하면 적용하지 않고 멈춘다. */
    public static void check(Flyway flyway) {
        MigrationInfoService info = flyway.info();
        String refusal = refusal(versions(info.applied()), versions(info.pending()),
                Arrays.stream(info.all()).filter(m -> m.getState().isResolved() && m.getVersion() != null)
                        .map(MigrationInfo::getVersion).toList());
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
    }

    /**
     * 거부 사유. applied 는 DB 의 적용 이력, pending 은 적용할 파일, resolved 는 실행 파일 안의 파일이다.
     * 적용 이력이 없는 빈 DB 는 순서 문제가 생기지 않는다.
     */
    static String refusal(List<MigrationVersion> applied, List<MigrationVersion> pending,
                          List<MigrationVersion> resolved) {
        if (applied.isEmpty()) {
            return null;
        }
        boolean pendingMsp = pending.stream().anyMatch(v -> v.isAtLeast(MSP_FIRST.getVersion()));
        boolean leaderShipped = resolved.stream()
                .anyMatch(v -> v.isAtLeast(LEADER_FIRST.getVersion()) && !v.isAtLeast(MSP_FIRST.getVersion()));
        if (!pendingMsp || leaderShipped) {
            return null;
        }
        return "적용 이력이 있는 DB 에 V70 이상을 적용하려는데 실행 파일에 V48~V69 가 없다. 지금 적용하면 이 DB 에는"
                + " 마켓별 리더의 V48~V69 를 넣을 수 없게 된다. 두 작업을 병합한 판으로 적용하거나 새 DB 를 쓴다"
                + "(DB 설계 §10.6).";
    }

    private static List<MigrationVersion> versions(MigrationInfo[] migrations) {
        return Arrays.stream(migrations).map(MigrationInfo::getVersion).filter(v -> v != null).toList();
    }
}
