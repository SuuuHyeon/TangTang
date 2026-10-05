-- 서버가 켜진 뒤 누적된 행 잠금 대기 통계. k6 실행 전후에 한 번씩 찍어서 차이를 본다.
--   Innodb_row_lock_waits     : 잠금을 기다린 횟수
--   Innodb_row_lock_time      : 기다린 시간 합계 (ms)
--   Innodb_row_lock_time_max  : 가장 오래 기다린 한 번 (ms)
SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%';
