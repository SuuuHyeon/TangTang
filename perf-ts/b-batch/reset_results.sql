-- 개선 전후 비교 전에 결산과 기소를 비운다 (그룹·참여자·거래는 그대로)
DELETE i FROM tbl_indictment i JOIN tbl_challenge_group g ON g.id = i.group_id WHERE g.invite_code LIKE 'PFB%';
DELETE r FROM tbl_group_challenge_daily_result r JOIN tbl_challenge_group g ON g.id = r.group_id WHERE g.invite_code LIKE 'PFB%';
-- 증분 처리의 기준 시각도 지운다. 남아 있으면 다음 incremental 실행이 "바뀐 것 없음"으로 보고 아무것도 계산하지 않는다
DROP TABLE IF EXISTS perf_eval_watermark;
