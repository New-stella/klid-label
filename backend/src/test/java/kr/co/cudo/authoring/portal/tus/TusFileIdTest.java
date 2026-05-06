package kr.co.cudo.authoring.portal.tus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 11 — TusFileId 구분자/추출 단위 테스트.
 *
 * 핵심: 구분자를 '_' → '~' 로 변경하여 underscore 포함 userId 도 정확히 추출 (CRITICAL-1 회귀 방지).
 */
class TusFileIdTest {

    @Test
    @DisplayName("TusFileId가_underscore_포함_userId도_정확히_추출")
    void extractUserIdWithUnderscoreUserId() {
        // given: 외부 시스템이 발급하는 sub 가 "user_1" 처럼 underscore 포함하는 케이스
        String userId = "user_1";
        String fileId = TusFileId.generate(userId);

        // then: '~' 구분자 채택으로 underscore 와 충돌 없이 정확히 추출됨
        assertThat(fileId).startsWith("user_1~");
        assertThat(TusFileId.extractUserId(fileId)).isEqualTo("user_1");
        assertThat(TusFileId.ownerMatches(fileId, "user_1")).isTrue();
        // IDOR 방어: 부분 일치한 다른 userId 는 불일치
        assertThat(TusFileId.ownerMatches(fileId, "user")).isFalse();
        assertThat(TusFileId.ownerMatches(fileId, "user_2")).isFalse();
    }

    @Test
    @DisplayName("TusFileId_구분자_여러개_포함시_형식오류")
    void multipleSeparatorsRejected() {
        String malformed = "alice~uuid~extra";
        assertThatThrownBy(() -> TusFileId.validateFormat(malformed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TusFileId_path_traversal_차단")
    void pathTraversalRejected() {
        assertThatThrownBy(() -> TusFileId.validateFormat("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TusFileId.validateFormat("alice~" + UUID.randomUUID() + "/.."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TusFileId_정상_형식_검증_통과")
    void validFormatAccepted() {
        String fileId = TusFileId.generate("alice");
        assertThat(fileId).matches("alice~[0-9a-f-]{36}");
        // 호출이 예외 없이 통과해야 함
        TusFileId.validateFormat(fileId);
    }
}
