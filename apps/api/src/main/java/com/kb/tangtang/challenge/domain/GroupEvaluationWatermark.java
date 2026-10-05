package com.kb.tangtang.challenge.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * [perf-ts] 평가 배치 증분 처리의 기준 시각 ({@code tbl_batch_watermark} 한 행).
 *
 * <p>시각은 전부 <b>DB 시계</b>로 잰다. 비교 대상인 {@code tbl_transaction.updated_at} 을 DB 가 채우기 때문에,
 * 앱 서버 시계를 섞으면 두 시계가 어긋난 만큼 변경을 놓친다.
 */
@Getter
@NoArgsConstructor
public class GroupEvaluationWatermark {

    /** 마지막으로 끝까지 돈 실행의 시작 시각. 다음 실행은 이 시각 - 겹침 이후의 변경을 본다 */
    private LocalDateTime lastStartedAt;
    /** 마지막 전체 재계산의 시작 시각 */
    private LocalDateTime lastFullAt;
    /** 마지막 전체 재계산의 배치 기준일. 기준일이 바뀌면(자정) 전체를 다시 돈다 */
    private LocalDate lastFullDate;
}
