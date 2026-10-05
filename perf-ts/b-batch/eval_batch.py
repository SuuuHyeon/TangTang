"""
평가 배치(GroupChallengeEvaluationBatchService → GroupChallengeEvaluationService) 를
DB 수준에서 그대로 흉내 내는 스크립트. SQL 은 매퍼 XML 에서 include 를 펼쳐 그대로 옮겼다.

  python3 eval_batch.py --mode current  --limit 1000      # 지금 코드: 그룹마다 트랜잭션 1개, 문장 5개
  python3 eval_batch.py --mode chunk    --limit 1000 --chunk 200   # 개선 1: 그룹 묶음 단위 집합 처리
  python3 eval_batch.py --mode incremental --chunk 100 --isolation READ-COMMITTED   # 개선 3: 바뀐 사람의 그룹만

출력: 배치 1회 실행 시간(total_s), 문장 종류별 누적 시간(per_stmt_ms)과 횟수(calls), 만든 기소 수

incremental 모드 (개선 3)
  - 지난 실행 시작 시각을 perf_eval_watermark 테이블에 남긴다 (처음 실행이면 전체를 한 번 돌고 시각만 남긴다)
  - "지난 시작 시각 - 겹침(--overlap 초)" 이후 updated_at 이 바뀐 거래의 사용자를 찾아, 그 사람이 속한 그룹만 다시 계산한다
  - --member-check 이면 그 사이 참여자가 늘었거나(joined_at) 상태가 바뀐(updated_at) 그룹도 다시 계산한다
  - 다시 계산하는 방법은 chunk 모드와 같다 (같은 SQL, 같은 묶음, 같은 격리수준)
  - 거래 삭제와 자정에 새로 필요한 행은 잡지 못한다 → 가끔 도는 전체 재계산(chunk 모드)이 바로잡는다
"""
import argparse, time, datetime, collections, json, os
import pymysql

UPSERT = """
INSERT INTO tbl_group_challenge_daily_result
    (group_id, user_id, challenge_date, daily_amount, target_count)
SELECT m.group_id, m.user_id, %s,
       COALESCE(SUM(CASE WHEN t.is_refund = 1 THEN -COALESCE(t.refunded_amount, t.amount) ELSE t.amount END), 0),
       COALESCE(SUM(CASE WHEN t.id IS NOT NULL AND t.is_refund = 0 THEN 1 ELSE 0 END), 0)
  FROM tbl_group_member m
  JOIN tbl_challenge_group g ON g.id = m.group_id
  LEFT JOIN tbl_transaction t
         ON t.user_id = m.user_id
        AND t.tr_date = %s
        AND t.classification = 'CONSUMPTION'
        AND t.direction = 'OUT'
        AND t.is_excluded_from_summary = 0
        AND (g.category_id IS NULL OR t.category_id = g.category_id)
 WHERE {where}
 GROUP BY m.group_id, m.user_id
ON DUPLICATE KEY UPDATE
    daily_amount = VALUES(daily_amount),
    target_count = VALUES(target_count)
"""

OVERFLOW = """
SELECT r.id, r.group_id, r.user_id, r.challenge_date,
       r.daily_amount, r.verdict_deduction_amount, r.effective_amount, r.target_count
  FROM tbl_group_challenge_daily_result r
  JOIN tbl_challenge_group g ON g.id = r.group_id
 WHERE {where}
   AND g.eval_type = 'DAILY'
   AND r.verdict_deduction_amount > 0
   AND r.verdict_deduction_amount > r.daily_amount
 ORDER BY r.user_id, r.challenge_date
"""

OVER_LIMIT_DAILY = """
SELECT r.id AS result_id, r.user_id, r.challenge_date, r.effective_amount AS amount,
       COALESCE(NULLIF(TRIM(u.nickname), ''), u.social_name) AS nickname, r.group_id
  FROM tbl_group_challenge_daily_result r
  JOIN tbl_challenge_group g ON g.id = r.group_id
  JOIN tbl_user u ON u.id = r.user_id
 WHERE {where}
   AND r.challenge_date = %s
   AND r.effective_amount > g.limit_amount
   AND g.eval_type = 'DAILY'
   AND NOT EXISTS (SELECT 1 FROM tbl_indictment i WHERE i.result_id = r.id)
 ORDER BY r.user_id
"""

INSERT_INDICTMENT = """
INSERT INTO tbl_indictment (result_id, group_id, user_id, status, message)
VALUES (%s, %s, %s, 'DEFENSE_WAIT', %s)
"""

FIND_GROUPS = """
SELECT g.id, g.eval_type, g.start_date, g.end_date
  FROM tbl_challenge_group g
  LEFT JOIN tbl_category c ON c.id = g.category_id
 WHERE g.status = 'ACTIVE' AND g.start_date <= %s AND g.end_date >= %s
 ORDER BY g.id
"""


def connect(isolation):
    c = pymysql.connect(host=os.environ.get('DB_HOST', '127.0.0.1'),
                        port=int(os.environ.get('DB_PORT', 3307)),
                        user=os.environ.get('DB_USER', 'root'),
                        password=os.environ['DB_PASSWORD'],
                        database='tangtang', autocommit=False)
    with c.cursor() as cur:
        cur.execute(f"SET SESSION transaction_isolation = '{isolation}'")
    return c


class Timer:
    def __init__(self):
        self.t = collections.defaultdict(float)
        self.n = collections.Counter()

    def run(self, cur, name, sql, args=()):
        s = time.perf_counter()
        cur.execute(sql, args)
        rows = cur.fetchall() if cur.description else cur.rowcount
        self.t[name] += time.perf_counter() - s
        self.n[name] += 1
        return rows


def dates_for(g, today):
    out = []
    for d in (today - datetime.timedelta(days=1), today):
        if g['start_date'] <= d <= g['end_date']:
            out.append(d)
    return out


def db_today():
    """DB 기준 오늘. 시드가 CURDATE() 로 날짜를 넣으므로 같은 시계를 쓴다.
    맥(한국 시간)과 DB 컨테이너(UTC)는 0시~9시에 날짜가 하루 다르다"""
    c = connect('READ-COMMITTED')
    with c.cursor() as cur:
        cur.execute("SELECT CURDATE()")
        d = cur.fetchone()[0]
    c.close()
    return d


def evaluate_chunked(c, cur, tm, groups, today, chunk):
    """chunk 모드의 본체. groups 를 chunk 개씩 묶어 같은 SQL 을 IN 조건으로 보낸다. 만든 기소 수를 돌려준다"""
    indicted = 0
    for i in range(0, len(groups), chunk):
        part = groups[i:i + chunk]
        ids = [g['id'] for g in part]
        ph = ','.join(['%s'] * len(ids))
        for d in (today - datetime.timedelta(days=1), today):
            in_range = [g['id'] for g in part if g['start_date'] <= d <= g['end_date']]
            if not in_range:
                continue
            ph2 = ','.join(['%s'] * len(in_range))
            tm.run(cur, 'upsert', UPSERT.format(where=f'm.group_id IN ({ph2})'), (d, d, *in_range))
        tm.run(cur, 'overflow', OVERFLOW.format(where=f'r.group_id IN ({ph})'), tuple(ids))
        for d in (today - datetime.timedelta(days=1), today):
            for t in tm.run(cur, 'over_limit', OVER_LIMIT_DAILY.format(where=f'r.group_id IN ({ph})'), (*ids, d)):
                tm.run(cur, 'insert_indictment', INSERT_INDICTMENT,
                       (t['result_id'], t['group_id'], t['user_id'], 'perf'))
                indicted += 1
        s = time.perf_counter(); c.commit(); tm.t['commit'] += time.perf_counter() - s; tm.n['commit'] += 1
    return indicted


WATERMARK_DDL = """
CREATE TABLE IF NOT EXISTS perf_eval_watermark (
  id INT PRIMARY KEY,
  last_started_at DATETIME NOT NULL
)"""

CHANGED_USERS = """
SELECT DISTINCT user_id FROM tbl_transaction
 WHERE updated_at >= %s AND tr_date IN (%s, %s)
"""

GROUPS_OF_USERS = "SELECT DISTINCT group_id FROM tbl_group_member WHERE user_id IN ({ph})"

CHANGED_GROUPS = """
SELECT group_id FROM tbl_group_member WHERE joined_at >= %s
UNION
SELECT id FROM tbl_challenge_group WHERE updated_at >= %s
"""


def incremental(chunk, isolation, today, overlap, member_check):
    c = connect(isolation)
    cur = c.cursor(pymysql.cursors.DictCursor)
    tm = Timer()
    start = time.perf_counter()
    cur.execute(WATERMARK_DDL)
    cur.execute("SELECT NOW() AS now")
    started_at = cur.fetchone()['now']          # DB 시계 기준. 앱 서버와 DB 시계가 달라도 어긋나지 않게
    cur.execute("SELECT last_started_at FROM perf_eval_watermark WHERE id = 1")
    row = cur.fetchone()
    groups = tm.run(cur, 'find_groups', FIND_GROUPS, (today, today - datetime.timedelta(days=1)))
    c.commit()
    out = {'mode': 'incremental', 'isolation': isolation, 'overlap_s': overlap, 'member_check': member_check}

    if row is None:
        # 기준 시각이 없으면 무엇이 바뀌었는지 알 수 없다 → 전체를 한 번 계산하고 시각을 남긴다
        target = groups
        out['bootstrap'] = True
        changed_users = None
    else:
        since = row['last_started_at'] - datetime.timedelta(seconds=overlap)
        out['since'] = str(since)
        users = [r['user_id'] for r in tm.run(cur, 'changed_users', CHANGED_USERS,
                                              (since, today - datetime.timedelta(days=1), today))]
        changed_users = len(users)
        group_ids = set()
        for i in range(0, len(users), 1000):
            part = users[i:i + 1000]
            ph = ','.join(['%s'] * len(part))
            group_ids |= {r['group_id'] for r in tm.run(cur, 'groups_of_users', GROUPS_OF_USERS.format(ph=ph), tuple(part))}
        if member_check:
            group_ids |= {r['group_id'] for r in tm.run(cur, 'changed_groups', CHANGED_GROUPS, (since, since))}
        c.commit()
        target = [g for g in groups if g['id'] in group_ids]   # 진행 중인 그룹만

    indicted = evaluate_chunked(c, cur, tm, target, today, chunk)
    # 다시 계산이 끝난 뒤에 기준 시각을 옮긴다. 중간에 실패하면 다음 실행이 같은 구간을 다시 본다
    cur.execute("INSERT INTO perf_eval_watermark (id, last_started_at) VALUES (1, %s) "
                "ON DUPLICATE KEY UPDATE last_started_at = VALUES(last_started_at)", (started_at,))
    c.commit()
    total = time.perf_counter() - start
    c.close()
    out.update({'changed_users': changed_users, 'groups': len(target), 'active_groups': len(groups),
                'total_s': round(total, 3), 'indicted': indicted,
                'per_stmt_ms': {k: round(v * 1000, 1) for k, v in tm.t.items()}, 'calls': dict(tm.n)})
    return out


def tick(mode, limit, chunk, isolation, today):
    c = connect(isolation)
    cur = c.cursor(pymysql.cursors.DictCursor)
    tm = Timer()
    start = time.perf_counter()
    groups = tm.run(cur, 'find_groups', FIND_GROUPS, (today, today - datetime.timedelta(days=1)))
    c.commit()
    groups = groups[:limit] if limit else groups
    indicted = 0

    if mode == 'current':
        # GroupChallengeEvaluationService.evaluate(): 그룹 하나 = 트랜잭션 하나
        for g in groups:
            ds = dates_for(g, today)
            for d in ds:
                tm.run(cur, 'upsert', UPSERT.format(where='m.group_id = %s'), (d, d, g['id']))
            tm.run(cur, 'overflow', OVERFLOW.format(where='r.group_id = %s'), (g['id'],))
            if g['eval_type'] == 'DAILY':
                for d in ds:
                    for t in tm.run(cur, 'over_limit', OVER_LIMIT_DAILY.format(where='r.group_id = %s'), (g['id'], d)):
                        tm.run(cur, 'insert_indictment', INSERT_INDICTMENT,
                               (t['result_id'], g['id'], t['user_id'], 'perf'))
                        indicted += 1
            s = time.perf_counter(); c.commit(); tm.t['commit'] += time.perf_counter() - s; tm.n['commit'] += 1
    else:
        # 개선 1: 그룹을 chunk 개씩 묶어 같은 SQL 을 IN 조건으로 한 번에
        indicted = evaluate_chunked(c, cur, tm, groups, today, chunk)

    total = time.perf_counter() - start
    c.close()
    return {'mode': mode, 'groups': len(groups), 'isolation': isolation, 'total_s': round(total, 2),
            'indicted': indicted,
            'per_stmt_ms': {k: round(v * 1000) for k, v in tm.t.items()},
            'calls': dict(tm.n)}


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--mode', default='current', choices=['current', 'chunk', 'incremental'])
    p.add_argument('--limit', type=int, default=0)
    p.add_argument('--chunk', type=int, default=200)
    p.add_argument('--isolation', default='REPEATABLE-READ')
    p.add_argument('--overlap', type=int, default=120, help='incremental: 기준 시각을 몇 초 겹쳐 읽을지 (늦은 커밋 대비)')
    p.add_argument('--no-member-check', action='store_true', help='incremental: 참여자·상태 변경 그룹은 보지 않는다')
    p.add_argument('--today', help='오늘 날짜를 바꿔 실행 (예: 자정 시나리오). 기본은 실제 오늘')
    a = p.parse_args()
    today = datetime.date.fromisoformat(a.today) if a.today else db_today()
    if a.mode == 'incremental':
        print(json.dumps(incremental(a.chunk, a.isolation, today, a.overlap, not a.no_member_check), default=str))
    else:
        print(json.dumps(tick(a.mode, a.limit, a.chunk, a.isolation, today)))
