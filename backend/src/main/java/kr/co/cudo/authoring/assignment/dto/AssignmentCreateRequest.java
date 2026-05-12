package kr.co.cudo.authoring.assignment.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record AssignmentCreateRequest(
        @NotNull(message = "pjtId 는 필수입니다.")
        @Positive
        Long pjtId,

        @NotNull(message = "workerId 는 필수입니다.")
        @Positive
        Long workerId,

        @NotEmpty(message = "rawDataIds 는 1건 이상이어야 합니다.")
        List<@NotNull @Positive Long> rawDataIds,

        /**
         * 옵셔널 — 함께 등록할 REVIEWER userNo.
         * null 이면 reviewer 미등록.
         */
        @Positive
        Long reviewerId
) {
    /**
     * Backward-compatible 보조 생성자 — reviewerId 도입 이전 코드/테스트 호환용.
     */
    public AssignmentCreateRequest(Long pjtId, Long workerId, List<Long> rawDataIds) {
        this(pjtId, workerId, rawDataIds, null);
    }
}
