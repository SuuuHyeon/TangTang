-- 지금 tbl_indictment 에 걸린 잠금을 종류별로 센다 (다른 터미널에서 배치 UPDATE 를 붙잡고 있는 동안 실행)
SELECT lock_type, lock_mode, COUNT(*) AS cnt
  FROM performance_schema.data_locks
 WHERE object_name = 'tbl_indictment'
 GROUP BY lock_type, lock_mode;
