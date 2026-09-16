package kr.co.cudo.authoring.portal.dto;

import java.util.List;

/**
 * 데이터셋 영상 목록 응답 — 등록 상태 + 페이지. @design API-253
 *
 * <p>페이지 필드 이름은 다른 목록 창구의 페이지 응답과 같다({@code content · totalElements · totalPages ·
 * number · size}). 등록 상태가 {@link PortalDatasetRegistrationState#DONE} 이 아니면 지금까지 등록된
 * 영상만 싣는다.
 *
 * @design ADR-068
 */
public record PortalDatasetVideoPageResponse(
        PortalDatasetRegistrationState registrationState,
        List<PortalDatasetVideoResponse> content,
        long totalElements,
        int totalPages,
        int number,
        int size) {
}
