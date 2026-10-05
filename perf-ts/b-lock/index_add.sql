-- 해결: 상태로 먼저 좁히고, 그 안에서 기소 시각 범위를 찾는 복합 인덱스
CREATE INDEX idx_ind_status_created ON tbl_indictment (status, created_at);
