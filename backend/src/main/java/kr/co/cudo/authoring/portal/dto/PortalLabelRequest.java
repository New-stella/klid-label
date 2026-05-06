package kr.co.cudo.authoring.portal.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Phase 11 — 포털 수동 라벨 등록(체험) 요청.
 *
 *  - 본 Phase 는 echo only — 실제 LS_DATA_LBL 연동은 후속.
 *  - items 최대 100건 (DoS 방어 — CWE-770).
 */
public record PortalLabelRequest(
        @NotNull(message = "portalVideoSn 은 필수입니다.") Long portalVideoSn,
        @NotNull(message = "items 는 필수입니다.")
        @Size(max = 100, message = "items 최대 100건") List<Item> items
) {
    public record Item(String label, String lblTypeCd, List<List<Double>> points) {}
}
