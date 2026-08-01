package kr.co.cudo.authoring.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * [개발/검수 전용] 영상 업로드 + 관제 INSERT → 픽업 적재 응답 (Phase 3).
 *
 * <p>업로드는 관제 두 테이블에 행을 넣고 <b>운영 픽업 스캔</b>을 1회 호출한 뒤 적재된 {@code rawSn} 을
 * 회수한다. 적재까지 성공하면 {@code PROCESSING}(선두 비식별이 백그라운드로 진행 중), 이번 스캔에서
 * 적재되지 않았으면 {@code rawSn=null} + {@code INGEST_PENDING} 이다 — <b>거짓 성공을 만들지 않는다</b>.
 *
 * <p>응답에는 저장소 기준 상대 경로만 노출 — 절대 경로 (CWE-209 Information Leak) 미노출.
 */
@Schema(description = "[개발/검수 전용] 업로드된 영상 식별자와 백그라운드 파이프라인 상태")
public record AutolabelTestResponse(
        @Schema(description = "LS_DATA_RAW.RAW_SN — 픽업 적재된 영상 식별자. 미적재(INGEST_PENDING) 시 null",
                example = "12345", nullable = true)
        Long rawSn,

        @Schema(description = "저장된 파일의 storage 기준 상대 경로 (절대 경로 미노출)",
                example = "data/upload/v2/CLP-a1b2c3d4.mp4")
        String savedFilePath,

        @Schema(description = """
                파이프라인 상태.
                - PROCESSING: 관제 INSERT + 픽업 적재 성공, 선두 비식별이 백그라운드 진행 중
                - INGEST_PENDING: 관제 INSERT 는 됐으나 이번 스캔에서 적재되지 않음 (주기 배치가 다음 tick 에 픽업)""",
                example = "PROCESSING")
        String pipelineStatus,

        @Schema(description = "트리거 시각 epoch millis", example = "1715520000000")
        Long startedAt
) {
}
