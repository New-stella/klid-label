package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.portal.service.PortalDatasetRegistrationFailureReason;

/**
 * 데이터셋 영상 등록 재착수 응답 — 현재 등록 상태 + 실패 사유. @design API-262
 *
 * <p>두 필드의 이름·값역은 목록 응답({@link PortalDatasetVideoPageResponse})과 <b>같은 것</b>이다 — 화면이
 * 한 판정으로 두 창구를 읽는다. {@code registrationFailureReason} 은 {@code registrationState} 가
 * {@link PortalDatasetRegistrationState#FAILED} 일 때만 값이 있고, 재착수가 접수됐을 때는 비어 있다.
 *
 * <p>⚠ 내부 경로·표식 파일 이름을 싣지 않는다(CWE-209).
 *
 * @design ADR-068
 */
public record PortalDatasetRegistrationResponse(
        PortalDatasetRegistrationState registrationState,
        PortalDatasetRegistrationFailureReason registrationFailureReason) {
}
