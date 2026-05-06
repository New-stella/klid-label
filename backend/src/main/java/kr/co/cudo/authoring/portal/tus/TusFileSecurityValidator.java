package kr.co.cudo.authoring.portal.tus;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 11 — TUS 업로드 보안 검증기.
 *
 * 보안 (Critical):
 *  - CWE-434 Unrestricted File Upload: 확장자 allowlist (mp4/mov/avi 기본).
 *  - 파일 크기 상한 (기본 5GB) — 헤더 단계에서 즉시 차단.
 *  - MIME 타입 매핑: video/mp4, video/quicktime, video/x-msvideo.
 *  - CWE-22 Path Manipulation: 파일명 정규화 (디렉토리 구분자 제거).
 */
@Component
public class TusFileSecurityValidator {

    /** 확장자별 허용 MIME (allowlist). */
    private static final java.util.Map<String, String> EXT_TO_MIME = java.util.Map.of(
            "mp4", "video/mp4",
            "mov", "video/quicktime",
            "avi", "video/x-msvideo"
    );

    private final long maxFileSizeBytes;
    private final Set<String> allowedExtensions;

    public TusFileSecurityValidator(
            @Value("${portal.upload.max-file-size-bytes:5368709120}") long maxFileSizeBytes,
            @Value("${portal.upload.allowed-extensions:mp4,mov,avi}") String allowedExtensionsCsv) {
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.allowedExtensions = Arrays.stream(allowedExtensionsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public long maxFileSize() {
        return maxFileSizeBytes;
    }

    /** Upload-Length 검증 — 초과 시 413 매핑. */
    public void validateSize(long uploadLength) {
        if (uploadLength <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Length 는 1 이상이어야 합니다.");
        }
        if (uploadLength > maxFileSizeBytes) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "최대 파일 크기 초과 (limit=" + maxFileSizeBytes + ", got=" + uploadLength + ")");
        }
    }

    /**
     * 파일명 + filetype 검증 → 정규화된 (filename, mimeType) 반환.
     * - 확장자 allowlist
     * - filetype 이 헤더에 명시되어 있으면 확장자 매핑과 일치 검증 (CWE-345 방어)
     */
    public ValidatedMeta validateAndNormalize(String rawFilename, String rawFiletype) {
        if (rawFilename == null || rawFilename.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "filename 누락");
        }
        // CWE-22 path traversal 방어 — 디렉토리 구분자 / null byte 제거
        String safeName = stripPath(rawFilename);
        if (safeName.indexOf('\0') >= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "filename 에 null byte 포함");
        }
        int dot = safeName.lastIndexOf('.');
        if (dot < 0 || dot == safeName.length() - 1) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "확장자 누락");
        }
        String ext = safeName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!allowedExtensions.contains(ext)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않는 확장자: ." + ext + " (allow=" + allowedExtensions + ")");
        }
        String expectedMime = EXT_TO_MIME.get(ext);
        if (expectedMime == null) {
            // 운영자가 allowedExtensions 에 미등록 확장자를 추가한 경우 — 안전 거부
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "MIME 매핑이 없는 확장자: ." + ext);
        }
        if (rawFiletype != null && !rawFiletype.isBlank() && !rawFiletype.equalsIgnoreCase(expectedMime)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "filetype 과 확장자 불일치 (filetype=" + rawFiletype + ", expected=" + expectedMime + ")");
        }
        return new ValidatedMeta(safeName, expectedMime);
    }

    private static String stripPath(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    public record ValidatedMeta(String fileName, String mimeType) {}
}
