#!/bin/bash
# [C] 개선 1: 빌드를 먼저 하고, API 컨테이너만 바꿔 끼운다
#   - 빌드하는 동안 기존 컨테이너는 계속 요청을 받는다
#   - DB 와 Redis 는 건드리지 않는다 (--no-deps)
# 남는 중단: 기존 API 를 멈추고 새 API 의 톰캣이 다 뜰 때까지
source "$(dirname "$0")/_common.sh"
cd "$ROOT" || exit 1
touch_source
T0=$(ts); step "배포 시작"
docker builder prune -f --keep-storage=4GB > /dev/null
docker image prune -f > /dev/null
step "이미지 빌드 (기존 API 는 계속 서비스 중)"
"${COMPOSE[@]}" build api
step "API 컨테이너만 교체 (여기서부터 중단)"; T_SWAP=$(ts)
"${COMPOSE[@]}" up -d --no-deps api
wait_healthy http://localhost:8080/api/health
T_UP=$(ts); step "헬스체크 통과 (서비스 재개)"
echo "배포 전체 $((T_UP - T0))s / 교체 시작부터 다시 응답까지 $((T_UP - T_SWAP))s"
