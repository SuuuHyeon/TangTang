-- k6 가 투표할 (투표자, 기소) 짝을 뽑는다. 피고 본인은 자기 재판에 투표할 수 없어서 뺀다.
-- 실행: ./perf-ts/mysql.sh -N -B < perf-ts/b-lock/vote_pairs.sql > perf-ts/b-lock/vote_pairs.tsv
SELECT m.user_id, i.id
  FROM tbl_indictment i
  JOIN tbl_group_member m ON m.group_id = i.group_id AND m.user_id <> i.user_id
 WHERE i.message = 'perf-voting' AND i.status = 'VOTING'
 ORDER BY RAND();
