package kr.co.cudo.authoring.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

/**
 * [개발/검수 전용] 레거시 비식별 프레임 복구 실행 요청.
 *
 * <p>필드는 {@code rawSn} 하나뿐이며 <b>선택</b>이다 — 지정하면 그 영상 1건만, 생략(null)하면 대상
 * 전체를 1회 상한만큼 처리한다. 상태를 바꾸는 값은 이 하나뿐이라 Mass Assignment 표면이 없다
 * (엔티티 직접 바인딩 금지 규칙 준수 — CWE-915).
 *
 * @param rawSn 복구 대상 영상 PK (선택 — 생략 시 전체)
 */
@Schema(description = "레거시 비식별 프레임 복구 실행 요청 (rawSn 생략 시 전체 대상)")
public record DeidentFrameRecoveryRequest(
        @Schema(description = "복구 대상 영상 PK. 생략하면 대상 전체를 상한만큼 처리한다.", example = "12")
        @Min(value = 1, message = "rawSn 은 1 이상이어야 합니다.")
        Long rawSn
) {
}
