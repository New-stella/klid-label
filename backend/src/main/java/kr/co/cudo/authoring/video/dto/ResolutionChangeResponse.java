package kr.co.cudo.authoring.video.dto;

/**
 * 해상도 변경 결과 — Phase 1 (RQ-SFR-06-03 v1.8/1.10).
 *
 * <p>다운스케일된 프레임 이미지셋의 추적 레코드(LS_RESOLUTION_EXPORT) 식별자와
 * 원본/타겟 해상도, 생성된 프레임 개수를 반환한다.
 * 내부 파일 경로는 포함하지 않는다(정보 노출 방어 CWE-209).
 */
public record ResolutionChangeResponse(
        Long exportSn,
        int srcW,
        int srcH,
        int targetW,
        int targetH,
        int frameCount
) {
}
