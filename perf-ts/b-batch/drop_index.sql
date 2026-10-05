-- [B-7, 선택] 인덱스가 없을 때와 비교하려면 지운다. 비교가 끝나면 add_index.sql 을 다시 넣는다
DROP INDEX idx_tx_updated_at ON tbl_transaction;
