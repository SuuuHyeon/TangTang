package com.kb.tangtang.challenge.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 한도를 넘겨 기소해야 할 대상 하나. 평가 배치(#168)의 조회 결과 전용이다.
 *
 * <p><b>일일평가와 기간평가의 결과 모양을 하나로 맞추려고 둔다.</b> 두 평가는 집계 범위가 다르지만
 * ({@code effective_amount} 하루치 vs {@code SUM(daily_amount)} 기간 전체) 기소를 만드는 쪽이
 * 필요로 하는 것은 <b>어느 행에 붙일지(resultId) · 누구를(userId) · 언제(challengeDate) ·
 * 얼마를 썼는지(amount)</b> 넷이다. 모양이 같으면 기소 생성 코드가 갈라지지 않는다.
 *
 * <p>여기에 {@code nickname} 이 하나 더 붙는데, 판정에 쓰이지 않는 <b>표시 전용</b> 값이다
 * (이슈 #355의 채팅 시스템 메시지 문구). 조회에서 같이 뽑는 이유는 배치 루프 안에서 따로 읽으면
 * 기소 대상 수만큼 왕복이 늘기 때문이다 — 두 조회 모두 그룹당 한 번만 돈다.
 *
 * <p>{@code groupId} · {@code limitAmount} 는 담지 않는다 — 호출부가 이미
 * {@link ChallengeGroup} 을 손에 들고 루프를 돈다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndictmentTarget {

    /**
     * 기소를 붙일 {@code tbl_group_challenge_daily_result.id}.
     *
     * <p>일일평가는 위반한 날의 행, 기간평가는 {@code end_date} 행이다.
     * {@code tbl_indictment.result_id} 의 UNIQUE(uk_ind_result)가 여기에 걸린다.
     */
    private Long resultId;
    /** 묶음 조회(findOverLimitDailyForGroups)에서만 채워진다. 그룹별 조회에서는 NULL */
    private Long groupId;

    private Long userId;

    /** {@code resultId} 행의 날짜. 기간평가에서는 {@code end_date} 이며 "위반 날짜" 가 아니다. */
    private LocalDate challengeDate;

    /**
     * 한도를 넘긴 소비액.
     *
     * <p>일일평가는 그 날의 {@code effective_amount}, 기간평가는 기간 전체
     * {@code SUM(daily_amount)} 다. 알림·기소 사유 문구에 그대로 쓴다.
     */
    private BigDecimal amount;

    /**
     * 표시용 닉네임. 채팅 시스템 메시지 문구(「…님의 소비가 한도를 넘었어요.」)에만 쓴다.
     *
     * <p>닉네임을 정하지 않은 소셜 로그인 사용자는 {@code social_name} 이 내려온다
     * ({@code GroupChallengeResultMapper.xml} 의 {@code displayNickname} 조각).
     */
    private String nickname;
}
