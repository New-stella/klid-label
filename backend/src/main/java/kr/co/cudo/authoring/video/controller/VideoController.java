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

    /**
     * 오토라벨 요약 placeholder — SCR-AUTO-002 진입 시 외부 시계열 메타/객체 검증 요약 표시용.
     * V1.7 기준 시계열 메타 자동 추출은 외부 시스템 책임이며, 본 엔드포인트는 빈 placeholder 만 반환한다.
     */
    @Operation(
            summary = "오토라벨 요약 조회 (REVIEWER) — placeholder",
            description = "V1.7 외부 시스템(시계열 메타) 연동 전 placeholder. 객체 수/검증 통과율/메타 카운트 0 반환."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping("/{rawSn}/auto-summary")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<java.util.Map<String, Object>> autoSummary(
            @Parameter(description = "raw 영상 PK", required = true, example = "1") @PathVariable Long rawSn) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("videoId", rawSn);
        body.put("yoloObjectCount", 0);
        body.put("sam2TrackCount", 0);
        body.put("vlmVerifiedCount", 0);
        body.put("metaCount", 0);
        body.put("status", "PENDING");
        body.put("message", "외부 시계열 메타 추출 시스템 연동 전 — placeholder 응답");
        return ApiResponse.ok(body);
    }
}
