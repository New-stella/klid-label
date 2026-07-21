package kr.co.cudo.authoring.video.dto;

import java.util.List;

/**
 * 해상도 변경 결과 — Phase 3 (RQ-SFR-06-03 파생영상 전환).
 *
 * <p><b>정책 전환</b>: 구 "경량 export 1행 즉시 반환"에서 <b>프리셋(1080/720/480)별 파생영상(새 RAW_SN)
 * 생성 + 검수 파이프라인 진입</b> 오케스트레이션으로 전환됐다. 원본과 동일 해상도 프리셋은 스킵되고,
 * 프리셋별 부분 실패는 다른 프리셋에 영향 없이 {@link DerivativeStatus#FAILED} 로 표기된다.
 *
 * <p>내부 파일 경로·EXPORT_SN 등 내부 식별자는 포함하지 않는다(정보 노출 방어 CWE-209).
 */
public record ResolutionChangeResponse(
        List<CreatedDerivative> derivatives
) {

    /** 생성 시도된 프리셋 1건의 결과(스킵된 동일 해상도 프리셋은 목록에서 제외). */
    public record CreatedDerivative(
            Long rawSn,
            String goalResCd,
            int targetW,
            int targetH,
            DerivativeStatus status
    ) {
        public static CreatedDerivative created(Long rawSn, String goalResCd, int targetW, int targetH) {
            return new CreatedDerivative(rawSn, goalResCd, targetW, targetH, DerivativeStatus.CREATED);
        }

        public static CreatedDerivative failed(String goalResCd, int targetW, int targetH) {
            return new CreatedDerivative(null, goalResCd, targetW, targetH, DerivativeStatus.FAILED);
        }
    }

    /** 프리셋별 파생영상 생성 상태. */
    public enum DerivativeStatus {
        CREATED,
        FAILED
    }
}
