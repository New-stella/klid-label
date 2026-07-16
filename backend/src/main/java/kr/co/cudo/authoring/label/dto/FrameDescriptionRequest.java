package kr.co.cudo.authoring.label.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 프레임 설명(NIA image.description) 저장/수정 요청.
 *
 * <p>path 의 {@code srcSn} 과 body 의 {@code srcSn} 불일치는 컨트롤러에서 400 으로 거부한다(CWE-345).
 * description 은 null/빈값 허용(설명 삭제) — {@code @Size} 로 상한만 강제.
 *
 * @param srcSn       프레임 PK (path 와 일치해야 함)
 * @param description 프레임 설명(자연어). null/빈값 = 삭제. 최대 1000자.
 */
public record FrameDescriptionRequest(
        @NotNull Long srcSn,
        @Size(max = 1000, message = "설명은 1000자 이내여야 합니다.") String description
) {
}
