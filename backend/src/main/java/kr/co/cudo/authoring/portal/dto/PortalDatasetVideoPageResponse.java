package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.portal.service.PortalDatasetRegistrationFailureReason;

import java.util.List;

/**
 * 데이터셋 영상 목록 응답 — 등록 상태 + 페이지. @design API-253
 *
 * <p>페이지 필드 이름은 다른 목록 창구의 페이지 응답과 같다({@code content · totalElements · totalPages ·
 * number · size}). 등록 상태가 {@link PortalDatasetRegistrationState#DONE} 이 아니면 지금까지 등록된
 * 영상만 싣는다.
 *
 * <p>{@code registrationFailureReason} 은 등록 상태가 {@link PortalDatasetRegistrationState#FAILED} 일 때만
 * 값이 있고 그 밖에는 비어 있다(API-253 v3). 값역은 재착수 응답({@link PortalDatasetRegistrationResponse})과
 * 같다 — 화면이 사유를 문구로 바꿔 보이고, 같은 답이 돌아올 사유에서는 재착수를 권하지 않는다.
 *
 * @design ADR-068
 */
public record PortalDatasetVideoPageResponse(
        PortalDatasetRegistrationState registrationState,
        PortalDatasetRegistrationFailureReason registrationFailureReason,
        List<PortalDatasetVideoResponse> content,
        long totalElements,
        int totalPages,
        int number,
        int size) {
}
