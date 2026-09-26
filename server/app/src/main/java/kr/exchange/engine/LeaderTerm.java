package kr.exchange.engine;

/**
 * 리스를 얻은 순간 만든 기간(DB 설계 §5.4-c 6). 자원·holder·epoch·세대로 이뤄지고, 승격 재생 sink·설치 루프·스냅샷·치명
 * 처리기·접수 게이트가 같은 기간 객체를 쥔다.
 *
 * 엔진 기원 쓰기는 이 기간의 epoch 로 DB 판정을 받고 쓰기 전에 리스 서비스가 지금 쥔 기간과 같은지 본다. 같은 노드가
 * 재구성 뒤 새 epoch 를 얻어도, 강등 전 스레드가 쥔 옛 기간은 앱에서 거부되고 앱 검사를 빠뜨려도 DB 가 옛 epoch 를
 * 거부한다. 기간의 동일성은 객체 동일성이다. 리스 서비스가 획득마다 기간을 하나만 만든다.
 */
public final class LeaderTerm {

    private final LeaderLeaseService lease;
    private final String resource;
    private final String holder;
    private final long epoch;
    private final long generation;

    LeaderTerm(LeaderLeaseService lease, String resource, String holder, long epoch, long generation) {
        this.lease = lease;
        this.resource = resource;
        this.holder = holder;
        this.epoch = epoch;
        this.generation = generation;
    }

    public String resource() {
        return resource;
    }

    public String holder() {
        return holder;
    }

    public long epoch() {
        return epoch;
    }

    /** 노드 안에서 획득마다 1씩 오르는 번호. */
    public long generation() {
        return generation;
    }

    public LeaderIdentity identity() {
        return new LeaderIdentity(holder, epoch);
    }

    /** 리스 서비스가 지금 이 기간을 쥐고 있는가. */
    public boolean isCurrent() {
        return lease.isCurrent(this);
    }

    /** 쓰기 진입점. 이 기간이 끝났으면 DB 왕복 없이 거부한다. */
    public LeaderIdentity requireCurrent() {
        if (!isCurrent()) {
            throw new NotLeaderException("기간 종료: resource=" + resource + ", holder=" + holder
                    + ", epoch=" + epoch + ", generation=" + generation);
        }
        return identity();
    }

    /** fencing 거부 뒤 이 기간의 epoch 로 권위를 다시 확인한다. 기간이 이미 끝났으면 아무것도 하지 않는다. */
    public boolean demoteUnlessStillLeader(String reason) {
        return lease.demoteUnlessStillLeader(this, reason);
    }

    /** 그 파티션의 좀비 판정 진행 축(DB 설계 §5.4-c 8). 파티션 층 판정을 걸지 않은 배선은 아무도 읽지 않는다. */
    public LeaderWriteProgress progress() {
        return lease.partitionProgress();
    }

    LeaderLeaseService lease() {
        return lease;
    }

    @Override
    public String toString() {
        return "LeaderTerm[" + resource + ", holder=" + holder + ", epoch=" + epoch
                + ", generation=" + generation + "]";
    }
}
