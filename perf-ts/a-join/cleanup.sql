-- a-join/seed.sql 로 만든 데이터만 지운다 (참여자는 FK CASCADE 로 함께 지워진다)
DELETE FROM tbl_challenge_group WHERE invite_code LIKE 'PFJ%';
DELETE FROM tbl_user WHERE provider_user_id LIKE 'PERFJ-%';
DROP TABLE IF EXISTS perf_seq;
SELECT COUNT(*) AS remaining FROM tbl_user WHERE provider_user_id LIKE 'PERFJ-%';
