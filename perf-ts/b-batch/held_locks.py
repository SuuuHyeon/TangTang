"""
묶음 하나만큼의 재집계(INSERT ... SELECT)를 실행해 둔 채로, 그 트랜잭션이 어떤 잠금을 쥐고 있는지 센다.
  python3 held_locks.py REPEATABLE-READ 1      # 지금처럼 그룹 1개
  python3 held_locks.py REPEATABLE-READ 100    # 묶음 100개
  python3 held_locks.py READ-COMMITTED 100
"""
import os
import pymysql, sys, datetime
from eval_batch import UPSERT
iso, n = sys.argv[1], int(sys.argv[2])
def _conn(auto):
    return pymysql.connect(host=os.environ.get('DB_HOST', '127.0.0.1'), port=int(os.environ.get('DB_PORT', 3307)),
                           user=os.environ.get('DB_USER', 'root'), password=os.environ['DB_PASSWORD'],
                           database='tangtang', autocommit=auto)
c = _conn(False)
cur = c.cursor()
cur.execute(f"SET SESSION transaction_isolation='{iso}'")
cur.execute("SELECT id FROM tbl_challenge_group WHERE invite_code LIKE 'PFB%%' ORDER BY id LIMIT %s", (n,))
ids = [r[0] for r in cur.fetchall()]
cur.execute("SELECT CURDATE()")
d = cur.fetchone()[0]
ph = ','.join(['%s'] * len(ids))
cur.execute(UPSERT.format(where=f'm.group_id IN ({ph})'), (d, d, *ids))
m = _conn(True)
mc = m.cursor()
mc.execute("SELECT object_name, index_name, lock_mode, COUNT(*) FROM performance_schema.data_locks WHERE lock_type='RECORD' GROUP BY 1,2,3 ORDER BY 1,2,3")
for r in mc.fetchall(): print(iso, n, r)
c.rollback()
