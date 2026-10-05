import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { createAccessToken } from '../../perf/lib/jwt.js';

/*
 * [B] 변론 마감 배치가 도는 동안 투표하기
 *
 * 초당 일정한 수(RATE)의 투표를 DURATION 동안 보낸다. 같은 사람이 같은 재판에 두 번 투표하지 않도록
 * vote_pairs.tsv 의 짝을 한 줄씩 차례로 쓴다 (20,000 짝 → 30건/초 × 60초 = 1,800건이면 충분).
 *
 * 무엇을 보나
 *   tt_vote_duration 의 p95, p99, max   배치가 테이블을 잠그는 순간에 들어온 투표가 얼마나 기다렸나
 *   tt_vote_slow                       100ms 넘게 걸린 투표 수
 *   tt_vote_error                      200 이 아닌 응답 수 (0 이어야 응답시간 숫자를 믿을 수 있다)
 *
 * 실행 (Monorepo 루트에서)
 *   k6 run -e JWT_SECRET=<.env.perf 의 JWT_SECRET> perf-ts/b-lock/vote-during-batch.js
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET;
const RATE = Number(__ENV.RATE || 30);
const DURATION = __ENV.DURATION || '60s';

const pairs = new SharedArray('vote pairs', () =>
    open('./vote_pairs.tsv').trim().split('\n').map((line) => {
        const [userId, indictmentId] = line.split('\t');
        return { userId: Number(userId), indictmentId: Number(indictmentId) };
    }));

const voteDuration = new Trend('tt_vote_duration', true);
const voteSlow = new Counter('tt_vote_slow');
const voteError = new Counter('tt_vote_error');

export const options = {
    scenarios: {
        votes: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 50,
            maxVUs: 200,
        },
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
    if (!JWT_SECRET) throw new Error('JWT_SECRET 이 비어 있다. -e JWT_SECRET=... 로 넘긴다');
    const i = exec.scenario.iterationInTest;
    if (i >= pairs.length) return;   // 짝을 다 쓰면 더 보내지 않는다
    const { userId, indictmentId } = pairs[i];

    const res = http.post(
        `${BASE_URL}/api/group-challenges/trials/${indictmentId}/votes`,
        JSON.stringify({ verdict: i % 2 === 0 ? 'GUILTY' : 'INNOCENT' }),
        {
            headers: {
                Authorization: `Bearer ${createAccessToken(userId, JWT_SECRET)}`,
                'Content-Type': 'application/json',
            },
            tags: { name: 'vote' },
        },
    );

    voteDuration.add(res.timings.duration);
    if (res.timings.duration > 100) voteSlow.add(1);
    if (res.status !== 200) {
        voteError.add(1);
        let code = null;
        try { code = res.json('code'); } catch (e) { /* JSON 아님 */ }
        console.warn(`vote failed status=${res.status} code=${code} user=${userId} indictment=${indictmentId}`);
    }
}
