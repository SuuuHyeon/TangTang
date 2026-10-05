#!/bin/bash
# perf-ts 의 MySQL 컨테이너에 root 로 접속한다.
#   대화형:   ./perf-ts/mysql.sh
#   파일 실행: ./perf-ts/mysql.sh < perf-ts/b-lock/seed.sql
#   한 줄:    ./perf-ts/mysql.sh -e "SELECT 1"
# 호스트에 mysql 클라이언트를 따로 설치하지 않으려고 컨테이너 안의 mysql 을 쓴다.
cd "$(dirname "$0")" || exit 1
set -a; source ./.env.perf; set +a
if [ -t 0 ]; then TTY="-it"; else TTY="-T"; fi
exec docker compose -f docker-compose.perf.yml --env-file .env.perf exec $TTY db \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 tangtang "$@"
