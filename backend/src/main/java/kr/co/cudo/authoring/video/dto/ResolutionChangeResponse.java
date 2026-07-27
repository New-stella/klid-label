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

    /**
     * 프리셋별 파생영상 생성 상태.
     *
     * <p>{@link #CREATED} 는 <b>예약 성공</b>만 의미한다 — 실제 확정(파일 산출·라벨 복사)은 비동기라
     * 요청 응답 시점에는 아직 끝나지 않았다(E-ISSUE-24). 확정 결과는 조회 API
     * ({@code GET /api/v1/videos/{rawSn}/resolution})가 {@link #COMPLETED}/{@link #IN_PROGRESS}/
     * {@link #FAILED} 로 알려준다.
     */
    public enum DerivativeStatus {
        /** 예약 성공(비동기 확정 대기) — POST 응답 전용. */
        CREATED,
        /** 확정 진행 중(예약됨, 아직 비식별 확정 전) — 조회 응답 전용. */
        IN_PROGRESS,
        /** 확정 완료(파일·라벨 산출 완료) — 조회 응답 전용. */
        COMPLETED,
        /** 예약 또는 확정 실패. */
        FAILED
    }
}
