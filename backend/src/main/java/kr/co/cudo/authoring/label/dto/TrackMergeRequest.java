package kr.co.cudo.authoring.label.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Phase 4 — 트랙 병합 요청. {@code fromTrackId} 의 원 키프레임을 {@code toTrackId} 로 이관한 뒤 재보간한다.
 *
 * <p>입력 검증(CWE-20): 두 트랙 ID 모두 NotBlank + 길이 상한(LS_DATA_LBL.TRCK_ID VARCHAR(30)).
 * from==to 동일성·존재성은 서비스에서 각각 400/404 로 처리한다.
 *
 * @param fromTrackId 병합될(사라질) 원본 트랙 ID
 * @param toTrackId   병합 대상(유지될) 트랙 ID
 */
public record TrackMergeRequest(
        @NotBlank(message = "fromTrackId 는 필수입니다.")
        @Size(max = 30, message = "trackId 는 30자 이하여야 합니다.")
        String fromTrackId,

        @NotBlank(message = "toTrackId 는 필수입니다.")
        @Size(max = 30, message = "trackId 는 30자 이하여야 합니다.")
        String toTrackId
) {
}
