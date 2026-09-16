package kr.co.cudo.authoring.portal.dto;

/**
 * 데이터셋 영상의 <b>원장 등록 상태</b> — 소재 준비(READY) 뒤에 이어지는 별도 단계의 값역. @design API-253
 *
 * <p>⚠ 소재 준비 완료가 곧 등록 완료가 아니다. {@link #DONE} 일 때만 목록이 완전하다.
 *
 * @design ADR-068
 */
public enum PortalDatasetRegistrationState {

    /** 등록이 진행 중이다 — 목록은 지금까지 등록된 영상만 싣는다. */
    IN_PROGRESS,

    /** 등록이 끝났다. */
    DONE,

    /** 등록이 실패했다 — 배포본 구성이 가정과 다르거나 적재가 끝나지 않았다. */
    FAILED
}
