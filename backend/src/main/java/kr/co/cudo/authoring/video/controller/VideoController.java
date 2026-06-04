package kr.co.cudo.authoring.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.video.dto.AutoLabelResultResponse;
import kr.co.cudo.authoring.video.dto.ResolutionChangeRequest;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse;
import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.dto.VideoSummaryResponse;
import kr.co.cudo.authoring.video.service.FrameImageService;
import kr.co.cudo.authoring.video.service.VideoQueryService;
import kr.co.cudo.authoring.video.service.VideoResolutionService;
import kr.co.cudo.authoring.video.service.VideoStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

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
@Validated
public class VideoController {

    private final VideoQueryService videoQueryService;
    private final FrameImageService frameImageService;
    private final VideoStreamService videoStreamService;
    private final VideoResolutionService videoResolutionService;

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
    public ApiResponse<Page<VideoSummaryResponse>> list(
            @PageableDefault(size = 20) Pageable pageable,
            @Parameter(description = "데이터 상태 코드 필터 (예: BATCH_COMPLETED, BATCH_PROCESSING, PENDING, BATCH_FAILED)")
            @RequestParam(required = false) String dataSttsCd,
            @Parameter(description = "검수 상태 코드 필터 — LS_RAW_DATA_STATUS 기준 (PENDING/ASSIGNED/IN_REVIEW/APPROVED/REJECTED). " +
                    "지정 시 LS_RAW_DATA_STATUS INNER JOIN 으로 필터링되어 row 가 없는 영상은 제외된다. " +
                    "증강 요청 화면(SCR-AUG-001)에서 APPROVED 영상만 노출하는 용도.")
            @RequestParam(required = false) String reviewStatusCd) {
        return ApiResponse.ok(videoQueryService.list(pageable, dataSttsCd, reviewStatusCd));
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

    @Operation(
            summary = "영상별 오토라벨 결과 조회",
            description = "rawSn 영상에 연결된 모든 프레임의 라벨(auto + manual) 목록. FE 라벨 패널 표시용."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패")
    })
    @GetMapping("/{rawSn}/labels/auto")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<AutoLabelResultResponse> getAutoLabels(
            @Parameter(description = "raw 영상 PK", required = true, example = "1") @PathVariable Long rawSn) {
        return ApiResponse.ok(videoQueryService.getAutoLabels(rawSn));
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

    /**
     * 영상 파일 스트리밍 — HTTP Range 지원 (영상 시크 가능).
     * <p>LS_DATA_RAW.FILE_PATH 기반. Path Traversal 방어 (CWE-22).
     */
    @Operation(
            summary = "영상 파일 스트리밍",
            description = "raw 영상 PK 로 영상 파일을 HTTP Range 지원하여 스트리밍. " +
                    "Range 헤더 없으면 200 OK + 전체 파일, Range 있으면 206 Partial Content. " +
                    "Path Traversal 방어 (CWE-22) + Cache-Control: private, max-age=3600."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공 — 전체 파일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "206", description = "성공 — Range 부분 응답"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Path Traversal 의심"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상/파일 없음")
    })
    @GetMapping("/{rawSn}/stream")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ResourceRegion> streamVideo(
            @Parameter(description = "raw 영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @RequestHeader HttpHeaders headers) throws IOException {
        return videoStreamService.stream(rawSn, headers);
    }

    /**
     * SCR-REVIEW-002 — 프레임 이미지 byte streaming.
     * <p>rawSn + frameNo 기반. PRVC/PSDO 영상은 비식별 경로만 사용 (REVIEWER 도 원본 강제 노출 금지).
     */
    @Operation(
            summary = "프레임 이미지 다운로드 (rawSn + frameNo)",
            description = "검수 화면용 프레임 이미지 byte streaming. " +
                    "비식별 대상(PRVC/PSDO) 영상은 deid 경로 강제 사용. " +
                    "Path Traversal 방어 (CWE-22) + 확장자 allowlist + Cache-Control: private, max-age=3600."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공 — image/jpeg or image/png or image/webp"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Path Traversal 의심 / 허용 외 확장자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상/프레임/파일 없음 또는 비식별 미완료")
    })
    @GetMapping("/{rawSn}/frames/{frameNo}/image")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> getFrameImage(
            @Parameter(description = "raw 영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @Parameter(description = "프레임 번호 (0-base)", required = true, example = "0")
            @PathVariable @Min(value = 0, message = "frameNo는 0 이상이어야 합니다.") Integer frameNo,
            @Parameter(description = "REVIEWER 한정 — true 면 원본(RAW) 프레임 반환. WORKER 는 무시되고 DEID 강제.")
            @RequestParam(name = "raw", defaultValue = "false") boolean raw,
            @AuthenticationPrincipal TokenClaims actor
    ) throws IOException {
        if (frameNo == null || frameNo < 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "frameNo는 0 이상이어야 합니다.");
        }
        return frameImageService.serve(rawSn, frameNo, raw, actor);
    }

    /**
     * 해상도 변경 — Phase 1 (RQ-SFR-06-03 v1.8/1.10).
     * <p>검수 완료(APPROVED) 원본 영상의 프레임 이미지셋을 표준 하위 해상도(RES_1080P/RES_720P/RES_480P)로
     * 종횡비 보존 다운스케일한다. 영상(비디오) 재생성·라벨/메타 복사·새 영상(RAW_SN) 생성은 하지 않으며,
     * 산출물은 다운스케일 이미지셋 + LS_RESOLUTION_EXPORT 1행뿐이다. REVIEWER 만 호출 가능.
     */
    @Operation(
            summary = "해상도 변경 (REVIEWER)",
            description = "검수 완료(APPROVED) 원본 영상의 프레임 이미지셋을 표준 하위 해상도(RES_1080P/RES_720P/RES_480P)로 " +
                    "종횡비 보존 다운스케일한다. 영상·라벨·메타·신규 영상 행은 생성하지 않으며 LS_RESOLUTION_EXPORT 1행만 기록한다. " +
                    "업스케일(목표 ≥ 원본 높이)·증강본(PARENT_RAW_SN 보유)·프레임 0건·중복 요청은 거부된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 — exportSn + 타겟 해상도 + 프레임 개수 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "프리셋 오류 / 업스케일 / 증강본 / 프레임 0건 / 해상도 확인 불가"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "미검수 영상 / 중복 결과 존재")
    })
    @PostMapping("/{rawSn}/resolution")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ResolutionChangeResponse> changeResolution(
            @Parameter(description = "원본 영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @Valid @RequestBody ResolutionChangeRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        String regId = actor != null ? actor.sub() : null;
        return ApiResponse.ok(videoResolutionService.changeResolution(rawSn, request, regId));
    }
}
