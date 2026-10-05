"""
[B-7] 동기화 배치가 5분 동안 하는 일을 흉내 낸다: 진행 중 그룹 참여자 N명에게 오늘 결제를 1건씩 넣는다.
  python3 make_changes.py --users 33      # 3분에 최대 20명 → 5분에 약 33명 (사전 점검 기록 2절의 계산)
codef 키가 PROBE- 로 시작해서 cleanup.sql 이 함께 지운다.
"""
import argparse, os, random, time
import pymysql

ap = argparse.ArgumentParser()
ap.add_argument('--users', type=int, default=33)
a = ap.parse_args()
c = pymysql.connect(host=os.environ.get('DB_HOST', '127.0.0.1'), port=int(os.environ.get('DB_PORT', 3307)),
                    user=os.environ.get('DB_USER', 'root'), password=os.environ['DB_PASSWORD'],
                    database='tangtang', autocommit=False)
cur = c.cursor()
cur.execute("SELECT m.user_id FROM tbl_group_member m JOIN tbl_challenge_group g ON g.id = m.group_id "
            "WHERE g.invite_code LIKE 'PFB%%' AND g.status = 'ACTIVE'")
users = random.sample([r[0] for r in cur.fetchall()], a.users)
for u in users:
    cur.execute("INSERT INTO tbl_transaction (user_id, source_type, codef_tr_key, amount, direction, tr_date,"
                " classification, category_id, is_refund, is_excluded_from_summary)"
                " VALUES (%s,'CARD_CREDIT',%s,%s,'OUT',CURDATE(),'CONSUMPTION',21,0,0)",
                (u, f'PROBE-INC-{u}-{time.time_ns()}', random.randint(3000, 30000)))
c.commit()
print(f'changed_users={len(users)}')
