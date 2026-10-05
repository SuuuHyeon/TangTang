#!/bin/bash
# [C] 개선 전: .github/workflows/deploy.yml 과 같은 순서
#   정리 → 전부 내리기(down) → 빌드하면서 올리기(up --build) → 헬스체크
# 내린 순간부터 새 컨테이너가 응답할 때까지 서비스가 멈춘다. 그 사이에 Gradle 빌드가 통째로 들어 있다.
source "$(dirname "$0")/_common.sh"
cd "$ROOT" || exit 1
touch_source
T0=$(ts); step "배포 시작"
docker builder prune -f --keep-storage=4GB > /dev/null
docker image prune -f > /dev/null
step "down (여기서부터 서비스 중단)"; T_DOWN=$(ts)
"${COMPOSE[@]}" down
step "up --build"
"${COMPOSE[@]}" up --build -d
sleep 15
wait_healthy http://localhost:8080/api/health
T_UP=$(ts); step "헬스체크 통과 (서비스 재개)"
echo "배포 전체 $((T_UP - T0))s / 내린 뒤 다시 응답까지 $((T_UP - T_DOWN))s"
