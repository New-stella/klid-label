package kr.co.cudo.authoring.marking.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 마킹 생성 요청 DTO.
 *
 * <p>이벤트명은 더 이상 요청으로 받지 않는다(API-047 계약 변경). 마킹의 이벤트명은
 * 영상의 이벤트 유형({@code LS_DATA_RAW.EVNT_TYPE_CD})에서 서버가 자동 소싱한다.
 *
 * @param mode           마킹 모드 — "AUTO" 또는 "MANUAL" (필수)
 * @param intervalFrames 자동 모드 시 프레임 간격(프레임 수) — AUTO 모드일 때 필수, 1 이상
 * @param marks          수동 모드 시 marks 배열 — MANUAL 모드일 때 필수
 */
public record MarkingRequest(
        @NotBlank(message = "mode 는 필수입니다.")
        String mode,

        Integer intervalFrames,

        List<MarkItem> marks
) {}
