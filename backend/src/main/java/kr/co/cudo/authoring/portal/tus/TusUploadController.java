package kr.co.cudo.authoring.portal.tus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.entity.LsPortalUserVideo;
import kr.co.cudo.authoring.portal.service.PortalUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 11 — TUS 1.0 업로드 엔드포인트.
 *
 * URL: {@code /v1/portal/uploads} (context-path /api 적용 → /api/v1/portal/uploads)
 *
 * 보안:
 *  - SecurityConfig 의 {@code /v1/portal/**} 는 PORTAL_USER 만 허용 (Channel 분리).
 *  - 본 컨트롤러 메서드별 @PreAuthorize 추가 — 다중 방어선.
 *  - IDOR 방어는 TusService.find 내부에서 (fileId.userId 와 토큰 sub 일치 검증).
 *
 * TUS 헤더 (필수): {@code Tus-Resumable: 1.0.0} 모든 응답에 포함.
 */
@Tag(name = "Portal TUS Upload", description = "포털 TUS 1.0 재개 가능 업로드 — PORTAL_USER 전용. 모든 응답에 Tus-Resumable: 1.0.0 헤더. IDOR 방어 적용.")
@Slf4j
@RestController
@RequestMapping("/v1/portal/uploads")
@RequiredArgsConstructor
public class TusUploadController {

    private final TusService tusService;
    private final PortalUploadService portalUploadService;
    private final TusFileSecurityValidator securityValidator;

    /** OPTIONS — TUS 서버 capability 광고. 인증 없이 허용 (TUS 표준). */
    @Operation(
            summary = "TUS OPTIONS — 서버 capability 광고",
            description = "TUS 서버 버전·확장(creation, termination)·최대 크기를 광고한다. 인증 불필요(TUS 표준). " +
                    "응답 헤더: Tus-Resumable, Tus-Version, Tus-Extension, Tus-Max-Size."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "성공 — 헤더로 capability 반환")
    })
    @RequestMapping(method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> options() {
        return ResponseEntity.noContent()
                .header("Tus-Resumable", TusService.TUS_VERSION)
                .header("Tus-Version", TusService.TUS_VERSION)
                .header("Tus-Extension", "creation,termination")
                .header("Tus-Max-Size", String.valueOf(securityValidator.maxFileSize()))
                .build();
    }

    /** POST — 업로드 세션 생성. */
    @Operation(
            summary = "TUS POST — 업로드 세션 생성",
            description = "Upload-Length 헤더 필수. 응답에는 Location(상대 경로) 및 Upload-Offset:0, Tus-Resumable:1.0.0 헤더 포함."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "세션 생성 — Location 헤더에 fileId URL"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Upload-Length 누락/한도 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "포털 토큰 없음/검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "PORTAL_USER 권한 없음")
    })
    @PostMapping
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<Void> create(@Parameter(description = "업로드할 전체 파일 크기 (byte)", required = true) @RequestHeader(value = "Upload-Length") long uploadLength,
                                       @Parameter(description = "Base64 인코딩 메타데이터 (filename, filetype 등)") @RequestHeader(value = "Upload-Metadata", required = false) String uploadMetadata,
                                       @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        TusFile file = tusService.create(actor.sub(), uploadLength, uploadMetadata);
        // Location 헤더 — context-path(/api) 까지 포함된 절대 경로는 클라이언트에 맡기고
        // 여기서는 service-relative path 만 (RFC 7231 Location 은 상대 허용).
        String location = "/api/v1/portal/uploads/" + file.fileId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, location)
                .header("Tus-Resumable", TusService.TUS_VERSION)
                .header("Upload-Offset", "0")
                .build();
    }

    /** HEAD — 현재 offset 조회. */
    @Operation(
            summary = "TUS HEAD — 현재 offset 조회",
            description = "재개를 위한 현재 업로드 offset 을 조회한다. 응답 헤더: Upload-Offset, Upload-Length, Tus-Resumable, Cache-Control:no-store. " +
                    "본인 fileId만 접근 가능 (IDOR 방어)."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "성공 — 헤더로 offset/length 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "포털 토큰 없음/검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 fileId 아님 (IDOR 방어)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "fileId 없음")
    })
    @RequestMapping(value = "/{fileId}", method = RequestMethod.HEAD)
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<Void> head(@Parameter(description = "TUS fileId", required = true) @PathVariable String fileId,
                                     @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        TusFile file = tusService.find(fileId, actor.sub());
        // TUS 1.0.0 표준: HEAD 응답은 204 No Content (기존 200 → 204 로 정정)
        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .header("Upload-Offset", String.valueOf(file.offset()))
                .header("Upload-Length", String.valueOf(file.size()))
                .header("Tus-Resumable", TusService.TUS_VERSION)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /** PATCH — 청크 업로드. Content-Type: application/offset+octet-stream */
    @Operation(
            summary = "TUS PATCH — 청크 업로드",
            description = "Content-Type: application/offset+octet-stream 필수. Upload-Offset 헤더로 위치 지정. " +
                    "응답 헤더에 갱신된 Upload-Offset 반환. 업로드 완료 시 LS_PORTAL_USER_VIDEO 자동 등록."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "청크 수신 성공 — Upload-Offset 갱신"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "PATCH body 누락 또는 offset 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "포털 토큰 없음/검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 fileId 아님 (IDOR 방어)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "fileId 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "offset 불일치 (재전송 필요)")
    })
    @PatchMapping(value = "/{fileId}", consumes = "application/offset+octet-stream")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<Void> patch(@Parameter(description = "TUS fileId", required = true) @PathVariable String fileId,
                                       @Parameter(description = "현재 누적 offset (byte)", required = true) @RequestHeader("Upload-Offset") long uploadOffset,
                                       @RequestBody(required = false) byte[] body,
                                       @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        if (body == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "PATCH body 누락");
        }
        TusFile updated = tusService.patch(fileId, actor.sub(), uploadOffset, body);

        // 업로드 완료 시 LS_PORTAL_USER_VIDEO 자동 등록 (한 번만)
        if (updated.isComplete()) {
            try {
                portalUploadService.registerCompleted(actor.sub(), updated);
            } catch (Exception e) {
                log.warn("[Tus] post-complete register failed fileId={} message={}", fileId, e.getMessage());
                // 등록 실패는 업로드 완료 응답에 영향 주지 않음 (재시도 가능)
            }
        }
        return ResponseEntity.noContent()
                .header("Upload-Offset", String.valueOf(updated.offset()))
                .header("Tus-Resumable", TusService.TUS_VERSION)
                .build();
    }

    /** DELETE — 업로드 취소. */
    @Operation(
            summary = "TUS DELETE — 업로드 세션 취소",
            description = "TUS termination 확장. 미완료 업로드 세션을 삭제한다. 본인 fileId만 (IDOR 방어)."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "포털 토큰 없음/검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 fileId 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "fileId 없음")
    })
    @DeleteMapping("/{fileId}")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<Void> delete(@Parameter(description = "TUS fileId", required = true) @PathVariable String fileId,
                                        @AuthenticationPrincipal TokenClaims actor) {
        requireActor(actor);
        tusService.delete(fileId, actor.sub());
        return ResponseEntity.noContent()
                .header("Tus-Resumable", TusService.TUS_VERSION)
                .build();
    }

    private void requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
    }
}
