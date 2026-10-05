-- [B-7] 증분 처리용 인덱스
-- "지난 실행 이후 바뀐 거래"를 updated_at 으로 찾는다. 인덱스가 없으면 그 조회가 거래 270만 건을 전부 읽어
-- 증분 처리가 오히려 느려진다. 거래 테이블은 동기화(팀원 코드) 영역이라, 평가 배치용으로 추가한다는 점을 밝힌다.
-- 큰 테이블이라 1~2분 걸릴 수 있다.
CREATE INDEX idx_tx_updated_at ON tbl_transaction (updated_at);
SHOW INDEX FROM tbl_transaction WHERE Key_name = 'idx_tx_updated_at';
