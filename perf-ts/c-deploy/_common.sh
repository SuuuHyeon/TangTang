# 배포 스크립트 3종이 같이 쓰는 함수. 직접 실행하지 않는다.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"   # Monorepo 루트
PERF="$ROOT/perf-ts"
COMPOSE=(docker compose -f "$PERF/docker-compose.perf.yml" --env-file "$PERF/.env.perf")

ts() { date +%s; }
step() { echo "[$(date +%H:%M:%S)] $*"; }

# 실제 배포는 새 코드를 받아 와서 빌드한다. 로컬에서는 코드가 그대로라 Docker 가 빌드를 캐시로 건너뛴다.
# 그래서 소스 폴더에 배포 시각을 적은 파일을 하나 바꿔서 "소스가 바뀐 배포"를 흉내 낸다.
# (git 에 안 올라가게 .git/info/exclude 에 등록해 둔다. 가이드 3-1 참고)
touch_source() {
  date +%s > "$ROOT/apps/api/src/main/resources/deploy-marker.txt"
}

# 헬스체크가 통과할 때까지 기다린다. 원래 deploy.yml 은 15초 쉬고 한 번만 확인하지만,
# 실험에서는 "언제 다시 살아났는지"를 재야 해서 살아날 때까지 2초마다 확인한다.
wait_healthy() {
  local url=$1
  until curl -sf "$url" > /dev/null; do sleep 2; done
}
