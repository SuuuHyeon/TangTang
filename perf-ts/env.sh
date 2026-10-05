# 새 터미널을 열 때마다: source ~/Documents/GitHub/tangtang-perf/perf-ts/env.sh
# 터미널 창에만 기억되는 설정(별칭, 환경 변수, 가상환경)을 한 번에 다시 건다.
# 비밀번호는 여기 적지 않고 .env.perf 에서 읽어 온다 → 이 파일이 커밋돼도 비밀번호가 새지 않는다
cd ~/Documents/GitHub/tangtang-perf
alias perf='docker compose -f perf-ts/docker-compose.perf.yml --env-file perf-ts/.env.perf'
export JWT_SECRET=$(grep ^JWT_SECRET perf-ts/.env.perf | cut -d= -f2)
export DB_PASSWORD=$(grep ^MYSQL_ROOT_PASSWORD perf-ts/.env.perf | cut -d= -f2)
source perf-ts/.venv/bin/activate
