package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.FrameDescriptionRequest;
import kr.co.cudo.authoring.label.dto.FrameDescriptionResponse;
import kr.co.cudo.authoring.label.service.FrameDescriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * blocker#2 — 프레임 설명(NIA image.description) 저장/조회 API.
 *
 * <p>인가는 서비스의 {@link FrameDescriptionService}(LabelAccessGuard 재사용)에서 처리한다:
 * REVIEWER 통과 / WORKER 본인 배정 프레임만 / 그 외 403(CWE-639 IDOR 방어).
 */
@Tag(name = "FrameDescription", description = "프레임 설명(NIA image.description) 저장·조회 — REVIEWER/WORKER. 본인 배정 검증(IDOR 방어).")
@RestController
@RequestMapping("/v1/frames")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class FrameDescriptionController {

    private final FrameDescriptionService frameDescriptionService;

    @Operation(
            summary = "프레임 설명 조회",
            description = "프레임에 저장된 설명(자연어)을 반환한다. WORKER는 본인 배정 프레임만 접근 가능. 미입력 시 null."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (CWE-639 방어)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음")
    })
    @GetMapping("/{srcSn}/description")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<FrameDescriptionResponse> getDescription(
            @Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(frameDescriptionService.get(srcSn, actor));
    }

    @Operation(
            summary = "프레임 설명 저장/수정",
            description = "프레임 설명(자연어, 최대 1000자)을 저장/수정한다. 빈값/null 저장 시 설명 삭제. "
                    + "path srcSn 과 body srcSn 불일치 시 400 (CWE-345). 검수 완료(APPROVED) 후 수정 시 TASK_MODIFIED 통지."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패 / srcSn 불일치 / 1000자 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (CWE-639 방어)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음")
    })
    @PutMapping("/{srcSn}/description")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<FrameDescriptionResponse> updateDescription(
            @Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
            @Valid @RequestBody FrameDescriptionRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        // path 의 srcSn 과 body 의 srcSn 불일치 시 거부 (CWE-345).
        if (!srcSn.equals(req.srcSn())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "path 의 srcSn 과 body 의 srcSn 이 다릅니다.");
        }
        return ApiResponse.ok(frameDescriptionService.update(srcSn, req.description(), actor));
    }
}
