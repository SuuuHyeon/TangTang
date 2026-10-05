-- =====================================================================
-- [A] 동시 참여 실험용 시드
--
-- 만드는 것
--   그룹 100개: 모집 중(RECRUITING), 내일 시작, 정원 6명, 이미 5명 참여 → 남은 자리 1개
--   참가 희망자 1,000명: 그룹마다 10명씩 같은 초대 코드로 동시에 들어올 사람
--
-- 정상이라면 그룹마다 10명 중 1명만 들어가고 9명은 GROUP_FULL 을 받아야 한다.
-- 지우기: cleanup.sql (실행할 때마다 지우고 다시 만든다)
-- =====================================================================

SET @groups = 100;
SET @joiners_per_group = 10;

DROP TABLE IF EXISTS perf_seq;
CREATE TABLE perf_seq (n INT PRIMARY KEY);
INSERT INTO perf_seq (n)
SELECT a.d + b.d*10 + c.d*100 + d.d*1000
  FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d;

-- 1. 기존 참여자 (그룹당 5명) + 참가 희망자 (그룹당 10명)
INSERT INTO tbl_user (social_provider, provider_user_id, nickname, difficulty_id)
SELECT 'GOOGLE', CONCAT('PERFJ-OLD-', n), CONCAT('old', n), 1 FROM perf_seq WHERE n < @groups * 5;
INSERT INTO tbl_user (social_provider, provider_user_id, nickname, difficulty_id)
SELECT 'GOOGLE', CONCAT('PERFJ-NEW-', n), CONCAT('new', n), 1 FROM perf_seq WHERE n < @groups * @joiners_per_group;

-- 2. 그룹: 방장은 기존 참여자 중 첫 번째
INSERT INTO tbl_challenge_group
    (admin_id, group_name, limit_amount, eval_type, max_members, start_date, end_date, invite_code, status)
SELECT u.id, CONCAT('perf-join-', s.n), 10000, 'DAILY', 6,
       CURDATE() + INTERVAL 1 DAY, CURDATE() + INTERVAL 7 DAY,
       CONCAT('PFJ', s.n), 'RECRUITING'
  FROM perf_seq s
  JOIN tbl_user u ON u.provider_user_id = CONCAT('PERFJ-OLD-', s.n * 5)
 WHERE s.n < @groups;

-- 3. 그룹마다 기존 참여자 5명
INSERT INTO tbl_group_member (group_id, user_id, lives_count)
SELECT g.id, u.id, 3
  FROM tbl_challenge_group g
  JOIN perf_seq s ON s.n < 5
  JOIN tbl_user u ON u.provider_user_id = CONCAT('PERFJ-OLD-', CAST(SUBSTRING(g.invite_code, 4) AS UNSIGNED) * 5 + s.n)
 WHERE g.invite_code LIKE 'PFJ%';

-- 확인: 그룹 100개, 참여자 수는 전부 5
SELECT COUNT(*) AS group_count, MIN(cnt) AS min_members, MAX(cnt) AS max_members
  FROM (SELECT g.id, COUNT(m.user_id) AS cnt
          FROM tbl_challenge_group g JOIN tbl_group_member m ON m.group_id = g.id
         WHERE g.invite_code LIKE 'PFJ%' GROUP BY g.id) t;
