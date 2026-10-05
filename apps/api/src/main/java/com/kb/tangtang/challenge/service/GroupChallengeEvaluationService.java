package com.kb.tangtang.challenge.service;

import com.kb.tangtang.challenge.domain.ChallengeGroup;
import com.kb.tangtang.challenge.domain.EvalType;
import com.kb.tangtang.challenge.domain.GroupChallengeDailyResult;
import com.kb.tangtang.challenge.domain.GroupTrialEvents;
import com.kb.tangtang.challenge.domain.Indictment;
import com.kb.tangtang.challenge.domain.IndictmentStatus;
import com.kb.tangtang.challenge.domain.IndictmentTarget;
import com.kb.tangtang.challenge.mapper.GroupChallengeResultMapper;
import com.kb.tangtang.challenge.mapper.IndictmentMapper;
import com.kb.tangtang.notification.domain.NotificationRequestedEvent;
import com.kb.tangtang.notification.domain.NotificationType;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 그룹 챌린지 한 건의 소비 집계 · 기소 (이슈 #168).
 *
 * <p>한 틱에 하는 일은 셋이다.
 * <ol>
 *   <li>어제·오늘 두 날짜의 소비를 다시 집계해 UPSERT</li>
 *   <li>감액이 집계액을 넘어선 이상 행을 로그로 남김</li>
 *   <li>한도를 넘긴 참여자를 기소하고 알림 · 그룹 채팅 시스템 메시지 발행</li>
 * </ol>
 *
 * <p><b>어제를 매번 다시 집계하는 것이 이 클래스의 핵심이다.</b> 거래 동기화가 뒤늦게 도착하기
 * 때문이다 — 23:57 결제는 그 시각의 배치가 볼 때 아직 DB 에 없고, 동기화가 끝난 00:10 시점의
 * 배치는 기준일이 이미 오늘로 넘어가 있다. 오늘만 보면 그 결제는 <b>영원히 집계되지 않는다.</b>
 * ({@code tbl_transaction.tr_date} 는 거래 발생일이라 수집이 늦어도 어제로 남는다)
 *
 * <p>시각으로 분기하지 않는다(예: "00~01시에만 어제를 본다"). 동기화가 한 시간 넘게 밀리면
 * 그 구멍이 그대로 되살아난다. 매 틱 두 날짜를 보는 편이 단순하고 빈틈이 없다 —
 * 그룹당 참여자가 최대 6명이라 비용도 무시할 만하다.
 *
 * <p><b>배치 본체와 클래스를 나눈 이유</b>는 트랜잭션을 그룹 하나마다 끊기 위해서다. 한 클래스
 * 안에서 부르면 자기호출이라 프록시를 타지 않아 {@code @Transactional} 이 걸리지 않고, 배치
 * 전체가 한 트랜잭션이 되면 마지막 그룹의 오류가 앞서 만든 기소까지 되돌린다.
 * ({@link ChallengeGroupStatusBatchService} → {@link ChallengeGroupStatusTransitionService} 와 같은 구조)
 */
@Service
@Log4j2
public class GroupChallengeEvaluationService {

    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("M월 d일");

    private final GroupChallengeResultMapper resultMapper;
    private final IndictmentMapper indictmentMapper;
    private final ApplicationEventPublisher events;

    public GroupChallengeEvaluationService(GroupChallengeResultMapper resultMapper,
                                           IndictmentMapper indictmentMapper,
                                           ApplicationEventPublisher events) {
        this.resultMapper = resultMapper;
        this.indictmentMapper = indictmentMapper;
        this.events = events;
    }

    /**
     * 그룹 하나를 평가한다.
     *
     * @param today 배치 기준일
     * @return 이번 틱에 실제로 만든 기소 수
     */
    @Transactional
    public int evaluate(ChallengeGroup group, LocalDate today) {
        List<LocalDate> dates = aggregationDates(group, today);
        for (LocalDate date : dates) {
            resultMapper.upsertDailyResults(group.getId(), date);
        }

        warnDeductionOverflow(group);

        int created = 0;
        for (IndictmentTarget target : findTargets(group, today, dates)) {
            if (indict(group, target)) {
                created++;
            }
        }
        return created;
    }

    /**
     * [perf-ts] 그룹 여러 개를 트랜잭션 하나에서 평가한다. 판정 규칙은 {@link #evaluate} 와 같다.
     *
     * <p>그룹마다 문장을 보내지 않고, 같은 SQL 을 그룹 묶음에 IN 조건으로 한 번씩 보낸다.
     * 기간평가 기소 대상만 그룹별로 본다 — 종료 다음 날 하루만 호출되는 경로라 그룹 수가 적다.
     *
     * <p><b>READ COMMITTED 로 실행한다.</b> REPEATABLE READ 에서 INSERT ... SELECT 는 원본
     * tbl_transaction 의 읽은 행과 간격에 공유 넥스트키 락을 건다. 묶음이 커져 트랜잭션이 길어지면
     * 그동안 오늘 결제 저장(INSERT)과 카테고리 갱신(UPDATE)이 기다리고, 카테고리 갱신과는
     * 교착까지 생긴다(perf-ts 측정). 매 틱 다시 집계하는 값이라 문장 단위 최신 읽기로 충분하다.
     *
     * @return 이번 묶음에서 만든 기소 수
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int evaluateChunk(List<ChallengeGroup> groups, LocalDate today) {
        Map<Long, ChallengeGroup> byId = new LinkedHashMap<>();
        for (ChallengeGroup group : groups) {
            byId.put(group.getId(), group);
        }

        /* 1. 날짜마다, 그 날짜가 기간 안에 드는 그룹만 모아 한 번에 재집계 */
        Map<LocalDate, List<Long>> idsByDate = new LinkedHashMap<>();
        for (LocalDate date : List.of(today.minusDays(1), today)) {
            List<Long> ids = new ArrayList<>();
            for (ChallengeGroup group : groups) {
                if (!date.isBefore(group.getStartDate()) && !date.isAfter(group.getEndDate())) {
                    ids.add(group.getId());
                }
            }
            if (!ids.isEmpty()) {
                resultMapper.upsertDailyResultsForGroups(ids, date);
                idsByDate.put(date, ids);
            }
        }

        /* 2. 감액 이상 행 경고 (evaluate 의 warnDeductionOverflow 와 같은 내용) */
        for (GroupChallengeDailyResult row : resultMapper.findDeductionOverflowForGroups(new ArrayList<>(byId.keySet()))) {
            log.warn("감액이 집계액을 넘었다 groupId={} userId={} date={} 집계={} 감액={}"
                            + " — 환불·카테고리 재분류로 집계액이 줄었을 수 있다",
                    row.getGroupId(), row.getUserId(), row.getChallengeDate(),
                    row.getDailyAmount(), row.getVerdictDeductionAmount());
        }

        int created = 0;

        /* 3-1. 일일평가: 방금 집계한 날짜를 묶음으로 다시 본다 */
        for (Map.Entry<LocalDate, List<Long>> entry : idsByDate.entrySet()) {
            List<Long> dailyIds = new ArrayList<>();
            for (Long id : entry.getValue()) {
                if (!EvalType.PERIOD.name().equals(byId.get(id).getEvalType())) {
                    dailyIds.add(id);
                }
            }
            if (dailyIds.isEmpty()) {
                continue;
            }
            for (IndictmentTarget target : resultMapper.findOverLimitDailyForGroups(dailyIds, entry.getKey())) {
                if (indict(byId.get(target.getGroupId()), target)) {
                    created++;
                }
            }
        }

        /* 3-2. 기간평가: 챌린지가 끝난 그룹만, 그룹별로 */
        for (ChallengeGroup group : groups) {
            if (EvalType.PERIOD.name().equals(group.getEvalType()) && today.isAfter(group.getEndDate())) {
                for (IndictmentTarget target : resultMapper.findOverLimitPeriod(group.getId())) {
                    if (indict(group, target)) {
                        created++;
                    }
                }
            }
        }
        return created;
    }

    /**
     * 이번 틱에 다시 집계할 날짜. 어제·오늘 중 챌린지 기간 안에 드는 것만 남긴다.
     *
     * <p>기간 밖 날짜를 집계하면 그 날 행이 새로 생겨 기간평가 합계가 부풀어 오른다.
     */
    private List<LocalDate> aggregationDates(ChallengeGroup group, LocalDate today) {
        List<LocalDate> dates = new ArrayList<>(2);
        for (LocalDate date : List.of(today.minusDays(1), today)) {
            if (!date.isBefore(group.getStartDate()) && !date.isAfter(group.getEndDate())) {
                dates.add(date);
            }
        }
        return dates;
    }

    /**
     * 기소 대상 조회.
     *
     * <p>일일평가는 방금 집계한 날짜를 그대로 다시 본다 — 어제 행이 이번 틱에 처음 한도를 넘겼을 수
     * 있어서다. 기간평가는 <b>챌린지가 끝난 뒤에만</b> 본다. 기간 도중 합계가 한도를 넘었다고
     * 기소하면 "기간 전체를 보고 판단한다" 는 기간평가의 정의가 무너진다.
     */
    private List<IndictmentTarget> findTargets(ChallengeGroup group, LocalDate today, List<LocalDate> dates) {
        if (EvalType.PERIOD.name().equals(group.getEvalType())) {
            return today.isAfter(group.getEndDate())
                    ? resultMapper.findOverLimitPeriod(group.getId())
                    : List.of();
        }

        List<IndictmentTarget> targets = new ArrayList<>();
        for (LocalDate date : dates) {
            targets.addAll(resultMapper.findOverLimitDaily(group.getId(), date));
        }
        return targets;
    }

    /**
     * 기소 한 건 생성 + 알림 + 그룹 채팅 시스템 메시지.
     *
     * <p>{@code DuplicateKeyException} 은 <b>정상 경로</b>다. 조회와 INSERT 사이에 다른 인스턴스가
     * 같은 행을 기소했다는 뜻이므로 알림·채팅까지 통째로 건너뛴다. 잡지 않으면 이 그룹의 트랜잭션이
     * 통째로 되돌아가 앞서 만든 기소도 사라진다. <b>채팅 메시지의 멱등성이 이 가드에 얹혀 있다</b> —
     * 배치를 몇 번 다시 돌려도 같은 기소로 카드가 두 번 뜨지 않는다(이슈 #355).
     *
     * <p>채팅 이벤트를 둘 다 쏘는 이유는 카드가 「적발 → 개시」 두 장으로 설계돼 있어서다
     * ({@code ChatSystemType.VIOLATION_DETECTED} · {@code TRIAL_OPENED}). 수신부는
     * {@code AFTER_COMMIT} 이라 이 트랜잭션이 롤백되면 채팅에도 남지 않는다.
     *
     * @return 실제로 기소를 만들었으면 {@code true}
     */
    private boolean indict(ChallengeGroup group, IndictmentTarget target) {
        String period = periodLabel(group, target);
        Indictment indictment = Indictment.builder()
                .resultId(target.getResultId())
                .groupId(group.getId())
                .userId(target.getUserId())
                .status(IndictmentStatus.DEFENSE_WAIT.name())
                .message(message(group, target, period))
                .build();

        try {
            indictmentMapper.insertIndictment(indictment);
        } catch (DuplicateKeyException e) {
            log.info("기소 건너뜀 groupId={} userId={} resultId={} — 이미 기소된 행이다",
                    group.getId(), target.getUserId(), target.getResultId());
            return false;
        }

        events.publishEvent(new NotificationRequestedEvent(
                target.getUserId(),
                NotificationType.GROUP_TRIAL_OPENED,
                Map.of("groupName", group.getGroupName(),
                        "period", period,
                        "amount", money(target.getAmount())),
                defenseLink(group, indictment)));

        events.publishEvent(new GroupTrialEvents.ViolationDetected(
                group.getId(), indictment.getId(), target.getNickname()));
        events.publishEvent(new GroupTrialEvents.TrialOpened(
                group.getId(), indictment.getId(), target.getNickname()));

        log.info("기소 생성 groupId={} userId={} resultId={} indictmentId={} 소비={} 한도={}",
                group.getId(), target.getUserId(), target.getResultId(),
                indictment.getId(), target.getAmount(), group.getLimitAmount());
        return true;
    }

    /**
     * {@code verdict_deduction_amount > daily_amount} 인 이상 행을 남긴다.
     *
     * <p>{@code effective_amount} 가 {@code GREATEST(..., 0)} 이라 이 상태는 화면에서 그냥 0원으로
     * 보인다. 고치지는 않는다 — 배치가 판결 컬럼을 손대는 순간 컬럼 소유권 분리가 깨진다.
     * ({@code db/migration/20260814_group_challenge_verdict_deduction.sql} 이 요구한 사항)
     */
    private void warnDeductionOverflow(ChallengeGroup group) {
        for (GroupChallengeDailyResult row : resultMapper.findDeductionOverflow(group.getId())) {
            log.warn("감액이 집계액을 넘었다 groupId={} userId={} date={} 집계={} 감액={}"
                            + " — 환불·카테고리 재분류로 집계액이 줄었을 수 있다",
                    group.getId(), row.getUserId(), row.getChallengeDate(),
                    row.getDailyAmount(), row.getVerdictDeductionAmount());
        }
    }

    /**
     * 알림 딥링크. 기소 직후 상태는 DEFENSE_WAIT 이고 알림을 받는 사람은 피고 본인이라
     * 재판 상세가 아니라 <b>변론 플로우의 첫 화면</b>으로 보낸다.
     * ({@code apps/web/src/router/index.js:187} — name {@code defenseViolation})
     */
    private String defenseLink(ChallengeGroup group, Indictment indictment) {
        return "/group-challenges/" + group.getId() + "/defense/" + indictment.getId();
    }

    /** 일일평가는 위반한 날 하루, 기간평가는 챌린지 기간 전체. */
    private String periodLabel(ChallengeGroup group, IndictmentTarget target) {
        if (EvalType.PERIOD.name().equals(group.getEvalType())) {
            return group.getStartDate().format(DATE_LABEL) + "~" + group.getEndDate().format(DATE_LABEL);
        }
        return target.getChallengeDate().format(DATE_LABEL);
    }

    /** {@code tbl_indictment.message} 는 VARCHAR(300). 아래 조합은 그 안에 넉넉히 들어간다. */
    private String message(ChallengeGroup group, IndictmentTarget target, String period) {
        String scope = group.getCategoryName() == null ? "전체 소비" : group.getCategoryName();
        return String.format("%s %s %s · 한도 %s 초과",
                period, scope, money(target.getAmount()), money(BigDecimal.valueOf(group.getLimitAmount())));
    }

    /** 원 단위 미만은 버린다. 표시용이고 판정은 이미 DB 에서 끝났다. */
    private String money(BigDecimal amount) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(amount) + "원";
    }
}
