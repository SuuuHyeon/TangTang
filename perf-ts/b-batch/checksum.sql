-- 개선 전후 결과가 같은지 확인: 일별 결산 행과 기소 대상(그룹, 사용자, 날짜)을 해시로 비교한다
SELECT COUNT(*) AS result_rows, SUM(r.daily_amount) AS amount_sum, SUM(r.target_count) AS cnt_sum,
       SUM(CRC32(CONCAT_WS(',', r.group_id, r.user_id, r.challenge_date, r.daily_amount, r.target_count))) AS result_hash
  FROM tbl_group_challenge_daily_result r JOIN tbl_challenge_group g ON g.id = r.group_id WHERE g.invite_code LIKE 'PFB%';
SELECT COUNT(*) AS indictments,
       SUM(CRC32(CONCAT_WS(',', r.group_id, r.user_id, r.challenge_date))) AS indictment_hash
  FROM tbl_indictment i JOIN tbl_group_challenge_daily_result r ON r.id = i.result_id
  JOIN tbl_challenge_group g ON g.id = i.group_id WHERE g.invite_code LIKE 'PFB%';
