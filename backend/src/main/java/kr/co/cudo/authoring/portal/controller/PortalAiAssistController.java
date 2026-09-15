package kr.co.cudo.authoring.portal.controller;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.label.dto.AiCancelResponse;
import kr.co.cudo.authoring.label.dto.AutolabelRequest;
import kr.co.cudo.authoring.label.dto.AutolabelResponse;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentResponse;
import kr.co.cudo.authoring.label.dto.YoloTrackRequest;
import kr.co.cudo.authoring.label.dto.YoloTrackResponseDto;
import kr.co.cudo.authoring.label.service.AutolabelOnlineService;
import kr.co.cudo.authoring.portal.service.PortalAiAssistService;
import kr.co.cudo.authoring.sysconfig.dto.AiDefaultsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포털 라벨링 화면의 AI 보조 창구 — AI 탐지 · AI 분할 · AI 자동 추적 + 정밀도·대기 예산 조회 + 취소.
 *
 * <p><b>내부 창구를 포털 채널에 열지 않고 따로 둔다</b>(2026-09-15 사용자 확정) — 두 채널은 인가 주체가 다르고,
 * 내부 창구가 전제하는 본인 배정 검증·비식별 신고 거부·재비식별 작업락이 포털 자산에는 성립하지 않는다.
 * 요청·응답 본문은 내부 창구와 <b>같은 DTO</b> 이며 추론 본체도 내부 서비스를 재사용한다.
 *
 * <p>인가 2단 — 1차 {@code SecurityConfig} 의 {@code /v1/portal/**} 매처(CHANNEL_PORTAL + PORTAL_USER|
 * PORTAL_STREAM_SIGNED), 2차 메서드 {@code @PreAuthorize("hasRole('PORTAL_USER')")} 로 서명 스트림 컨텍스트를
 * 배제한다. 대상 판정(데이터마트 노출 영상 또는 본인 업로드 자산)은 서비스의 입력 경계가 한다.
 *
 * <p>사용자별 요청량 제한({@code portalAiAssist-{sub}}, 설정 {@code portalAiAssist})은 <b>실행 창구 세 곳이 함께
 * 쓴다</b> — 조회·취소에는 걸지 않는다(취소가 한도에 막히면 끊어야 할 요청이 계속 자원을 잡는다).
 *
 * <p><b>한도 소모 순서</b> — 요청 본문 검증 실패({@code @Valid})·경로/본문 {@code srcSn} 불일치(400)는 permit 을
 * <b>소모하지 않는다</b>(창구 진입 전·permit 획득 전에 끝난다). 반면 작업 대상 인가 실패(403/404)와 자동 추적의
 * 교차 영상 판정(400)은 permit 을 얻은 <b>뒤</b> 서비스에서 판정되므로 <b>소모한다</b> — 존재 탐색성 호출도 한도에
 * 들어가야 남용 억제가 성립하기 때문이며 의도된 순서다.
 *
 * <p>선택 객체 추적(sam2-track)과 스켈레톤 전용 창구는 두지 않는다.
 */
@Slf4j
@Tag(name = "Portal AI Assist", description = "포털 AI 보조 — PORTAL_USER 전용. 포털 작업 대상만, 결과 미저장.")
@RestController
@RequestMapping("/v1/portal")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalAiAssistController {

    /** 실행 창구 공용 per-user RateLimiter config 이름(application.yml resilience4j.ratelimiter.configs 키와 일치). */
    static final String AI_ASSIST_RL_CONFIG = "portalAiAssist";

    private final PortalAiAssistService portalAiAssistService;
    private final RateLimiterRegistry portalRateLimiterRegistry;

    @Operation(summary = "포털 AI 탐지 (PORTAL_USER)",
            description = "포털 작업 대상 프레임 한 장을 객체 탐지해 좌표만 돌려준다(미저장). 본문·응답은 내부 AI 탐지 "
                    + "창구와 같다. 남의 자산·미노출 영상 403, 프레임 부재 404, 같은 프레임 진행 중 409, "
                    + "요청량 한도·동시 호출 상한 초과 429.")
    @PostMapping("/frames/{srcSn}/autolabel")
    @PreAuthorize("hasRole('PORTAL_USER')")
    // [design: API-255]
    public ApiResponse<AutolabelResponse> autolabel(
            @Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
            @RequestBody(required = false) @Valid AutolabelRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        acquireAiPermit(actor.sub());
        AutolabelOnlineService.AutolabelOutcome outcome = portalAiAssistService.autolabel(srcSn, request, actor);
        // 안내 우선순위는 내부 창구와 같다: 폴리곤 상한/부분실패 message > mock 안내 > 정상(message 없음).
        if (outcome.message() != null) {
            return ApiResponse.ok(outcome.response(), outcome.message());
        }
        return outcome.mock()
                ? ApiResponse.ok(outcome.response(), AutolabelResponse.MOCK_UNAVAILABLE_MESSAGE)
                : ApiResponse.ok(outcome.response());
    }

    @Operation(summary = "포털 AI 분할 (PORTAL_USER)",
            description = "클릭·박스로 지목한 한 객체를 분할해 폴리곤과 신뢰도를 돌려준다(미저장). 본문·응답은 내부 AI 분할 "
                    + "창구와 같다. path/body srcSn 불일치 400, 남의 자산·미노출 영상 403, 부재 404, 이미지 상한 413, "
                    + "요청량 한도 429.")
    @PostMapping("/frames/{srcSn}/sam2-segment")
    @PreAuthorize("hasRole('PORTAL_USER')")
    // [design: API-257]
    public ApiResponse<Sam2SegmentResponse> sam2Segment(
            @Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
            @Valid @RequestBody Sam2SegmentRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        requireSameSrcSn(srcSn, request.srcSn());
        acquireAiPermit(actor.sub());
        Sam2SegmentResponse res = portalAiAssistService.segment(request, actor);
        // mock(모델 미로드)이면 본체가 빈 폴리곤을 돌려준다 → 안내 message(자동 적용 차단 신호). 내부 창구와 같다.
        return res.isEmpty()
                ? ApiResponse.ok(res, Sam2SegmentResponse.MOCK_UNAVAILABLE_MESSAGE)
                : ApiResponse.ok(res);
    }

    @Operation(summary = "포털 AI 자동 추적 (PORTAL_USER)",
            description = "시작 프레임부터 후속 프레임까지 객체를 검출·추적한다(미저장). 본문·응답은 내부 AI 자동 추적 "
                    + "창구와 같다. path/body srcSn 불일치·후속 프레임이 다른 영상 400, 남의 자산·미노출 영상 403, "
                    + "부재 404, 요청량 한도 429.")
    @PostMapping("/frames/{srcSn}/yolo-track")
    @PreAuthorize("hasRole('PORTAL_USER')")
    // [design: API-254]
    public ApiResponse<YoloTrackResponseDto> yoloTrack(
            @Parameter(description = "시작 프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
            @Valid @RequestBody YoloTrackRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        requireSameSrcSn(srcSn, request.srcSn());
        acquireAiPermit(actor.sub());
        // 예산 절단은 안내 문구가 아니라 truncated/resume 로 알린다(내부 창구와 같은 규칙).
        return ApiResponse.ok(portalAiAssistService.track(request, actor));
    }

    @Operation(summary = "포털 AI 정밀도 기본값·대기 예산 조회 (PORTAL_USER)",
            description = "내부 조회 창구와 같은 값·같은 형태. 저장값이 없는 항목은 생략된다.")
    @GetMapping("/ai-defaults")
    @PreAuthorize("hasRole('PORTAL_USER')")
    // [design: API-256]
    public ApiResponse<AiDefaultsResponse> aiDefaults() {
        return ApiResponse.ok(portalAiAssistService.aiDefaults());
    }

    @Operation(summary = "포털 AI 추론 취소 (PORTAL_USER)",
            description = "AI 실행 요청에 X-AI-Request-Id 헤더로 실어 보낸 식별자로 그 요청의 추론을 끊는다. "
                    + "본인(같은 채널)이 시작한 요청만 끊으며, 이미 끝났거나 남의 요청·다른 노드면 cancelled=false 로 200.")
    @PostMapping("/ai-requests/{requestId}/cancel")
    @PreAuthorize("hasRole('PORTAL_USER')")
    // [design: API-258]
    public ApiResponse<AiCancelResponse> cancel(
            @Parameter(description = "추론 요청 시 보낸 취소 식별자", required = true, example = "a1b2c3d4")
            @PathVariable String requestId,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(portalAiAssistService.cancel(requestId, actor));
    }

    private static void requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null || actor.sub().isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
    }

    /** path 의 srcSn 과 body 의 srcSn 불일치 거부(CWE-345) — 내부 창구와 같은 규칙. */
    private static void requireSameSrcSn(Long pathSrcSn, Long bodySrcSn) {
        if (!pathSrcSn.equals(bodySrcSn)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "path 의 srcSn 과 body 의 srcSn 이 다릅니다.");
        }
    }

    /**
     * 실행 창구 공용 per-user 요청량 제한(CWE-770). 대기 없이 permit 을 얻고 실패 시 429.
     * 한도는 자동 추적의 이어 보내기 연속 호출을 끊지 않는 값으로 설정이 정한다.
     */
    private void acquireAiPermit(String owner) {
        RateLimiter limiter =
                portalRateLimiterRegistry.rateLimiter("portalAiAssist-" + owner, AI_ASSIST_RL_CONFIG);
        if (!limiter.acquirePermission()) {
            log.warn("[PortalAi] ai assist rate limit exceeded user={}", LogSanitizer.sanitize(owner));
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                    "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
