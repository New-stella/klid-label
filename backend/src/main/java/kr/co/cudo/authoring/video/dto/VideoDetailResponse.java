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
        List<FramePreviewDto> framePreviews
) {
    /** 프레임 미리보기 항목 — srcSn으로 라벨링 도구 진입, thumbnailUrl로 이미지 표시. */
    public record FramePreviewDto(Long srcSn, Integer frameNo, String thumbnailUrl) {}

    public static VideoDetailResponse from(LsDataRaw e) {
        return from(e, null, null, 0L, Collections.emptyList());
    }

    public static VideoDetailResponse from(LsDataRaw e, String cctvName, String localGov, Long frameCount) {
        return from(e, cctvName, localGov, frameCount, Collections.emptyList());
    }

    public static VideoDetailResponse from(
            LsDataRaw e,
            String cctvName,
            String localGov,
            Long frameCount,
            List<FramePreviewDto> framePreviews
    ) {
        String resolvedCctv = (cctvName != null && !cctvName.isBlank()) ? cctvName : e.getVmsCctvId();
        String resolvedGov = (localGov != null && !localGov.isBlank()) ? localGov : e.getLclgvCd();
        Long resolvedFrame = (frameCount != null) ? frameCount : 0L;
        List<FramePreviewDto> resolvedPreviews = (framePreviews != null) ? framePreviews : Collections.emptyList();
        return new VideoDetailResponse(
                e.getRawSn(),
                resolvedCctv,
                e.getEvntTypeCd(),
                e.getEvntTypeCd(),
                resolvedGov,
                resolvedFrame,
                e.getDataSttsCd(),
                e.getRawSn(),
                e.getVmsClipId(),
                e.getVmsCctvId(),
                e.getEvntTypeCd(),
                e.getLclgvCd(),
                e.getPrvcTypeCd(),
                e.getPrvcYn(),
                e.getDeIdntfYn(),
                e.getFilePath(),
                e.getCapturedAt(),
                e.getDurationSec(),
                e.getDataSttsCd(),
                e.getRegDt(),
                e.getUpdDt(),
                resolvedPreviews
        );
    }
}
