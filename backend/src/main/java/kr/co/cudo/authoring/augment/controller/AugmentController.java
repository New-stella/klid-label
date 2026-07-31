package kr.co.cudo.authoring.augment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.augment.dto.AugmentCancelRequest;
import kr.co.cudo.authoring.augment.dto.AugmentCancelResponse;
import kr.co.cudo.authoring.augment.dto.AugmentJobResponse;
import kr.co.cudo.authoring.augment.dto.AugmentProgressResponse;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.dto.AugmentRequestResponse;
import kr.co.cudo.authoring.augment.dto.AugmentRestoreRequest;
import kr.co.cudo.authoring.augment.dto.AugmentResultResponse;
import kr.co.cudo.authoring.augment.dto.AugmentSummaryResponse;
import kr.co.cudo.authoring.augment.dto.RejectRequest;
import kr.co.cudo.authoring.augment.service.AugmentCancelService;
import kr.co.cudo.authoring.augment.service.AugmentDiscardService;
import kr.co.cudo.authoring.augment.service.AugmentProgressService;
import kr.co.cudo.authoring.augment.service.AugmentRequestService;
import kr.co.cudo.authoring.augment.service.AugmentResultViewService;
import kr.co.cudo.authoring.augment.service.AugmentReviewService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 9 — 데이터 증강 검수 API.
 * V1.5 정책에 따라 증강 본체는 외부 SFR-07 시스템이며, 본 API 는 결과 검수만 담당.
 */
@Tag(name = "Augment", description = "데이터 증강 검수 — V1.5: 증강 본체는 외부 SFR-07 책임. 저작도구는 결과 검수(승인/반려)만 제공.")
@RestController
@RequestMapping("/v1/augments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AugmentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AugmentReviewService service;
    private final AugmentRequestService requestService;
    /** 결과 본문(해상도 파생 프레임 쌍) 구성 — 검수 서비스와 책임 분리. */
    private final AugmentResultViewService resultViewService;
    /** 진행상태 조회(외부 §4.4 폴링 + 웹훅 유실분 회수) — 트랜잭션을 열지 않는 오케스트레이터. */
    private final AugmentProgressService progressService;
    /** 취소(클레임 → 외부 §4.6 → 확정) — 부분 실패를 응답으로 드러낸다. */
    private final AugmentCancelService cancelService;
    /** Phase 7 — 폐기(반려) 복구. 표식 해제 + 검수 재오픈을 함께 수행한다. */
    private final AugmentDiscardService discardService;

    /**
     * 증강 잡 카드(영상 단위 그룹) 조회 — FE {@code AugmentJob} 계약 정합.
     * - srcSn 미지정 시: 전체 증강을 영상 단위로 그룹핑한 잡 카드 페이징
     * - srcSn 지정 시: 해당 원본 영상의 잡 카드만 Page 로 반환 (필터)
     *
     * <p>두 분기 모두 {@code Page<AugmentJobResponse>} 를 반환한다(FE listAugmentJobs 동일 타입 기대).
     */
    @Operation(
            summary = "증강 잡 카드 조회",
            description = "영상 단위로 그룹핑한 잡 카드 페이징. srcSn 지정 시 해당 영상만 필터. REVIEWER/WORKER 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "size 한도 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<org.springframework.data.domain.Page<AugmentJobResponse>> list(
            @Parameter(description = "필터용 대표프레임 ID. 이 값은 LS_DATA_SRC.SRC_SN(원본 영상 대표프레임 PK)이며, "
                    + "응답의 videoId(=원본 RAW_SN)와 다른 도메인 값이다. job.videoId(RAW_SN)를 이 파라미터로 넘기면 "
                    + "조용히 다른 영상이 조회되니 절대 혼용 금지. 없으면 전체 페이징.", example = "1")
            @RequestParam(required = false) Long srcSn,
            @Parameter(description = "페이지 번호 (0-based, srcSn 미지정 시 사용)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (max 100)", example = "20") @RequestParam(defaultValue = "20") int size) {
        if (srcSn != null) {
            return ApiResponse.ok(service.findBySource(srcSn));
        }
        if (size > MAX_PAGE_SIZE) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "size 한도 초과 (max=" + MAX_PAGE_SIZE + ")");
        }
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.ok(service.listAll(pageable));
    }

    /**
     * 외부 SFR-07 증강 시스템 요청 (REVIEWER 만).
     *
     * <p>검수 완료(APPROVED)된 영상만 요청 가능. 미검수 영상 포함 시 NOT_REVIEWED 와 함께
     * blockedVideoIds 를 응답 data 에 포함하여 400 반환. 외부 미연동 단계이므로 jobId 는
     * placeholder 시퀀스로 발급된다.
     */
    @Operation(
            summary = "증강 요청 (REVIEWER)",
            description = "검수 완료 영상에 대해 외부 SFR-07 증강 시스템에 4종 증강을 요청한다. 미검수 영상 포함 시 NOT_REVIEWED 400."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공 (jobId 발급)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 검증 실패 또는 미검수 영상 포함 (NOT_REVIEWED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @PostMapping("/request")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<AugmentRequestResponse> request(@Valid @RequestBody AugmentRequestRequest req,
                                                       @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(requestService.request(req, actor));
    }

    /**
     * 증강 작업(jobId) 결과 조회 — FE 결과 화면(SCR-AUG-002).
     *
     * <p>{@code status} 는 해당 원본 영상(jobId) 증강 row 의 실제 집계 상태
     * (COMPLETED|FAILED|PROCESSING)다. {@code results} 는 <b>해상도 파생(RESL_*)</b> 의 프레임 쌍
     * (부모 비식별 ↔ 파생 리스케일)을 반환한다 — 구 구현은 이를 빈 배열로 하드코딩해 비교 이미지가
     * 하나도 표시되지 않았다. 외부 위탁 증강(WINTER/NIGHT/RAIN)의 프레임별 결과는 외부 SFR-07 연동
     * 이후 제공되므로 기존과 동일하게 비어 있다.
     *
     * <p><b>페이징 축이 둘이다</b> — {@code page}/{@code size} 는 <b>프레임 쌍</b>,
     * {@code itemPage}/{@code itemSize} 는 <b>결과 항목</b> 축이다. 신규 파라미터는 전부 optional 이며
     * 기존 파라미터의 <b>기본값·의미는 불변</b>이라 구 호출({@code ?page=&size=})이 그대로 동작한다
     * ({@code rules/api-design.md} 하위호환 조항).
     */
    @Operation(
            summary = "증강 작업 결과 조회 (REVIEWER)",
            description = "집계 상태(COMPLETED|FAILED|PROCESSING) + 해상도 파생(RESL_*) 프레임 쌍을 반환한다. "
                    + "페이징 축 2개: page/size = 프레임 쌍, itemPage/itemSize = 결과 항목(항목 축 총량은 "
                    + "응답 totalElements/totalPages). 이미지는 파일 경로가 아니라 "
                    + "/v1/frames/{srcSn}/deid-image API 경로로만 노출된다. "
                    + "외부 위탁 증강(WINTER/NIGHT/RAIN) 프레임 쌍은 외부 SFR-07 연동 이후 제공."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "page/size 또는 itemPage/itemSize 범위 위반"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 누락 신고 구간(재비식별 대기) — 결과 조회 차단")
    })
    @GetMapping("/{jobId}/result")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<AugmentResultResponse> result(
            @Parameter(description = "증강 jobId(=원본 RAW_SN)", required = true, example = "1") @PathVariable Long jobId,
            @Parameter(description = "프레임 쌍 페이지 번호 (0-based)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "프레임 쌍 페이지 크기 (기본 12, max 100)", example = "12")
            @RequestParam(defaultValue = "12") int size,
            @Parameter(description = "결과 항목 페이지 번호 (0-based, optional)", example = "0")
            @RequestParam(defaultValue = "0") int itemPage,
            @Parameter(description = "결과 항목 페이지 크기 (기본 20, max 100, optional)", example = "20")
            @RequestParam(defaultValue = "20") int itemSize) {
        return ApiResponse.ok(resultViewService.result(jobId, page, size, itemPage, itemSize));
    }

    /**
     * 증강 진행상태 조회 (REVIEWER/WORKER) — FE 폴링 대상.
     *
     * <p>{@code id} 는 {@code LS_DATA_AUG.DATA_AUG_SN} 이며 accept/reject 와 동일 식별자다.
     *
     * <h3>진행률 산식 — <b>청크 job 의 파일 수 가중 평균</b></h3>
     * <pre>
     *   progress = round( Σ(weight_i × p_i) / Σ(weight_i) )
     *     weight_i = max(1, 그 청크가 위탁한 입력 파일 수)
     *     p_i      = 종결 청크 → 100 / 비종결 청크 → 외부 상태조회(§4.4) progress (미제공 0)
     * </pre>
     * <p><b>최솟값(min)이 아니다</b> — min 을 쓰면 청크 3개 중 2개가 100%여도 전체가 0%로 보인다.
     *
     * <h3>외부 장애는 200 으로 degrade 한다</h3>
     * <p>{@code progress:null} + {@code unavailableReason} 으로 사유를 구분해 회신한다:
     * {@code NOOP}(외부 미연동 — 오류 아님) / {@code TRANSIENT_ERROR}(서킷 open·타임아웃 — 진짜 장애) /
     * {@code AWAITING_ACK}(외부 작업 ID 미수신 — 접수 확인 중) /
     * {@code QUERY_LIMIT_EXCEEDED}(<b>우리 쪽</b> 자체 상한 — 청크가 많거나 요청 시간 예산 소진, 오류 아님).
     * 화면은 이 넷을 다르게 표시해야 한다.
     *
     * <p>{@code nextPollAfterMs} 는 <b>권고</b> 폴링 간격이다(0 = 종결이므로 폴링 중단). 서버가 속도를
     * 강제하지 않으므로 폴링 증폭을 줄이는 수단은 이 힌트뿐이다.
     *
     * <p><b>회수(INT-030) 부작용은 REVIEWER 폴링에서만</b> 일어난다 — 회수는 파생영상 생성·프레임
     * 재추출까지 연쇄하는 상태 변경이라 WORKER 의 GET 이 그 시점을 정하지 않게 한다(DEV_FIX MED-4).
     * WORKER 도 진행률 조회 자체는 그대로 가능하다.
     */
    @Operation(
            summary = "증강 진행상태 조회 (REVIEWER/WORKER)",
            description = "외부 위탁 증강의 진행 상태·진행률을 조회한다. "
                    + "진행률 = 청크 job 들의 <b>파일 수 가중 평균</b>(min 아님) — "
                    + "round(Σ(weight×p)/Σweight), weight=max(1,위탁 파일 수), "
                    + "p=종결 100 / 비종결은 외부 상태조회 progress. "
                    + "외부 조회 실패 시 500 이 아니라 200 + progress=null + unavailableReason"
                    + "(NOOP=미연동 / TRANSIENT_ERROR=일시 장애 / AWAITING_ACK=외부 작업 ID 미수신 / "
                    + "QUERY_LIMIT_EXCEEDED=자체 상한(청크 수·요청 시간 예산), 오류 아님) 로 degrade 한다. "
                    + "웹훅이 유실된 작업은 이 조회 시점에 외부 결과조회(INT-030)로 회수된다 "
                    + "(회수 부작용은 REVIEWER 조회에서만 — WORKER 는 진행률만 조회). "
                    + "해상도 파생(RESL_*)은 외부 위탁이 없어 400."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공(외부 장애 시에도 degrade 200)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "해상도 파생(RESL_*) 등 진행상태 대상이 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "증강 결과 없음")
    })
    @GetMapping("/{id}/progress")
    @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")
    public ApiResponse<AugmentProgressResponse> progress(
            @Parameter(description = "증강 결과 PK(LS_DATA_AUG.DATA_AUG_SN)", required = true, example = "1")
            @PathVariable Long id,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(progressService.progress(id, actor));
    }

    /**
     * 증강 요청 취소 (REVIEWER 만).
     *
     * <p>증강 1건이 여러 청크 job 으로 분할 위탁되므로 <b>비종결 청크 전부</b>에 외부 취소(§4.6)를
     * 보낸다(요청 단위 시간 예산 안에서). 일부만 성립하면 {@code fullyCanceled=false} +
     * {@code failedJobSeqs} 로 <b>부분 실패를 드러낸다</b> — 단 <b>재시도를 권하지 않는다</b>(증강이
     * 이미 종결이라 재요청하면 멱등 200 + "이미 종결" 만 나온다, DEV_FIX MED-7).
     *
     * <p>응답 shape 은 accept/reject({@code AugmentSummaryResponse})와 <b>다르다</b> —
     * {@code AugmentCancelResponse}(부분 성공 표현 전용). 같은 파서로 다루지 말 것.
     *
     * <p>계약상 취소는 웹훅을 발사하지 않으므로 이 응답이 유일한 통보다. 그래서 증강 상태를 같은
     * 요청에서 {@code CANCELED} 로 확정한다(그러지 않으면 PENDING 에 영구 고착된다).
     *
     * <p><b>동시 취소·이미 종결은 409 가 아니라 멱등 200</b>({@code canceled=false})이다.
     *
     * <p>요청 바디는 선택이며 {@code reason} 필드 하나만 받는다 — 종결 판정을 요청으로 조작할 수
     * 있는 필드를 두지 않는다(Mass Assignment 방어).
     */
    @Operation(
            summary = "증강 요청 취소 (REVIEWER)",
            description = "외부 위탁 증강을 취소한다. 비종결 청크 전부에 외부 취소를 보내며(요청 단위 시간 예산 내), "
                    + "일부만 성립하면 fullyCanceled=false + failedJobSeqs 로 부분 실패를 알린다. "
                    + "벤더 404(JOB_NOT_FOUND)/409(STATE_CONFLICT)는 '취소할 대상 없음'이라 성립으로 센다. "
                    + "부분 실패여도 재시도 동선은 없다(증강이 이미 종결이라 재요청은 멱등 200). "
                    + "이미 종결됐거나 동시 취소의 후행 요청은 409 가 아니라 200 + canceled=false 다. "
                    + "응답 shape 은 accept/reject(AugmentSummaryResponse)와 다른 AugmentCancelResponse 다. "
                    + "해상도 파생(RESL_*)은 외부 위탁이 없어 400."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "취소 확정 또는 멱등 응답"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "사유 길이 초과 또는 해상도 파생(RESL_*)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "증강 결과 없음")
    })
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<AugmentCancelResponse> cancel(
            @Parameter(description = "증강 결과 PK(LS_DATA_AUG.DATA_AUG_SN)", required = true, example = "1")
            @PathVariable Long id,
            @Valid @RequestBody(required = false) AugmentCancelRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(cancelService.cancel(id, req == null ? null : req.reason(), actor));
    }

    /**
     * 증강 결과 승인 (REVIEWER 만).
     */
    @Operation(
            summary = "증강 결과 승인 (REVIEWER)",
            description = "외부 SFR-07이 생성한 증강 결과를 검수 승인한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "증강 결과 없음")
    })
    @PostMapping("/{id}/accept")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<AugmentSummaryResponse> accept(@Parameter(description = "증강 결과 PK", required = true, example = "1") @PathVariable Long id,
                                                      @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(service.accept(id, actor));
    }

    /**
     * 증강 결과 반려 (REVIEWER 만, 사유 필수).
     */
    @Operation(
            summary = "증강 결과 반려 (REVIEWER)",
            description = "외부 SFR-07 증강 결과를 사유와 함께 반려한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "사유 누락 등 입력 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "증강 결과 없음")
    })
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<AugmentSummaryResponse> reject(@Parameter(description = "증강 결과 PK", required = true, example = "1") @PathVariable Long id,
                                                      @Valid @RequestBody RejectRequest req,
                                                      @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(service.reject(id, req.reason(), actor));
    }

    /**
     * 폐기(반려)된 증강 파생영상 <b>복구</b> — 유예 기간 내에만 가능 (REVIEWER 만, 사유 필수).
     *
     * <p>복구는 표식 해제에 그치지 않고 <b>반려 자체를 되돌린다</b> — 검수가 PENDING 으로 재오픈되어
     * 다시 채택/반려를 고를 수 있다. 되돌린 이력(누가·언제·왜)은 폐기 원장에 남는다.
     *
     * <p>유예가 지나 이미 실삭제됐으면 409(되돌릴 대상 없음), 폐기 이력 자체가 없으면 404 다.
     */
    @Operation(
            summary = "증강 폐기 복구 (REVIEWER)",
            description = "반려로 폐기 표식이 찍힌 파생영상을 유예 기간 내에 되돌린다. 검수가 PENDING 으로 "
                    + "재오픈되어 다시 채택/반려를 결정할 수 있다. 유예 경과 후 삭제된 건은 409."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "사유 누락 등 입력 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "증강 결과 또는 폐기 이력 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 삭제됨 · 되돌릴 수 없는 상태")
    })
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<AugmentSummaryResponse> restore(
            @Parameter(description = "증강 결과 PK", required = true, example = "1") @PathVariable Long id,
            @Valid @RequestBody AugmentRestoreRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(discardService.restore(id, req.reason(), actor));
    }
}
