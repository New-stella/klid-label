package kr.co.cudo.authoring.video.dto;

/**
 * 해상도 파생영상 생성 결과 — Phase 2 (RQ-SFR-06-03 파생영상).
 *
 * <p>동기 예약 시점의 결과다. 파생 RAW_SN·EXPORT_SN 과 원본/목표 해상도, 배율을 반환한다.
 * 실제 비디오 복사/프레임 리스케일/라벨 복사는 커밋 후 비동기로 확정되며, 파생 RAW 는 확정 전까지
 * PENDING 이다. 내부 파일 경로는 포함하지 않는다(정보 노출 방어 CWE-209).
 */
public record ResolutionDerivativeResponse(
        Long newRawSn,
        Long exportSn,
        int srcW,
        int srcH,
        int targetW,
        int targetH,
        double scaleX,
        double scaleY
) {
}
