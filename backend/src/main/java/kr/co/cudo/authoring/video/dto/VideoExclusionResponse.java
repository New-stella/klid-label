package kr.co.cudo.authoring.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 영상 제외 응답. [@design API-260] [@design AC-1125]
 *
 * <h3>★ 「바꿨다」와 「이미 그 상태였다」는 상태코드로 가르지 않는다</h3>
 * 두 경우 모두 <b>성공</b>이며 상태코드가 같다(멱등 — 되풀이 호출은 오류가 아니다). 호출자는
 * {@link #changed} 로 가른다. 무변경이면 사유와 제외 시각을 <b>비운다</b> — 값이 실제로 바뀐 경우에만
 * 이력이 남으므로, 남지 않은 이력의 시각을 지어내지 않기 위해서다.
 *
 * @param rawSn      제외된 영상의 단위 식별자
 * @param excluded   요청 처리 후 그 영상이 제외 상태인지 — 이 창구가 성공하면 항상 참이다
 * @param changed    이번 요청이 실제로 값을 바꿨는지. 거짓이면 이미 제외돼 있었고 <b>이력도 남지 않았다</b>
 * @param reason     기록된 사유(정규화·절단 후 값). 무변경이면 {@code null}
 * @param excludedAt 제외 이력이 남은 시각. 무변경이면 {@code null}
 */
@Schema(description = "영상 제외 결과")
public record VideoExclusionResponse(

        @Schema(description = "제외된 영상의 단위 식별자", example = "1")
        Long rawSn,

        @Schema(description = "요청 처리 후 제외 상태인지. 이 창구가 성공하면 항상 true.", example = "true")
        boolean excluded,

        @Schema(description = "이번 요청이 실제로 값을 바꿨는지. false 면 이미 제외돼 있었고 이력도 남지 않았다.",
                example = "true")
        boolean changed,

        @Schema(description = "기록된 사유(개행·제어문자 제거 + 길이 제한 후 값). 무변경이면 null.",
                example = "관제 인입 시험 데이터라 작업 대상이 아님")
        String reason,

        @Schema(description = "제외 이력이 남은 시각. 무변경이면 null.", example = "2026-09-15T17:20:00")
        LocalDateTime excludedAt
) {

    /** 실제로 제외된 경우. */
    public static VideoExclusionResponse changed(Long rawSn, String reason, LocalDateTime excludedAt) {
        return new VideoExclusionResponse(rawSn, true, true, reason, excludedAt);
    }

    /** 이미 제외돼 있던 경우 — 아무것도 바꾸지 않았고 이력도 남기지 않았다. */
    public static VideoExclusionResponse unchanged(Long rawSn) {
        return new VideoExclusionResponse(rawSn, true, false, null, null);
    }
}
