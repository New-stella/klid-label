package kr.co.cudo.authoring.marking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 마킹 생성 요청 DTO.
 *
 * @param eventName      이벤트명 (필수)
 * @param mode           마킹 모드 — "AUTO" 또는 "MANUAL" (필수)
 * @param intervalFrames 자동 모드 시 프레임 간격(프레임 수) — AUTO 모드일 때 필수, 1 이상
 * @param marks          수동 모드 시 marks 배열 — MANUAL 모드일 때 필수
 */
public record MarkingRequest(
        @NotBlank(message = "eventName 은 필수입니다.")
        @Size(max = 100, message = "eventName 은 최대 100자입니다.")
        String eventName,

        @NotBlank(message = "mode 는 필수입니다.")
        String mode,

        Integer intervalFrames,

        List<MarkItem> marks
) {}
