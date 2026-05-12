package kr.co.cudo.authoring.user.dto;

import kr.co.cudo.authoring.user.repository.dto.WorkerWithTaskCount;

/**
 * 작업자(WORKER) 목록 응답 — REVIEWER 의 배정 모달용.
 *
 * <p>BE 원본 컬럼(userNo/userId/userNm/userEmail) 외에 FE 호환 alias 필드를 함께 노출한다
 * (Video/Review/Assignment alias 패턴 동일).
 * <ul>
 *   <li>{@code id}     = {@code userNo}</li>
 *   <li>{@code name}   = {@code userNm}</li>
 *   <li>{@code active} = 항상 {@code true} — 쿼리에서 {@code useYn='Y'} 로 이미 필터링</li>
 * </ul>
 */
public record WorkerSummaryResponse(
        // FE 호환 alias
        Long id,
        String name,
        Boolean active,
        // BE 원본 필드 (호환 유지)
        Long userNo,
        String userId,
        String userNm,
        String userEmail,
        long activeTaskCount
) {
    public static WorkerSummaryResponse from(WorkerWithTaskCount source) {
        long taskCount = source.activeTaskCount() == null ? 0L : source.activeTaskCount();
        return new WorkerSummaryResponse(
                source.userNo(),
                source.userNm(),
                true,
                source.userNo(),
                source.userId(),
                source.userNm(),
                source.userEmail(),
                taskCount
        );
    }
}
