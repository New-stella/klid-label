package kr.co.cudo.authoring.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.dto.VideoExclusionRequest;
import kr.co.cudo.authoring.video.dto.VideoExclusionResponse;
import kr.co.cudo.authoring.video.dto.VideoRestoreResponse;
import kr.co.cudo.authoring.video.service.VideoExclusionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>영상 제외·복원</b> 창구 — 영상을 지우지 않고 저작도구 화면 목록에서만 뺀다.
 * [@design ADR-069] [@design API-260] [@design API-261] [@design UC-043]
 *
 * <h3>왜 두 창구인가</h3>
 * 제외가 「제외 표시」라는 하위 자원을 <b>세우고</b> 복원이 그것을 <b>지운다</b>. 주소는 같고 방식만
 * 다르므로 짝이 주소에서 눈에 보인다. <b>같은 주소에 파라미터를 붙여 행위를 가르지 않는다</b>(저장소
 * 규약). 배치 단계 건너뛰기가 같은 모양의 선례다.
 *
 * <h3>인가 — 검수자 이상</h3>
 * 역할 계층으로 관리자도 그대로 수행한다. ★<b>관리자 전용으로 좁히지 않는다</b> — 목록을 정리하는
 * 사람이 검수자이고, 좁히면 그 사람이 자기 작업 화면을 정리하지 못한다. 화면에서 동작을 감추는 것과
 * 서버가 막는 것은 <b>서로를 대신하지 못하므로</b>, 화면을 거치지 않고 이 창구를 직접 부른 작업자의
 * 요청도 여기서 거부된다.
 *
 * <h3>★ 경계</h3>
 * 제외는 저작도구 화면 시야만 바꾼다 — 배치 파이프라인 · 관제 통지 · 데이터마트 조회 뷰 · 학습데이터
 * 산출물 · 관제 조회 창구 · 통계 · 포털 채널은 전부 무변경이다. 자세한 사유는
 * {@link VideoExclusionService} 클래스 주석에 있다.
 */
@Tag(name = "Video", description = "영상 제외·복원 — 화면 목록에서 빼고 되돌린다(검수자 이상).")
@RestController
@RequestMapping("/v1/videos")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Validated
public class VideoExclusionController {

    private final VideoExclusionService videoExclusionService;

    @Operation(
            summary = "영상 제외 — 화면 목록에서 뺀다 (검수자 이상)",
            description = "영상 하나를 저작도구 화면 목록 세 곳(영상 처리 현황 · 작업 목록 · 검수 목록)에서 뺀다. "
                    + "행을 지우지 않고 제외 표시만 세우므로 언제든 되돌릴 수 있다. "
                    + "사유는 필수이며, 받은 문구는 개행·제어문자를 제거한 뒤 사유 칸 폭(500자) 안으로 "
                    + "길이를 제한해 저장한다 — 사유에 개인정보를 적지 않는다. "
                    + "이미 제외된 영상에 같은 요청을 다시 보내면 아무것도 바꾸지 않고 이력도 남기지 않으며 "
                    + "200 이다(멱등, 응답의 changed 로 구분한다). "
                    + "배정이 있는 영상은 409 로 거부하며 이는 일시 조건이다 — 배정을 해제하면 같은 요청이 수락된다. "
                    + "★제외는 화면 시야만 바꾼다 — 배치 파이프라인·관제 통지·데이터마트 조회 뷰·학습데이터 "
                    + "산출물·관제 조회 창구·통계·포털 채널은 전부 무변경이고, 라벨링 상세 진입도 막지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "제외됨(또는 이미 제외된 영상에 대한 멱등 무변경 성공)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "사유가 비어 있거나 공백만 / 영상 식별자 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "검수자 미만 — 라벨링 작업자는 수행할 수 없다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "배정이 있는 영상 — 먼저 배정을 해제해야 한다")
    })
    @PostMapping("/{rawSn}/exclusion")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<VideoExclusionResponse> exclude(
            @Parameter(description = "제외할 영상의 단위 식별자(1 이상)")
            @PathVariable @Min(1) Long rawSn,
            @Valid @RequestBody VideoExclusionRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(videoExclusionService.exclude(rawSn, request.reason(), actor));
    }

    @Operation(
            summary = "영상 복원 — 화면 목록 제외 해제 (검수자 이상)",
            description = "제외했던 영상을 다시 저작도구 화면 목록에 보이게 한다. 제외가 세운 하위 자원을 지우는 짝이다. "
                    + "요청 본문이 없다 — 사유를 받지 않는다. 「왜」는 제외의 확정 요구였고 복원은 아니며, "
                    + "사유를 주소에 실으면 접근 기록에 개인정보가 남는다(감추는 쪽만 사유를 남긴다). "
                    + "이미 보이는 영상을 복원하면 아무것도 바꾸지 않고 이력도 남기지 않으며 200 이다(멱등). "
                    + "배정 존재 조건을 두지 않는 것은 의도다 — 제외된 영상에는 배정이 존재할 수 없다. "
                    + "★복원은 아무것도 재생성하지 않고 관제 수정 통지도 내지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "복원됨(또는 이미 보이는 영상에 대한 멱등 무변경 성공)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "영상 식별자 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "검수자 미만 — 라벨링 작업자는 수행할 수 없다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @DeleteMapping("/{rawSn}/exclusion")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<VideoRestoreResponse> restore(
            @Parameter(description = "복원할 영상의 단위 식별자(1 이상)")
            @PathVariable @Min(1) Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(videoExclusionService.restore(rawSn, actor));
    }
}
