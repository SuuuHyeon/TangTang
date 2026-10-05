#!/bin/bash
# [C] 블루그린 실험 준비: 첫 이미지(v0)를 만들고 blue 를 활성으로 띄운다. 한 번만 실행한다.
source "$(dirname "$0")/_common.sh"
cd "$ROOT" || exit 1
BG=(docker compose -f "$PERF/c-deploy/docker-compose.bluegreen.yml" --env-file "$PERF/.env.perf")
STATE="$PERF/c-deploy/.bluegreen.state"

step "기존 perf 스택의 API 를 내린다 (DB 볼륨은 그대로 둔다)"
"${COMPOSE[@]}" down

step "첫 이미지 tangtang-api:v0 빌드"
docker build -t tangtang-api:v0 -f apps/api/Dockerfile .

printf 'ACTIVE=blue\nBLUE_TAG=v0\nGREEN_TAG=v0\n' > "$STATE"
sed "s/__ACTIVE__/api-blue/" "$PERF/c-deploy/nginx/default.conf.tpl" > "$PERF/c-deploy/nginx/conf.d/default.conf"

step "db · redis · api-blue · nginx 기동"
BLUE_TAG=v0 GREEN_TAG=v0 "${BG[@]}" up -d --remove-orphans db redis api-blue nginx
wait_healthy http://localhost:8088/api/health
step "준비 완료: http://localhost:8088 → api-blue"
