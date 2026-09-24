SELECT count(*) FROM command_journal c
 WHERE c.command_type = 'NEW'
   AND NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = c.seq)
