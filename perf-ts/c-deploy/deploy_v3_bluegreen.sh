#!/bin/bash
# [C] 개선 2: 블루그린 배포
#   1) 새 이미지를 빌드한다                     (기존 쪽이 계속 서비스)
#   2) 쉬고 있는 쪽에 새 이미지를 띄운다         (기존 쪽이 계속 서비스)
#   3) 새 쪽 헬스체크가 통과할 때까지 기다린다   (기존 쪽이 계속 서비스)
#   4) nginx 가 가리키는 곳을 새 쪽으로 바꾼다   (reload: 처리 중인 요청은 기존 워커가 마저 처리)
#   5) 잠깐 기다렸다가 기존 쪽을 멈춘다
source "$(dirname "$0")/_common.sh"
cd "$ROOT" || exit 1
BG=(docker compose -f "$PERF/c-deploy/docker-compose.bluegreen.yml" --env-file "$PERF/.env.perf")
STATE="$PERF/c-deploy/.bluegreen.state"
source "$STATE"
IDLE=$([ "$ACTIVE" = blue ] && echo green || echo blue)
NEW_TAG="v$(date +%s)"
if [ "$IDLE" = blue ]; then BLUE_TAG=$NEW_TAG; else GREEN_TAG=$NEW_TAG; fi
export BLUE_TAG GREEN_TAG

touch_source
T0=$(ts); step "배포 시작: $ACTIVE(현재) → $IDLE(새 버전 $NEW_TAG)"
docker builder prune -f --keep-storage=4GB > /dev/null
step "1) 이미지 빌드"
docker build -q -t "tangtang-api:$NEW_TAG" -f apps/api/Dockerfile . > /dev/null
step "2) api-$IDLE 기동"
"${BG[@]}" up -d --no-deps "api-$IDLE"
step "3) api-$IDLE 헬스체크 대기"
until "${BG[@]}" exec -T "api-$IDLE" curl -sf http://localhost:8080/api/health > /dev/null 2>&1; do sleep 2; done
step "4) nginx 전환 → api-$IDLE"; T_SWITCH=$(ts)
sed "s/__ACTIVE__/api-$IDLE/" "$PERF/c-deploy/nginx/default.conf.tpl" > "$PERF/c-deploy/nginx/conf.d/default.conf"
"${BG[@]}" exec -T nginx nginx -s reload
sleep 5
step "5) api-$ACTIVE 정지 (처리 중인 요청은 최대 30초 기다려 준다)"
"${BG[@]}" stop -t 30 "api-$ACTIVE"
printf 'ACTIVE=%s\nBLUE_TAG=%s\nGREEN_TAG=%s\n' "$IDLE" "$BLUE_TAG" "$GREEN_TAG" > "$STATE"
T_END=$(ts)
echo "배포 전체 $((T_END - T0))s / 전환 시점 $((T_SWITCH - T0))s 지점"
