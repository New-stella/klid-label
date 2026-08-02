package kr.co.cudo.authoring.portal.controller;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.common.util.SortAllowlist;
import kr.co.cudo.authoring.portal.dto.DatamartLabelResponse;
import kr.co.cudo.authoring.portal.dto.DatamartVideoResponse;
import kr.co.cudo.authoring.portal.dto.PortalFrameLabelsResponse;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelResponse;
import kr.co.cudo.authoring.portal.service.PortalLabelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * Phase 11 — 포털 채널 라벨링 API.
 *
 * 엔드포인트 (모두 PORTAL_USER 만):
 *  - GET  /v1/portal/datamart/labels: 데이터마트 원본 라벨 Load (페이징)
 *  - POST /v1/portal/user-labels     : 사용자 작업 라벨 저장 (원본 미수정)
 *  - GET  /v1/portal/user-labels     : 본인 작업 라벨 조회 (IDOR 방어)
 *
 * 보안:
 *  - SecurityConfig {@code /v1/portal/**} → PORTAL_USER 만 (다른 역할 403).
 *  - 본인 데이터 검증: PortalLabelService 내부에서 토큰 sub 비교 (CWE-639).
 */
@Slf4j
@Tag(name = "Portal Label", description = "포털 채널 라벨링 — PORTAL_USER 전용. 본인 데이터만 접근 가능 (CWE-639 방어).")
@RestController
@RequestMapping("/v1/portal")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalLabelController {

    /** 라벨 저장 per-user RateLimiter config 이름(application.yml resilience4j.ratelimiter.configs 키와 일치). */
    private static final String USER_LABEL_RL_CONFIG = "portalUserLabel";

    private final PortalLabelService portalLabelService;
    /**
     * per-user RateLimiter — 형제 {@code PortalUploadController} 와 동일 패턴. 저장 경로에만 속도
     * 제한이 통째로 빠져 있어 인증된 PORTAL_USER 한 명이 무제한으로 라벨 행을 적재할 수 있었다
     * (CWE-770 / OWASP API4). 라벨 행은 삭제 API 가 없어 누적되므로 유입 속도 제한이 특히 중요하다.
     */
    private final RateLimiterRegistry portalRateLimiterRegistry;

    @Operation(summary = "데이터마트 영상 목록 (Phase B)",
            description = "포털 홈 — 데이터마트 노출(검수 완료=APPROVED) 영상 목록 페이징 조회. PORTAL_USER 전용. " +
                    "프레임 0건 영상은 진입 불가하므로 제외. 미승인 영상은 쿼리 게이트로 미포함. " +
                    "정렬(sort)은 영상 목록 allowlist(capturedAt/shtDt, regDt/createdAt, updatedAt, rawSn/id)만 " +
                    "허용하며 미등록 키·과다 항목은 400.")
    @GetMapping("/datamart/videos")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<Page<DatamartVideoResponse>> listDatamartVideos(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        // A-ISSUE-61 (HIGH, CWE-770/209/20) — 정렬 키를 allowlist 로만 해석한다. 미배선 상태에서는
        //   Pageable 이 LsDataRaw 조회로 직행해 미등록 키가 500 으로 새어나갔고(ERROR 로그에 JPQL 원문
        //   적재), 응답에 노출하지 않는 내부 컬럼(rawFilePathNm — 원본 파일 경로)으로도 정렬됐다.
        //   allowlist 는 내부 영상 목록과 동일한 SortAllowlist.VIDEO 를 재사용한다(같은 엔티티·같은
        //   노출 축이라 사본을 두면 드리프트가 생긴다). strict 모드 — 변경 전에도 200 이 아니었다.
        //   폴백 unsorted: 리포지토리 JPQL 이 ORDER BY v.regDt DESC 를 고정 보유해 기존 순서가 유지된다.
        Pageable safePageable = SortAllowlist.apply(pageable, SortAllowlist.VIDEO, Sort.unsorted());
        return ApiResponse.ok(portalLabelService.listDatamartVideos(actor, safePageable));
    }

    @Operation(summary = "데이터마트 라벨 Load (V2.0)",
            description = "rawSn 에 해당하는 원본 라벨 목록 조회 (페이징). 데이터마트 노출(검수 완료=APPROVED) 영상만 " +
                    "접근 가능하며(미승인·미존재 403), 비식별 누락 신고 구간에는 412.")
    @GetMapping("/datamart/labels")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<List<DatamartLabelResponse>> loadDatamartLabels(
            @RequestParam Long rawSn,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        return ApiResponse.ok(portalLabelService.loadDatamartLabels(rawSn, page, size, actor));
    }

    @Operation(summary = "사용자 라벨 저장 (V2.0)",
            description = "원본 미수정 — LS_PORTAL_USER_LABEL 별도 적재. lblTypeCd 는 BBOX/POLYGON 만 허용(그 외 400). "
                    + "사용자별 요청량 제한 초과 시 429.")
    @PostMapping("/user-labels")
    @PreAuthorize("hasRole('PORTAL_USER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PortalUserLabelResponse> saveUserLabel(
            @Valid @RequestBody PortalUserLabelRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        acquireSavePermit(actor.sub());
        return ApiResponse.ok(portalLabelService.saveUserLabel(req, actor));
    }

    @Operation(summary = "본인 작업 라벨 조회 (V2.0)", description = "IDOR 방어 — 본인 작업 데이터만 반환.")
    @GetMapping("/user-labels")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<List<PortalUserLabelResponse>> listMyLabels(
            @RequestParam Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        return ApiResponse.ok(portalLabelService.listMyLabels(rawSn, actor));
    }

    @Operation(summary = "포털 프레임 라벨 Load (V2.0/R16)",
            description = "프레임 단위 라벨 조회 — datamart 원본 + 본인 user-label 병합(본인 작업분 우선). PORTAL_USER 전용.")
    @GetMapping("/frames/{srcSn}/labels")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalFrameLabelsResponse> loadFrameLabels(
            @PathVariable Long srcSn,
            @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        return ApiResponse.ok(portalLabelService.loadFrameLabels(srcSn, actor));
    }

    @Operation(summary = "포털 프레임 이미지 서빙 (V2.0/R16)",
            description = "데이터마트 노출(검수 완료) 영상의 비식별 프레임 이미지 바이너리. PORTAL_USER 전용. " +
                    "미승인 영상 403, 프레임/파일 부재 404. Path Traversal(CWE-22) 방어.")
    @GetMapping("/frames/{srcSn}/image")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<Resource> getFrameImage(
            @PathVariable Long srcSn,
            @AuthenticationPrincipal TokenClaims actor) throws IOException {
        requireActor(actor);
        return portalLabelService.serveFrameImage(srcSn, actor);
    }

    private void requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
    }

    /**
     * 라벨 저장 per-user 요청량 제한(CWE-770). per-user 이름({@code portalUserLabel-{userNo}})으로
     * {@code portalUserLabel} config 를 공유하는 RateLimiter permit 을 대기 없이 획득한다. 실패 시 429.
     */
    private void acquireSavePermit(String owner) {
        RateLimiter limiter =
                portalRateLimiterRegistry.rateLimiter("portalUserLabel-" + owner, USER_LABEL_RL_CONFIG);
        if (!limiter.acquirePermission()) {
            log.warn("[Portal] user label rate limit exceeded user={}", LogSanitizer.sanitize(owner));
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                    "저장 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
