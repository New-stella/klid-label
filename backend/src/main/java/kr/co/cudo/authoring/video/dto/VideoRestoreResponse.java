package kr.co.cudo.authoring.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 영상 복원 응답. [@design API-261] [@design AC-1125]
 *
 * <p>제외 응답과 <b>모양이 다른 것은 의도</b>다 — 복원은 사유를 받지도 남기지도 않으므로 사유와 시각
 * 칸이 없다. 「일관성」을 이유로 사유 칸을 붙이지 말 것.
 *
 * <p>{@link #restored} 가 거짓이면 이미 보이는 영상이었고 <b>이력도 남지 않았다</b>(멱등 — 오류가 아니다).
 * 이 응답은 산출물 재생성이나 관제 수정 통지를 뜻하지 않는다.
 *
 * @param rawSn    복원 대상 영상의 단위 식별자
 * @param excluded 이 요청 이후의 제외 표시 상태 — 복원했으므로 언제나 거짓이다
 * @param restored 이 요청이 실제로 표시를 바꿨는지
 */
@Schema(description = "영상 복원 결과")
public record VideoRestoreResponse(

        @Schema(description = "복원 대상 영상의 단위 식별자", example = "1024")
        Long rawSn,

        @Schema(description = "이 요청 이후의 제외 표시 상태. 복원했으므로 언제나 false.", example = "false")
        boolean excluded,

        @Schema(description = "이 요청이 실제로 표시를 바꿨는지. false 면 이미 보이는 영상이었고 이력도 남지 않았다.",
                example = "true")
        boolean restored
) {

    public static VideoRestoreResponse of(Long rawSn, boolean restored) {
        return new VideoRestoreResponse(rawSn, false, restored);
    }
}
