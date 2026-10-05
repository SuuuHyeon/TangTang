"""
[B-7] 증분 처리가 놓치는 경우(빈틈) 네 가지를 실제로 만들어, 결과가 틀리는지 확인한다.

  python3 gap_check.py

같은 네 시나리오를 두 가지 설정으로 돌린다.
  단순 증분 : updated_at 만 본다 (겹침 0초, 참여자 변경 확인 안 함)
  보완 증분 : 겹침 + 참여자·상태가 바뀐 그룹도 다시 계산 (eval_batch.py incremental 의 방식)
             실제 기본값은 겹침 120초지만, 여기서는 시나리오끼리 섞이지 않게 10초로 줄이고
             시나리오마다 11초를 쉬었다가 시작한다 (앞 시나리오의 변경이 겹침 구간에 들어오지 않게)

시나리오마다 증분 1회 뒤 "틀린 결산 행 수"를 세고, 전체 재계산(안전망) 뒤 다시 센다.
틀린 결산 행 = 지금 거래로 다시 계산한 값과 결산 테이블 값이 다르거나, 있어야 할 행이 없는 것

  1 늦은 참여 : 오늘 이미 결제한 사람이 오늘 그룹에 들어온다 (거래는 안 바뀜)
  2 늦은 커밋 : 결제 INSERT 는 증분 실행 전에, 커밋은 실행 뒤에 된다
  3 삭제     : 반영까지 끝난 결제가 지워진다
  4 자정     : 날짜가 바뀌었는데 아무 거래도 바뀌지 않았다

실험이 만든 사용자·거래는 PERFB-GAP- / PROBE-GAP- 로 시작해서 cleanup.sql 이 지운다.
자정 시나리오가 만든 내일 날짜 결산 행은 끝에서 지운다.
"""
import datetime, os, threading, time
import pymysql
from eval_batch import connect, incremental, tick, db_today

TODAY = db_today()          # DB 기준 오늘 (시드와 같은 시계)
TOMORROW = TODAY + datetime.timedelta(days=1)

MISMATCH = """
SELECT COUNT(*) AS bad,
       SUM(r.id IS NULL) AS missing
  FROM (SELECT m.group_id, m.user_id, d.dt,
               COALESCE(SUM(CASE WHEN t.is_refund = 1 THEN -COALESCE(t.refunded_amount, t.amount) ELSE t.amount END), 0) AS expected
          FROM tbl_group_member m
          JOIN tbl_challenge_group g ON g.id = m.group_id
          JOIN (SELECT %s AS dt UNION ALL SELECT %s) d ON d.dt BETWEEN g.start_date AND g.end_date
          LEFT JOIN tbl_transaction t
                 ON t.user_id = m.user_id AND t.tr_date = d.dt
                AND t.classification = 'CONSUMPTION' AND t.direction = 'OUT'
                AND t.is_excluded_from_summary = 0
                AND (g.category_id IS NULL OR t.category_id = g.category_id)
         WHERE g.invite_code LIKE 'PFB%%' AND g.status = 'ACTIVE'
         GROUP BY m.group_id, m.user_id, d.dt) e
  LEFT JOIN tbl_group_challenge_daily_result r
         ON r.group_id = e.group_id AND r.user_id = e.user_id AND r.challenge_date = e.dt
 WHERE r.id IS NULL OR r.daily_amount <> e.expected
"""


def raw():
    return pymysql.connect(host=os.environ.get('DB_HOST', '127.0.0.1'), port=int(os.environ.get('DB_PORT', 3307)),
                           user=os.environ.get('DB_USER', 'root'), password=os.environ['DB_PASSWORD'],
                           database='tangtang', autocommit=True)


def bad_rows(today=TODAY):
    c = raw(); cur = c.cursor()
    cur.execute(MISMATCH, (today - datetime.timedelta(days=1), today))
    bad, missing = cur.fetchone()
    c.close()
    return int(bad or 0), int(missing or 0)


def full(today=TODAY):
    """안전망: 전체 재계산 (묶음 100 + READ COMMITTED)"""
    tick('chunk', 0, 100, 'READ-COMMITTED', today)


def inc(cfg, today=TODAY):
    return incremental(100, 'READ-COMMITTED', today, cfg['overlap'], cfg['member_check'])


def reset_watermark():
    """다음 증분이 '지금 이후'만 보도록 기준 시각을 지금으로 맞춘다"""
    c = raw(); cur = c.cursor()
    cur.execute("CREATE TABLE IF NOT EXISTS perf_eval_watermark (id INT PRIMARY KEY, last_started_at DATETIME NOT NULL)")
    cur.execute("INSERT INTO perf_eval_watermark VALUES (1, NOW()) ON DUPLICATE KEY UPDATE last_started_at = NOW()")
    c.close()
    time.sleep(1.1)   # updated_at 이 초 단위라, 기준 시각과 다음 변경이 같은 초에 찍히지 않게 띄운다


def group(cur, code):
    cur.execute("SELECT id FROM tbl_challenge_group WHERE invite_code = %s", (code,))
    return cur.fetchone()[0]


def a_member(cur, gid):
    cur.execute("SELECT user_id FROM tbl_group_member WHERE group_id = %s ORDER BY user_id LIMIT 1", (gid,))
    return cur.fetchone()[0]


def add_tx(cur, user, key, amount, updated_at_sql='NOW()'):
    cur.execute("INSERT INTO tbl_transaction (user_id, source_type, codef_tr_key, amount, direction, tr_date,"
                " classification, category_id, is_refund, is_excluded_from_summary, updated_at)"
                f" VALUES (%s,'CARD_CREDIT',%s,%s,'OUT',CURDATE(),'CONSUMPTION',21,0,0,{updated_at_sql})",
                (user, key, amount))


def s1_late_join(cfg, tag):
    """오늘 09시에 이미 결제한 사람이, 지금 그룹에 들어온다"""
    c = raw(); cur = c.cursor()
    cur.execute("INSERT INTO tbl_user (social_provider, provider_user_id, nickname, difficulty_id)"
                " VALUES ('GOOGLE', %s, 'gap', 1)", (f'PERFB-GAP-{tag}-1',))
    uid = cur.lastrowid
    add_tx(cur, uid, f'PROBE-GAP-{tag}-1', 50000, 'NOW() - INTERVAL 1 HOUR')   # 1시간 전에 동기화된 결제
    reset_watermark()
    cur.execute("INSERT INTO tbl_group_member (group_id, user_id, lives_count) VALUES (%s, %s, 3)",
                (group(cur, 'PFB0'), uid))
    c.close()
    return inc(cfg)


def s2_late_commit(cfg, tag):
    """INSERT 는 증분 실행 전에, 커밋은 실행 뒤에"""
    reset_watermark()
    c = raw(); cur = c.cursor()
    uid = a_member(cur, group(cur, 'PFB10'))
    w = pymysql.connect(host=os.environ.get('DB_HOST', '127.0.0.1'), port=int(os.environ.get('DB_PORT', 3307)),
                        user=os.environ.get('DB_USER', 'root'), password=os.environ['DB_PASSWORD'],
                        database='tangtang', autocommit=False)
    wc = w.cursor()
    add_tx(wc, uid, f'PROBE-GAP-{tag}-2', 40000)      # updated_at = 지금, 아직 커밋 안 함
    time.sleep(1.5)
    first = inc(cfg)                                   # 이 실행은 위 행을 못 본다 (커밋 전)
    w.commit(); w.close()
    time.sleep(1.1)
    second = inc(cfg)                                  # 다음 실행: 겹침이 없으면 이 행의 시각이 기준보다 이르다
    c.close()
    return second


def s3_delete(cfg, tag):
    """반영까지 끝난 결제가 지워진다"""
    reset_watermark()
    c = raw(); cur = c.cursor()
    uid = a_member(cur, group(cur, 'PFB20'))
    add_tx(cur, uid, f'PROBE-GAP-{tag}-3', 1000)
    time.sleep(1.1)
    inc(cfg)                                           # 결제가 결산에 반영된다
    cur.execute("DELETE FROM tbl_transaction WHERE codef_tr_key = %s", (f'PROBE-GAP-{tag}-3',))
    time.sleep(1.1)
    c.close()
    return inc(cfg)


def s4_midnight(cfg, tag):
    """날짜만 바뀌었다 (내일 날짜로 실행해서 흉내 낸다)"""
    reset_watermark()
    return inc(cfg, TOMORROW)


SCENARIOS = [('1 늦은 참여', s1_late_join, TODAY), ('2 늦은 커밋', s2_late_commit, TODAY),
             ('3 삭제', s3_delete, TODAY), ('4 자정', s4_midnight, TOMORROW)]
CONFIGS = [('단순 증분', {'overlap': 0, 'member_check': False}, 'n'),
           ('보완 증분', {'overlap': 10, 'member_check': True}, 'p')]


def drop_tomorrow():
    """자정 시나리오가 만든 내일 날짜 결산·기소를 지운다"""
    c = raw(); cur = c.cursor()
    cur.execute("DELETE i FROM tbl_indictment i JOIN tbl_group_challenge_daily_result r ON r.id = i.result_id"
                " WHERE r.challenge_date = %s", (TOMORROW,))
    cur.execute("DELETE r FROM tbl_group_challenge_daily_result r JOIN tbl_challenge_group g ON g.id = r.group_id"
                " WHERE g.invite_code LIKE 'PFB%%' AND r.challenge_date = %s", (TOMORROW,))
    c.close()


def main():
    drop_tomorrow()
    full()
    print(f'시작 전 틀린 결산 행: {bad_rows()[0]} (0 이어야 한다)\n')
    rows = []
    for cname, cfg, tag in CONFIGS:
        for sname, fn, day in SCENARIOS:
            time.sleep(11)                     # 앞 시나리오의 변경이 겹침 구간에 들어오지 않게
            res = fn(cfg, f'{tag}{int(time.time())}')
            after_inc = bad_rows(day)
            full(day)
            after_full = bad_rows(day)
            drop_tomorrow()
            rows.append((cname, sname, res['groups'], after_inc, after_full))
            print(f'{cname} | {sname} | 다시 계산한 그룹 {res["groups"]}개 | '
                  f'증분 뒤 틀린 행 {after_inc[0]} (없는 행 {after_inc[1]}) | 전체 재계산 뒤 {after_full[0]}', flush=True)
    print('\n끝. 자정 시나리오가 만든 내일 날짜 결산 행은 지웠다')


if __name__ == '__main__':
    main()
