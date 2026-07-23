package kr.co.cudo.authoring.assignment.dto;

import java.time.LocalDateTime;

/**
 * SCR-TASK-001 REVIEWER 통합 작업 목록 응답 — 처리 완료 영상 + (optional) LABELER 배정.
 *
 * <p>BE 가 LEFT JOIN 결과를 그대로 노출하는 응답 전용 DTO. task 가 미배정인 행은 task 측 필드가
 * 모두 null 이며, status 는 'UNASSIGNED' 로 폴백된다.
 *
 * <p>Mass Assignment 방어 (CWE-915): Entity 직접 노출 금지 — 응답 전용 record DTO 로 한정.
 *
 * <p>FE Task.status (AssignmentStatus) 매핑:
 * <ul>
 *   <li>LS_RAW_DATA_STATUS row 없음 + LABELER 배정 없음 = "UNASSIGNED"</li>
 *   <li>LS_RAW_DATA_STATUS row 없음 + LABELER 배정 있음 = "PENDING"</li>
 *   <li>ASSIGNED  = "PENDING"</li>
 *   <li>PENDING   = "REVIEW_PENDING"</li>
 *   <li>IN_REVIEW = "REVIEW_PENDING"</li>
 *   <li>APPROVED  = "COMPLETED"</li>
 *   <li>REJECTED  = "REJECTED"</li>
 * </ul>
 */
public record TaskBoardItemResponse(
        Long videoId,
        String cctvName,
        String eventName,
        String eventTypeCd,
        Long frameCount,
        LocalDateTime capturedAt,
        String batchStatus,
        String status,
        Long assignmentId,
        Long workerId,
        String workerName,
        LocalDateTime assignedAt,
        Long firstSrcSn,
        Long reviewerId,
        String reviewerName,
        // 증강/해상도 파생 영상 여부 — LS_DATA_RAW.ORGNL_RAW_SN != null (R3). 원본이면 false.
        boolean augmented,
        // 증강 종류(정규화 WINTER|NIGHT|RAIN|RESL_1080P|RESL_720P|RESL_480P) — 원본/파싱실패 시 null.
        String augType
) {
}
