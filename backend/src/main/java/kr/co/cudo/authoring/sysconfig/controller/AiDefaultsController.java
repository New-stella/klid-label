package kr.co.cudo.authoring.sysconfig.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.sysconfig.dto.AiDefaultsResponse;
import kr.co.cudo.authoring.sysconfig.service.AiDefaultsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 정밀도 기본값 읽기 전용 조회 (API-193).
 *
 * <p><b>관리 영역({@code /v1/manage/**}) 밖</b>에 둔다 — 그 경로는 검수자 전용이라 작업자가 부르면
 * 매 진입마다 403 이 쌓인다. 라벨링 화면은 역할과 무관하게 이 값이 필요하다.
 *
 * <p>기존 {@code GET /v1/manage/configs} 의 읽기 권한만 넓히는 방식은 채택하지 않았다. 그 응답은
 * 설정 9종 전량과 함께 <b>마지막 수정자 계정 식별자</b>를 담고 있어, 작업자에게 운영 파라미터와
 * 검수자 계정 정보가 함께 나간다. 화면이 실제로 쓰는 값은 두 개뿐이므로 그 둘만 노출한다.
 *
 * <p><b>쓰기 대응물을 두지 않는다.</b> 설정 변경은 검수자 전용
 * {@code PUT /v1/manage/configs/{key}} 가 담당한다.
 *
 * <p>인가는 2단이다 — 1차는 {@code SecurityConfig} 의 {@code /v1/**} 매처(INTERNAL 채널 +
 * REVIEWER|WORKER|STREAM_SIGNED)이고, 여기 {@code @PreAuthorize} 가 2차로 서명 스트림 컨텍스트를
 * 배제해 사람 역할만 남긴다.
 */
@Tag(name = "AI Defaults", description = "AI 정밀도 기본값 조회 — 내부 채널 검수자·작업자 읽기 전용")
@RestController
@RequestMapping("/v1/ai-defaults")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AiDefaultsController {

    private final AiDefaultsService service;

    @Operation(
            summary = "AI 정밀도 기본값 조회 (REVIEWER/WORKER)",
            description = "라벨링 화면 AI 도구 슬라이더의 초기값 두 개를 반환한다. "
                    + "저장값이 없거나 숫자로 해석되지 않는 항목은 응답에서 생략되며 화면이 자체 기본값으로 대체한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "내부 채널 검수자·작업자 아님")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<AiDefaultsResponse> get() {
        return ApiResponse.ok(service.get());
    }
}
