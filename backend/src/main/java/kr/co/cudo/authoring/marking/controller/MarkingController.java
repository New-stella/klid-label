package kr.co.cudo.authoring.marking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.dto.MarkingRequest;
import kr.co.cudo.authoring.marking.dto.MarkingResponse;
import kr.co.cudo.authoring.marking.service.MarkingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 영상 마킹 API — 자동/수동 이벤트 마킹.
 */
@Tag(name = "Marking", description = "영상 마킹 API -- 자동/수동 이벤트 마킹")
@RestController
@RequestMapping("/v1/videos/{rawSn}/markings")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class MarkingController {

    private final MarkingService markingService;

    @Operation(summary = "마킹 생성",
            description = "자동 또는 수동 모드로 영상 마킹을 생성합니다. "
                    + "[AUTO 모드 제약] 자동 마킹은 영상의 실제 FPS 를 사용하지 않고 30fps 로 고정 가정하여 "
                    + "프레임 번호(durationSec×30)와 타임스탬프(frameIndex/30초)를 계산합니다. "
                    + "30fps 가 아닌 영상은 마크 프레임/타임스탬프가 실제와 어긋날 수 있으므로, "
                    + "정확한 프레임 정렬이 필요하면 수동(MANUAL) 모드를 사용하세요.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<MarkingResponse> create(
            @Parameter(description = "raw 영상 PK", required = true) @PathVariable Long rawSn,
            @Valid @RequestBody MarkingRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(markingService.create(rawSn, req, actor));
    }
}
