#!/bin/bash
# [B-7] 증분 처리 1회 실행 시간을 잰다.
#   동기화가 5분 동안 바꾸는 양(참여자 33명에게 결제 1건씩)을 넣고 증분을 1회 돌리는 것을 3번 반복한다.
#   회차 사이에 130초를 쉰다. 겹침이 120초라, 쉬지 않으면 앞 회차의 변경까지 다시 읽어서 실제보다 많이 계산한다.
#   전체 약 7분. 환경 변수: USERS(기본 33), GAP(기본 130초)
#   ./run 위치: perf-ts/b-batch
cd "$(dirname "$0")" || exit 1
USERS=${USERS:-33}; GAP=${GAP:-130}
python3 eval_batch.py --mode incremental --chunk 100 --isolation READ-COMMITTED > /dev/null   # 기준 시각 맞추기 (따라잡기)
for i in 1 2 3; do
  echo "[$i/3] ${GAP}초 기다리는 중..."
  sleep "$GAP"
  python3 make_changes.py --users "$USERS"
  python3 eval_batch.py --mode incremental --chunk 100 --isolation READ-COMMITTED
done
