package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DatasetExportPathResolver} 순수 단위 테스트 — Spring 컨텍스트 없이 경로 계산/방어를 검증한다.
 *
 * <p>최종 폴더 규약: {@code {labeling_root}/{RAW_SN}/v{n}/{orgnl|deid}/} (버전이 종류 상위).
 */
class DatasetExportPathResolverTest {

    private static final String ROOT = "./storage/labeling";

    private final DatasetExportPathResolver resolver = new DatasetExportPathResolver(ROOT);

    @Test
    @DisplayName("export경로가_labeling루트_하위로_정규화된다")
    void resolvesUnderLabelingRootNormalized() {
        // given
        long rawSn = 42L;

        // when
        Path resolved = resolver.resolve(rawSn, ExportKind.ORIGINAL, 1);

        // then — 절대경로이며 labeling_root 하위 + {rawSn}/v1/orgnl 로 끝난다 (CWE-22 base 포함)
        assertThat(resolved.isAbsolute()).isTrue();
        assertThat(resolved.startsWith(resolver.labelingRoot())).isTrue();
        assertThat(resolved.endsWith(Path.of("42", "v1", "orgnl"))).isTrue();
    }

    @Test
    @DisplayName("버전이_orgnl보다_상위경로다")
    void versionSegmentIsParentOfKind() {
        // given / when
        Path orgnl = resolver.resolve(7L, ExportKind.ORIGINAL, 3);
        Path deid = resolver.resolve(7L, ExportKind.DEIDENTIFIED, 3);

        // then — .../v3/orgnl, .../v3/deid — 마지막 세그먼트가 종류, 그 부모가 v3
        assertThat(orgnl.getFileName().toString()).isEqualTo("orgnl");
        assertThat(orgnl.getParent().getFileName().toString()).isEqualTo("v3");
        assertThat(deid.getFileName().toString()).isEqualTo("deid");
        assertThat(deid.getParent().getFileName().toString()).isEqualTo("v3");
        // 같은 버전의 orgnl/deid 는 동일한 v{n} 부모를 공유한다
        assertThat(orgnl.getParent()).isEqualTo(deid.getParent());
    }

    @Test
    @DisplayName("version이_0이하면_거부된다")
    void rejectsNonPositiveVersion() {
        // when / then — version 0, 음수 모두 INVALID_INPUT (CWE-20)
        assertThatThrownBy(() -> resolver.resolve(1L, ExportKind.ORIGINAL, 0))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> resolver.resolve(1L, ExportKind.ORIGINAL, -5))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("videoId가_0이하면_거부된다")
    void rejectsNonPositiveVideoId() {
        // when / then — videoId 0, 음수 모두 INVALID_INPUT (CWE-20)
        assertThatThrownBy(() -> resolver.resolve(0L, ExportKind.ORIGINAL, 1))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> resolver.resolve(-1L, ExportKind.DEIDENTIFIED, 1))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("상대경로_root도_절대경로로_정규화되어_base하위_보장")
    void relativeRootIsNormalizedToAbsoluteBase() {
        // given — 상대경로 root 로 구성해도 base 는 절대경로로 정규화된다
        DatasetExportPathResolver relative = new DatasetExportPathResolver("storage/../storage/labeling");

        // when
        Path resolved = relative.resolve(9L, ExportKind.DEIDENTIFIED, 2);

        // then — 결과가 정규화된 base 하위이며 예상 세그먼트로 끝난다 (CWE-22 이탈 없음)
        assertThat(relative.labelingRoot().isAbsolute()).isTrue();
        assertThat(resolved.startsWith(relative.labelingRoot())).isTrue();
        assertThat(resolved.endsWith(Path.of("9", "v2", "deid"))).isTrue();
    }
}
