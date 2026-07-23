package kr.co.cudo.authoring.video.dto;

import kr.co.cudo.authoring.video.entity.LsDataRaw;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 영상 상세 응답 — 상세 화면용.
 * <p>
 * BE 원본 컬럼 외 FE 호환 alias 필드를 함께 노출 (id/cctvName/eventTypeCd 등).
 * 자세한 매핑 규칙은 {@link VideoSummaryResponse} 참고.
 */
public record VideoDetailResponse(
        // FE 호환 alias
        Long id,
        String cctvName,
        String eventName,
        String eventTypeCd,
        String localGov,
        Long frameCount,
        String status,
        // 검수 상태 — LS_RAW_DATA_STATUS.DATA_STTS_CD (APPROVED=검수완료). 상태 row 없으면 null.
        // status(=배치단계 LS_DATA_RAW.DATA_STTS_CD)와 출처·의미가 다른 별도 필드다.
        String reviewSttsCd,
        // BE 원본 필드
        Long rawSn,
        String vmsClipId,
        String vmsCctvId,
        String evntTypeCd,
        String lclgvCd,
        String prvcTypeCd,
        String prvcYn,
        String deIdntfYn,
        String filePath,
        LocalDateTime capturedAt,
        Integer durationSec,
        String dataSttsCd,
        LocalDateTime regDt,
        LocalDateTime updDt,
        // 프레임 미리보기 (최대 6개)
        List<FramePreviewDto> framePreviews,
        // 배치 파이프라인 단계별 진행 상태 (이슈1). canonical 순서(비식별~보간).
        // 배치 미진행/기존 영상이면 빈 배열 → FE 가 기존 배지로 폴백(하위호환). PII/경로/스택 미포함.
        List<StageStatusDto> stages
) {
    /** 프레임 미리보기 항목 — srcSn으로 라벨링 도구 진입, thumbnailUrl로 이미지 표시. */
    public record FramePreviewDto(Long srcSn, Integer frameNo, String thumbnailUrl) {}

    /** 배치 단계 상태 — name=단계코드(DEIDENTIFY 등), status=DONE/PROGRESS/PENDING/FAIL, progress=nullable. */
    public record StageStatusDto(String name, String status, Integer progress) {}

    public static VideoDetailResponse from(LsDataRaw e) {
        return from(e, null, null, 0L, Collections.emptyList(), null, Collections.emptyList());
    }

    public static VideoDetailResponse from(LsDataRaw e, String cctvName, String localGov, Long frameCount) {
        return from(e, cctvName, localGov, frameCount, Collections.emptyList(), null, Collections.emptyList());
    }

    public static VideoDetailResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            List<FramePreviewDto> framePreviews
    ) {
        return from(e, cctvName, localGov, frameCount, framePreviews, null, Collections.emptyList());
    }

    public static VideoDetailResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            List<FramePreviewDto> framePreviews,
            String reviewSttsCd
    ) {
        return from(e, cctvName, localGov, frameCount, framePreviews, reviewSttsCd, Collections.emptyList());
    }

    public static VideoDetailResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            List<FramePreviewDto> framePreviews,
            String reviewSttsCd,
            List<StageStatusDto> stages
    ) {
        String resolvedCctv = (cctvName != null && !cctvName.isBlank()) ? cctvName : e.getVmsCctvId();
        String resolvedGov = (localGov != null && !localGov.isBlank()) ? localGov : e.getLclgvCd();
        Long resolvedFrame = (frameCount != null) ? frameCount : 0L;
        List<FramePreviewDto> resolvedPreviews = (framePreviews != null) ? framePreviews : Collections.emptyList();
        List<StageStatusDto> resolvedStages = (stages != null) ? stages : Collections.emptyList();
        return new VideoDetailResponse(
                e.getRawSn(),
                resolvedCctv,
                e.getEvntTypeCd(),
                e.getEvntTypeCd(),
                resolvedGov,
                resolvedFrame,
                e.getDataSttsCd(),
                reviewSttsCd,
                e.getRawSn(),
                e.getVmsClipId(),
                e.getVmsCctvId(),
                e.getEvntTypeCd(),
                e.getLclgvCd(),
                e.getPrvcTypeCd(),
                e.getPrvcYn(),
                e.getDeIdntfYn(),
                e.getRawFilePathNm(),
                e.getShtDt(),
                e.getDurationSec(),
                e.getDataSttsCd(),
                e.getRegDt(),
                e.getMdfcnDt(),
                resolvedPreviews,
                resolvedStages
        );
    }
}
