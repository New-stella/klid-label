package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.label.service.Sam2TrackService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Label", description = "라벨 CRUD 및 SAM2 Track 추론 — REVIEWER/WORKER. 본인 배정 프레임 검증(IDOR 방어) 적용.")
@RestController
@RequestMapping("/v1/frames")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class LabelController {

    private final LabelService labelService;
    private final Sam2TrackService sam2TrackService;

    @Operation(
            summary = "프레임 라벨 조회",
            description = "프레임에 부여된 모든 라벨(BBox/Polygon/Segmentation/Track) 반환. WORKER는 본인 배정 프레임만 접근 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 (CWE-639 방어)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음")
    })
    @GetMapping("/{srcSn}/labels")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<LabelResponse> getLabels(@Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
                                                @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(labelService.getByFrame(srcSn, actor));
    }

    @Operation(
            summary = "프레임 라벨 일괄 저장",
            description = "프레임 라벨을 일괄 upsert (전체 교체 의미론). 저장 후 Gitea 자동 커밋 및 LS_DATA_LBL_HSTRY 기록."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음")
    })
    @PutMapping("/{srcSn}/labels")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<LabelResponse> bulkUpsert(@Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
                                                  @Valid @RequestBody LabelBulkUpsertRequest req,
                                                  @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(labelService.bulkUpsert(srcSn, req, actor));
    }

    @Operation(
            summary = "SAM2 Track 추론",
            description = "프레임의 시드 마스크/박스를 ai-server로 송신하여 SAM2 트래킹 결과를 받는다. " +
                    "path srcSn과 body srcSn 불일치 시 400 (CWE-345)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패 / srcSn 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "ai-server 연동 실패 (서킷 브레이커)")
    })
    @PostMapping("/{srcSn}/sam2-track")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<Sam2TrackResponseDto> sam2Track(@Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
                                                       @Valid @RequestBody Sam2TrackRequest req,
                                                       @AuthenticationPrincipal TokenClaims actor) {
        // path srcSn 과 body srcSn 불일치 시 거부 (CWE-345)
        if (!srcSn.equals(req.srcSn())) {
            throw new kr.co.cudo.authoring.common.exception.CustomException(
                    kr.co.cudo.authoring.common.exception.ErrorCode.INVALID_INPUT,
                    "path 의 srcSn 과 body 의 srcSn 이 다릅니다.");
        }
        return ApiResponse.ok(sam2TrackService.track(req, actor));
    }
}
