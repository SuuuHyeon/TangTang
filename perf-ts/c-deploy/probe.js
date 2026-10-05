import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { createAccessToken } from '../../perf/lib/jwt.js';

/*
 * [C] 배포하는 동안 계속 요청을 보내며 "언제부터 언제까지 실패했는지"를 잰다.
 *
 * 초당 RATE 건씩, 헬스체크(DB 안 거침)와 내 그룹 목록(DB 거침)을 번갈아 보낸다.
 * 배포 스크립트는 다른 터미널에서 이 테스트가 도는 중에 실행한다.
 *
 * 결과 읽는 법 (요약표)
 *   tt_fail                실패한 요청 수 (연결 거부, 502, 5xx 전부)
 *   tt_fail_at   min, max  실패가 처음 난 시각과 마지막으로 난 시각 (테스트 시작 후 몇 초인지)
 *                          → max - min 이 "서비스가 멈춘 시간"에 가깝다
 *   tt_ok_duration p95     성공한 요청의 응답시간
 *
 * 실행 (Monorepo 루트에서)
 *   v1, v2: k6 run -e JWT_SECRET=... -e USER_ID=<probe 사용자 id> perf-ts/c-deploy/probe.js
 *   v3    : 위 명령에 -e BASE_URL=http://localhost:8088 추가 (nginx 를 거친다)
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET;
const USER_ID = Number(__ENV.USER_ID || 1);
const RATE = Number(__ENV.RATE || 20);
const DURATION = __ENV.DURATION || '6m';

const fail = new Counter('tt_fail');
const failAt = new Trend('tt_fail_at');   // 단위: 초 (테스트 시작 기준)
const okDuration = new Trend('tt_ok_duration', true);

export const options = {
    scenarios: {
        probe: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 20,
            maxVUs: 200,
        },
    },
    summaryTrendStats: ['min', 'med', 'p(95)', 'max', 'count'],
};

export function setup() {
    if (!JWT_SECRET) throw new Error('JWT_SECRET 이 비어 있다. -e JWT_SECRET=... 로 넘긴다');
    return { start: Date.now(), token: createAccessToken(USER_ID, JWT_SECRET, 3 * 3600) };
}

export default function (data) {
    const useDb = __ITER % 2 === 1;
    const url = useDb ? `${BASE_URL}/api/group-challenges` : `${BASE_URL}/api/health`;
    const res = http.get(url, {
        headers: useDb ? { Authorization: `Bearer ${data.token}` } : {},
        timeout: '5s',
        tags: { name: useDb ? 'my-groups' : 'health' },
    });

    if (res.status === 200) {
        okDuration.add(res.timings.duration);
    } else {
        fail.add(1);
        failAt.add((Date.now() - data.start) / 1000);
    }
}
