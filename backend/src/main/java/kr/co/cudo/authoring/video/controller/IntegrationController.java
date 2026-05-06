package kr.co.cudo.authoring.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.auth.m2m.M2mTokenAuthenticationFilter;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.video.dto.VideoIngestRequest;
import kr.co.cudo.authoring.video.dto.VideoIngestResponse;
import kr.co.cudo.authoring.video.service.VideoIngestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관제서버 → 저작도구 통합 수신 API.
 * - SecurityConfig 의 path 매처(/v1/integration/control/**)와 M2M_AUTHORITY 권한이 함께 보호.
 * - @PreAuthorize 는 Method-level Security 가 적용되도록 명시 (이중 방어).
 */
@Tag(name = "Integration (Control M2M)", description = "관제서버 ↔ 저작도구 M2M 통합 수신 — X-M2M-Token 헤더 필수. JWT 사용자 토큰 아님.")
@RestController
@RequestMapping("/v1/integration/control")
@RequiredArgsConstructor
public class IntegrationController {

    private final VideoIngestService videoIngestService;

    @Operation(
            summary = "관제서버로부터 영상 수신 (배치 진입)",
            description = "관제서버가 라벨링 필요 영상 목록을 송신한다. 신규 영상은 201 Created, 기존 영상 업데이트는 200 OK를 반환. " +
                    "X-M2M-Token 헤더로 인증되며 사용자 JWT 토큰과는 별개의 채널이다."
    )
    @SecurityRequirement(name = "m2mToken")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "신규 영상 생성"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기존 영상 업데이트"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "M2M 토큰 없음/검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "M2M 권한 없음")
    })
    @PostMapping("/videos")
    @PreAuthorize("hasAuthority('" + M2mTokenAuthenticationFilter.M2M_AUTHORITY + "')")
    public ResponseEntity<ApiResponse<VideoIngestResponse>> ingest(@Valid @RequestBody VideoIngestRequest req) {
        VideoIngestResponse res = videoIngestService.ingest(req);
        HttpStatus status = res.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(res));
    }
}
