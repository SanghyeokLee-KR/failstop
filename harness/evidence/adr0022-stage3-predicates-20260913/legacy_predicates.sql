-- 개정 전 술어, C22 두 팔, 대조의 양성 축. 출력은 label|value 한 줄씩이다.
-- 술어 문장은 InvariantBandAxisIntegrationTest 의 LEGACY_C05·C15·C22·C23, ARM_TIME·ARM_MARKET 과 같다.
SELECT 'legacy_c05', COALESCE((max(seq) - min(seq) + 1) - count(*), 0) FROM command_journal;
SELECT 'legacy_c15', (SELECT count(*) FROM ledger_entries_default)
                   + (SELECT count(*) FROM command_journal_default)
                   + (SELECT count(*) FROM event_journal_default)
                   + (SELECT count(*) FROM trades_default)
                   + (SELECT count(*) FROM outbox_default);
SELECT 'legacy_c22', count(*) FROM (
    SELECT seq, fencing_token,
           lag(fencing_token) OVER (ORDER BY seq) AS prev
    FROM command_journal
) t
WHERE t.prev > t.fencing_token;
SELECT 'legacy_c23', (
    SELECT count(*) FROM journal_counters c
    WHERE c.name = 'command_seq'
      AND c.next_value <= (SELECT COALESCE(max(seq), 0) FROM command_journal)
) + (
    SELECT count(*) FROM market_journal_counters m
    WHERE m.name = 'event_seq'
      AND m.next_value <= (SELECT COALESCE(max(e.market_seq), 0)
                           FROM event_journal e
                           WHERE e.market_id = m.market_id
                             AND e.market_seq IS NOT NULL)
);
SELECT 'arm_time', count(*) FROM (
    SELECT fencing_token,
           lag(fencing_token) OVER (ORDER BY created_at, seq) AS prev
    FROM command_journal
) t
WHERE t.prev > t.fencing_token;
SELECT 'arm_market', count(*) FROM (
    SELECT fencing_token,
           lag(fencing_token) OVER (PARTITION BY market_id ORDER BY seq) AS prev
    FROM command_journal
) t
WHERE t.prev > t.fencing_token;
-- 0 이 아니면 created_at 순서가 seq 순서와 달라 시간 축 팔 대 구 C22 대조가 NOT RUN 이다.
SELECT 'created_at_regressions', count(*) FROM (
    SELECT created_at, lag(created_at) OVER (ORDER BY seq) AS prev
    FROM command_journal
) t
WHERE t.prev > t.created_at;
SELECT 'journal_rows', count(*) FROM command_journal;
SELECT 'journal_max_seq', COALESCE(max(seq), 0) FROM command_journal;
SELECT 'journal_markets', count(DISTINCT market_id) FROM command_journal;
SELECT 'journal_epochs', count(DISTINCT fencing_token) FROM command_journal;
SELECT 'band_spec_rows', count(*) FROM journal_band_spec WHERE name = 'command_seq';
-- 0 이면 C05 대역 항과 C23 대역 팔은 대상 행이 없어 N/A 다.
SELECT 'band_rows', count(*) FROM command_journal
WHERE seq >= (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq');
SELECT 'event_rows_with_market_seq', count(*) FROM event_journal WHERE market_seq IS NOT NULL;
SELECT 'timeline_id', timeline_id FROM pg_control_checkpoint();
