package kr.co.cudo.authoring.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * [개발/검수 전용] 레거시 비식별 프레임 복구 결과.
 *
 * <p><b>경로·파일명·PII 를 담지 않는다</b>(CWE-359/209) — 영상 식별자(rawSn)와 건수, 그리고 미리
 * 정의된 사유 코드({@code reason})만 싣는다. 사유 코드는 자유 문자열이 아니라 서버가 고른 열거값이라
 * 요청값이 응답으로 반사되지 않는다.
 *
 * @param dryRun                    true 면 아무것도 변경하지 않은 조회 결과(대상·예상치)다
 * @param videoCount                이번 응답에 담긴 영상 수
 * @param remainingCount            1회 상한 이후 남은 <b>전체</b> 대상 영상 수 (재호출 유도용)
 * @param recoveredVideoCount       실제로 비식별 프레임을 붙인 영상 수 (dry-run 이면 0)
 * @param skippedVideoCount         전제 미충족으로 건너뛴 영상 수
 * @param restoredVideoFrameNoCount 복원한 {@code VDO_FRM_NO} 행 수 합계 (dry-run 이면 0)
 * @param attachedDeidFrameCount    붙인 비식별 프레임 수 합계 (dry-run 이면 0)
 * @param items                     영상별 처리 결과
 */
@Schema(description = "레거시 비식별 프레임 복구 결과 (영상별 성공/건너뜀 + 사유)")
public record DeidentFrameRecoveryResponse(
        boolean dryRun,
        int videoCount,
        long remainingCount,
        int recoveredVideoCount,
        int skippedVideoCount,
        int restoredVideoFrameNoCount,
        int attachedDeidFrameCount,
        List<Item> items
) {

    /** 영상 1건의 처리 결과. */
    @Schema(description = "영상 1건 처리 결과")
    public record Item(
            @Schema(description = "영상 PK", example = "12")
            long rawSn,

            @Schema(description = "처리 결과", allowableValues = {"RECOVERED", "PLANNED", "SKIPPED", "FAILED"})
            String result,

            @Schema(description = "건너뜀/실패 사유 코드. 성공이면 null.",
                    example = "MARK_COUNT_MISMATCH")
            String reason,

            @Schema(description = "영상의 프레임 행 수", example = "21")
            int frameCount,

            @Schema(description = "마킹 frameIndex 개수. 마킹을 읽지 않았으면 null.", example = "57")
            Integer markCount,

            @Schema(description = "비식별 프레임 경로가 비어 있는 프레임 수", example = "21")
            int missingDeidPathCount,

            @Schema(description = "복원 대상/복원한 VDO_FRM_NO 행 수", example = "21")
            int videoFrameNoRestored,

            @Schema(description = "붙일/붙인 비식별 프레임 수", example = "21")
            int deidFrameAttached
    ) {
    }
}
