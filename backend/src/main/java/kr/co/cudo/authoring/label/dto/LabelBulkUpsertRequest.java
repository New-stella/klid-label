package kr.co.cudo.authoring.label.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 프레임 내 라벨 bulk upsert 요청.
 * - items 가 빈 리스트면 = 모든 라벨 삭제 (기존 라벨이 있을 경우).
 * - 한 요청 최대 500 건 (CWE-770 DoS 방어).
 */
public record LabelBulkUpsertRequest(
        @NotNull @Valid @Size(max = 500, message = "한 번에 처리 가능한 라벨 수 초과 (최대 500)") List<LabelItemDto> items
) {
}
