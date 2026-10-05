-- [B-8] 앱의 증분 처리 뒤에 결산이 맞는지 확인한다.
-- 지금 거래로 다시 계산한 값과 결산 테이블 값이 다르거나, 있어야 할 행이 없는 (그룹, 사람, 날짜) 수를 센다.
-- 0 이면 증분이 바뀐 것을 빠짐없이 반영한 것이다. (거래를 지웠거나 자정을 넘긴 경우는 다음 전체 재계산 전까지 0이 아닐 수 있다)
-- 날짜는 DB 기준 CURDATE() 를 쓴다. 앱 기준일(Asia/Seoul)과 DB 날짜가 다르면(컨테이너가 UTC면 09시 전) 숫자가 틀어질 수 있다.
SELECT COUNT(*) AS bad_rows, COALESCE(SUM(r.id IS NULL), 0) AS missing_rows
  FROM (SELECT m.group_id, m.user_id, d.dt,
               COALESCE(SUM(CASE WHEN t.is_refund = 1 THEN -COALESCE(t.refunded_amount, t.amount) ELSE t.amount END), 0) AS expected
          FROM tbl_group_member m
          JOIN tbl_challenge_group g ON g.id = m.group_id
          JOIN (SELECT CURDATE() - INTERVAL 1 DAY AS dt UNION ALL SELECT CURDATE()) d
            ON d.dt BETWEEN g.start_date AND g.end_date
          LEFT JOIN tbl_transaction t
                 ON t.user_id = m.user_id AND t.tr_date = d.dt
                AND t.classification = 'CONSUMPTION' AND t.direction = 'OUT'
                AND t.is_excluded_from_summary = 0
                AND (g.category_id IS NULL OR t.category_id = g.category_id)
         WHERE g.invite_code LIKE 'PFB%' AND g.status = 'ACTIVE'
         GROUP BY m.group_id, m.user_id, d.dt) e
  LEFT JOIN tbl_group_challenge_daily_result r
         ON r.group_id = e.group_id AND r.user_id = e.user_id AND r.challenge_date = e.dt
 WHERE r.id IS NULL OR r.daily_amount <> e.expected;
-- 기준 시각 (앱이 마지막으로 돈 시각, 마지막 전체 재계산 시각)
SELECT * FROM tbl_batch_watermark;
