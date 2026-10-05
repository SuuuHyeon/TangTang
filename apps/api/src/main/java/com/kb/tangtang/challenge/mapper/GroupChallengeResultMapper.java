package com.kb.tangtang.challenge.mapper;

import com.kb.tangtang.challenge.domain.GroupChallengeDailyResult;
import com.kb.tangtang.challenge.domain.GroupFinalizeRow;
import com.kb.tangtang.challenge.domain.GroupMemberConsumptionRow;
import com.kb.tangtang.challenge.domain.GroupRankingRow;
import com.kb.tangtang.challenge.domain.IndictmentTarget;
import com.kb.tangtang.challenge.domain.TrialTransactionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code tbl_group_challenge_daily_result} 접근. 평가·기소 배치(#168)가 쓴다.
 *
 * {@code @Mapper} 가 없으면 등록되지 않는다 — RootConfig 가
 * {@code @MapperScan(annotationClass = Mapper.class)} 로 제한돼 있다.
 */
@Mapper
public interface GroupChallengeResultMapper {

    /**
     * 그룹 전원의 하루치 소비를 다시 집계해 UPSERT 한다. 한 번에 그룹 전체를 처리한다.
     *
     * <p><b>{@code verdict_deduction_amount} 는 절대 건드리지 않는다.</b> UPDATE 절에 넣는 순간
     * 무죄 판결로 인정된 감액이 5분마다 0 으로 초기화되고, 화면에는 아무 흔적도 남지 않는다.
     * ({@code db/migration/20260814_group_challenge_verdict_deduction.sql})
     *
     * <p>거래가 없는 참여자도 0원 행이 생긴다. 랭킹이 참여자 전원을 보여야 하기 때문이다.
     *
     * @param challengeDate 집계 대상 날짜. 호출부가 챌린지 기간 안인지 먼저 확인한다
     * @return 영향받은 행 수. ON DUPLICATE KEY UPDATE 라 삽입 1 · 갱신 2 로 세어져 의미가 없다
     */
    int upsertDailyResults(@Param("groupId") Long groupId,
                           @Param("challengeDate") LocalDate challengeDate);

    /**
     * 일일평가(DAILY) 기소 대상. 그 날 {@code effective_amount} 가 한도를 넘었고 아직 기소가 없는 참여자.
     *
     * <p>비교 대상이 {@code daily_amount} 가 아니라 {@code effective_amount} 인 것이 중요하다.
     * 무죄로 감액을 받은 참여자가 그 감액 덕에 한도 이하가 됐다면 다시 기소하지 않는다.
     *
     * <p>이미 기소된 행을 걸러 내는 것은 <b>성능을 위한 것이지 정합성 장치가 아니다.</b>
     * 조회와 INSERT 사이에 다른 인스턴스가 끼어들 수 있어, 중복을 실제로 막는 것은
     * {@code uk_ind_result} UNIQUE 다.
     */
    List<IndictmentTarget> findOverLimitDaily(@Param("groupId") Long groupId,
                                              @Param("challengeDate") LocalDate challengeDate);

    /**
     * 기간평가(PERIOD) 기소 대상. 기간 전체 소비액이 한도를 넘긴 참여자.
     *
     * <p><b>호출 시점을 호출부가 통제한다.</b> 챌린지가 끝나기 전에는 부르지 않는다
     * ({@code today > end_date} 일 때만). 기간 도중 합계가 한도를 넘었다고 기소하면
     * "기간 전체를 보고 판단한다" 는 기간평가의 정의가 무너진다.
     *
     * <p>기소는 {@code end_date} 행에 붙인다. 어느 날의 거래가 한도를 넘겼는지 특정할 수 없고,
     * 변론 화면도 이 날짜가 아니라 그룹의 {@code start_date ~ end_date} 로 거래를 조회한다.
     *
     * <p>소비액 식은 {@code GroupChallengeResultMapper.xml} 의
     * 「★ 기간평가(PERIOD) 소비액 공식」 주석이 소유한다 —
     * {@code GREATEST(SUM(daily_amount - verdict_deduction_amount), 0)}.
     * 무죄 감액을 뺀 뒤 비교하므로 일일평가가 {@code effective_amount} 로 비교하는 것과 취지가 같다.
     * {@code SUM(effective_amount)} 로 바꾸면 환불 상쇄용 음수 행이 행별로 0 으로 깎여
     * <b>환불받은 소비로 기소된다.</b>
     */
    List<IndictmentTarget> findOverLimitPeriod(@Param("groupId") Long groupId);

    /**
     * {@code verdict_deduction_amount > daily_amount} 인 이상 행.
     *
     * <p>{@code effective_amount} 가 {@code GREATEST(..., 0)} 이라 이 상태가 화면에서는
     * 그냥 0원으로 보인다. 환불·카테고리 재분류로 집계액이 줄면 실제로 생기는 상태이므로
     * 배치가 매 틱 확인해 로그로 남긴다. 마이그레이션이 배치에 요구한 사항이다.
     * ({@code db/migration/20260814_group_challenge_verdict_deduction.sql})
     *
     * <p>고치지는 않는다. 원본 두 컬럼이 그대로 남아 있어 언제든 되짚을 수 있고,
     * 배치가 판결 컬럼을 손대는 순간 위의 소유권 분리가 깨진다.
     *
     * <p><b>일일평가 그룹만 검사한다(이슈 #170).</b> 기간평가는 기간 전체의 감액을 기소가 붙은
     * {@code end_date} 행 한 줄에 적으므로 {@code verdict_deduction_amount > daily_amount} 가
     * 정상이다. 검사에 포함하면 정상 데이터에서 경고가 떠 로그를 못 믿게 된다.
     */
    List<GroupChallengeDailyResult> findDeductionOverflow(@Param("groupId") Long groupId);

    /**
     * 그룹 상세 화면이 쓰는 참여자별 소비액 (이슈 #169). 배치가 아니라 조회용이다.
     *
     * <p>평가 주기에 맞춰 기준이 갈린다 — 일일평가는 {@code challengeDate} 하루치
     * {@code effective_amount}, 기간평가는 「★ 기간평가(PERIOD) 소비액 공식」 주석의 식이다.
     * 이 두 갈래는 {@code IndictmentMapper.xml} 의 {@code periodConsumption} 조각과 같은 계산이어야
     * 한다. 어긋나면 <b>「초과 아님」으로 보이는 참여자가 재판에 올라가 있는</b> 화면이 나온다.
     *
     * <p>집계 행이 없는 참여자도 0원으로 나온다. 카드가 통째로 빠지면 인원이 모자라 보인다.
     *
     * @param challengeDate 일일평가의 기준 날짜(보통 오늘). 기간평가에서는 쓰이지 않는다
     */
    List<GroupMemberConsumptionRow> findMemberConsumption(@Param("groupId") Long groupId,
                                                          @Param("challengeDate") LocalDate challengeDate);

    /**
     * 변론 화면의 결산 구간 거래 목록 (이슈 #170). 피고 본인에게만 내려간다.
     *
     * <p><b>기소를 발화시킨 거래 1건이 아니라 구간 전체다.</b> 한도 초과는 누적으로 판정되므로
     * 발화 거래가 문제의 거래라는 보장이 없다 — 친구들 몫까지 대납한 큰 거래는 그 시점에 한도
     * 미달이어서 기소를 만들지 않고, 뒤따르는 평범한 소비가 기소를 만든다.
     *
     * <p><b>조건이 집계 배치와 같아야 한다.</b> {@link #upsertDailyResults} 와 같은
     * {@code consumptionFilter} 조각을 쓰고 환불도 같은 규칙으로 음수화한다. 어긋나면
     * 화면의 「합계 vs 한도」 계산이 무너지고 사용자가 기소 이유를 확인할 수 없게 된다.
     *
     * <p>분류 전 거래({@code category_id} NULL)는 카테고리 챌린지 목록에서 빠진다.
     * 집계도 같이 빠지므로 합계와는 여전히 맞는다.
     *
     * @param fromDate 일일결산은 위반 날짜, 기간결산은 그룹 {@code start_date}
     * @param toDate   일일결산은 {@code fromDate} 와 같은 날, 기간결산은 그룹 {@code end_date}
     */
    List<TrialTransactionRow> findTrialTransactions(@Param("groupId") Long groupId,
                                                    @Param("userId") Long userId,
                                                    @Param("fromDate") LocalDate fromDate,
                                                    @Param("toDate") LocalDate toDate);

    /**
     * 무죄 확정 — 인정된 감액을 결산 행에 반영한다 (이슈 #172).
     *
     * <p>대입이 아니라 <b>누적</b>({@code = verdict_deduction_amount + #{amount}})이다. 기간평가는
     * 기간 전체의 기소가 {@code end_date} 행 한 줄에 붙고, 일일평가도 같은 행이 다시 기소될 수
     * 있다 — 대입으로 쓰면 앞선 무죄의 감액이 사라진다.
     *
     * <p><b>{@code effective_amount} 는 건드리지 않는다.</b> STORED 생성 컬럼이라 UPDATE 절에
     * 넣으면 SQL 이 죽는다. 이 한 줄이 바뀌면 DB 가 알아서 다시 계산한다.
     * ({@code db/migration/20260814_group_challenge_verdict_deduction.sql})
     *
     * <p>감액이 집계액을 넘어서는 상태는 막지 않는다 — 환불·카테고리 재분류로 실제로 생기고,
     * {@code effective_amount} 의 {@code GREATEST(..., 0)} 가 0 으로 받아 준다.
     * {@link #findDeductionOverflow} 가 그 상태를 로그로 남긴다.
     *
     * @param resultId {@code tbl_indictment.result_id}. 기소가 붙어 있던 그 행이다
     * @return 바꾼 행 수. 결산 행이 지워졌으면 0 이다(정상 경로에서는 일어나지 않는다)
     */
    int addVerdictDeduction(@Param("resultId") Long resultId,
                            @Param("amount") BigDecimal amount);

    /**
     * 최종 확정 배치가 볼 참여자별 판정 재료 (이슈 #172). 그룹 하나당 한 번 호출한다.
     *
     * <p><b>소비액 식을 평가 주기별로 가르는 것이 핵심이다.</b> 기간평가는
     * 「★ 기간평가(PERIOD) 소비액 공식」 주석의 식({@code GREATEST(SUM(daily_amount -
     * verdict_deduction_amount), 0)})을 그대로 쓴다. {@code SUM(effective_amount)} 로 바꾸면
     * 행마다 걸린 {@code GREATEST(...,0)} 가 환불 음수 행을 0 으로 깎아 합계가 부풀고,
     * <b>기소를 만든 {@link #findOverLimitPeriod} 와 값이 달라진다</b> — 「한도 초과로 기소됐는데
     * 최종 판정은 완주」 같은 상태가 생긴다.
     *
     * <p>일일평가는 반대로 {@code SUM(effective_amount)} 가 맞다. 하루씩 따로 판정하는 평가라
     * 클램프도 하루 단위로 걸려야 한다. 어차피 탈락 여부는 목숨으로 정해지고 이 값은
     * 순위·표시용이다.
     *
     * <p>집계 행이 하나도 없는 참여자도 0 으로 내려온다 — 스칼라 서브쿼리 + {@code COALESCE} 다.
     * 빠지면 그 사람의 {@code final_*} 가 영영 NULL 로 남는다.
     *
     * @param groupId 확정할 그룹
     * @return 참여자 전원. {@code user_id} 오름차순
     */
    List<GroupFinalizeRow> findFinalConsumption(@Param("groupId") Long groupId);

    /**
     * 명예 법정 랭킹 — <b>진행 중</b> 그룹용 (이슈 #173). {@code RANK()} 로 그 자리에서 순위를 만든다.
     *
     * <p>정렬은 요구사항정의서 6.6 을 따른다 — 일일평가는 남은 목숨 내림차순 → 누적 소비액
     * 오름차순, 기간평가는 누적 소비액 오름차순. 동률은 공동 순위(1, 1, 3)다.
     *
     * <p>소비액 두 갈래는 {@link #findFinalConsumption} 과 같은 식이다. 기간평가는
     * 「★ 기간평가(PERIOD) 소비액 공식」 주석의 식, 일일평가는 {@code SUM(effective_amount)}.
     * 어긋나면 진행 중 순위와 종료 후 확정 순위가 마지막 날 밤사이 이유 없이 뒤바뀐다.
     *
     * @param evalType 정렬 분기용. 호출부가 이미 읽은 그룹의 {@code eval_type} 을 넘긴다 —
     *                 SQL 이 다시 읽으면 정렬 기준의 판단 주체가 둘이 된다
     */
    List<GroupRankingRow> findRankingActive(@Param("groupId") Long groupId,
                                            @Param("evalType") String evalType);

    /**
     * 명예 법정 랭킹 — <b>종료(CLOSED)</b> 그룹용 (이슈 #173).
     *
     * <p><b>순위를 다시 계산하지 않는다.</b> {@code final_rank}·{@code final_outcome}·
     * {@code final_charge_amount} 는 확정 배치(#172)가 write-once 로 남긴 값이고, 여기서
     * 재계산하면 종료 후 환불·재분류로 소비액이 바뀔 때마다 이미 보여 준 순위가 뒤집힌다.
     * 이 계약을 SQL 로 보증하기 위해 이 구문에는 <b>윈도우 함수 자체가 없다</b> —
     * {@code ChallengeMapperXmlTest} 가 모양을 검사한다.
     *
     * <p>누적 소비액은 표시 전용으로 함께 내려간다({@code totalConsumption}).
     */
    List<GroupRankingRow> findRankingClosed(@Param("groupId") Long groupId);

    /**
     * 마지막으로 결산이 끝난 날짜 (이슈 #173). 일일평가 명예 법정의 「{날짜} 결산 반영」 부제용.
     *
     * <p>오늘 행은 5분 배치가 재집계하는 중이라 제외한다 — {@code challenge_date < today}.
     * 결산 행이 아직 없으면(첫날 등) NULL 이고, 화면이 날짜 부분을 생략한다.
     *
     * @param today 오늘. 서비스가 {@code Clock} 으로 만든다 — SQL 의 {@code CURDATE()} 를 쓰면
     *              테스트에서 시간을 고정할 수 없다
     */
    LocalDate findLastSettlementDate(@Param("groupId") Long groupId,
                                     @Param("today") LocalDate today);

    /* ── [perf-ts] 평가 배치 묶음 처리 ─────────────────────────────────────
     * 아래 세 메서드는 위 upsertDailyResults · findOverLimitDaily · findDeductionOverflow 와
     * SQL 이 같고 WHERE 의 그룹 조건만 IN 으로 넓힌 것이다. 그룹마다 문장을 보내던 것을
     * 그룹 묶음당 한 번으로 줄이려고 둔다(GroupChallengeEvaluationService#evaluateChunk).
     */
    int upsertDailyResultsForGroups(@Param("groupIds") List<Long> groupIds,
                                    @Param("challengeDate") LocalDate challengeDate);

    List<IndictmentTarget> findOverLimitDailyForGroups(@Param("groupIds") List<Long> groupIds,
                                                       @Param("challengeDate") LocalDate challengeDate);

    List<GroupChallengeDailyResult> findDeductionOverflowForGroups(@Param("groupIds") List<Long> groupIds);
}
