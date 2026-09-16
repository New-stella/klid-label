package kr.co.cudo.authoring.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 영상 제외 요청 — <b>사유 하나</b>를 받는다. [@design API-260] [@design ADR-069]
 *
 * <h3>왜 사유가 필수인가</h3>
 * 「왜 뺐는가」가 이 창구의 확정 요구다. 무엇이 왜 보이지 않게 됐는지가 남아 있지 않으면 나중에
 * 되돌릴지 판단할 근거가 없다. <b>감추는 쪽만 사유를 남긴다</b> — 되돌리는 복원 창구는 요청 본문 자체가
 * 없다(지우는 방식 창구에 본문을 두지 않고, 사유를 주소에 실으면 접근 기록에 개인정보가 남는다).
 *
 * <h3>길이 상한은 거부가 아니라 절단이다</h3>
 * {@code @NotBlank} 만 걸고 길이 제약을 걸지 않는다 — 창구 계약이 정한 400 사유는 「비어 있거나 공백만」
 * 하나이고, 받은 문구는 개행·제어문자를 제거한 뒤 이벤트 원장의 사유 칸 폭 안으로 <b>길이를 제한해
 * 저장</b>한다. 정규화·절단의 소유자는 {@code VideoExclusionService} 한 곳이다.
 *
 * <p>⚠ 사유에 개인정보를 적지 않는다 — 원장에 그대로 남고 이력 화면에 그대로 보인다(화면이 입력 칸에서
 * 그 점을 안내한다).
 */
@Schema(description = "영상 제외 요청")
public record VideoExclusionRequest(

        @Schema(description = "제외 사유. 필수이며 공백만으로는 수락하지 않는다. 개행·제어문자를 제거한 뒤 "
                + "작업 이벤트 원장의 사유 칸 폭(500자) 안으로 길이를 제한해 저장한다. "
                + "개인정보를 적지 않는다.",
                example = "관제 인입 시험 데이터라 작업 대상이 아님",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "제외 사유를 입력해 주세요.")
        String reason
) {
}
