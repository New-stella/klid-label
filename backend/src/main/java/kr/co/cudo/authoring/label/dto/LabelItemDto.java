package kr.co.cudo.authoring.label.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 라벨 항목 (요청/응답 공용).
 * - id == null  : 신규 추가
 * - id != null  : 기존 라벨 수정 (AUTO_LBL_YN 은 유지됨)
 * - lblTypeCd : BBOX / POLYGON / SEGMENT / TRACK
 * - labelId : LS_LABEL FK (Phase 2). null 허용 — 수정 시 기존 값 유지, 신규 시 NULL 저장.
 * - points : [[x, y], ...] (좌표 검증은 Service 에서 — 음수/이미지 경계 초과 차단)
 */
public record LabelItemDto(
        Long id,
        @NotBlank @Pattern(regexp = "BBOX|POLYGON|SEGMENT|TRACK",
                message = "lblTypeCd 는 BBOX/POLYGON/SEGMENT/TRACK 중 하나여야 합니다.") String lblTypeCd,
        Long labelId,
        @NotBlank @Size(max = 80) String label,
        @NotEmpty List<List<Double>> points,
        String autoLblYn   // 응답 전용 (요청 시 무시, REVIEWER 도 변경 불가 — Mass Assignment 방어)
) {
}
