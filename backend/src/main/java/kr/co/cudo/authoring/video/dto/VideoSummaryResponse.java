package kr.co.cudo.authoring.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
 *   <li>{@code cctvName}   = 화면 표시명 — CCTV 명 → vmsCctvId → {@code 영상 #{rawSn}}
 *       ({@link CctvDisplayNamePolicy} 단일 판정)</li>
 *   <li>{@code eventTypeCd} = {@code evntTypeCd} (camelCase 정정)</li>
 *   <li>{@code eventName}  = 이벤트 표기용 (현 단계는 코드값 fallback)</li>
 *   <li>{@code localGov}   = 지자체명(미조인 시 lclgvCd fallback)</li>
 *   <li>{@code frameCount} = LS_DATA_SRC count by rawSn (서비스에서 주입)</li>
 *   <li>{@code status}     = {@code dataSttsCd} (FE BadgeStatus 와 1:1)</li>
 *   <li>{@code capturedAt} = {@code shtDt} (촬영/녹화 시각) — <b>수신 시각(regDt) 아님</b></li>
 *   <li>{@code exportStatus} = 영상-프로젝트 매핑 기반 최신 export 상태 ("EXPORTED"/"FAILED"/null)</li>
 *   <li>{@code exportedAt} = 최신 export 완료 시각 (없으면 null)</li>
 *   <li>{@code lastExportFailureReason} = FAILED 일 때 사유 (그 외 null)</li>
 *   <li>{@code updatedAt} = {@code updDt} (마지막 수정 시각; 미수정이면 null)</li>
 *   <li>{@code reviewCompletedAt} = {@code LsRawDataStatus.UPD_DT} (검수 상태가 APPROVED 일 때만; 그 외 null)</li>
 *   <li>{@code deIdntfYn} = {@code LsDataRaw.deIdntfYn} (비식별 처리 코드 'Y'/'F'/'N')</li>
 *   <li>{@code deidentStatus} = 비식별 진행 상태 파생값 (IN_PROGRESS/FAILED/DONE/NONE)</li>
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
        // 촬영/녹화 시각 = LS_DATA_RAW.SHT_DT. 수신 시각(regDt)으로 폴백하지 않는다 —
        // 화면 컬럼명("녹화일")·정렬 키(capturedAt→shtDt)·기간 필터가 모두 SHT_DT 축인데 표시값만
        // REG_DT 였던 드리프트를 정정한 것이다. 촬영 시각이 없는 영상은 그대로 null(화면 '-')이며
        // 같은 이유로 기간 필터에도 잡히지 않는다. 수신 시각은 별도 필드 regDt 로 계속 나간다.
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
        // 내보내기 상태 (구 LS_DATA_SET 기반 — V86 삭제됨, 소스 없어 항상 null. API 계약 유지용 필드)
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
        String assignStatus,
        // 비식별 상태 (LS_DATA_RAW.DE_IDENT_YN + LS_DEIDENT_PROC_LOG 최신행 파생). 추가 전용 — 기존 필드 무영향.
        @Schema(description = "비식별 처리 코드 — 'Y'(완료)/'F'(실패)/'N'(미수행)")
        String deIdntfYn,
        @Schema(description = "비식별 진행 상태 — IN_PROGRESS/FAILED/DONE/NONE")
        String deidentStatus
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
     * {@code exportSttsCd} 는 구 LS_DATA_SET 원본 코드(COMPLETED/FAILED) — DTO 변환 시
     * COMPLETED → "EXPORTED", FAILED → "FAILED" 로 매핑한다. V86 에서 LS_DATA_SET 이 삭제되어
     * 현재는 소스가 없어 실제 주입되지 않으나(항상 null), API 응답 계약 유지를 위해 형태만 남긴다.
     */
    public record ExportInfo(String exportSttsCd, LocalDateTime exportedAt, String errorMessage) {}

    /**
     * 비식별 진행 상태 코드 — FE Badge 매핑용. 매직스트링 대신 상수로 관리한다.
     * <ul>
     *   <li>{@code DONE}        = 비식별 완료(deIdntfYn='Y') → 마킹 진입 가능</li>
     *   <li>{@code FAILED}      = 비식별 실패(deIdntfYn='F' 또는 최신 procLog FAILED)</li>
     *   <li>{@code IN_PROGRESS} = 비식별 진행 중(최신 procLog REQUESTED/WAITING/POLLING)</li>
     *   <li>{@code NONE}        = 미수행(procLog 없음 & deIdntfYn='N')</li>
     * </ul>
     */
    public static final class DeidentStatus {
        public static final String IN_PROGRESS = "IN_PROGRESS";
        public static final String FAILED = "FAILED";
        public static final String DONE = "DONE";
        public static final String NONE = "NONE";

        private DeidentStatus() {}
    }

    /**
     * 비식별 상태 요약 (서비스 레이어에서 LS_DATA_RAW.DE_IDENT_YN + 최신 LS_DEIDENT_PROC_LOG 로 파생해 주입).
     * {@code deidentStatus} 는 {@link DeidentStatus} 의 코드값.
     */
    public record DeidentInfo(String deIdntfYn, String deidentStatus) {}

    /**
     * 현재 활성 LABELER 배정 요약 (서비스 레이어에서 주입). 작업 목록(/v1/tasks/board)과 동일하게
     * TASK_TYPE_CD='LABELER' 배정 중 REG_DT 가 가장 최근인 1건 기준으로 산출한다.
     * 배정 상태(assignStatus)는 단순 "ASSIGNED" 고정 — LS_TASK_ALTMNT 존재 자체가 활성 배정을 의미한다.
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
        return from(e, cctvName, localGov, frameCount, exportInfo, reviewCompletedAt, assignmentInfo, null);
    }

    /**
     * 보강 매핑 (export + 검수완료시각 + LABELER 배정 + 비식별 상태 포함). deidentInfo 가 null 이면
     * deIdntfYn 은 엔티티 값으로, deidentStatus 는 {@link DeidentStatus#NONE} fallback 으로 응답한다.
     */
    public static VideoSummaryResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            ExportInfo exportInfo,
            LocalDateTime reviewCompletedAt,
            AssignmentInfo assignmentInfo,
            DeidentInfo deidentInfo
    ) {
        // 표시명 폴백(CCTV명 → CCTV ID → 영상 #{rawSn})은 상세 응답과 <같은 판정기>를 쓴다.
        //   삼항식을 각자 들면 한쪽만 갱신돼 같은 영상이 목록/상세에서 다른 이름으로 보인다.
        String resolvedCctv = CctvDisplayNamePolicy.resolve(cctvName, e.getVmsCctvId(), e.getRawSn());
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
                // capturedAt — 촬영 시각(SHT_DT). regDt 폴백 금지(위 필드 주석 참조).
                e.getShtDt(),
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
                assignmentInfo != null ? AssignmentInfo.STATUS_ASSIGNED : null,
                deidentInfo != null ? deidentInfo.deIdntfYn() : e.getDeIdntfYn(),
                deidentInfo != null ? deidentInfo.deidentStatus() : DeidentStatus.NONE
        );
    }
}
