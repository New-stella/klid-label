package kr.co.cudo.authoring.portal.controller;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.portal.service.PortalDatamartDownloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 포털 데이터마트 작업 데이터 ZIP 다운로드 API. @design API-203, AC-034, AC-035
 *
 * <h3>왜 {@link PortalLabelController} 에 얹지 않는가</h3>
 * <p>그 컨트롤러의 생성자 계약을 바꾸지 않기 위해서다(기존 회귀 테스트가 2-인자 생성자를 직접
 * 호출한다). 축이 다른 신규 기능이므로 별도 컨트롤러로 둔다 — 인가·채널 규칙은
 * {@code SecurityConfig} 의 {@code /v1/portal/**} 매처가 그대로 적용된다(설정 변경 없음).
 *
 * <h3>판정 순서</h3>
 * <p>①인증(PORTAL_USER — SecurityConfig + {@code @PreAuthorize} + 토큰 확인) → ②속도 제한(429, 이 클래스)
 * → ③데이터마트 노출(403) → ④비식별 누락 신고(412) → ⑤본인 저장 라벨 0건(410) → ⑥200 ZIP.
 * ③~⑤ 는 {@code PortalDatamartDownloadTxService} 가 그 순서로 평가한다(<b>③이 ④보다 먼저</b> —
 * 뒤집으면 미노출 영상의 신고 상태가 응답으로 새어나간다, CWE-209).
 */
@Slf4j
@Tag(name = "Portal Datamart Download",
        description = "포털 데이터마트 작업 데이터 ZIP 다운로드 — PORTAL_USER 전용. 본인 저장 라벨만 담긴다.")
@RestController
@RequestMapping("/v1/portal/datamart")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalDatamartDownloadController {

    /**
     * 다운로드 per-user RateLimiter config 이름
     * ({@code application.yml} 의 {@code resilience4j.ratelimiter.configs} 키와 일치).
     */
    private static final String DOWNLOAD_RL_CONFIG = "portalDatamartDownload";

    private final PortalDatamartDownloadService downloadService;
    /**
     * per-user RateLimiter — 형제 {@code PortalUploadController}/{@code PortalLabelController} 와 동일 패턴.
     * 산출물에 영상이 포함되면 한 요청의 입출력 부담이 커서 연타를 막는다(CWE-770 / OWASP API4).
     *
     * <p>⚠ 이 제한기는 <b>노드별 in-memory</b> 라 2노드 Active-Active 에서는 실질 한도가 2배다.
     * 형제 제한기(portalUpload·portalUserLabel)와 <b>동일한 성질</b>이며, 이를 이유로 분산 제한기를
     * 새로 도입하지 않는다(확정 사항).
     */
    private final RateLimiterRegistry portalRateLimiterRegistry;

    @Operation(summary = "작업 데이터 ZIP 다운로드 (API-203)",
            description = "본인 저장 라벨 기준으로 라벨 JSON + 비식별 프레임 이미지 + 비식별 영상(있을 때만)을 "
                    + "ZIP 하나로 반환한다. 데이터마트 미노출 403, 비식별 누락 신고 구간 412, "
                    + "본인 저장 라벨 0건 410, 요청량 초과 429.")
    @GetMapping("/videos/{rawSn}/download")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        String owner = requireActor(actor);
        acquireDownloadPermit(owner);
        return downloadService.download(rawSn, actor);
    }

    private String requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        return actor.sub();
    }

    /**
     * 다운로드 per-user 요청량 제한(CWE-770). per-user 이름({@code portalDatamartDownload-{userNo}})으로
     * config 를 공유하는 RateLimiter permit 을 대기 없이 획득한다. 실패 시 429.
     *
     * <p>속도 제한은 <b>자원 판정보다 앞</b>이다(판정 순서 ②) — 뒤에 두면 제한을 넘긴 요청도 매번
     * 영상 조회·게이트 판정을 수행하게 되어 제한기가 보호하려던 비용이 그대로 발생한다.
     */
    private void acquireDownloadPermit(String owner) {
        RateLimiter limiter = portalRateLimiterRegistry.rateLimiter(
                DOWNLOAD_RL_CONFIG + "-" + owner, DOWNLOAD_RL_CONFIG);
        if (!limiter.acquirePermission()) {
            log.warn("[PortalDownload] rate limit exceeded user={}", LogSanitizer.sanitize(owner));
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                    "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
