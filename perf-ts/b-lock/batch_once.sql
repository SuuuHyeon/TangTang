-- 변론 마감 배치(IndictmentMapper.moveExpiredDefensesToVoting)와 같은 UPDATE 를
-- 한 번 실행해 걸린 시간을 재고, 데이터는 되돌린다(ROLLBACK).
-- 배치는 이 문장 하나로 된 트랜잭션이라, 이 시간이 곧 "배치가 잠금을 쥐고 있는 시간"이다.
START TRANSACTION;
SET @t = NOW(6);
UPDATE tbl_indictment
   SET status = 'VOTING'
 WHERE status = 'DEFENSE_WAIT'
   AND created_at <= DATE_SUB(NOW(), INTERVAL 6 HOUR);
SELECT ROW_COUNT() AS moved_rows,
       TIMESTAMPDIFF(MICROSECOND, @t, NOW(6)) / 1000 AS elapsed_ms;
ROLLBACK;
