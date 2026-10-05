package com.kb.tangtang.challenge.mapper;

import com.kb.tangtang.challenge.domain.GroupEvaluationWatermark;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * [perf-ts] {@code tbl_batch_watermark} 접근. 평가 배치 증분 처리의 기준 시각을 읽고 쓴다.
 */
@Mapper
public interface GroupEvaluationWatermarkMapper {

    /** DB 시계의 현재 시각. updated_at 과 같은 시계로 기준 시각을 잡기 위해 쓴다 */
    LocalDateTime now();

    /** 없으면 NULL — 처음 실행이라 무엇이 바뀌었는지 알 수 없다 */
    GroupEvaluationWatermark find(@Param("batchName") String batchName);

    int save(@Param("batchName") String batchName,
             @Param("lastStartedAt") LocalDateTime lastStartedAt,
             @Param("lastFullAt") LocalDateTime lastFullAt,
             @Param("lastFullDate") LocalDate lastFullDate);
}
