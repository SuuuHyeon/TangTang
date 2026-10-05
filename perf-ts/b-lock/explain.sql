-- 두 배치 쿼리의 실행 계획.
-- 1) 변론 마감 배치 (5분마다). 단일 테이블 UPDATE 라 EXPLAIN ANALYZE 대신 EXPLAIN 으로 본다
EXPLAIN
UPDATE tbl_indictment SET status = 'VOTING'
 WHERE status = 'DEFENSE_WAIT' AND created_at <= DATE_SUB(NOW(), INTERVAL 6 HOUR)\G

-- 2) 개표 배치 대상 조회 (1분마다). IndictmentMapper.findVotingToTally 에서 WHERE 와 표 수만 남긴 것
EXPLAIN ANALYZE
SELECT i.id,
       (SELECT COUNT(*) FROM tbl_vote v WHERE v.indictment_id = i.id AND v.verdict = 'GUILTY')   AS guilty_count,
       (SELECT COUNT(*) FROM tbl_vote v WHERE v.indictment_id = i.id AND v.verdict = 'INNOCENT') AS innocent_count
  FROM tbl_indictment i
  JOIN tbl_challenge_group g ON g.id = i.group_id
  JOIN tbl_group_challenge_daily_result r ON r.id = i.result_id
  JOIN tbl_user u ON u.id = i.user_id
 WHERE i.status = 'VOTING'
   AND ( i.created_at <= DATE_SUB(DATE_SUB(NOW(), INTERVAL 6 HOUR), INTERVAL 24 HOUR)
         OR (SELECT COUNT(*) FROM tbl_vote v WHERE v.indictment_id = i.id)
            >= (SELECT COUNT(*) FROM tbl_group_member gm WHERE gm.group_id = i.group_id) - 1 )
 ORDER BY i.created_at, i.id\G
