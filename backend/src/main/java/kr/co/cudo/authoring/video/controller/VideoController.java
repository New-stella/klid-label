package kr.co.cudo.authoring.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.dto.VideoSummaryResponse;
import kr.co.cudo.authoring.video.service.VideoQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 영상 조회 API. REVIEWER/WORKER 모두 조회 가능.
 * - WORKER 의 본인 배정 영상 한정 필터는 Phase 5+ 에서 어노테이션 화면 진입 시 적용.
 * - 본 Phase 는 페이징 검증 + 단건 조회만 제공.
 */
@Tag(name = "Video", description = "영상(원본 raw) 조회 — REVIEWER/WORKER. WORKER는 향후 본인 배정 영상만 노출 예정.")
@RestController
@RequestMapping("/v1/videos")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class VideoController {

    private final VideoQueryService videoQueryService;

    @Operation(
            summary = "영상 목록 조회 (페이징)",
            description = "수신된 raw 영상 목록을 페이징 조회. 기본 size=20."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패")
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Page<VideoSummaryResponse>> list(@PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(videoQueryService.list(pageable));
    }

    @Operation(
            summary = "영상 상세 조회",
            description = "raw 영상 PK로 단건 조회. 메타·비식별 여부·길이 등 상세 정보 반환."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/{rawSn}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<VideoDetailResponse> getOne(@Parameter(description = "raw 영상 PK", required = true, example = "1") @PathVariable Long rawSn) {
        return ApiResponse.ok(videoQueryService.getOne(rawSn));
    }
}
