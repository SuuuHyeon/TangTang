-- 신규 설치 시 실행: 예. schema.sql → seed.sql 뒤 migration 순서에 따라 실행한다.
--
-- [perf-ts] 평가 배치 증분 처리용 인덱스 (프로젝트 종료 후 개인 포크에서 추가).
-- "지난 실행 이후 updated_at 이 바뀐 거래"를 찾는다. 인덱스가 없으면 그 조회가 거래 전체를 읽어서
-- 증분 처리가 전체 재계산보다 느려진다.
-- 쓰기마다 인덱스 갱신이 하나 늘지만 updated_at 은 행마다 한 값이라 비용이 작다.
-- ⚠ perf-ts/b-batch/add_index.sql 로 같은 이름의 인덱스를 이미 넣었다면 이 파일은 건너뛴다 (중복 이름 오류).
--
-- 되돌리는 방법: DROP INDEX idx_tx_updated_at ON tbl_transaction;
CREATE INDEX idx_tx_updated_at ON tbl_transaction (updated_at);
