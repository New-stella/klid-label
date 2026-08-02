package kr.co.cudo.authoring.portal.controller;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.common.util.SortAllowlist;
import kr.co.cudo.authoring.portal.dto.PortalUploadDetailResponse;
import kr.co.cudo.authoring.portal.dto.PortalUploadFrameResponse;
import kr.co.cudo.authoring.portal.dto.PortalUploadResponse;
import kr.co.cudo.authoring.portal.service.PortalUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * V107 — 포털 이미지 업로드 + 자산 관리 API (PORTAL_USER 전용).
 *
 * <p>보안:
 * <ul>
 *   <li>SecurityConfig {@code /v1/portal/**} → PORTAL 채널 + PORTAL_USER 만(다른 채널/역할 403).</li>
 *   <li>메서드 {@code @PreAuthorize("hasRole('PORTAL_USER')")} 이중 방어.</li>
 *   <li>소유자 스코프(IDOR/CWE-639)는 서비스에서 소유자 스코프 리포지토리로 강제.</li>
 * </ul>
 * 저장 파일명은 UUID 강제이며 원본명은 표시용(ORGNL_FILE_NM)만 보관한다. 업로드 응답의 원본
 * 파일명은 JSON 문자열로 그대로 직렬화되며(HTML 렌더링 없음 — FE 이스케이프 책임), 이미지 서빙은
 * DB 확정 MIME + {@code X-Content-Type-Options: nosniff} 로 sniffing 을 차단한다.
 */
@Slf4j
@Tag(name = "Portal Upload", description = "포털 이미지 업로드/자산 관리 — PORTAL_USER 전용, 본인 자산만 접근.")
@RestController
@RequestMapping("/v1/portal/uploads")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalUploadController {

    /** 목록/프레임 조회 페이지 크기 상한(무제한 조회 방지 — 컨트롤러 레벨 하드캡). */
    private static final int MAX_PAGE_SIZE = 100;
    /** 업로드 per-user RateLimiter config 이름(application.yml resilience4j.ratelimiter.configs 키와 일치). */
    private static final String PORTAL_UPLOAD_RL_CONFIG = "portalUpload";

    private final PortalUploadService portalUploadService;
    /**
     * PortalSam2Service 와 동일한 per-user RateLimiter 패턴 재사용 — 업로드 엔드포인트에 사용자별
     * 요청량 제한을 적용해 대용량 multipart 폭주(자원 소진, OWASP API4/CWE-770)를 격리한다.
     */
    private final RateLimiterRegistry portalRateLimiterRegistry;

    @Operation(summary = "이미지 다중 업로드", description = "multipart files[] — 전 파일 사전검증 통과 시에만 저장(all-or-nothing).")
    @PostMapping("/images")
    @PreAuthorize("hasRole('PORTAL_USER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<List<PortalUploadResponse>> uploadImages(
            @RequestParam("files") List<MultipartFile> files,
            @AuthenticationPrincipal TokenClaims actor) {
        String owner = requireActor(actor);
        acquireUploadPermit(owner);
        return ApiResponse.ok(portalUploadService.uploadImages(owner, files));
    }

    @Operation(summary = "본인 업로드 자산 목록",
            description = "PORTAL_USER 본인 자산만 페이징 조회. type 로 IMAGE/VIDEO 필터. "
                    + "정렬(sort)은 allowlist(regDt/uploadedAt, uldSn/id, status, type, fileSz)만 허용하며 "
                    + "미등록 키·과다 항목은 400.")
    @GetMapping
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<Page<PortalUploadResponse>> listUploads(
            @RequestParam(name = "type", required = false) String type,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal TokenClaims actor) {
        String owner = requireActor(actor);
        return ApiResponse.ok(portalUploadService.listUploads(owner, type, capped(safeSort(pageable))));
    }

    @Operation(summary = "자산 상세 + 프레임 요약", description = "본인 자산만. 타 사용자/부재 자산은 403.")
    @GetMapping("/{uldSn}")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalUploadDetailResponse> getUpload(
            @PathVariable Long uldSn,
            @AuthenticationPrincipal TokenClaims actor) {
        String owner = requireActor(actor);
        return ApiResponse.ok(portalUploadService.getUpload(uldSn, owner));
    }

    @Operation(summary = "자산 프레임 목록",
            description = "본인 자산 프레임 페이징 조회. 소유자 스코프 조인. "
                    + "정렬(sort)은 allowlist(frmeNo/frameNo, uldFrmeSn/id, regDt)만 허용하며 "
                    + "미등록 키·과다 항목은 400.")
    @GetMapping("/{uldSn}/frames")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<Page<PortalUploadFrameResponse>> listFrames(
            @PathVariable Long uldSn,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal TokenClaims actor) {
        String owner = requireActor(actor);
        return ApiResponse.ok(portalUploadService.listFrames(uldSn, owner, capped(safeFrameSort(pageable))));
    }

    @Operation(summary = "프레임 이미지 서빙", description = "본인 자산 이미지 바이너리. DB 확정 Content-Type + nosniff.")
    @GetMapping("/frames/{uldFrmeSn}/image")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<Resource> getFrameImage(
            @PathVariable Long uldFrmeSn,
            @AuthenticationPrincipal TokenClaims actor) {
        String owner = requireActor(actor);
        return portalUploadService.serveFrameImage(uldFrmeSn, owner);
    }

    @Operation(summary = "자산 삭제", description = "본인 자산만. 파일(프레임/원본) 삭제 후 DB 행 삭제(CASCADE).")
    @DeleteMapping("/{uldSn}")
    @PreAuthorize("hasRole('PORTAL_USER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUpload(
            @PathVariable Long uldSn,
            @AuthenticationPrincipal TokenClaims actor) {
        String owner = requireActor(actor);
        portalUploadService.deleteUpload(uldSn, owner);
    }

    private String requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        return actor.sub();
    }

    /**
     * A-ISSUE-61 (HIGH, CWE-770/209/20) — 자산 목록 정렬 키를 allowlist 로만 해석한다.
     *
     * <p>미배선 상태에서는 {@code Pageable} 이 리포지토리로 직행해 미등록 키가
     * {@code PropertyReferenceException} → 500 으로 새어나갔고(ERROR 로그에 내부 엔티티명 적재),
     * 정렬 항목 개수 상한도 없었다. strict 모드 — 변경 전에도 200 이 아니었으므로 400 은 하위호환
     * 파손이 아니다(CLAUDE.md 목록 정렬 정책).
     *
     * <p>폴백은 {@link Sort#unsorted()} 다 — 이 엔드포인트의 {@code @PageableDefault} 에 기본 정렬이
     * 없어 정렬 미지정 시 기존 동작(리포지토리 기본 순서)을 그대로 보존한다.
     */
    private Pageable safeSort(Pageable pageable) {
        return SortAllowlist.apply(pageable, SortAllowlist.PORTAL_UPLOAD, Sort.unsorted());
    }

    /**
     * 프레임 목록 정렬 allowlist 적용 — {@link #safeSort(Pageable)} 와 동일 정책(strict).
     * 리포지토리 JPQL 이 {@code order by f.frmeNo asc} 를 고정 보유하므로 폴백은 unsorted 로 둔다.
     */
    private Pageable safeFrameSort(Pageable pageable) {
        return SortAllowlist.apply(pageable, SortAllowlist.PORTAL_UPLOAD_FRAME, Sort.unsorted());
    }

    /** 페이지 크기 하드캡 — size &gt; {@value #MAX_PAGE_SIZE} 이면 {@value #MAX_PAGE_SIZE} 로 클램프. */
    private Pageable capped(Pageable pageable) {
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }

    /**
     * 업로드 per-user 요청량 제한(CWE-770). per-user 이름({@code portalUpload-{userNo}})으로
     * {@code portalUpload} config 를 공유하는 RateLimiter permit 을 대기 없이 획득한다. 실패 시 429.
     */
    private void acquireUploadPermit(String owner) {
        RateLimiter limiter =
                portalRateLimiterRegistry.rateLimiter("portalUpload-" + owner, PORTAL_UPLOAD_RL_CONFIG);
        if (!limiter.acquirePermission()) {
            log.warn("[PortalUpload] rate limit exceeded user={}", LogSanitizer.sanitize(owner));
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                    "업로드 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
