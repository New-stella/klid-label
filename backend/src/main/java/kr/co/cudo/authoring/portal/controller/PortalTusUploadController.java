package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalTusCreateCommand;
import kr.co.cudo.authoring.portal.entity.LsPortalTusUpload;
import kr.co.cudo.authoring.portal.service.PortalVideoUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * 포털 TUS 1.0 재개 가능 영상 업로드 endpoint — PORTAL_USER 전용.
 *
 * <p>관제 {@code TusUploadController} 프로토콜 구현을 준용하되 경로/권한/메타를 포털용으로 분리한다.
 * <ul>
 *   <li>경로: {@code /v1/portal/uploads/tus/**} — SecurityConfig 로 PORTAL 채널 격리 + 메서드
 *       {@code @PreAuthorize("hasRole('PORTAL_USER')")} 이중 방어.</li>
 *   <li>응답: TUS 1.0 헤더 기반(ApiResponse 미사용 — 표준 tus-js-client 호환). 오류는
 *       {@code GlobalExceptionHandler} 가 상태코드를 유지하며 JSON 으로 응답.</li>
 * </ul>
 * 세션 소유자 검증(#3 IDOR)은 서비스에서 PORTAL_USER_NO(토큰 sub) 기준 수행.
 */
@Tag(name = "Portal TUS Upload",
        description = "포털 TUS 1.0 재개 가능 영상 업로드 — PORTAL_USER 전용, 헤더 기반 프로토콜.")
@RestController
@RequestMapping("/v1/portal/uploads/tus")
@RequiredArgsConstructor
public class PortalTusUploadController {

    private static final String TUS_VERSION = "1.0.0";
    private static final String H_RESUMABLE = "Tus-Resumable";
    private static final String H_VERSION = "Tus-Version";
    private static final String H_EXTENSION = "Tus-Extension";
    private static final String H_MAX_SIZE = "Tus-Max-Size";
    private static final String H_UPLOAD_LENGTH = "Upload-Length";
    private static final String H_UPLOAD_OFFSET = "Upload-Offset";
    private static final String H_UPLOAD_METADATA = "Upload-Metadata";
    private static final String OFFSET_OCTET_STREAM = "application/offset+octet-stream";
    private static final int MAX_METADATA_BYTES = 1024;
    /** 포털 영상 상한 5GB — OPTIONS 광고용. */
    private static final long PORTAL_MAX_SIZE = 5_368_709_120L;

    private final PortalVideoUploadService uploadService;

    /** OPTIONS — TUS 서버 능력 광고. */
    @RequestMapping(method = RequestMethod.OPTIONS)
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<Void> options() {
        return ResponseEntity.noContent()
                .header(H_RESUMABLE, TUS_VERSION)
                .header(H_VERSION, TUS_VERSION)
                .header(H_EXTENSION, "creation,termination")
                .header(H_MAX_SIZE, String.valueOf(PORTAL_MAX_SIZE))
                .build();
    }

    /** POST — 세션 생성. 201 + Location: /v1/portal/uploads/tus/{uldId}. */
    @Operation(summary = "포털 TUS 영상 업로드 세션 생성 (PORTAL_USER)")
    @PostMapping
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<Void> create(
            @RequestHeader(value = H_RESUMABLE, required = false) String tusResumable,
            @RequestHeader(value = H_UPLOAD_LENGTH, required = false) Long uploadLength,
            @RequestHeader(value = H_UPLOAD_METADATA, required = false) String uploadMetadata,
            @AuthenticationPrincipal TokenClaims actor) {
        requireTusVersion(tusResumable);
        if (uploadLength == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Length 헤더가 필요합니다.");
        }
        String fileName = parseFileName(uploadMetadata);
        UUID uldId = uploadService.createSession(requireUser(actor),
                new PortalTusCreateCommand(uploadLength, fileName));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(H_RESUMABLE, TUS_VERSION)
                .header(HttpHeaders.LOCATION, "/v1/portal/uploads/tus/" + uldId)
                .build();
    }

    /** HEAD — 현재 offset 조회(재개용). */
    @Operation(summary = "포털 TUS 업로드 offset 조회 (재개)")
    @RequestMapping(value = "/{uldId}", method = RequestMethod.HEAD)
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<Void> head(
            @RequestHeader(value = H_RESUMABLE, required = false) String tusResumable,
            @PathVariable UUID uldId,
            @AuthenticationPrincipal TokenClaims actor) {
        requireTusVersion(tusResumable);
        LsPortalTusUpload session = uploadService.getForOwner(uldId, requireUser(actor));
        return ResponseEntity.noContent()
                .header(H_RESUMABLE, TUS_VERSION)
                .header(H_UPLOAD_OFFSET, String.valueOf(session.getOffsetBytes()))
                .header(H_UPLOAD_LENGTH, String.valueOf(session.getLengthBytes()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /** PATCH — 청크 append (application/offset+octet-stream). 본문은 스트리밍 위임(OOM 방어). */
    @Operation(summary = "포털 TUS 청크 업로드")
    @PatchMapping(value = "/{uldId}", consumes = OFFSET_OCTET_STREAM)
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<Void> patch(
            @RequestHeader(value = H_RESUMABLE, required = false) String tusResumable,
            @RequestHeader(value = H_UPLOAD_OFFSET, required = false) Long uploadOffset,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @PathVariable UUID uldId,
            HttpServletRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        requireTusVersion(tusResumable);
        if (uploadOffset == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Offset 헤더가 필요합니다.");
        }
        if (contentLength == null || contentLength <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Content-Length 헤더가 필요합니다.");
        }
        try (InputStream in = request.getInputStream()) {
            PortalVideoUploadService.PortalTusPatchResult result =
                    uploadService.appendChunk(uldId, requireUser(actor), uploadOffset, in, contentLength);
            return ResponseEntity.noContent()
                    .header(H_RESUMABLE, TUS_VERSION)
                    .header(H_UPLOAD_OFFSET, String.valueOf(result.newOffset()))
                    .build();
        } catch (java.io.IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "청크 처리 중 오류가 발생했습니다.");
        }
    }

    /** DELETE — 세션 취소 + 임시파일 삭제. */
    @Operation(summary = "포털 TUS 업로드 세션 취소")
    @DeleteMapping("/{uldId}")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = H_RESUMABLE, required = false) String tusResumable,
            @PathVariable UUID uldId,
            @AuthenticationPrincipal TokenClaims actor) {
        requireTusVersion(tusResumable);
        uploadService.cancel(uldId, requireUser(actor));
        return ResponseEntity.noContent().header(H_RESUMABLE, TUS_VERSION).build();
    }

    // ======================== 헬퍼 ========================

    private void requireTusVersion(String tusResumable) {
        if (tusResumable != null && !TUS_VERSION.equals(tusResumable)) {
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "지원하지 않는 TUS 버전입니다. 필요: " + TUS_VERSION);
        }
    }

    private String requireUser(TokenClaims actor) {
        if (actor == null || actor.sub() == null || actor.sub().isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 정보가 없습니다.");
        }
        return actor.sub();
    }

    /** Upload-Metadata(base64)에서 filename 만 파싱 — 표시용(저장명은 서비스에서 UUID 강제). */
    private String parseFileName(String uploadMetadata) {
        if (uploadMetadata == null || uploadMetadata.isBlank()) {
            return null;
        }
        if (uploadMetadata.length() > MAX_METADATA_BYTES) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE, "Upload-Metadata 가 1KB 를 초과했습니다.");
        }
        for (String pair : uploadMetadata.split(",")) {
            String[] kv = pair.trim().split("\\s+", 2);
            if (kv.length >= 1 && "filename".equals(kv[0].trim())) {
                return kv.length == 2 ? decodeBase64(kv[1].trim()) : "";
            }
        }
        return null;
    }

    private String decodeBase64(String b64) {
        try {
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Metadata 디코딩에 실패했습니다.");
        }
    }
}
