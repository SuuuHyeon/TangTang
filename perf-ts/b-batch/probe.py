"""
배치가 도는 동안 '다른 쪽' 요청이 얼마나 기다리는지 잰다.

  writer: 거래 동기화 저장을 흉내 낸다. 그룹 참여자 중 무작위 1명에게 오늘 날짜 결제 1건을
          (codef 키로 UPDATE 시도 → 0행이면 INSERT) 넣고 커밋한다. FinancialSyncServiceImpl 저장 패턴.
  categorize: LLM 분류 결과 반영을 흉내 낸다. 오늘 거래 1건의 category_id 를 바꾼다 (updateCategory).
  reader: 홈 '오늘 쓴 돈' 같은 사용자 조회. 이번 달 소비 합계.

  python3 probe.py --seconds 20 --rate 10
"""
import argparse, threading, time, random, datetime, json, os, statistics
import pymysql

def conn():
    return pymysql.connect(host=os.environ.get('DB_HOST', '127.0.0.1'), port=int(os.environ.get('DB_PORT', 3307)),
                           user=os.environ.get('DB_USER', 'root'), password=os.environ['DB_PASSWORD'],
                           database='tangtang', autocommit=False)

def pct(xs, p):
    if not xs: return None
    xs = sorted(xs); k = min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))
    return round(xs[k], 1)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--seconds', type=float, default=20)
    ap.add_argument('--rate', type=float, default=10)   # 종류별 초당 요청 수
    ap.add_argument('--tag', default='')
    a = ap.parse_args()

    c0 = conn(); cur = c0.cursor()
    cur.execute("SELECT m.user_id FROM tbl_group_member m JOIN tbl_challenge_group g ON g.id=m.group_id "
                "WHERE g.invite_code LIKE 'PFB%%' AND g.status='ACTIVE'")
    users = [r[0] for r in cur.fetchall()]
    cur.execute("SELECT id FROM tbl_transaction WHERE tr_date = CURDATE() AND codef_tr_key LIKE 'PERFB-%%' LIMIT 20000")
    today_tx = [r[0] for r in cur.fetchall()]
    c0.close()
    c1 = conn(); k = c1.cursor(); k.execute("SELECT CURDATE()"); today = k.fetchone()[0]; c1.close()   # DB 기준 오늘
    month_start = today.replace(day=1)
    lat = {'writer': [], 'categorize': [], 'reader': []}
    errors = {'writer': 0, 'categorize': 0, 'reader': 0}
    stop = time.time() + a.seconds

    def loop(kind):
        c = conn(); cur = c.cursor()
        cur.execute("SET SESSION innodb_lock_wait_timeout = 50")
        interval = 1.0 / a.rate
        nxt = time.time()
        seq = 0
        while time.time() < stop:
            nxt += interval
            u = random.choice(users)
            s = time.perf_counter()
            try:
                if kind == 'writer':
                    seq += 1
                    key = f'PROBE-{os.getpid()}-{threading.get_ident()}-{seq}-{time.time_ns()}'
                    cur.execute("UPDATE tbl_transaction SET amount = amount WHERE codef_tr_key = %s", (key,))
                    cur.execute("INSERT INTO tbl_transaction (user_id, source_type, codef_tr_key, amount, direction, tr_date,"
                                " classification, category_id, is_refund, is_excluded_from_summary)"
                                " VALUES (%s,'CARD_CREDIT',%s,5000,'OUT',CURDATE(),'CONSUMPTION',21,0,0)", (u, key))
                    c.commit()
                elif kind == 'categorize':
                    tid = random.choice(today_tx)
                    cur.execute("UPDATE tbl_transaction SET category_id = 21 + (id %% 44), category_source = 'LLM'"
                                " WHERE id = %s AND (category_source IS NULL OR category_source <> 'USER')", (tid,))
                    c.commit()
                else:
                    cur.execute("SELECT COALESCE(SUM(amount),0) FROM tbl_transaction WHERE user_id = %s"
                                " AND tr_date BETWEEN %s AND %s AND classification = 'CONSUMPTION'"
                                " AND direction = 'OUT' AND is_excluded_from_summary = 0", (u, month_start, today))
                    cur.fetchall(); c.commit()
                lat[kind].append((time.perf_counter() - s) * 1000)
            except Exception as e:
                errors[kind] += 1; c.rollback()
            d = nxt - time.time()
            if d > 0: time.sleep(d)
        c.close()

    ts = [threading.Thread(target=loop, args=(k,)) for k in lat]
    [t.start() for t in ts]; [t.join() for t in ts]
    out = {'tag': a.tag}
    for k, xs in lat.items():
        out[k] = {'n': len(xs), 'p50': pct(xs, 50), 'p95': pct(xs, 95), 'p99': pct(xs, 99),
                  'max': round(max(xs), 1) if xs else None, 'over100ms': sum(x > 100 for x in xs), 'err': errors[k]}
    print(json.dumps(out, ensure_ascii=False))

if __name__ == '__main__':
    main()
