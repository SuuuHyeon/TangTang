-- b-lock/seed.sql 로 만든 데이터만 지운다.
-- 그룹을 지우면 참여자 · 결산 · 기소 · 투표가 FK CASCADE 로 함께 지워진다.
-- 100만 건을 한 문장으로 지우면 오래 걸리고 언두 로그가 커져서 500그룹씩 나눠 지운다.
DELIMITER //
DROP PROCEDURE IF EXISTS perf_cleanup //
CREATE PROCEDURE perf_cleanup()
BEGIN
  REPEAT
    DELETE FROM tbl_challenge_group WHERE invite_code LIKE 'PF%' LIMIT 500;
  UNTIL ROW_COUNT() = 0 END REPEAT;
END //
DELIMITER ;
CALL perf_cleanup();
DROP PROCEDURE perf_cleanup;
DELETE FROM tbl_user WHERE provider_user_id LIKE 'PERF-%';
DROP TABLE IF EXISTS perf_seq;
SELECT COUNT(*) AS remaining_perf_indictments FROM tbl_indictment WHERE message LIKE 'perf%';
