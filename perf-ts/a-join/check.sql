-- 실험 결과 확인. 정원(6명)을 넘긴 그룹이 몇 개인지가 이 실험의 핵심 지표다.
SELECT COUNT(*)                         AS total_groups,
       SUM(cnt > max_members)           AS over_capacity_groups,
       MAX(cnt)                         AS max_members_seen,
       SUM(cnt) - COUNT(*) * 5          AS joined_total
  FROM (SELECT g.id, g.max_members, COUNT(m.user_id) AS cnt
          FROM tbl_challenge_group g JOIN tbl_group_member m ON m.group_id = g.id
         WHERE g.invite_code LIKE 'PFJ%' GROUP BY g.id, g.max_members) t;
