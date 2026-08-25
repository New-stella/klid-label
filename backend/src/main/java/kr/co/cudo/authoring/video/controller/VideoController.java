package kr.co.cudo.authoring.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Min;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.StreamNonceCookie;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.video.dto.AutoLabelResultResponse;
import kr.co.cudo.authoring.video.dto.ResolutionChangeRequest;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse;
import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.dto.VideoListFilter;
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
import org.springframework.format.annotation.DateTimeFormat;
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
import java.time.LocalDate;

/**
 * 영상 조회 API. REVIEWER/WORKER 모두 조회 가능하되 <b>조회 범위가 역할에 따라 갈린다</b>.
 *
 * <p>REVIEWER 는 전체 영상을, WORKER 는 본인에게 LABELER 로 배정된 영상만 본다 — 목록은
 * {@code VideoQueryService} 의 스코핑으로 좁히고(결과 축소), 단건·스트림·프레임은
 * {@code LabelAccessGuard} 가 배정을 강제한다(403). 두 축은 서로를 대체하지 않는다.
 * [@design API-042] [@design SCREEN-008] [@design ROLE-002]
 */
@Tag(name = "Video", description = "영상(원시 raw) 조회 — REVIEWER는 전체, WORKER는 본인 배정 영상만.")
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
    /** 영상 단위 인가 — 라벨/트랙 경로와 동일한 확립된 가드를 재사용한다(B-ISSUE-63). */
    private final LabelAccessGuard labelAccessGuard;
    /** 서명 스트림의 클라이언트 바인딩 nonce 쿠키 (A-ISSUE-11). */
    private final StreamNonceCookie streamNonceCookie;

    @Operation(
            summary = "영상 목록 조회 (페이징 · 검색/필터)",
            description = "원본 raw 영상 목록을 페이징 조회. 기본 size=20. 파생영상(ORGNL_RAW_SN 보유)은 노출되지 않는다. " +
                    "검색·필터 파라미터는 전부 선택이며, 하나도 보내지 않으면 기존과 동일한 목록·정렬이 반환된다. " +
                    "필터는 모두 DB 조건으로 적용되어 totalElements 도 필터 적용 후 전체 건수다. " +
                    "REVIEWER 는 전체 영상을, WORKER 는 본인에게 LABELER 로 배정된 영상만 조회한다 — " +
                    "범위 제한은 거부가 아니라 결과 축소이며 배정이 없으면 403 이 아니라 빈 목록이다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "검색어 길이 초과 / 날짜 형식 오류 / 시작일 > 종료일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패")
    })
    @GetMapping
    // 관찰-1: 역할 미배정(role=null) INTERNAL 사용자의 영상 콘텐츠 노출 차단. REVIEWER/WORKER 만 허용.
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<Page<VideoSummaryResponse>> list(
            @PageableDefault(size = 20) Pageable pageable,
            @Parameter(description = "배치 단계 상태 필터 — LS_DATA_RAW.DATA_STTS_CD "
                    + "(PENDING / MARKING_READY / PROCESSING / COMPLETED / FAILED)")
            @RequestParam(required = false) String dataSttsCd,
            @Parameter(description = "검수 상태 코드 필터 — LS_RAW_DATA_STATUS 기준 (PENDING/ASSIGNED/IN_REVIEW/APPROVED/REJECTED). " +
                    "지정 시 LS_RAW_DATA_STATUS 조인으로 필터링되어 row 가 없는 영상은 제외된다. " +
                    "증강 요청 화면(SCR-AUG-001)에서 APPROVED 영상만 노출하는 용도.")
            @RequestParam(required = false) String reviewStatusCd,
            @Parameter(description = "검색어(최대 100자) — CCTV 명 부분일치(대소문자 무시). "
                    + "숫자만 입력하면 영상 ID(RAW_SN) 일치도 함께 매칭된다. LIKE 메타문자(%, _)는 리터럴로 취급.")
            @RequestParam(required = false) String cctvNameKeyword,
            @Parameter(description = "이벤트 유형 필터 — 관제 마스터의 카테고리 키(EVNT_CLS_CD+EVNT_CTGRY_CD, 예 010001). "
                    + "영상이 보유한 상세 EV-코드를 카테고리로 변환해 비교한다. 미등록 키는 오류가 아니라 0건.")
            @RequestParam(required = false) String eventTypeCd,
            @Parameter(description = "촬영일(SHT_DT) 시작 — yyyy-MM-dd. 해당일 00:00:00 부터 포함.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "촬영일(SHT_DT) 종료 — yyyy-MM-dd. 해당일 23:59:59 까지 포함.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "건너뛴 작업 묶음 필터 — VLM(시계열) / AUTOLABEL. "
                    + "지금 그 묶음이 건너뛴 상태인 영상만 남기며 건너뛰기가 해제된 영상은 제외된다. "
                    + "벤더 연동이 끝난 뒤 건너뛴 영상을 모아 되살리는 자리에서 쓴다. 지원하지 않는 값은 400.")
            @RequestParam(required = false) String skippedStage,
            @Parameter(description = "실패한 작업 묶음 필터 — VLM(시계열) / AUTOLABEL. "
                    + "지금 그 묶음이 실패한 상태인 영상만 남긴다. 시계열 위탁은 논블로킹이라 실패해도 "
                    + "배치 단계 상태는 완료로 남으므로 dataSttsCd=FAILED 로는 그 영상을 모을 수 없다. "
                    + "skippedStage 와 함께 지정할 수 있다(축이 다르다). 지원하지 않는 값은 400.")
            @RequestParam(required = false) String failedStage,
            @AuthenticationPrincipal TokenClaims actor) {
        // ★ 신규 파라미터는 전부 optional 이며 BE 기본값을 바꾸지 않는다 — 보내지 않던 기존 호출의
        //   결과가 조금도 달라지면 안 된다(하위호환 계약). [design: API-042]
        // ★ 조회 범위는 filter 가 아니라 actor 에서만 나온다 — WORKER 는 본인 배정분으로 좁혀지고
        //   REVIEWER 는 전체다. 사용자 축을 요청 파라미터가 채울 수 있는 자리에 두면 그 자체가
        //   IDOR 입구이므로 VideoListFilter 에 넣지 않는다(CWE-639). [design: API-042] [design: ROLE-002]
        VideoListFilter filter = new VideoListFilter(
                dataSttsCd, reviewStatusCd, cctvNameKeyword, eventTypeCd, from, to,
                skippedStage, failedStage);
        return ApiResponse.ok(videoQueryService.listForActor(safeSort(pageable, reviewStatusCd), filter, actor));
    }

    /**
     * 외부 노출 정렬 키 → 엔티티 필드 allowlist 매핑 (CWE-20).
     *
     * <p>FE 는 {@code sort=capturedAt,desc} 같은 외부 키를 보내는데 엔티티 실제 필드는 {@code shtDt}(촬영시각)다.
     * allowlist 밖의 임의 프로퍼티가 Pageable 로 직행하면 Spring Data 가
     * {@code PropertyReferenceException} → 500 으로 노출하므로, 컨트롤러에서 안전하게 변환·폴백한다.
     * allowlist 밖 키는 drop 되고 유효 정렬이 없으면 {@code regDt DESC} 기본 정렬로 폴백한다 (500 금지).
     *
     * <p>정의는 단일 원천 {@link kr.co.cudo.authoring.common.util.SortAllowlist#VIDEO} 에 둔다 — 여기에
     * 사본을 두면 테스트가 사본만 검증해 확장 시 드리프트를 놓친다.
     */
    private static final java.util.Map<String, String> VIDEO_SORT_ALLOWLIST =
            kr.co.cudo.authoring.common.util.SortAllowlist.VIDEO;

    /**
     * 검수 상태 필터가 지정된 호출 전용 allowlist — 위 키 + 검수 완료 시각({@code reviewCompletedAt}).
     *
     * <p>그 호출만 {@code LS_RAW_DATA_STATUS} 를 조인하므로 조인 alias 를 참조하는 정렬 키도 그때만
     * 유효하다. 조건은 {@link VideoQueryService#usesReviewStatusJoin} 하나를 서비스의 쿼리 분기와
     * 공유한다(복제 금지 — 어긋나면 파생 쿼리로 alias 가 흘러가 500).
     */
    private static final java.util.Map<String, String> VIDEO_SORT_ALLOWLIST_WITH_REVIEW =
            kr.co.cudo.authoring.common.util.SortAllowlist.VIDEO_WITH_REVIEW_STATUS;

    private static final org.springframework.data.domain.Sort DEFAULT_VIDEO_SORT =
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "regDt");

    private Pageable safeSort(Pageable pageable, String reviewStatusCd) {
        java.util.Map<String, String> allowlist = VideoQueryService.usesReviewStatusJoin(reviewStatusCd)
                ? VIDEO_SORT_ALLOWLIST_WITH_REVIEW
                : VIDEO_SORT_ALLOWLIST;
        return kr.co.cudo.authoring.common.web.SortFieldMapper.apply(pageable, allowlist, DEFAULT_VIDEO_SORT);
    }

    @Operation(
            summary = "영상 상세 조회",
            description = "raw 영상 PK로 단건 조회. 메타·비식별 여부·길이 등 상세 정보 반환. "
                    + "vrfcEvntTypeCd 는 이 영상의 검증 이벤트 유형이고 vrfcEvntQuestions 는 그 유형에 "
                    + "등록된 질문 목록(정렬순서 오름차순, 첫 번째가 기본 질문)이다. 마킹 화면이 작업자에게 "
                    + "질문을 보여주고 고른 값을 마킹 등록에 실을 때 쓴다 — 질문 카탈로그 관리 조회 경로는 "
                    + "REVIEWER 전용이라 작업자가 쓸 수 없다. 유형이 없거나 등록된 질문이 없으면 빈 배열이며 "
                    + "오류가 아니다. 그 영상의 유형 하나에 대한 질문일 뿐 카탈로그 전체가 아니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/{rawSn}")
    // 관찰-1: 역할 미배정(role=null) 차단.
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<VideoDetailResponse> getOne(
            @Parameter(description = "raw 영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        // B-ISSUE-63 (DEV_FIX H-1) — /stream 형제 경로 우회 차단. 영상 상세는 파일 경로·촬영지·클립 ID 등
        // 영상 자산 메타를 그대로 노출하므로 스트림과 동일한 영상 단위 인가를 적용한다(CWE-639 IDOR).
        labelAccessGuard.verifyRawAccess(rawSn, actor);
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
            @Parameter(description = "raw 영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        // B-ISSUE-63 (DEV_FIX H-1) — 영상 전체 프레임의 라벨 본문(좌표 포함)을 반환하는 경로다.
        // 프레임 단위 /v1/frames/{srcSn}/labels 는 이미 LabelAccessGuard 를 타는데 여기만 열려 있으면
        // rawSn 순회로 타 영상 라벨을 통째로 수집할 수 있다(CWE-639 IDOR).
        labelAccessGuard.verifyRawAccess(rawSn, actor);
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
            @AuthenticationPrincipal TokenClaims actor,
            HttpServletRequest request,
            HttpServletResponse response) {
        // B-ISSUE-63 — 영상 단위 인가(REVIEWER 전체 / WORKER 본인 배정). 캐시 뒤가 아니라 **진입부**에서
        // 판정해야 캐시 히트가 인가를 건너뛰지 않는다(CWE-639 IDOR).
        labelAccessGuard.verifyRawAccess(rawSn, actor);
        // CWE-284 — 발급 요청자 subject 를 서명에 바인딩한다(u 변조 거부).
        String userNo = actor == null ? null : actor.sub();
        // A-ISSUE-11 — URL 에 없는 클라이언트 바인딩 nonce 를 HttpOnly 쿠키로 내려 서명 입력에 섞는다.
        //   → URL 만 유출되면 재생 불가. 쿠키는 TTL 동안 재사용 가능(다수 Range 요청 대응).
        // DEV_FIX M-1 — 쿠키는 서버 비밀 + 이 발급자(userNo)로 봉인되어 나간다. 공격자가 심어둔 값이나
        //   타 사용자에게 발급된 값은 봉인 검증에 실패해 채택되지 않고 새 nonce 가 발급된다(nonce fixation 차단).
        String nonce = streamNonceCookie.resolveOrIssue(request, response, userNo);
        return ApiResponse.ok(videoStreamService.issueSignedUrl(rawSn, userNo, nonce));
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
            @RequestHeader HttpHeaders headers,
            @AuthenticationPrincipal TokenClaims actor) throws IOException {
        // B-ISSUE-63 — 역할만 보던 게이트에 영상 단위 인가를 추가한다(CWE-639 IDOR).
        //   REVIEWER 전체 / WORKER 본인 배정 영상만. 서명 경로도 동일하게 적용된다 —
        //   StreamSignatureFilter 가 principal 에 실제 발급자 sub + 재조회 역할을 채우기 때문.
        //   진입부 판정이라 stream-meta 캐시 히트가 인가를 건너뛰지 않는다.
        labelAccessGuard.verifyRawAccess(rawSn, actor);
        return videoStreamService.stream(rawSn, headers);
    }

    /**
     * SCR-REVIEW-002 — 프레임 이미지 byte streaming.
     *
     * <p>rawSn + frameNo 기반이며 판정은 형제 경로({@code GET /v1/frames/{srcSn}/image})와
     * <b>같은 단일 원천</b>({@code FrameImageService})이다.
     * <ul>
     *   <li><b>기본 서빙 = 비식별(DEID) 프레임</b>. WORKER 의 {@code raw=true} 는 무시된다.</li>
     *   <li><b>REVIEWER 가 {@code raw=true} 를 명시하면 원본을 서빙한다 — PRVC/PSDO 영상도 포함</b>
     *       (검수자는 마스킹 품질을 원본과 대조해야 한다). 구 서술 "REVIEWER 도 원본 강제 노출 금지"는
     *       현재 코드와 반대라 정정한 것이다.</li>
     *   <li>비식별 경로가 없을 때: PRVC/PSDO 는 404(원본 폴백 금지), ANONY 레거시는 원본 폴백.</li>
     *   <li>비식별 누락 신고 구간({@code DE_IDNTF_YN='F'})은 역할 무관 412.</li>
     * </ul>
     */
    @Operation(
            summary = "프레임 이미지 다운로드 (rawSn + frameNo)",
            description = "검수 화면용 프레임 이미지 byte streaming. " +
                    "기본은 비식별(DEID) 프레임이며 REVIEWER 가 raw=true 를 명시할 때만 원본을 서빙한다" +
                    "(WORKER 의 raw=true 는 무시). 비식별 경로가 없으면 PRVC/PSDO 는 404, ANONY 는 원본 폴백. " +
                    "Path Traversal/심링크 방어 (CWE-22/59) + 확장자 allowlist + " +
                    "Cache-Control: no-store (신고 게이트가 매 요청 평가되어야 하므로 클라이언트 캐시 재사용 금지)."
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
        // B-ISSUE-63 (DEV_FIX H-1) — /stream 만 잠그고 이 경로를 열어두면 rawSn·frameNo 순회로 임의 영상의
        // 전체 프레임 이미지를 수집할 수 있어 스트림 통제가 무의미해진다. 형제 경로
        // /v1/frames/{srcSn}/image 는 이미 LabelAccessGuard 를 타므로 동일 기준으로 맞춘다(CWE-639 IDOR).
        labelAccessGuard.verifyRawAccess(rawSn, actor);
        return frameImageService.serve(rawSn, frameNo, raw, actor);
    }

    /**
     * 해상도 변경 — Phase 3 (RQ-SFR-06-03 파생영상 전환).
     * <p>검수 완료(APPROVED) 원본 영상에서 표준 프리셋(RESL_1080P/RESL_720P/RESL_480P)마다 새 파생영상(RAW_SN)을
     * 생성해 검수 파이프라인에 진입시킨다. 요청 바디의 {@code presets} 는 선택이며, 미지정(바디 생략/빈 목록)이면
     * 표준 3종 전체를 생성한다. 원본과 동일 해상도 프리셋은 스킵되고, 업스케일(확대)도 허용된다.
     * 프리셋별 부분 실패는 다른 프리셋에 영향 없이 결과에 FAILED 로 표기되며, 전부 실패하면 500 으로 응답한다.
     * REVIEWER 만 호출 가능.
     */
    @Operation(
            summary = "해상도 변경 — 파생영상 생성 (REVIEWER)",
            description = "검수 완료(APPROVED) 원본 영상에서 표준 프리셋(RESL_1080P/RESL_720P/RESL_480P)마다 새 파생영상(RAW_SN)을 " +
                    "생성한다. 요청 바디의 presets 는 선택이며 미지정 시 표준 3종 전체를 생성한다. 원본과 동일 해상도 프리셋은 " +
                    "스킵되고, 업스케일(확대)도 허용된다. 프리셋별 부분 실패는 다른 프리셋에 영향 없이 결과에 FAILED 로 표기된다. " +
                    "증강본(ORGNL_RAW_SN 보유)·미검수 영상은 거부된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 — 프리셋별 파생 RAW_SN + 목표 해상도 + 상태 목록 반환(1건 이상 CREATED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "프리셋 값 오류 / 증강본 / 해상도 확인 불가 / 적용 가능한 프리셋 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "미검수 영상"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "시도한 모든 프리셋의 파생영상 생성 실패")
    })
    @PostMapping("/{rawSn}/resolution")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ResolutionChangeResponse> changeResolution(
            @Parameter(description = "원시 영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @Valid @RequestBody(required = false) ResolutionChangeRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        String regId = actor != null ? actor.sub() : null;
        return ApiResponse.ok(videoResolutionService.changeResolution(rawSn, request, regId));
    }

    /**
     * 해상도 파생영상 확정 상태 조회 (E-ISSUE-24).
     *
     * <p>생성 API 의 201 CREATED 는 "예약 성공"만 의미하고 실제 확정은 비동기라, 확정 실패를 어느 화면
     * 에서도 볼 수 없었다. 이 조회로 프리셋별 확정 결과(COMPLETED/IN_PROGRESS/FAILED)를 확인한다.
     */
    @Operation(
            summary = "해상도 파생영상 확정 상태 조회 (REVIEWER)",
            description = "원본 영상의 해상도 파생영상 목록과 확정 상태(COMPLETED/IN_PROGRESS/FAILED)를 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/{rawSn}/resolution")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ResolutionChangeResponse> listDerivatives(
            @Parameter(description = "원시 영상 PK", required = true, example = "1") @PathVariable Long rawSn) {
        return ApiResponse.ok(videoResolutionService.listDerivatives(rawSn));
    }

}
