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
import kr.co.cudo.authoring.video.service.AutoLabelSummaryService;
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
@Tag(name = "Video", description = "영상(원시 raw) 조회 — REVIEWER/WORKER. WORKER는 향후 본인 배정 영상만 노출 예정.")
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
    private final AutoLabelSummaryService autoLabelSummaryService;

    @Operation(
            summary = "영상 목록 조회 (페이징)",
            description = "수신된 raw 영상 목록을 페이징 조회. 기본 size=20."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패")
    })
    @GetMapping
    // 관찰-1: 역할 미배정(role=null) INTERNAL 사용자의 영상 콘텐츠 노출 차단. REVIEWER/WORKER 만 허용.
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<Page<VideoSummaryResponse>> list(
            @PageableDefault(size = 20) Pageable pageable,
            @Parameter(description = "데이터 상태 코드 필터 (예: BATCH_COMPLETED, BATCH_PROCESSING, PENDING, BATCH_FAILED)")
            @RequestParam(required = false) String dataSttsCd,
            @Parameter(description = "검수 상태 코드 필터 — LS_RAW_DATA_STATUS 기준 (PENDING/ASSIGNED/IN_REVIEW/APPROVED/REJECTED). " +
                    "지정 시 LS_RAW_DATA_STATUS INNER JOIN 으로 필터링되어 row 가 없는 영상은 제외된다. " +
                    "증강 요청 화면(SCR-AUG-001)에서 APPROVED 영상만 노출하는 용도.")
            @RequestParam(required = false) String reviewStatusCd) {
        return ApiResponse.ok(videoQueryService.list(safeSort(pageable), dataSttsCd, reviewStatusCd));
    }

    /**
     * 외부 노출 정렬 키 → 엔티티 필드 allowlist 매핑 (CWE-20).
     *
     * <p>FE 는 {@code sort=capturedAt,desc} 같은 외부 키를 보내는데 엔티티 실제 필드는 {@code shtDt}(촬영시각)다.
     * allowlist 밖의 임의 프로퍼티가 Pageable 로 직행하면 Spring Data 가
     * {@code PropertyReferenceException} → 500 으로 노출하므로, 컨트롤러에서 안전하게 변환·폴백한다.
     * allowlist 밖 키는 drop 되고 유효 정렬이 없으면 {@code regDt DESC} 기본 정렬로 폴백한다 (500 금지).
     */
    private static final java.util.Map<String, String> VIDEO_SORT_ALLOWLIST = java.util.Map.of(
            "capturedAt", "shtDt",
            "shtDt", "shtDt",
            "regDt", "regDt",
            "createdAt", "regDt",
            "updatedAt", "mdfcnDt",
            "rawSn", "rawSn",
            "id", "rawSn"
    );

    private static final org.springframework.data.domain.Sort DEFAULT_VIDEO_SORT =
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "regDt");

    private Pageable safeSort(Pageable pageable) {
        return kr.co.cudo.authoring.common.web.SortFieldMapper.apply(pageable, VIDEO_SORT_ALLOWLIST, DEFAULT_VIDEO_SORT);
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
    // 관찰-1: 역할 미배정(role=null) 차단. (후속: WORKER 본인 배정 영상 한정 IDOR 검증은 Phase 5+)
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
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
    // 관찰-1: 역할 미배정(role=null) 차단.
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<AutoLabelResultResponse> getAutoLabels(
            @Parameter(description = "raw 영상 PK", required = true, example = "1") @PathVariable Long rawSn) {
        return ApiResponse.ok(videoQueryService.getAutoLabels(rawSn));
    }

    /**
     * 오토라벨 요약 조회 (SCR-AUTO-001) — 실데이터 집계.
     *
     * <p>LS_DATA_SRC(프레임)·LS_DATA_LBL(라벨) 기준으로 총 프레임/총 라벨/클래스 분포/신뢰도 분포/
     * 저신뢰 프레임 목록을 집계한다. 영상 미존재 시 404, 프레임 0건(배치 미완료) 시 status='PENDING'.
     */
    @Operation(
            summary = "오토라벨 요약 조회 (REVIEWER)",
            description = "rawSn 영상의 오토라벨 결과 실집계 — 총 프레임/총 라벨/클래스 분포/신뢰도 분포/저신뢰 프레임. " +
                    "영상 없음 404, 배치 미완료(프레임 0)면 status='PENDING'."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/{rawSn}/auto-summary")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<kr.co.cudo.authoring.video.dto.AutoLabelSummaryResponse> autoSummary(
            @Parameter(description = "raw 영상 PK", required = true, example = "1") @PathVariable Long rawSn) {
        return ApiResponse.ok(autoLabelSummaryService.summarize(rawSn));
    }

    /**
     * 영상 스트림 단기 서명 URL 발급.
     * <p>&lt;video&gt; 엘리먼트가 Authorization 헤더를 못 붙여 401 이 나는 문제를 우회한다.
     * 인증된 사용자가 호출하면 짧은 TTL HMAC 서명 쿼리가 붙은 스트림 URL 을 반환한다 (JWT 본문 미노출).
     */
    @Operation(
            summary = "영상 스트림 서명 URL 발급",
            description = "인증 필수(INTERNAL 채널). 짧은 TTL HMAC 서명 쿼리가 붙은 스트림 URL 을 발급한다. " +
                    "<video> 가 Authorization 헤더를 못 붙이는 문제를 우회한다. JWT 본문은 URL 에 노출되지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공 — url + expiresAt"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "서명 발급 비활성화(서버 시크릿 미설정)")
    })
    @GetMapping("/{rawSn}/stream-url")
    // 관찰-1: 역할 미배정(role=null) 차단. 서명 URL 도 콘텐츠 접근 경로이므로 동일 게이트.
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<kr.co.cudo.authoring.video.dto.StreamUrlResponse> streamUrl(
            @Parameter(description = "raw 영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        // CWE-284 — 발급 요청자 subject 를 서명에 바인딩해 타 사용자 URL 재사용을 차단한다.
        String userNo = actor == null ? null : actor.sub();
        return ApiResponse.ok(videoStreamService.issueSignedUrl(rawSn, userNo));
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
    // 관찰-1: 역할 미배정(role=null) 차단. 단 서명 URL 스트림 경로는 예외로 허용한다 —
    // 서명은 role-gated /stream-url 발급(+userNo 바인딩)을 거친 정당 경로이므로 role=null 은 서명을 얻을 수 없다.
    // LOW 2-1: sub(subject) 값 비교(sub-스푸핑 의존) 대신 STREAM_SIGNED 권한 보유로 판정한다.
    // 이 권한은 StreamSignatureFilter 가 유효 서명 검증 시에만 부여하며, JWT 발급 경로
    // (JwtAuthenticationFilter)는 ROLE_*/CHANNEL_* 만 부여하므로 사용자가 절대 합성할 수 없다(CWE-863).
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER') or hasAuthority('STREAM_SIGNED')")
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
    // 관찰-1: 역할 미배정(role=null) 차단.
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
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
     * <p>검수 완료(APPROVED) 원시 영상의 프레임 이미지셋을 표준 하위 해상도(RES_1080P/RES_720P/RES_480P)로
     * 종횡비 보존 다운스케일한다. 영상(비디오) 재생성·라벨/메타 복사·새 영상(RAW_SN) 생성은 하지 않으며,
     * 산출물은 다운스케일 이미지셋 + LS_RESOLUTION_EXPORT 1행뿐이다. REVIEWER 만 호출 가능.
     */
    @Operation(
            summary = "해상도 변경 (REVIEWER)",
            description = "검수 완료(APPROVED) 원시 영상의 프레임 이미지셋을 표준 하위 해상도(RES_1080P/RES_720P/RES_480P)로 " +
                    "종횡비 보존 다운스케일한다. 영상·라벨·메타·신규 영상 행은 생성하지 않으며 LS_RESOLUTION_EXPORT 1행만 기록한다. " +
                    "업스케일(목표 ≥ 원본 높이)·증강본(ORGNL_RAW_SN 보유)·프레임 0건·중복 요청은 거부된다."
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
            @Parameter(description = "원시 영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @Valid @RequestBody ResolutionChangeRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        String regId = actor != null ? actor.sub() : null;
        return ApiResponse.ok(videoResolutionService.changeResolution(rawSn, request, regId));
    }
}
