package com.kb.tangtang.challenge.service;

import com.kb.tangtang.challenge.domain.ChallengeGroup;
import com.kb.tangtang.challenge.domain.ChallengeGroupStatus;
import com.kb.tangtang.challenge.domain.GroupEvaluationWatermark;
import com.kb.tangtang.challenge.mapper.ChallengeGroupMapper;
import com.kb.tangtang.challenge.mapper.GroupEvaluationWatermarkMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
 *
 * <p><b>[perf-ts] 스케줄러는 {@link #runScheduled} 를 부른다.</b> 평소에는 지난 실행 이후 바뀐 사람의
 * 그룹만 다시 계산하고(증분), 아래 경우에는 지금처럼 전체를 다시 계산한다(안전망).
 * <ul>
 *   <li>기준 시각이 없다 — 처음 실행이라 무엇이 바뀌었는지 모른다</li>
 *   <li>배치 기준일이 바뀌었다 — 자정에는 거래가 안 바뀌어도 새 날짜의 결산 행이 필요하다</li>
 *   <li>마지막 전체 재계산 뒤 {@link #FULL_INTERVAL} 이 지났다 — 거래 삭제처럼 updated_at 으로 못 잡는 변경을 바로잡는다</li>
 * </ul>
 * 증분이든 전체든 계산 방식은 같다(같은 SQL, 같은 묶음, 같은 격리수준). 고르는 대상만 다르다.
 * DEV 수동 트리거는 지금처럼 {@link #evaluateActiveGroups}(전체)를 부른다.
 */
@Service
@Log4j2
public class GroupChallengeEvaluationBatchService {

    /**
     * [perf-ts] 한 트랜잭션에서 함께 평가하는 그룹 수.
     * 그룹마다 문장 5개와 커밋 1번을 보내던 것을 묶음마다 한 번으로 줄인다.
     * 너무 크게 잡으면 트랜잭션이 길어지고 실패 시 다시 도는 범위가 넓어진다.
     */
    static final int CHUNK_SIZE = 100;

    /** [perf-ts] tbl_batch_watermark 의 행 이름 */
    static final String WATERMARK_NAME = "group-challenge-evaluation";
    /**
     * [perf-ts] 기준 시각을 이만큼 겹쳐 읽는다. updated_at 은 커밋 시각이 아니라 문장 실행 시각이라,
     * 지난 실행이 읽은 뒤에 커밋된 행은 지난 시작 시각보다 이른 시각을 달고 있다. 동기화 트랜잭션은
     * 사용자 한 명의 거래를 저장하는 동안만 열려 있어 2분이면 충분하다고 본다(추정, 미측정).
     */
    static final Duration OVERLAP = Duration.ofMinutes(2);
    /** [perf-ts] 전체 재계산(안전망) 주기 */
    static final Duration FULL_INTERVAL = Duration.ofHours(1);

    private final ChallengeGroupMapper challengeGroupMapper;
    private final GroupChallengeEvaluationService evaluationService;
    private final GroupEvaluationWatermarkMapper watermarkMapper;

    public GroupChallengeEvaluationBatchService(ChallengeGroupMapper challengeGroupMapper,
                                                GroupChallengeEvaluationService evaluationService,
                                                GroupEvaluationWatermarkMapper watermarkMapper) {
        this.challengeGroupMapper = challengeGroupMapper;
        this.evaluationService = evaluationService;
        this.watermarkMapper = watermarkMapper;
    }

    /**
     * [perf-ts] 스케줄러 진입점. 증분으로 돌지 전체로 돌지 정하고, 다 돈 뒤에 기준 시각을 옮긴다.
     *
     * <p>기준 시각은 <b>계산이 끝난 뒤</b>에 저장한다. 중간에 예외로 빠지면 저장되지 않아 다음 실행이
     * 같은 구간을 다시 본다. 다시 계산해도 결산은 UPSERT, 기소는 uk_ind_result 라 결과가 같다.
     *
     * @return 이번 실행에서 만든 기소 수
     */
    public int runScheduled(LocalDate today) {
        long started = System.nanoTime();   // 대상 고르기까지 포함해 잰다
        LocalDateTime startedAt = watermarkMapper.now();
        GroupEvaluationWatermark mark = watermarkMapper.find(WATERMARK_NAME);
        String reason = fullReason(mark, startedAt, today);

        if (reason != null) {
            int indicted = evaluateGroups(findActiveGroups(today), today, "전체(" + reason + ")", started);
            watermarkMapper.save(WATERMARK_NAME, startedAt, startedAt, today);
            return indicted;
        }

        LocalDateTime since = mark.getLastStartedAt().minus(OVERLAP);
        Set<Long> changed = new HashSet<>(
                challengeGroupMapper.findChangedGroupIds(since, today, today.minusDays(1)));
        List<ChallengeGroup> targets = findActiveGroups(today).stream()
                .filter(g -> changed.contains(g.getId()))
                .collect(Collectors.toList());
        int indicted = evaluateGroups(targets, today, "증분(since=" + since + ")", started);
        watermarkMapper.save(WATERMARK_NAME, startedAt, mark.getLastFullAt(), mark.getLastFullDate());
        return indicted;
    }

    /** 전체 재계산을 해야 하면 그 이유, 증분이면 NULL */
    private static String fullReason(GroupEvaluationWatermark mark, LocalDateTime now, LocalDate today) {
        if (mark == null) {
            return "기준 시각 없음";
        }
        if (!today.equals(mark.getLastFullDate())) {
            return "날짜 바뀜";
        }
        if (!mark.getLastFullAt().plus(FULL_INTERVAL).isAfter(now)) {
            return "주기 도래";
        }
        return null;
    }

    /**
     * 진행 중인 그룹을 전부 평가한다.
     *
     * @param today 배치 기준일. 시스템 시각을 SQL 이 아니라 호출부가 정한다 —
     *              그래야 테스트가 고정 Clock 으로 날짜를 바꿔 끼울 수 있다
     * @return 이번 실행에서 만든 기소 수
     */
    public int evaluateActiveGroups(LocalDate today) {
        return evaluateGroups(findActiveGroups(today), today, "전체(수동)", System.nanoTime());
    }

    private List<ChallengeGroup> findActiveGroups(LocalDate today) {
        return challengeGroupMapper.findGroupsToEvaluate(
                ChallengeGroupStatus.ACTIVE.name(), today, today.minusDays(1));
    }

    /** 주어진 그룹을 묶음 단위로 평가한다. 증분과 전체가 같은 길을 탄다 */
    private int evaluateGroups(List<ChallengeGroup> groups, LocalDate today, String mode, long started) {
        // [perf-ts] started 부터 끝날 때까지를 배치 1회 시간(elapsedMs)으로 로그에 남긴다
        if (groups.isEmpty()) {
            log.info("그룹 챌린지 평가 배치 완료 mode={} today={} 대상=0 기소=0 elapsedMs={}",
                    mode, today, (System.nanoTime() - started) / 1_000_000);
            return 0;
        }

        int indicted = 0;
        for (int from = 0; from < groups.size(); from += CHUNK_SIZE) {
            List<ChallengeGroup> chunk = groups.subList(from, Math.min(from + CHUNK_SIZE, groups.size()));
            try {
                indicted += evaluationService.evaluateChunk(chunk, today);
            } catch (RuntimeException e) {
                /*
                 * 묶음 하나가 실패하면 그 묶음만 원래 방식(그룹 하나 = 트랜잭션 하나)으로 다시 돈다.
                 * 묶음 트랜잭션은 통째로 롤백됐고, 다시 돌려도 결산은 UPSERT, 기소는 uk_ind_result 라
                 * 같은 결과가 된다. 그룹별 처리에서 한 그룹의 실패가 나머지를 막지 않는 것은 그대로다.
                 */
                log.warn("그룹 챌린지 묶음 평가 실패, 그룹별로 다시 평가한다 groupIds={}~{}",
                        chunk.get(0).getId(), chunk.get(chunk.size() - 1).getId(), e);
                for (ChallengeGroup group : chunk) {
                    try {
                        indicted += evaluationService.evaluate(group, today);
                    } catch (RuntimeException ex) {
                        log.error("그룹 챌린지 평가 실패 groupId={}", group.getId(), ex);
                    }
                }
            }
        }
        log.info("그룹 챌린지 평가 배치 완료 mode={} today={} 대상={} 기소={} elapsedMs={}",
                mode, today, groups.size(), indicted, (System.nanoTime() - started) / 1_000_000);
        return indicted;
    }
}
