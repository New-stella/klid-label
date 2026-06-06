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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @Operation(summary = "마킹 생성", description = "자동 또는 수동 모드로 영상 마킹을 생성합니다.")
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

    @Operation(summary = "마킹 목록 조회", description = "영상별 마킹 목록을 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<List<MarkingResponse>> list(
            @Parameter(description = "raw 영상 PK", required = true) @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(markingService.list(rawSn, actor));
    }

    @Operation(summary = "마킹 단건 조회", description = "마킹 PK로 단건 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "다른 영상의 마킹"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "마킹 없음")
    })
    @GetMapping("/{markingSn}")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<MarkingResponse> get(
            @Parameter(description = "raw 영상 PK", required = true) @PathVariable Long rawSn,
            @Parameter(description = "마킹 PK", required = true) @PathVariable Long markingSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(markingService.get(rawSn, markingSn, actor));
    }

    @Operation(summary = "마킹 삭제 (REVIEWER 전용)", description = "REVIEWER 만 마킹을 삭제할 수 있습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음 또는 다른 영상의 마킹"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "마킹 없음")
    })
    @DeleteMapping("/{markingSn}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<Void> delete(
            @Parameter(description = "raw 영상 PK", required = true) @PathVariable Long rawSn,
            @Parameter(description = "마킹 PK", required = true) @PathVariable Long markingSn) {
        markingService.delete(rawSn, markingSn);
        return ApiResponse.ok(null);
    }
}
