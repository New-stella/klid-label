package kr.co.cudo.authoring.assignment.dto;

import java.time.LocalDateTime;

/**
 * 작업(영상) 단위 이벤트 응답 — LS_TASK_EVENT_LOG 한 row 매핑.
 *
 * <p>SCR-TASK-003 작업 이력 화면 통합 타임라인 표시용. 배정/재배정/검수 제출/승인/반려를
 * 동일 응답 구조로 표현하며, 이벤트 종류에 따라 사용되는 필드가 다르다:
 * <ul>
 *   <li>ASSIGN    : actor=배정자(REVIEWER), subject=배정된 작업자</li>
 *   <li>REASSIGN  : actor=재배정자(REVIEWER), subject=새 작업자, prev=이전 작업자</li>
 *   <li>SUBMIT    : actor=subject=작업자(본인 제출)</li>
 *   <li>APPROVE   : actor=검수자(REVIEWER)</li>
 *   <li>REJECT    : actor=검수자(REVIEWER), reason=반려 사유</li>
 * </ul>
 *
 * <p>응답에 노출되는 userNo / userName 은 MNG_ACCT_USER 에서 batch lookup 된 값만 사용한다.
 */
public record AssignmentHistoryResponse(
        Long eventSeq,
        String eventTypeCd,
        Long actorUserNo,
        String actorUserName,
        Long subjectUserNo,
        String subjectUserName,
        Long prevUserNo,
        String prevUserName,
        String reason,
        LocalDateTime occurredAt
) {
}
