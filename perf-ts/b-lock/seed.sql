-- =====================================================================
-- [B] 변론 마감 배치 실험용 시드
--
-- 만드는 것
--   1) 판결이 끝난(GUILTY/INNOCENT) 기소 대량      → 6주 넘게 서비스가 돌았을 때 쌓이는 과거 기록
--   2) 진행 중 그룹 @active_groups 개와 진행 중인 기소 → 지금 사용자가 변론·투표하는 재판
--        그룹마다 참여자 6명 중
--          1번째: DEFENSE_WAIT, 7시간 전 기소 (변론 마감 6시간이 지남 → 배치가 VOTING 으로 옮길 대상)
--          2번째: DEFENSE_WAIT, 1시간 전 기소 (아직 변론 가능 → 혐의 인정 대상)
--          3~6번째: VOTING, 8시간 전 기소 (투표 중 → k6 가 투표할 대상)
--
-- 규모는 맨 위 숫자 두 개만 바꾼다
--   @closed_groups : 그룹 1개 = 6명 × 7일 = 판결 끝난 기소 42건
--                    2400 → 약 10만 건 / 24000 → 약 100만 건
--   @active_groups : 1000 이면 투표 중 기소 4,000건, 투표할 수 있는 (사람, 기소) 짝 20,000개
--
-- 지우기: cleanup.sql
-- =====================================================================

SET @closed_groups = 2400;
SET @active_groups = 1000;
SET @users = 600;

-- 숫자 테이블 0 ~ 999,999 (반복문 없이 대량 INSERT 하려고 쓴다)
DROP TABLE IF EXISTS perf_seq;
CREATE TABLE perf_seq (n INT PRIMARY KEY);
INSERT INTO perf_seq (n)
SELECT a.d + b.d*10 + c.d*100 + d.d*1000 + e.d*10000 + f.d*100000
  FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) e,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) f;

-- 1. 사용자 (PERF- 로 시작 → cleanup 이 골라 지운다)
INSERT INTO tbl_user (social_provider, provider_user_id, nickname, difficulty_id, created_at)
SELECT 'GOOGLE', CONCAT('PERF-', n), CONCAT('perf', n), 1, NOW() - INTERVAL 400 DAY
  FROM perf_seq WHERE n < @users;
SELECT MIN(id) INTO @u0 FROM tbl_user WHERE provider_user_id LIKE 'PERF-%';

-- 2. 그룹 (초대 코드 PFC = 종료, PFA = 진행 중)
INSERT INTO tbl_challenge_group
    (admin_id, group_name, limit_amount, eval_type, max_members, start_date, end_date, invite_code, status, created_at)
SELECT @u0 + (n * 6) % @users, CONCAT('perf-closed-', n), 10000, 'DAILY', 6,
       DATE('2026-06-01') - INTERVAL (n % 300) DAY,
       DATE('2026-06-01') - INTERVAL (n % 300) DAY + INTERVAL 6 DAY,
       CONCAT('PFC', n), 'CLOSED', NOW() - INTERVAL 120 DAY
  FROM perf_seq WHERE n < @closed_groups;

INSERT INTO tbl_challenge_group
    (admin_id, group_name, limit_amount, eval_type, max_members, start_date, end_date, invite_code, status, created_at)
SELECT @u0 + (n * 6) % @users, CONCAT('perf-active-', n), 10000, 'DAILY', 6,
       CURDATE() - INTERVAL 3 DAY, CURDATE() + INTERVAL 3 DAY,
       CONCAT('PFA', n), 'ACTIVE', NOW() - INTERVAL 5 DAY
  FROM perf_seq WHERE n < @active_groups;

-- 3. 참여자: 그룹마다 6명
INSERT INTO tbl_group_member (group_id, user_id, lives_count)
SELECT g.id, @u0 + ((g.id * 6) + s.n) % @users, 3
  FROM tbl_challenge_group g
  JOIN perf_seq s ON s.n < 6
 WHERE g.invite_code LIKE 'PF%';

-- 4. 일별 결산 (기소 1건은 결산 1행을 가리킨다)
INSERT INTO tbl_group_challenge_daily_result (group_id, user_id, challenge_date, daily_amount, target_count)
SELECT m.group_id, m.user_id, g.start_date + INTERVAL d.n DAY, 20000, 1
  FROM tbl_group_member m
  JOIN tbl_challenge_group g ON g.id = m.group_id AND g.invite_code LIKE 'PFC%'
  JOIN perf_seq d ON d.n < 7;

INSERT INTO tbl_group_challenge_daily_result (group_id, user_id, challenge_date, daily_amount, target_count)
SELECT m.group_id, m.user_id, CURDATE() - INTERVAL 1 DAY, 20000, 1
  FROM tbl_group_member m
  JOIN tbl_challenge_group g ON g.id = m.group_id AND g.invite_code LIKE 'PFA%';

-- 5-1. 판결 끝난 기소
INSERT INTO tbl_indictment (result_id, group_id, user_id, status, result, verdict_method, message, created_at)
SELECT r.id, r.group_id, r.user_id,
       IF(r.id % 2 = 0, 'GUILTY', 'INNOCENT'), IF(r.id % 2 = 0, 1, 0),
       'VOTE', 'perf-closed', r.challenge_date + INTERVAL 1 DAY
  FROM tbl_group_challenge_daily_result r
  JOIN tbl_challenge_group g ON g.id = r.group_id AND g.invite_code LIKE 'PFC%';

-- 5-2. 진행 중 기소 (그룹 안 순번 rn 으로 세 갈래)
INSERT INTO tbl_indictment (result_id, group_id, user_id, status, message, created_at)
SELECT id, group_id, user_id,
       CASE WHEN rn <= 1 THEN 'DEFENSE_WAIT' ELSE 'VOTING' END,
       CASE WHEN rn = 0 THEN 'perf-expired' WHEN rn = 1 THEN 'perf-fresh' ELSE 'perf-voting' END,
       CASE WHEN rn = 0 THEN NOW() - INTERVAL 7 HOUR
            WHEN rn = 1 THEN NOW() - INTERVAL 1 HOUR
            ELSE NOW() - INTERVAL 8 HOUR END
  FROM (SELECT r.*, ROW_NUMBER() OVER (PARTITION BY r.group_id ORDER BY r.user_id) - 1 AS rn
          FROM tbl_group_challenge_daily_result r
          JOIN tbl_challenge_group g ON g.id = r.group_id AND g.invite_code LIKE 'PFA%') x;

ANALYZE TABLE tbl_indictment;

-- 확인: 상태별 건수
SELECT status, message, COUNT(*) AS cnt
  FROM tbl_indictment
 WHERE message LIKE 'perf%'
 GROUP BY status, message;
