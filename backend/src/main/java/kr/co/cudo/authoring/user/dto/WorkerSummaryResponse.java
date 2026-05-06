package kr.co.cudo.authoring.user.dto;

import kr.co.cudo.authoring.user.repository.dto.WorkerWithTaskCount;

public record WorkerSummaryResponse(
        Long userNo,
        String userId,
        String userNm,
        String userEmail,
        long activeTaskCount
) {
    public static WorkerSummaryResponse from(WorkerWithTaskCount source) {
        return new WorkerSummaryResponse(
                source.userNo(),
                source.userId(),
                source.userNm(),
                source.userEmail(),
                source.activeTaskCount() == null ? 0L : source.activeTaskCount()
        );
    }
}
