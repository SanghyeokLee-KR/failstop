package kr.exchange.sim;

/**
 * 봇 반응속도 지터(설계 r17). 생성 시드에서 순수 해시로 팩터 [0.6, 1.6]을 산출해 봇마다
 * 재호가·도착 주기를 상이하게 만든다. rng 스트림·벽시계 미소비라 방향·크기 결정 열은 불변이고
 * 팩터는 sleep 상수 배율로만 쓰여 OPS 상한·정밀도 표면에 닿지 않는다.
 */
final class BotReaction {

    private BotReaction() {
    }

    static double reactionJitter(long seed) {
        // 고정 승법 해시 + xorshift 혼합. rng 미사용이라 같은 시드 = 같은 팩터.
        long h = seed * 0x2545F4914F6CDD1DL;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        int bucket = (int) Math.floorMod(h, 1001L);
        return 0.6 + bucket / 1000.0;
    }
}
