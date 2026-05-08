package kr.co.cudo.authoring.video.dto;

import kr.co.cudo.authoring.video.entity.LsDataRaw;

import java.time.LocalDateTime;

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
        LocalDateTime regDt
) {
    /** 단순 매핑 — frameCount/cctvName 등은 0/fallback 으로 채움. */
    public static VideoSummaryResponse from(LsDataRaw e) {
        return from(e, null, null, 0L);
    }

    /** 보강 매핑 — 서비스 레이어에서 CCTV 명/지자체명/프레임수를 함께 주입. */
    public static VideoSummaryResponse from(LsDataRaw e, String cctvName, String localGov, Long frameCount) {
        String resolvedCctv = (cctvName != null && !cctvName.isBlank()) ? cctvName : e.getVmsCctvId();
        String resolvedGov = (localGov != null && !localGov.isBlank()) ? localGov : e.getLclgvCd();
        Long resolvedFrame = (frameCount != null) ? frameCount : 0L;
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
                e.getRegDt()
        );
    }
}
