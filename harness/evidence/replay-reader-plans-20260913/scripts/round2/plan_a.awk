# usage: awk -v f=<name> -f plan_a.awk <file>
# 첫 EXPLAIN 의 계획 노드 요약과 3회 실행 시간 중앙값, 준비문 계획 수를 한 줄로 낸다.
function short(n) {
  if (n == "event_journal_p0_market_id_source_cmd_seq_event_ordinal_idx") return "CAND"
  if (n == "event_journal_p0_source_cmd_seq_idx") return "EVT_SRC"
  if (n == "event_journal_p0_pkey") return "EVT_PK"
  if (n == "event_journal_p0_market_id_market_seq_idx") return "EVT_MSEQ"
  if (n == "command_journal_p0_market_id_seq_idx") return "CMD_MKT"
  if (n == "command_journal_p0_pkey") return "CMD_PK"
  return n
}
BEGIN { done = 0; n = 0; nodes = ""; removed = ""; buf = ""; counts = "" }
/Execution Time:/ {
  v = $0; sub(/.*Execution Time: /, "", v); sub(/ ms.*/, "", v)
  n++; t[n] = v + 0; done = 1; next
}
/COUNTS/ { c = $0; gsub(/ +/, "", c); counts = c; next }
done == 0 {
  line = $0
  if (buf == "" && line ~ /Buffers:/) { buf = line; sub(/^ *Buffers: */, "", buf) }
  if (line ~ /Scan using [a-z0-9_]+ on (event|command)_journal_p0 /) {
    x = line; sub(/.*Scan using /, "", x); sub(/ .*/, "", x); nodes = nodes " " short(x)
  } else if (line ~ /Bitmap Index Scan on (event|command)_journal_p0_/) {
    x = line; sub(/.*Bitmap Index Scan on /, "", x); sub(/ .*/, "", x); nodes = nodes " bitmap:" short(x)
  } else if (line ~ /Seq Scan on (event|command)_journal_p0 /) {
    nodes = nodes " SEQSCAN"
  }
  if (line ~ /Incremental Sort  \(/) nodes = nodes " IncSort"
  else if (line ~ /Sort  \(/) nodes = nodes " Sort"
  if (line ~ /Rows Removed by Filter:/) { r = line; sub(/.*Filter: /, "", r); removed = removed " " r }
}
END {
  for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) if (t[j] < t[i]) { tmp = t[i]; t[i] = t[j]; t[j] = tmp }
  med = "NA"; if (n >= 3) med = t[2]; else if (n > 0) med = t[1]
  runs = ""; for (i = 1; i <= n; i++) runs = runs " " t[i]
  printf "%s|med=%s|runs=%s|plan=%s|removed=%s|buf=%s|%s\n", f, med, runs, nodes, removed, buf, counts
}
