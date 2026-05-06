package kr.co.cudo.authoring.portal.tus;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Phase 11 — TUS FileID. CVAT portable-modules/03 패턴 포팅.
 *
 * 형식: {userId}~{uuid4}
 *  예) {@code 5~550e8400-e29b-41d4-a716-446655440000}
 *  예) {@code user_1~550e8400-e29b-41d4-a716-446655440000} (userId 에 underscore 포함 OK)
 *
 * 보안 (Critical):
 *  - userId 부분으로 IDOR 1차 차단 (verify 시 토큰 sub 와 일치 확인).
 *  - uuid 로 충돌 방지.
 *  - 정규식 외 문자 (../, /, \, null byte 등) 모두 차단 → CWE-22 Path Manipulation 방어.
 *  - 구분자 '~' 채택 이유: URL-safe + UUID(0-9a-f-) 와 충돌 없음 + userId 의 underscore 와 충돌 없음 →
 *    {@code indexOf('~')} 로 안전하게 분리 가능 (이전 underscore 구분자는 user_1 같은 sub 와 충돌하여 IDOR 우회 가능).
 */
public final class TusFileId {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** userId: 영숫자 + 일부 안전 문자만 허용 (포털 발급 sub 형식 미정 → 보수적 allowlist). */
    private static final Pattern USER_ID_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]{1,50}");

    /** fileId 구분자 — userId 와 uuid 사이. underscore/하이픈과 충돌 없는 URL-safe 문자. */
    static final char SEPARATOR = '~';

    private TusFileId() {}

    /** 새 FileID 생성. userId 검증은 호출자 책임 (이미 토큰에서 추출). */
    public static String generate(String userId) {
        validateUserId(userId);
        return userId + SEPARATOR + UUID.randomUUID();
    }

    /** FileID 형식 자체가 올바른지 검증 (path traversal 차단 + uuid 형식). */
    public static void validateFormat(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId 누락");
        }
        // path traversal / null byte 차단
        if (fileId.contains("..") || fileId.contains("/") || fileId.contains("\\") || fileId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("fileId 에 허용되지 않은 문자가 포함됨");
        }
        int sep = fileId.indexOf(SEPARATOR);
        if (sep <= 0 || sep == fileId.length() - 1) {
            throw new IllegalArgumentException("fileId 형식 오류");
        }
        // 구분자가 여러 번 나오면 형식 오류 (uuid 에는 '~' 가 없으므로 1회만 허용)
        if (fileId.indexOf(SEPARATOR, sep + 1) >= 0) {
            throw new IllegalArgumentException("fileId 형식 오류");
        }
        String userId = fileId.substring(0, sep);
        String uuid = fileId.substring(sep + 1);
        validateUserId(userId);
        if (!UUID_PATTERN.matcher(uuid).matches()) {
            throw new IllegalArgumentException("fileId uuid 형식 오류");
        }
    }

    /** FileID 에서 userId 추출 (validateFormat 통과 가정). */
    public static String extractUserId(String fileId) {
        validateFormat(fileId);
        return fileId.substring(0, fileId.indexOf(SEPARATOR));
    }

    /** FileID 에 인코딩된 userId 와 토큰 userId 가 일치하는지 검증 (IDOR 1차 방어). */
    public static boolean ownerMatches(String fileId, String tokenUserId) {
        if (fileId == null || tokenUserId == null) {
            return false;
        }
        try {
            return extractUserId(fileId).equals(tokenUserId);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void validateUserId(String userId) {
        if (userId == null || !USER_ID_PATTERN.matcher(userId).matches()) {
            throw new IllegalArgumentException("userId 형식 오류");
        }
    }
}
