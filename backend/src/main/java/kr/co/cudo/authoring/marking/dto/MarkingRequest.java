package kr.co.cudo.authoring.marking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

        /*
         * C-ISSUE-01 — @Valid 누락으로 MarkItem 의 제약(@NotNull/@Min/@Pattern)이 <b>전혀 발화하지
         * 않던</b> 결함을 수정한다(중첩 검증은 @Valid 가 있어야 전파된다). @Size 는 과대 요청 DoS 방어
         * (CWE-770) — 30fps·10분 영상의 전 프레임 마킹(18,000)을 넉넉히 수용하는 상한.
         */
        @Valid
        @Size(max = 20000, message = "한 번에 처리 가능한 마킹 수 초과 (최대 20000)")
        List<MarkItem> marks
) {}
