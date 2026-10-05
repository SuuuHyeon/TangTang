-- 신규 설치 시 실행: 예. schema.sql → seed.sql 뒤 migration 순서에 따라 실행한다.
--
-- [perf-ts] 그룹 챌린지 평가 배치의 증분 처리용 기준 시각 (프로젝트 종료 후 개인 포크에서 추가).
-- 평가 배치는 5분마다 진행 중인 모든 그룹을 다시 합산했는데, 그사이 거래가 바뀌는 사람은 일부뿐이다.
-- 그래서 평소에는 "지난 실행 이후 바뀐 사람의 그룹"만 다시 계산하고, 그 기준 시각을 여기에 남긴다.
--
--   last_started_at : 마지막으로 끝까지 돈 실행의 시작 시각 (DB 시계 기준). 다음 실행은 이 시각 - 겹침 이후를 본다
--   last_full_at    : 마지막 전체 재계산의 시작 시각. 1시간이 지나면 다시 전체를 돈다 (삭제처럼 증분이 못 잡는 변경을 바로잡는 안전망)
--   last_full_date  : 마지막 전체 재계산의 배치 기준일. 날짜가 바뀌면(자정) 전체를 돈다
--
-- 되돌리는 방법: DROP TABLE tbl_batch_watermark;
CREATE TABLE IF NOT EXISTS tbl_batch_watermark (
  batch_name      VARCHAR(50) NOT NULL,
  last_started_at DATETIME    NOT NULL,
  last_full_at    DATETIME    NOT NULL,
  last_full_date  DATE        NOT NULL,
  updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (batch_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='배치 증분 처리 기준 시각';
