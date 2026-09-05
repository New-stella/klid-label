package kr.co.cudo.authoring.video.dto;

/**
 * 해상도 파생영상 생성 결과 — Phase 2 (RQ-SFR-06-03 파생영상).
 *
 * <p>동기 예약 시점의 결과다. 파생 RAW_SN·증강행 DATA_AUG_SN 과 원본 해상도, 실제 산출 크기, 배율을
 * 반환한다. 실제 비디오 복사/프레임 리스케일/라벨 복사는 커밋 후 비동기로 확정되며, 파생 RAW 는 확정
 * 전까지 PENDING 이다. 내부 파일 경로는 포함하지 않는다(정보 노출 방어 CWE-209).
 *
 * <p><b>{@code targetW}/{@code targetH} 는 요청 프리셋의 수치가 아니라 실제 산출 프레임 크기다</b>
 * (@design ADR-018 — 프리셋은 크기 상한이고 산출 크기는 원본 종횡비로 정해진다. 예: 원본 1440×1080 을
 * RESL_720P 로 요청하면 960×720). {@code scaleX}/{@code scaleY} 는 항상 같은 값(균일 배율)이며
 * 오프셋 가산이 없어 이 배율만으로 원본 좌표가 완전히 역산된다.
 */
public record ResolutionDerivativeResponse(
        Long newRawSn,
        Long dataAugSn,
        int srcW,
        int srcH,
        int targetW,
        int targetH,
        double scaleX,
        double scaleY
) {
}
