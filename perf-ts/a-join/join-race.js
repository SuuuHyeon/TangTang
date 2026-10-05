import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { createAccessToken } from '../../perf/lib/jwt.js';

/*
 * [A] 같은 초대 코드로 10명이 동시에 참여하기
 *
 * 그룹 100개를 한 라운드에 하나씩 연다. 라운드마다 VU 10개가 같은 그룹에 "같은 순간" 참여 요청을 보낸다.
 *
 * 왜 1,000명을 한꺼번에 안 쏘나
 *   API 의 DB 커넥션 풀이 10개라, 1,000건을 한 번에 보내도 DB 에 동시에 들어가는 트랜잭션은 10개뿐이다.
 *   그 10개가 서로 다른 그룹이면 경합이 안 생긴다. 실제로 문제가 되는 상황은
 *   "같은 링크를 여러 명이 동시에 누를 때"라서 그 상황만 정확히 만든다.
 *
 * 실행 (Monorepo 루트에서)
 *   k6 run -e JWT_SECRET=<.env.perf 의 JWT_SECRET> perf-ts/a-join/join-race.js
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET;
const ROUND_MS = Number(__ENV.ROUND_MS || 1000);   // 라운드 간격. 한 라운드 처리가 이보다 길면 늘린다
const PER_GROUP = 10;

// pairs.tsv: "user_id<TAB>초대코드" 한 줄씩. 같은 코드가 10줄씩 붙어 있다
const pairs = new SharedArray('pairs', () =>
    open('./pairs.tsv').trim().split('\n').map((line) => {
        const [userId, code] = line.split('\t');
        return { userId: Number(userId), code };
    }));
const ROUNDS = pairs.length / PER_GROUP;

const joinDuration = new Trend('tt_join_duration', true);
const joined = new Counter('tt_joined');          // 200: 참여 성공
const groupFull = new Counter('tt_group_full');   // 400 GROUP_FULL: 정원 초과로 거절 (정상 거절)
const otherError = new Counter('tt_other_error'); // 그 밖의 응답 (500 등). 0 이어야 한다

export const options = {
    scenarios: {
        rounds: {
            executor: 'per-vu-iterations',
            vus: PER_GROUP,
            iterations: ROUNDS,
            maxDuration: '10m',
        },
    },
};

export function setup() {
    if (!JWT_SECRET) throw new Error('JWT_SECRET 이 비어 있다. -e JWT_SECRET=... 로 넘긴다');
    // 모든 VU 가 같은 시각표를 쓰도록 시작 시각을 하나 정해서 넘긴다
    return { startAt: Date.now() + 3000 };
}

export default function (data) {
    const round = __ITER;                 // 몇 번째 그룹인가 (0 ~ 99)
    const seat = __VU - 1;                // 그 그룹의 몇 번째 지원자인가 (0 ~ 9)
    const { userId, code } = pairs[round * PER_GROUP + seat];

    // 이 라운드의 출발 시각까지 기다린다 → 10명이 거의 같은 순간에 요청한다
    const wait = data.startAt + round * ROUND_MS - Date.now();
    if (wait > 0) sleep(wait / 1000);

    const token = createAccessToken(userId, JWT_SECRET);
    const res = http.post(`${BASE_URL}/api/group-challenges/invite-codes/${code}/members`, null, {
        headers: { Authorization: `Bearer ${token}` },
        tags: { name: 'join' },
    });
    joinDuration.add(res.timings.duration);

    let errCode = null;
    try { errCode = res.json('code'); } catch (e) { /* 본문이 JSON 이 아니면 null */ }

    if (res.status === 200) joined.add(1);
    else if (res.status === 400 && errCode === 'GROUP_FULL') groupFull.add(1);
    else {
        otherError.add(1);
        console.warn(`unexpected status=${res.status} code=${errCode} user=${userId} group=${code}`);
    }
}
