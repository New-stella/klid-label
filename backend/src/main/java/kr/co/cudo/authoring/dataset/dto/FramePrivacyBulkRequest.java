package kr.co.cudo.authoring.dataset.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Phase 3 — 프레임 개인정보 메타 벌크 저장 요청 래퍼.
 *
 * <p>{@code @Valid} 를 리스트 원소({@link FramePrivacyBulkItem})까지 전파하기 위해 래핑한다.
 * 무제한 요청(API4 Resource Consumption) 방어를 위해 항목 수 상한({@code @Size})을 강제한다.
 *
 * @param items 저장 항목 목록(1건 이상, 상한 {@value #MAX_ITEMS} 건)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FramePrivacyBulkRequest(
        @NotEmpty(message = "items 는 1건 이상이어야 합니다.")
        @Size(max = MAX_ITEMS, message = "items 는 " + MAX_ITEMS + "건 이하여야 합니다.")
        @Valid List<FramePrivacyBulkItem> items
) {
    /** 벌크 상한 — 프레임 대량성(수천개)을 수용하되 무제한 요청은 차단. */
    public static final int MAX_ITEMS = 5000;
}
