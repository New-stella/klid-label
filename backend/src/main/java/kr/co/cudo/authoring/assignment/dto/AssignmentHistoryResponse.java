package kr.co.cudo.authoring.assignment.dto;

import java.time.LocalDateTime;

/**
 * 작업(영상) 단위 이벤트 응답 — LS_TASK_EVNT_LOG 한 row 매핑.
 *
 * <p>SCR-TASK-003 작업 이력 화면 통합 타임라인 표시용. 배정/재배정/검수 제출/승인/반려를
 * 동일 응답 구조로 표현하며, 이벤트 종류에 따라 사용되는 필드가 다르다:
 * <ul>
 *   <li>ASSIGN       : actor=배정자(REVIEWER), subject=배정된 작업자</li>
 *   <li>REASSIGN     : actor=재배정자(REVIEWER), subject=새 작업자, prev=이전 작업자</li>
 *   <li>START_REVIEW : actor=검수를 시작한 사람 — 그 영상의 점유를 세운다(ADR-067)</li>
 *   <li>SUBMIT       : actor=subject=작업자(본인 제출)</li>
 *   <li>APPROVE      : actor=검수자 또는 관리자</li>
 *   <li>REJECT       : actor=검수자 또는 관리자, reason=반려 사유</li>
 * </ul>
 *
 * <p>이 창구는 이벤트 종류로 거르는 질의 항목이 없어 값역이 넓어지면 새 종류도 그대로 실린다 —
 * 그래서 조회 대상 종류를 <b>개수로 적지 않는다</b>(열거가 곧 목록이다).
 *
 * <p>응답에 노출되는 userNo / userName 은 LS_ACNT_USER 에서 batch lookup 된 값만 사용한다.
 *
 * @design API-116
 * @design AC-1076
 */
public record AssignmentHistoryResponse(
        Long eventSeq,
        String eventTypeCd,
        Long actorUserNo,
        String actorUserName,
        /**
         * 그 행위를 한 <b>시점</b>의 행위자 역할 코드(ADMIN|REVIEWER|WORKER). 하위호환 추가 필드다.
         *
         * <p>조회 시점에 그 사람의 현재 역할을 다시 읽은 값이 아니라 행위 당시에 적재된 값이라,
         * 역할이 나중에 바뀌어도 과거 행위의 역할은 그대로 남는다. 계층으로 승격된 값이 아니라
         * 행위자의 <b>실제</b> 역할이다 — 관리자가 승인한 이벤트는 ADMIN 으로 남는다.
         *
         * <p>역할 칸이 생기기 전에 쌓인 이력과 역할을 남기지 않는 종류는 <b>null</b> 이며 지어낸 값으로
         * 채우지 않는다. 화면은 값이 없으면 역할을 비워 보인다.
         */
        String actorRoleCd,
        Long subjectUserNo,
        String subjectUserName,
        Long prevUserNo,
        String prevUserName,
        String reason,
        LocalDateTime occurredAt
) {
}
