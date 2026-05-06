package kr.co.cudo.authoring.version;

import kr.co.cudo.authoring.common.client.dto.DiffFile;
import kr.co.cudo.authoring.common.client.dto.DiffResponse;
import kr.co.cudo.authoring.version.dto.DiffFileDto;
import kr.co.cudo.authoring.version.dto.DiffResponseDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 — diff 응답 페이로드 크기 제한 테스트 (OWASP API4:2023 / CWE-770).
 *
 * <p>대용량 patch / 다수 파일 diff 응답 시 메모리·응답 크기 폭증 방어 검증.
 */
class DiffPayloadLimitTest {

    @Test
    @DisplayName("diff_patch_64KB_초과시_truncate되어_반환")
    void patchTruncatedWhenExceeds64KB() {
        // given — MAX 의 2배 patch (suffix 길이를 무시하고도 줄어들도록 충분히 큰 입력)
        int original = DiffFileDto.MAX_PATCH_LEN * 2;
        String hugePatch = "x".repeat(original);
        DiffFile file = new DiffFile("p.json", "modified", 1, 0, hugePatch);

        // when
        DiffFileDto dto = DiffFileDto.from(file);

        // then — 본문 = MAX 만큼의 'x' + suffix("... [TRUNCATED Nb]") 형태
        assertThat(dto.patch()).startsWith("x".repeat(DiffFileDto.MAX_PATCH_LEN));
        assertThat(dto.patch()).contains("[TRUNCATED " + original + "b]");
        assertThat(dto.patch().length()).isGreaterThan(DiffFileDto.MAX_PATCH_LEN);
        assertThat(dto.patch().length()).isLessThan(original); // 폭증 방어 — 원본보다 작음
    }

    @Test
    @DisplayName("diff_patch_64KB_이하면_원본_그대로_반환")
    void patchUnchangedWhenWithinLimit() {
        String shortPatch = "x".repeat(DiffFileDto.MAX_PATCH_LEN);
        DiffFile file = new DiffFile("p.json", "modified", 1, 0, shortPatch);

        DiffFileDto dto = DiffFileDto.from(file);

        assertThat(dto.patch()).isEqualTo(shortPatch);
        assertThat(dto.patch()).doesNotContain("TRUNCATED");
    }

    @Test
    @DisplayName("diff_files_100개_초과시_상위_100개만_반환")
    void filesTruncatedWhenExceedsMaxFiles() {
        List<DiffFile> bulk = new ArrayList<>();
        int total = DiffResponseDto.MAX_FILES + 50;
        for (int i = 0; i < total; i++) {
            bulk.add(new DiffFile("p" + i + ".json", "modified", 1, 0, "small"));
        }

        DiffResponseDto resp = DiffResponseDto.of("from", "to", new DiffResponse(bulk));

        assertThat(resp.files()).hasSize(DiffResponseDto.MAX_FILES);
        // 앞쪽 100개만 유지
        assertThat(resp.files().get(0).path()).isEqualTo("p0.json");
        assertThat(resp.files().get(DiffResponseDto.MAX_FILES - 1).path())
                .isEqualTo("p" + (DiffResponseDto.MAX_FILES - 1) + ".json");
    }

    @Test
    @DisplayName("diff_patch_null이면_null_그대로_반환")
    void patchNullPassthrough() {
        DiffFile file = new DiffFile("p.json", "added", 1, 0, null);

        DiffFileDto dto = DiffFileDto.from(file);

        assertThat(dto.patch()).isNull();
    }
}
