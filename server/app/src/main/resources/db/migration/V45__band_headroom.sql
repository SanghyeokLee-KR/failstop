-- 채번 대역의 여유. ADR 0022 7단계(3-2)다.
--
-- 왜 두는가. 마켓 대역 채번은 지역 번호를 1 부터 band_span - 1 까지 쓴다. 다 쓰면 채번 함수가 예외를 내고 그 마켓의
-- 접수나 정산이 선다. 운영이 카운터를 되돌려 번호를 되찾을 수 없어(저널 PK 가 seq 재사용을, append-only 트리거가 행
-- 정리를 막는다) 소진은 비가역이다. 정적 바닥은 이 뷰를 읽는 C26 이, 소진 속도는 이 뷰를 읽는 게이지가 본다.
--
-- remaining 은 아직 배정할 수 있는 지역 번호 수다. next_market_journal_value 는 시작 번호와 개수의 합이 band_span 을
-- 넘으면 거부하므로 마지막 번호가 band_span - 1 이고, next_value 가 band_span 이면 remaining 이 0 이다.
-- 카운터 행이 없는 마켓은 쓴 번호가 없으므로 행이 없다.
--
-- 되돌리기. 뷰 DROP. 카운터와 저널을 건드리지 않는다.

CREATE VIEW v_journal_band_headroom AS
SELECT c.name,
       c.market_id,
       c.next_value,
       s.band_span - c.next_value AS remaining,
       (s.band_span - c.next_value)::numeric / s.band_span AS remaining_ratio
FROM market_journal_counters c
JOIN journal_band_spec s ON s.name = c.name;

COMMENT ON VIEW v_journal_band_headroom IS
    '마켓별 채번 대역 여유. remaining 은 남은 배정 가능 지역 번호 수(band_span - next_value)이고 remaining_ratio 는 remaining / band_span 이다(ADR 0022 3-2).';

-- 뷰는 소유자 권한으로 바탕 표를 읽으므로 뷰 SELECT 만 준다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT SELECT ON v_journal_band_headroom TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT ON v_journal_band_headroom TO harness_role;
    END IF;
END $$;
