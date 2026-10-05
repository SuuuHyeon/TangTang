#!/bin/bash
# MySQL 컨테이너가 "빈 볼륨으로 처음 뜰 때" 한 번만 실행된다 (docker-entrypoint-initdb.d 규칙).
# 스키마 → 기본 데이터 → 마이그레이션 순서로 넣는다.
# 마이그레이션 중 일부는 schema.sql 에 이미 반영된 변경이라 "이미 있다" 오류가 난다.
# 그런 오류는 건너뛰도록 --force 로 돌린다.
set -e
run() {
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "$@" tangtang
}
echo "[perf-ts] schema.sql"
run < /tangtang-db/schema.sql
echo "[perf-ts] seed.sql"
run < /tangtang-db/seed.sql
# 소비 카테고리(21~64번). 카테고리 챌린지와 [B] 평가 배치 시드가 쓴다
echo "[perf-ts] seed_category.sql"
run --force < /tangtang-db/seed_category.sql || true
for f in /tangtang-db/migration/*.sql; do
  echo "[perf-ts] migration $(basename "$f")"
  run --force < "$f" || true
done
# 잠금 관찰용: 실험 스크립트는 root 로 접속하지만, 혹시 tangtang 계정으로 볼 때를 위해 권한을 준다
mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "GRANT PROCESS ON *.* TO 'tangtang'@'%'; GRANT SELECT ON performance_schema.* TO 'tangtang'@'%';"
echo "[perf-ts] init done"
