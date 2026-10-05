-- b-batch/seed.sql 로 만든 데이터만 지운다.
--   그룹을 지우면 참여자 · 결산 · 기소가, 사용자를 지우면 그 사람의 거래내역이 FK CASCADE 로 함께 지워진다.
--   한 번에 지우면 언두 로그가 커져 오래 걸리므로 사용자 200명씩 나눠 지운다.
-- 실험 DB 를 통째로 처음 상태로 돌리는 것이 목적이면 이 파일보다 perf down -v 가 훨씬 빠르다.
DELIMITER //
DROP PROCEDURE IF EXISTS perfb_cleanup //
CREATE PROCEDURE perfb_cleanup()
BEGIN
  REPEAT
    DELETE FROM tbl_challenge_group WHERE invite_code LIKE 'PFB%' LIMIT 500;
  UNTIL ROW_COUNT() = 0 END REPEAT;
  DELETE FROM tbl_transaction WHERE codef_tr_key LIKE 'PROBE-%';
  REPEAT
    DELETE FROM tbl_user WHERE provider_user_id LIKE 'PERFB-%' LIMIT 200;
  UNTIL ROW_COUNT() = 0 END REPEAT;
END //
DELIMITER ;
CALL perfb_cleanup();
DROP PROCEDURE perfb_cleanup;
DROP TABLE IF EXISTS perf_seq;
DROP TABLE IF EXISTS perf_eval_watermark;
SELECT COUNT(*) AS remaining_users FROM tbl_user WHERE provider_user_id LIKE 'PERFB-%';
