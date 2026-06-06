package kr.co.cudo.authoring.upload.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.upload.dto.TusCreateCommand;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.service.TusUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * TUS 1.0 재개 가능 업로드 endpoint (관리 화면 대용량 영상 적재 — REVIEWER/INTERNAL 전용).
 *
 * <p><b>응답 형식 결정 (근거)</b>: 본 컨트롤러는 표준 {@code ApiResponse<T>} 래퍼를 사용하지
 * 않고 <b>TUS 1.0 프로토콜 규약(헤더 기반)</b>을 따른다. tus-js-client 등 표준 클라이언트는
 * {@code Upload-Offset}/{@code Location}/{@code Tus-Resumable} 응답 헤더와 상태코드로
 * 진행/재개를 제어하므로, 본문 래핑은 프로토콜 호환을 깨뜨린다. 오류 경로는
 * {@code CustomException} → {@code GlobalExceptionHandler} 가 {@code ApiResponse.error} JSON 으로
 * 응답하되 상태코드(409/410/412/413/429/403)는 TUS 의미를 그대로 유지한다.
 *
 * <p>인증/인가: SecurityConfig {@code /v1/**}(INTERNAL 채널) + {@code @PreAuthorize("hasRole('REVIEWER')")}.
 * 세션 소유자 검증(HIGH-8)은 서비스에서 USER_NO(토큰 sub) 기준 수행.
 */
@Tag(name = "tus-upload",
        description = "TUS 1.0 재개 가능 업로드 — 관리 화면 대용량 영상 적재. 헤더 기반 프로토콜(ApiResponse 미사용).")
@RestController
@RequestMapping("/v1/uploads")
@RequiredArgsConstructor
public class TusUploadController {

    private static final String TUS_VERSION = "1.0.0";
    private static final String H_RESUMABLE = "Tus-Resumable";
    private static final String H_VERSION = "Tus-Version";
    private static final String H_EXTENSION = "Tus-Extension";
    private static final String H_MAX_SIZE = "Tus-Max-Size";
    private static final String H_UPLOAD_LENGTH = "Upload-Length";
    private static final String H_UPLOAD_OFFSET = "Upload-Offset";
    private static final String H_UPLOAD_METADATA = "Upload-Metadata";
    private static final String OFFSET_OCTET_STREAM = "application/offset+octet-stream";
    /** Upload-Metadata 원문 상한 (HIGH-9) — 1KB. */
    private static final int MAX_METADATA_BYTES = 1024;

    private final TusUploadService tusUploadService;

    /** OPTIONS — TUS 서버 능력 광고 (tus-js-client 사전 협상). */
    @RequestMapping(method = RequestMethod.OPTIONS)
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<Void> options() {
        return ResponseEntity.noContent()
                .header(H_RESUMABLE, TUS_VERSION)
                .header(H_VERSION, TUS_VERSION)
                .header(H_EXTENSION, "creation,termination")
                .header(H_MAX_SIZE, String.valueOf(524288000L))
                .build();
    }

    /** POST — 세션 생성. 201 + Location: /v1/uploads/{uploadId}. */
    @Operation(summary = "TUS 업로드 세션 생성 (REVIEWER)")
    @PostMapping
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<Void> create(
            @RequestHeader(value = H_RESUMABLE, required = false) String tusResumable,
            @RequestHeader(value = H_UPLOAD_LENGTH, required = false) Long uploadLength,
            @RequestHeader(value = H_UPLOAD_METADATA, required = false) String uploadMetadata,
            @AuthenticationPrincipal TokenClaims actor) {
        requireTusVersion(tusResumable);
        if (uploadLength == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Length 헤더가 필요합니다.");
        }
        TusCreateCommand cmd = parseMetadata(uploadLength, uploadMetadata);
        UUID uploadId = tusUploadService.createSession(requireUser(actor), cmd);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(H_RESUMABLE, TUS_VERSION)
                .header(HttpHeaders.LOCATION, "/v1/uploads/" + uploadId)
                .build();
    }

    /** HEAD — 현재 offset 조회 (재개용). */
    @Operation(summary = "TUS 업로드 offset 조회 (재개)")
    @RequestMapping(value = "/{uploadId}", method = RequestMethod.HEAD)
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<Void> head(
            @RequestHeader(value = H_RESUMABLE, required = false) String tusResumable,
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal TokenClaims actor) {
        requireTusVersion(tusResumable);
        LsTusUpload session = tusUploadService.getForOwner(uploadId, requireUser(actor));
        return ResponseEntity.noContent()
                .header(H_RESUMABLE, TUS_VERSION)
                .header(H_UPLOAD_OFFSET, String.valueOf(session.getUploadOffset()))
                .header(H_UPLOAD_LENGTH, String.valueOf(session.getUploadLength()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /**
     * PATCH — 청크 append (application/offset+octet-stream).
     *
     * <p>HIGH-2: 본문을 {@code byte[]} 로 전체 메모리 적재하지 않고
     * {@link HttpServletRequest#getInputStream()} 으로 서비스에 스트리밍 위임한다(OOM 방어).
     * 서비스가 고정 64KB 버퍼로 FileChannel 에 기록하며 청크당 상한 초과 시 413 + truncate 롤백.
     */
    @Operation(summary = "TUS 청크 업로드")
    @PatchMapping(value = "/{uploadId}", consumes = OFFSET_OCTET_STREAM)
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<Void> patch(
            @RequestHeader(value = H_RESUMABLE, required = false) String tusResumable,
            @RequestHeader(value = H_UPLOAD_OFFSET, required = false) Long uploadOffset,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @PathVariable UUID uploadId,
            HttpServletRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        requireTusVersion(tusResumable);
        if (uploadOffset == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Offset 헤더가 필요합니다.");
        }
        // Content-Length 선검증 — 누락 시 스트리밍 길이 판단 불가하므로 거부.
        if (contentLength == null || contentLength <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Content-Length 헤더가 필요합니다.");
        }
        try (InputStream in = request.getInputStream()) {
            TusUploadService.TusPatchResult result =
                    tusUploadService.appendChunk(uploadId, requireUser(actor), uploadOffset, in, contentLength);
            return ResponseEntity.noContent()
                    .header(H_RESUMABLE, TUS_VERSION)
                    .header(H_UPLOAD_OFFSET, String.valueOf(result.newOffset()))
                    .build();
        } catch (java.io.IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "청크 처리 중 오류가 발생했습니다.");
        }
    }

    /** DELETE — 세션 취소 + 임시파일 삭제. */
    @Operation(summary = "TUS 업로드 세션 취소")
    @DeleteMapping("/{uploadId}")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = H_RESUMABLE, required = false) String tusResumable,
            @PathVariable UUID uploadId,
            @AuthenticationPrincipal TokenClaims actor) {
        requireTusVersion(tusResumable);
        tusUploadService.cancel(uploadId, requireUser(actor));
        return ResponseEntity.noContent().header(H_RESUMABLE, TUS_VERSION).build();
    }

    // ======================== 헬퍼 ========================

    /** Tus-Resumable 버전 불일치 → 412. */
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

    /**
     * Upload-Metadata(base64) 파싱 — TUS 규약: {@code key b64value,key2 b64value2}.
     * HIGH-9: 원문 1KB 상한. HIGH-7: filename 은 표시용만 (저장명은 서비스에서 UUID 강제).
     */
    private TusCreateCommand parseMetadata(long uploadLength, String uploadMetadata) {
        if (uploadMetadata != null && uploadMetadata.length() > MAX_METADATA_BYTES) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE, "Upload-Metadata 가 1KB 를 초과했습니다.");
        }
        String fileName = null;
        String vmsClipId = null;
        String cctvId = null;
        String eventTypeCd = null;
        String localGovCd = null;
        String prvcTypeCd = null;
        Instant capturedAt = null;
        if (uploadMetadata != null && !uploadMetadata.isBlank()) {
            for (String pair : uploadMetadata.split(",")) {
                String[] kv = pair.trim().split("\\s+", 2);
                if (kv.length < 1 || kv[0].isBlank()) {
                    continue;
                }
                String key = kv[0].trim();
                String value = kv.length == 2 ? decodeBase64(kv[1].trim()) : "";
                switch (key) {
                    case "filename" -> fileName = value;
                    case "vmsClipId" -> vmsClipId = value;
                    case "cctvId" -> cctvId = value;
                    case "eventTypeCd" -> eventTypeCd = value;
                    case "localGovCd" -> localGovCd = value;
                    case "prvcTypeCd" -> prvcTypeCd = value;
                    case "capturedAt" -> capturedAt = parseInstant(value);
                    default -> { /* 미지의 키는 무시 */ }
                }
            }
        }
        return new TusCreateCommand(uploadLength, fileName, vmsClipId, cctvId,
                eventTypeCd, localGovCd, prvcTypeCd, capturedAt);
    }

    private String decodeBase64(String b64) {
        try {
            return new String(Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Metadata 디코딩에 실패했습니다.");
        }
    }

    private Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "capturedAt 형식이 올바르지 않습니다.");
        }
    }
}
