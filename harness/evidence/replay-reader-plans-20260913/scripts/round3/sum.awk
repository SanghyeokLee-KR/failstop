# usage: awk -f sum.awk summary.txt
# floor 경우 x 모드별로 12 마켓 실행 시간 중앙값 합, JIT 발화 마켓 수, 최대 최상위 비용, 마켓 집합 판독 중앙값.
BEGIN { FS = "|" }
{
  split($1, p, "__"); mode = p[2]; name = p[1]
  v = $2; sub(/exec_med=/, "", v)
  jr = $8; sub(/jit_runs=/, "", jr)
  tc = $4; sub(/topcost=/, "", tc)
  if (name == "ms") { msq[mode] = v; msj[mode] = jr; mst[mode] = tc; next }
  k = name; sub(/^pm_m[0-9]+_/, "", k)
  key = k "|" mode
  s[key] += v; cnt[key]++
  if (jr + 0 > 0) jm[key]++
  if (tc + 0 > mx[key] + 0) mx[key] = tc
}
END {
  for (x in s) {
    split(x, q, "|")
    printf "case=%s mode=%s markets=%d sum_med_ms=%.3f markets_with_jit=%d max_topcost=%s market_set_med_ms=%s market_set_jit_runs=%s market_set_topcost=%s total_ms=%.3f\n", q[1], q[2], cnt[x], s[x], jm[x] + 0, mx[x], msq[q[2]], msj[q[2]], mst[q[2]], s[x] + msq[q[2]]
  }
}
