-- =====================================================================
-- [B] 평가 배치 실험용 시드: 사용자 · 거래내역 · 진행 중 그룹
--
-- 가정 (점검 기록 2절 참고)
--   사용자 @users 명, 1인당 최근 @days 일 동안 하루 @per_day 건 결제
--   진행 중 그룹 @groups 개, 그룹당 6명 (사용자는 그룹 하나에만 속한다)
--   그룹의 80% 는 일일평가(DAILY), 20% 는 기간평가(PERIOD)
--   그룹의 절반은 총소비 챌린지(category_id NULL), 절반은 카테고리 챌린지
--
-- 기본값: 사용자 30,000명 × 30일 × 3건 = 거래 270만 건, 그룹 5,000개
-- 실행 전 db/seed_category.sql 이 들어가 있어야 한다 (소비 카테고리 21~64번)
-- 지우기: cleanup.sql
-- =====================================================================

SET @users   = 30000;
SET @days    = 30;
SET @per_day = 3;
SET @groups  = 5000;

-- 숫자 테이블 0 ~ 999,999
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

-- 1. 사용자 (PERFB- 로 시작 → cleanup 이 골라 지운다)
INSERT INTO tbl_user (social_provider, provider_user_id, nickname, difficulty_id, created_at)
SELECT 'GOOGLE', CONCAT('PERFB-', n), CONCAT('pb', n), 1, NOW() - INTERVAL 400 DAY
  FROM perf_seq WHERE n < @users;
SELECT MIN(id) INTO @u0 FROM tbl_user WHERE provider_user_id LIKE 'PERFB-%';

-- 2. 거래내역: 사용자 × 날짜 × 하루 건수
--    10건 중 8건은 카드 소비, 1건은 이체(TRANSFER), 1건은 입금(INCOME) → 배치 필터가 실제로 걸러 내게 한다
--    금액 3,000 ~ 29,999원, 소비 카테고리 21~64번 중 하나 (해시로 고정 → 다시 만들어도 같은 데이터)
INSERT INTO tbl_transaction
    (user_id, source_type, codef_tr_key, merchant_name, amount, direction, tr_date, tr_time,
     classification, category_id, category_source, is_refund, is_excluded_from_summary, created_at, updated_at)
SELECT @u0 + u.n,
       'CARD_CREDIT',
       CONCAT('PERFB-', u.n, '-', d.n, '-', k.n),
       CONCAT('가맹점', CRC32(CONCAT(u.n, '-', d.n, '-', k.n)) % 500),
       3000 + CRC32(CONCAT('amt', u.n, '-', d.n, '-', k.n)) % 27000,
       CASE WHEN (u.n + d.n + k.n) % 10 = 9 THEN 'IN' ELSE 'OUT' END,
       CURDATE() - INTERVAL d.n DAY,
       SEC_TO_TIME(CRC32(CONCAT('t', u.n, d.n, k.n)) % 86400),
       CASE (u.n + d.n + k.n) % 10 WHEN 8 THEN 'TRANSFER' WHEN 9 THEN 'INCOME' ELSE 'CONSUMPTION' END,
       21 + CRC32(CONCAT('cat', u.n, '-', d.n, '-', k.n)) % 44,
       'RULE_MCC', 0, 0,
       CURDATE() - INTERVAL d.n DAY,
       CURDATE() - INTERVAL d.n DAY          -- updated_at: 지난 거래가 "방금 바뀐 것"으로 보이지 않게 거래 날짜로 둔다 (증분 처리 B-7)
  FROM perf_seq u
  JOIN perf_seq d ON d.n < @days
  JOIN perf_seq k ON k.n < @per_day
 WHERE u.n < @users;

-- 3. 진행 중 그룹: 3일 전 시작 ~ 3일 뒤 종료 (7일)
INSERT INTO tbl_challenge_group
    (admin_id, group_name, category_id, limit_amount, eval_type, max_members,
     start_date, end_date, invite_code, status, created_at, updated_at)
SELECT @u0 + n * 6,
       CONCAT('perfb-', n),
       CASE n % 10 WHEN 1 THEN 21 WHEN 3 THEN 22 WHEN 5 THEN 23 WHEN 7 THEN 25 WHEN 9 THEN 29 ELSE NULL END,
       CASE WHEN n % 5 = 4 THEN 200000 ELSE 30000 END,
       CASE WHEN n % 5 = 4 THEN 'PERIOD' ELSE 'DAILY' END,
       6,
       CURDATE() - INTERVAL 3 DAY, CURDATE() + INTERVAL 3 DAY,
       CONCAT('PFB', n), 'ACTIVE', NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 3 DAY   -- updated_at: 시작일에 ACTIVE 로 바뀐 것처럼
  FROM perf_seq WHERE n < @groups;

-- 4. 참여자: 그룹마다 연속된 사용자 6명
INSERT INTO tbl_group_member (group_id, user_id, lives_count, joined_at)
SELECT g.id, @u0 + CAST(SUBSTRING(g.invite_code, 4) AS UNSIGNED) * 6 + s.n, 3, NOW() - INTERVAL 4 DAY
  FROM tbl_challenge_group g
  JOIN perf_seq s ON s.n < 6
 WHERE g.invite_code LIKE 'PFB%';

ANALYZE TABLE tbl_transaction, tbl_group_member, tbl_challenge_group;

SELECT (SELECT COUNT(*) FROM tbl_user WHERE provider_user_id LIKE 'PERFB-%') AS users,
       (SELECT COUNT(*) FROM tbl_transaction WHERE codef_tr_key LIKE 'PERFB-%') AS transactions,
       (SELECT COUNT(*) FROM tbl_challenge_group WHERE invite_code LIKE 'PFB%') AS active_groups;
