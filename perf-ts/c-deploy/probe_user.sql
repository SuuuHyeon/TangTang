-- [C] probe.js 가 "내 그룹 목록"을 조회할 때 쓸 사용자 하나를 만든다. 출력된 id 를 USER_ID 로 넘긴다.
INSERT IGNORE INTO tbl_user (social_provider, provider_user_id, nickname, difficulty_id)
VALUES ('GOOGLE', 'PERF-PROBE', 'probe', 1);
SELECT id AS probe_user_id FROM tbl_user WHERE provider_user_id = 'PERF-PROBE';
