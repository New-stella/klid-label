package kr.co.cudo.authoring.assignment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReassignRequest(
        @NotNull(message = "workerId 는 필수입니다.")
        @Positive
        Long workerId
) {
}
