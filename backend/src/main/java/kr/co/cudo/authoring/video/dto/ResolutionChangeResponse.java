package kr.co.cudo.authoring.video.dto;

/**
 * 해상도 변경 결과 — Phase 3 (RQ-SFR-07-02).
 *
 * <p>새로 생성된 PENDING 영상의 RAW_SN 과 원본/타겟 해상도, 적용 배율, 복사 건수를 반환한다.
 * 내부 파일 경로는 포함하지 않는다(정보 노출 방어 CWE-209).
 */
public record ResolutionChangeResponse(
        Long newRawSn,
        int srcW,
        int srcH,
        int targetW,
        int targetH,
        double factor,
        int copiedFrames,
        int copiedLabels,
        int copiedMetas
) {
}
