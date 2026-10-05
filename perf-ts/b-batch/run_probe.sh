#!/bin/bash
# 배치를 쉬지 않고 반복 실행하면서, 그동안 다른 요청(결제 저장 · 카테고리 갱신 · 조회)이 얼마나 기다리는지 잰다.
#   ./perf-ts/b-batch/run_probe.sh <이름> [eval_batch.py 인자...]
#   인자를 비우면 배치 없이 기준선만 잰다
# 환경 변수: SECS(기본 60초), RATE(종류별 초당 요청 수, 기본 30)
cd "$(dirname "$0")" || exit 1
TAG=$1; shift
python3 probe.py --seconds "${SECS:-60}" --rate "${RATE:-30}" --tag "$TAG" > "/tmp/probe_$TAG.json" &
P=$!
sleep 2
B=""
if [ $# -gt 0 ]; then
  ( while kill -0 $P 2>/dev/null; do python3 eval_batch.py "$@" >> "/tmp/batch_$TAG.jsonl"; done ) &
  B=$!
fi
wait $P
[ -n "$B" ] && kill $B 2>/dev/null
wait 2>/dev/null
cat "/tmp/probe_$TAG.json"
[ -f "/tmp/batch_$TAG.jsonl" ] && tail -1 "/tmp/batch_$TAG.jsonl" && rm -f "/tmp/batch_$TAG.jsonl"
