package kr.co.cudo.authoring.controlnotify.dto;

import java.time.Instant;

/**
 * 영상별 요약 응답 — 프레임 수, 라벨 수, 메타 수, 상태, 검수자, 최종 수정일.
 *
 * <p>완료 통지 페이로드({@link kr.co.cudo.authoring.controlnotify.dto.TaskCompletedPayload})에는
 * 카운트·검수자가 없다. 관제는 통지 수신 후 이 조회 API 로
 * 상세를 보강한다(CLAUDE.md 관제서버 조회 패턴). 값은 모두 DB 실측이며 상수 self-fill 을 하지
 * 않는다(D-ISSUE-41).
 *
 * <p>CWE-359 Privacy: filePath, deidentFilePath 등 파일 경로 필드 미포함. 검수자는 <b>이름</b>만
 * 노출하고 이메일·계정 ID 등 식별 정보는 싣지 않는다.
 *
 * @param reviewerName 마지막 승인(APPROVE) 이벤트의 검수자명. 미승인/사용자 미조회 시 null
 */
public record TaskSummaryResponse(
        Long rawSn,
        String status,
        long totalFrames,
        long labeledFrames,
        long totalLabels,
        long totalMeta,
        String reviewerName,
        Instant lastModifiedAt
) {
}
