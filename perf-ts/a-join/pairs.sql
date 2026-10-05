-- k6 가 쓸 (참가 희망자 user_id, 초대 코드) 짝. 그룹마다 10명이 같은 코드를 받는다.
-- 실행: ./perf-ts/mysql.sh -N -B < perf-ts/a-join/pairs.sql > perf-ts/a-join/pairs.tsv
SELECT u.id, CONCAT('PFJ', FLOOR(CAST(SUBSTRING(u.provider_user_id, 11) AS UNSIGNED) / 10))
  FROM tbl_user u
 WHERE u.provider_user_id LIKE 'PERFJ-NEW-%'
 ORDER BY u.id;
