package com.kb.tangtang.challenge.mapper;

import com.kb.tangtang.challenge.domain.ChallengeGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code tbl_challenge_group} 접근.
 *
 * {@code @Mapper} 가 없으면 등록되지 않는다 — RootConfig 가
 * {@code @MapperScan(annotationClass = Mapper.class)} 로 제한돼 있다.
 */
@Mapper
public interface ChallengeGroupMapper {

    /** 생성. 채번된 id 는 {@code group.id} 에 채워진다. */
    int insertGroup(ChallengeGroup group);

    ChallengeGroup findById(@Param("groupId") Long groupId);

    ChallengeGroup findByInviteCode(@Param("inviteCode") String inviteCode);

    /**
     * 로그인 사용자가 참여 중인 그룹 목록.
     *
     * 화면의 「종료됨」 탭이 JUDGING·CLOSED 두 상태를 함께 보여줘야 해서 목록으로 받는다.
     *
     * @param statuses NULL 또는 빈 목록이면 전체
     */
    List<ChallengeGroup> findMyGroups(@Param("userId") Long userId,
                                      @Param("statuses") List<String> statuses);

    int countByInviteCode(@Param("inviteCode") String inviteCode);

    /**
     * 시작일이 도래했는데 아직 넘겨받은 상태에 머물러 있는 그룹. 상태 전이 배치(이슈 #152)가 쓴다.
     *
     * {@code idx_cg_status_start (status, start_date)} 를 그대로 탄다 — 선행 컬럼이 상등 비교,
     * 후행 컬럼이 범위 비교라 인덱스 순서와 조건 모양이 맞는다.
     *
     * @param status 찾을 상태. 값을 밖에서 넘겨 {@code ChallengeGroupStatus} 를 유일한 출처로 둔다
     * @param today  배치 기준일. 시스템 시각을 SQL(CURDATE) 이 아니라 애플리케이션이 정한다 —
     *               그래야 테스트가 고정 Clock 으로 날짜를 바꿔 끼울 수 있다
     */
    List<ChallengeGroup> findGroupsToStart(@Param("status") String status,
                                           @Param("today") LocalDate today);

    /**
     * 평가·기소 배치(이슈 #168)가 이번 틱에 볼 그룹.
     *
     * <b>어제가 조건에 들어가는 이유가 이 메서드의 핵심이다.</b> 배치는 매 틱 어제·오늘 두 날짜를
     * 집계한다. 거래 동기화가 뒤늦게 도착하기 때문이다 — 23:57 결제는 그 시각의 배치가 볼 때
     * 아직 DB 에 없고, 동기화가 끝난 00:10 시점의 배치는 이미 기준일이 오늘로 넘어가 있다.
     * 어제를 같이 보지 않으면 <b>그 결제가 어제 집계에 영원히 반영되지 않는다.</b>
     * ({@code tbl_transaction.tr_date} 는 거래 발생일이라 수집 시각과 무관하게 어제로 남는다)
     *
     * 그래서 종료 조건도 {@code end_date >= 어제} 다. {@code end_date >= 오늘} 로 좁히면
     * <b>챌린지 마지막 날의 심야 거래가 통째로 누락된다</b> — 그 거래를 처리해야 할 다음 날에는
     * 그룹이 조회에서 빠져 버리기 때문이다.
     *
     * 부수 효과로 이 조건은 {@code end_date + 1일} 까지만 그룹을 잡아 준다. 기간평가(PERIOD)
     * 기소를 판정할 수 있는 마지막 날과 정확히 일치한다.
     *
     * {@code idx_cg_status_start (status, start_date)} 를 탄다 — 선행 컬럼이 상등 비교,
     * 후행 컬럼이 범위 비교라 인덱스 순서와 조건 모양이 맞는다. {@code end_date} 는 걸러 낸 뒤
     * 평가하며, ACTIVE 그룹 수 자체가 작아 문제되지 않는다.
     *
     * @param status    찾을 상태. 보통 {@code ACTIVE}
     * @param today     배치 기준일
     * @param yesterday {@code today} 의 전날. 호출부가 계산해 넘긴다 —
     *                  SQL 안에서 {@code DATE_SUB(CURDATE(), ...)} 로 만들면
     *                  테스트가 날짜를 고정할 수 없다
     */
    List<ChallengeGroup> findGroupsToEvaluate(@Param("status") String status,
                                              @Param("today") LocalDate today,
                                              @Param("yesterday") LocalDate yesterday);

    /**
     * [perf-ts] 평가 배치 증분 처리: {@code since} 이후 결산이 달라졌을 수 있는 그룹 id.
     *
     * <p>세 경우를 합친다. 진행 중인지는 여기서 거르지 않는다 — 호출부가
     * {@link #findGroupsToEvaluate} 결과와 겹치는 그룹만 계산한다.
     * <ul>
     *   <li>참여자의 어제·오늘 거래가 바뀌었다 (동기화의 새 결제, LLM 카테고리 변경, 환불·집계 제외)</li>
     *   <li>참여자가 늘었다 — 시작일 참여(#350)는 이미 저장된 거래를 가진 채 들어온다</li>
     *   <li>그룹 행이 바뀌었다 — 시작(ACTIVE 전이) 등</li>
     * </ul>
     * 거래 삭제와 자정에 새로 필요한 결산 행은 여기서 잡히지 않는다. 배치가 주기적으로 도는 전체 재계산이 맡는다.
     */
    List<Long> findChangedGroupIds(@Param("since") LocalDateTime since,
                                   @Param("today") LocalDate today,
                                   @Param("yesterday") LocalDate yesterday);

    /**
     * 평가가 끝나 재판(JUDGING)으로 넘길 그룹 (이슈 #169).
     *
     * <b>{@code end_date} 가 지났다고 바로 넘기면 안 된다.</b> 평가·기소 배치는
     * {@link #findGroupsToEvaluate} 조건대로 <b>종료 다음 날까지</b> 그 그룹을 본다 —
     * 마지막 날 심야 거래와 기간평가(PERIOD) 기소가 그날 판정되기 때문이다. 그런데 그 조회는
     * {@code status = 'ACTIVE'} 를 함께 걸고 있어, 종료 다음 날 자정에 JUDGING 으로 바꿔 버리면
     * <b>마지막 날치 기소가 통째로 생기지 않는다.</b>
     *
     * 그래서 호출부가 {@code today - 1일} 을 넘기고 여기서 {@code end_date < 그 날짜} 로 거른다.
     * 결과적으로 전이는 <b>종료 다음다음 날</b> 자정에 일어난다.
     *
     * @param status      찾을 상태. 보통 {@code ACTIVE}
     * @param endedBefore 이 날짜보다 앞서 끝난 그룹만. 호출부가 {@code today.minusDays(1)} 을 넘긴다
     */
    List<ChallengeGroup> findGroupsToJudge(@Param("status") String status,
                                           @Param("endedBefore") LocalDate endedBefore);

    /**
     * 최종 결과를 확정할 그룹 (이슈 #172). {@code JUDGING → CLOSED}.
     *
     * <p><b>「미확정 재판이 없는 그룹만」이 이 조회의 전부다.</b> 재판이 아직 {@code DEFENSE_WAIT}
     * 나 {@code VOTING} 으로 남아 있는데 확정해 버리면, 그 재판이 유죄로 끝났을 때 깎였어야 할
     * 목숨이 순위에 반영되지 못한다. {@code final_*} 3개는 write-once 라 되돌릴 수도 없다.
     *
     * <p>기다리는 시간에 상한을 두지 않는다. 개표 배치가 마감 경과분을 1분마다 집어 가므로
     * 남아 있는 재판은 반드시 끝난다 — 상한을 두면 「끝났지만 반영되지 않은 재판」이 생긴다.
     *
     * <p>날짜 조건이 없는 이유는 {@code JUDGING} 자체가 이미 종료를 뜻하기 때문이다.
     * {@link #findGroupsToJudge} 가 {@code end_date} 를 보고 넘겨준 그룹만 이 상태에 있다.
     *
     * @param status 찾을 상태. 보통 {@code JUDGING}
     */
    List<ChallengeGroup> findGroupsToClose(@Param("status") String status);

    /**
     * 기대한 상태일 때만 상태를 바꾼다(compare-and-set).
     *
     * <b>배치 멱등성의 근거가 이 반환값이다.</b> 두 번째 실행이나 동시에 도는 인스턴스는 0 을 받고,
     * 그때 알림 발행을 건너뛴다. 조회 후 무조건 UPDATE 하면 배치를 두 번 돌릴 때
     * 같은 알림이 두 번 나간다.
     *
     * @return 바꾼 행 수. 0 이면 이미 다른 쪽이 바꿨다는 뜻이다
     */
    int updateStatusIfCurrent(@Param("groupId") Long groupId,
                              @Param("fromStatus") String fromStatus,
                              @Param("toStatus") String toStatus);

    /**
     * 기대한 상태일 때만 그룹을 지운다. 모집 미달로 시작하지 못한 그룹을 정리한다(이슈 #261).
     *
     * <b>{@code status} 조건이 없으면 정상 시작한 그룹을 지울 수 있다.</b> 배치는 조회와 처리가
     * 두 단계라, 조회 시점에는 참여자가 1명이었지만 그 사이 누가 들어와 다른 실행이 이미
     * {@code ACTIVE} 로 전이시켜 놓았을 수 있다. 그때 조건 없이 지우면 방금 시작된 멀쩡한 그룹이
     * 참여자·결산·재판까지 {@code ON DELETE CASCADE} 로 통째로 사라진다.
     * {@code updateStatusIfCurrent} 와 같은 compare-and-set 장치다.
     *
     * <p>중복 알림은 조건이 없어도 막힌다 — 행이 사라지므로 두 번째 실행은 어차피 0 을 받는다.
     * 조건절이 막는 것은 <b>되돌릴 수 없는 오삭제</b> 쪽이다.
     *
     * <p>자식 행({@code tbl_group_member} · {@code tbl_group_challenge_daily_result} ·
     * {@code tbl_indictment} · {@code tbl_vote})은 FK 의 {@code ON DELETE CASCADE} 가 정리한다.
     *
     * @return 지운 행 수. 0 이면 이미 다른 쪽이 처리했거나 그룹이 그 상태가 아니라는 뜻이다
     */
    int deleteIfCurrent(@Param("groupId") Long groupId,
                        @Param("status") String status);

    /**
     * 참여 판정을 위해 그룹 행을 잠근다 (이슈 #354). <b>저장소에서 유일한 {@code FOR UPDATE} 다.</b>
     *
     * <p>정원 판정이 「참여자 수를 읽고 → 비교하고 → INSERT」 세 단계라, 그 사이에 다른 참여가
     * 끼어들면 둘 다 「자리 있음」을 보고 둘 다 들어간다. PK {@code (group_id, user_id)} 는
     * <b>같은 사람의 중복만</b> 막고 정원은 못 막는다. 그룹 행 하나를 잠가 참여를 직렬화한다.
     *
     * <p><b>잠금 순서는 {@code tbl_challenge_group} → {@code tbl_group_member} 다.</b>
     * 반대로 잡는 곳을 만들면 데드락이 된다. 현재 그룹 챌린지의 쓰기 경로는 전부 이 순서다 —
     * {@code deleteIfCurrent}(그룹만) · {@code updateStatusIfCurrent}(그룹만) ·
     * {@code startOrCancel}(멤버는 잠금 없는 일반 SELECT 뒤 그룹 UPDATE).
     *
     * <p><b>카테고리를 조인하지 않는다.</b> 조인하면 {@code FOR UPDATE} 가 {@code tbl_category}
     * 행까지 잠근다 — 모든 그룹이 공유하는 참조 테이블이라 서로 무관한 참여끼리 막히게 된다.
     * 그래서 채워지는 것은 <b>판정에 필요한 4개뿐</b>이다:
     * {@code id} · {@code status} · {@code startDate} · {@code maxMembers}.
     * 응답 DTO 를 만드는 데 쓰지 말 것 — {@code categoryName} 이 NULL 이다.
     *
     * @return 잠근 그룹. {@code NULL} 이면 그 사이 미성립·방장 삭제로 사라졌다는 뜻이다
     */
    ChallengeGroup lockGroupForJoin(@Param("groupId") Long groupId);
}
