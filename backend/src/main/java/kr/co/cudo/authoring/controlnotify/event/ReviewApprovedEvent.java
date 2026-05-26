package kr.co.cudo.authoring.controlnotify.event;

import java.time.Instant;

/**
 * Phase 2 — 검수 완료 도메인 이벤트.
 *
 * <p>REVIEWER 가 검수를 APPROVED 처리하면 발행되어 outbound TASK_COMPLETED 통지를 트리거한다.
 *
 * @param rawSn      영상 단위 식별자 (LS_DATA_RAW.RAW_SN)
 * @param reviewerNo 검수자 번호
 * @param approvedAt 검수 완료 시각
 */
public record ReviewApprovedEvent(
        Long rawSn,
        Long reviewerNo,
        Instant approvedAt
) {}
