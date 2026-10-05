#!/bin/bash
# batch_once.sql 을 5번 돌려 시간을 잰다. 첫 회는 버퍼 풀이 덜 데워져 느릴 수 있으니 2~5회째를 본다.
cd "$(dirname "$0")/.." || exit 1
for i in 1 2 3 4 5; do
  ./mysql.sh -N -B < b-lock/batch_once.sql 2>/dev/null | sed "s/^/run $i: moved_rows, elapsed_ms = /"
done
