package kr.co.cudo.authoring.video.dto;

import kr.co.cudo.authoring.video.entity.LsDataRaw;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 영상 요약 응답 — 목록 화면용.
 * <p>
 * BE 원본 컬럼(rawSn/vmsCctvId/evntTypeCd/regDt 등) 외에 FE 호환 alias 필드를 함께 노출해
 * 화면 측 필드명 매핑을 단순화한다.
 * <ul>
 *   <li>{@code id}         = {@code rawSn}</li>
 *   <li>{@code cctvName}   = CCTV 명(미조인 시 vmsCctvId fallback)</li>
 *   <li>{@code eventTypeCd} = {@code evntTypeCd} (camelCase 정정)</li>
 *   <li>{@code eventName}  = 이벤트 표기용 (현 단계는 코드값 fallback)</li>
 *   <li>{@code localGov}   = 지자체명(미조인 시 lclgvCd fallback)</li>
 *   <li>{@code frameCount} = LS_DATA_SRC count by rawSn (서비스에서 주입)</li>
 *   <li>{@code status}     = {@code dataSttsCd} (FE BadgeStatus 와 1:1)</li>
 *   <li>{@code capturedAt} = {@code regDt} (수신 시각)</li>
 *   <li>{@code exportStatus} = 영상-프로젝트 매핑 기반 최신 export 상태 ("EXPORTED"/"FAILED"/null)</li>
 *   <li>{@code exportedAt} = 최신 export 완료 시각 (없으면 null)</li>
 *   <li>{@code lastExportFailureReason} = FAILED 일 때 사유 (그 외 null)</li>
 *   <li>{@code updatedAt} = {@code updDt} (마지막 수정 시각; 미수정이면 null)</li>
 *   <li>{@code reviewCompletedAt} = {@code LsRawDataStatus.UPD_DT} (검수 상태가 APPROVED 일 때만; 그 외 null)</li>
 * </ul>
 * 기존 필드는 backward-compat 유지.
 */
public record VideoSummaryResponse(
        // FE 호환 alias
        Long id,
        String cctvName,
        String eventName,
        String eventTypeCd,
        String localGov,
        Long frameCount,
        String status,
        LocalDateTime capturedAt,
        // BE 원본 필드 (호환 유지)
        Long rawSn,
        String vmsClipId,
        String vmsCctvId,
        String evntTypeCd,
        String prvcTypeCd,
        String prvcYn,
        String dataSttsCd,
        Integer durationSec,
        LocalDateTime regDt,
        // 내보내기 상태 (영상↔프로젝트 매핑 → LS_DATA_SET 최신 1건 기반)
        String exportStatus,
        LocalDateTime exportedAt,
        String lastExportFailureReason,
        // 마지막 수정 시각 (LS_DATA_RAW.UPD_DT)
        LocalDateTime updatedAt,
        // 검수 완료 시각 (LS_RAW_DATA_STATUS.UPD_DT) — dataSttsCd='APPROVED' 일 때만 노출, 그 외 null
        LocalDateTime reviewCompletedAt,
        // 현재 활성 LABELER 배정 정보 (작업 목록 /v1/tasks/board 와 동일 산출 기준). 미배정 시 모두 null.
        // 주의: 기존 status(배치/처리 단계)와 충돌 방지를 위해 배정 상태는 별도명 assignStatus 로 노출한다.
        Long assignmentId,
        Long workerId,
        String workerName,
        LocalDateTime assignedAt,
        String assignStatus
) {

    /**
     * 행정구역 코드(LCLGV_CD) → 한글 표기 매핑.
     * dev-seed.sql 의 코드 전체를 포함한다. 누락 코드는 원본 코드로 fallback.
     */
    private static final Map<String, String> LCLGV_CODE_TO_NAME = Map.ofEntries(
            Map.entry("11110", "서울 종로구"),
            Map.entry("11230", "서울 동대문구"),
            Map.entry("11290", "서울 성북구"),
            Map.entry("11440", "서울 마포구"),
            Map.entry("11650", "서울 서초구"),
            Map.entry("11680", "서울 강남구"),
            Map.entry("11710", "서울 송파구")
    );
    /**
     * 영상별 최신 내보내기 요약 정보 (서비스 레이어에서 주입).
     * {@code exportSttsCd} 는 LS_DATA_SET 원본 코드(COMPLETED/FAILED) — DTO 변환 시
     * COMPLETED → "EXPORTED", FAILED → "FAILED" 로 매핑한다.
     */
    public record ExportInfo(String exportSttsCd, LocalDateTime exportedAt, String errorMessage) {}

    /**
     * 현재 활성 LABELER 배정 요약 (서비스 레이어에서 주입). 작업 목록(/v1/tasks/board)과 동일하게
     * TASK_TYPE_CD='LABELER' 배정 중 REG_DT 가 가장 최근인 1건 기준으로 산출한다.
     * 배정 상태(assignStatus)는 단순 "ASSIGNED" 고정 — LS_TASK_ASSIGNMENT 존재 자체가 활성 배정을 의미한다.
     */
    public record AssignmentInfo(Long assignmentId, Long workerId, String workerName, LocalDateTime assignedAt) {

        /** LABELER 배정이 존재할 때의 노출용 상태값. */
        public static final String STATUS_ASSIGNED = "ASSIGNED";
    }

    /** 단순 매핑 — frameCount/cctvName 등은 0/fallback 으로 채움. */
    public static VideoSummaryResponse from(LsDataRaw e) {
        return from(e, null, null, 0L, null, null);
    }

    /** 보강 매핑 — 서비스 레이어에서 CCTV 명/지자체명/프레임수를 함께 주입. */
    public static VideoSummaryResponse from(LsDataRaw e, String cctvName, String localGov, Long frameCount) {
        return from(e, cctvName, localGov, frameCount, null, null);
    }

    /**
     * 보강 매핑 (export 포함) — 서비스 레이어에서 CCTV 명/지자체명/프레임수/내보내기 요약을 함께 주입.
     * exportInfo 가 null 이면 export 관련 3개 필드 모두 null 로 응답.
     */
    public static VideoSummaryResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            ExportInfo exportInfo
    ) {
        return from(e, cctvName, localGov, frameCount, exportInfo, null);
    }

    /**
     * 보강 매핑 (export + 검수완료시각 포함). reviewCompletedAt 은 호출 측에서
     * LS_RAW_DATA_STATUS 의 dataSttsCd='APPROVED' 인 경우의 UPD_DT 만 전달해야 한다 (그 외 null).
     */
    public static VideoSummaryResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            ExportInfo exportInfo,
            LocalDateTime reviewCompletedAt
    ) {
        return from(e, cctvName, localGov, frameCount, exportInfo, reviewCompletedAt, null);
    }

    /**
     * 보강 매핑 (export + 검수완료시각 + 현재 LABELER 배정 포함). assignmentInfo 가 null 이면
     * 배정 관련 5개 필드(assignmentId/workerId/workerName/assignedAt/assignStatus)는 모두 null 로 응답한다.
     */
    public static VideoSummaryResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            ExportInfo exportInfo,
            LocalDateTime reviewCompletedAt,
            AssignmentInfo assignmentInfo
    ) {
        String resolvedCctv = (cctvName != null && !cctvName.isBlank()) ? cctvName : e.getVmsCctvId();
        String resolvedGov = (localGov != null && !localGov.isBlank())
                ? localGov
                : (e.getLclgvCd() == null
                        ? null
                        : LCLGV_CODE_TO_NAME.getOrDefault(e.getLclgvCd(), e.getLclgvCd()));
        Long resolvedFrame = (frameCount != null) ? frameCount : 0L;
        String exportStatus = null;
        LocalDateTime exportedAt = null;
        String failureReason = null;
        if (exportInfo != null && exportInfo.exportSttsCd() != null) {
            switch (exportInfo.exportSttsCd()) {
                case "COMPLETED" -> {
                    exportStatus = "EXPORTED";
                    exportedAt = exportInfo.exportedAt();
                }
                case "FAILED" -> {
                    exportStatus = "FAILED";
                    exportedAt = exportInfo.exportedAt();
                    failureReason = exportInfo.errorMessage();
                }
                default -> {
                    // PENDING/IN_PROGRESS 등은 UI 상 'NEVER' 처리 — 모두 null 유지.
                }
            }
        }
        return new VideoSummaryResponse(
                e.getRawSn(),
                resolvedCctv,
                e.getEvntTypeCd(),
                e.getEvntTypeCd(),
                resolvedGov,
                resolvedFrame,
                e.getDataSttsCd(),
                e.getRegDt(),
                e.getRawSn(),
                e.getVmsClipId(),
                e.getVmsCctvId(),
                e.getEvntTypeCd(),
                e.getPrvcTypeCd(),
                e.getPrvcYn(),
                e.getDataSttsCd(),
                e.getDurationSec(),
                e.getRegDt(),
                exportStatus,
                exportedAt,
                failureReason,
                e.getMdfcnDt(),
                reviewCompletedAt,
                assignmentInfo != null ? assignmentInfo.assignmentId() : null,
                assignmentInfo != null ? assignmentInfo.workerId() : null,
                assignmentInfo != null ? assignmentInfo.workerName() : null,
                assignmentInfo != null ? assignmentInfo.assignedAt() : null,
                assignmentInfo != null ? AssignmentInfo.STATUS_ASSIGNED : null
        );
    }
}
