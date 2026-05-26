package kr.co.cudo.authoring.controlnotify.dto;

import java.time.Instant;

/**
 * 영상별 요약 응답 — 프레임 수, 라벨 수, 메타 수, 상태, 최종 수정일.
 * <p>CWE-359 Privacy: filePath, deidentFilePath 등 파일 경로 필드 미포함.
 */
public record TaskSummaryResponse(
        Long rawSn,
        String status,
        long totalFrames,
        long labeledFrames,
        long totalLabels,
        long totalMeta,
        Instant lastModifiedAt
) {
}
