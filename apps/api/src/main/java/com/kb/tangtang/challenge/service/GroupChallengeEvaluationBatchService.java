package com.kb.tangtang.challenge.service;

import com.kb.tangtang.challenge.domain.ChallengeGroup;
import com.kb.tangtang.challenge.domain.ChallengeGroupStatus;
import com.kb.tangtang.challenge.mapper.ChallengeGroupMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 그룹 챌린지 평가·기소 배치 (이슈 #168).
 *
 * <p>진행 중인 그룹을 훑어 {@link GroupChallengeEvaluationService} 에 하나씩 넘긴다.
 *
 * <p>여기에 {@code @Transactional} 을 붙이지 않는다. 그룹 하나가 실패해도 나머지는 처리돼야 한다 —
 * 트랜잭션 경계는 {@link GroupChallengeEvaluationService} 가 그룹 단위로 잡는다.
 *
 * <p><b>조회 조건에 어제가 들어간다.</b> {@code end_date >= 어제} 라서 챌린지가 끝난 다음 날까지
 * 그룹이 남는다. 두 가지가 그 하루에 걸려 있다.
 * <ul>
 *   <li>마지막 날 심야 거래 — 동기화가 자정을 넘겨 도착하므로 다음 날 배치가 집계해야 한다</li>
 *   <li>기간평가(PERIOD) 기소 — {@code today > end_date} 여야 판정할 수 있다</li>
 * </ul>
 * {@code end_date >= 오늘} 으로 좁히면 둘 다 통째로 사라진다.
 */
@Service
@Log4j2
public class GroupChallengeEvaluationBatchService {

    private final ChallengeGroupMapper challengeGroupMapper;
    private final GroupChallengeEvaluationService evaluationService;

    public GroupChallengeEvaluationBatchService(ChallengeGroupMapper challengeGroupMapper,
                                                GroupChallengeEvaluationService evaluationService) {
        this.challengeGroupMapper = challengeGroupMapper;
        this.evaluationService = evaluationService;
    }

    /**
     * 진행 중인 그룹을 전부 평가한다.
     *
     * @param today 배치 기준일. 시스템 시각을 SQL 이 아니라 호출부가 정한다 —
     *              그래야 테스트가 고정 Clock 으로 날짜를 바꿔 끼울 수 있다
     * @return 이번 실행에서 만든 기소 수
     */
    public int evaluateActiveGroups(LocalDate today) {
        long started = System.nanoTime();   // [perf-ts] 한 틱에 걸린 시간을 로그로 남긴다
        List<ChallengeGroup> groups = challengeGroupMapper.findGroupsToEvaluate(
                ChallengeGroupStatus.ACTIVE.name(), today, today.minusDays(1));
        if (groups.isEmpty()) {
            return 0;
        }

        int indicted = 0;
        for (ChallengeGroup group : groups) {
            try {
                indicted += evaluationService.evaluate(group, today);
            } catch (RuntimeException e) {
                /* 한 그룹의 실패가 나머지를 막지 않는다. 5분 뒤 다음 실행이 다시 집어 간다 */
                log.error("그룹 챌린지 평가 실패 groupId={}", group.getId(), e);
            }
        }
        log.info("그룹 챌린지 평가 배치 완료 today={} 대상={} 기소={} elapsedMs={}",
                today, groups.size(), indicted, (System.nanoTime() - started) / 1_000_000);
        return indicted;
    }
}
