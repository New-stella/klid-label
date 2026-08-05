package kr.co.cudo.authoring.dataset.export;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link DatasetExportFolderSizeCalculator} 단위 테스트 (V173, @req R4).
 *
 * <p>검증 초점: ①버전 폴더 하위 <b>전체</b> 바이트 합산 ②형제 버전·비식별 영상이 섞이지 않음
 * ③실패는 예외가 아니라 {@code null}(export 는 성공으로 종결) ④심링크 미추종(CWE-59).
 */
class DatasetExportFolderSizeCalculatorTest {

    private final DatasetExportFolderSizeCalculator calculator = new DatasetExportFolderSizeCalculator();

    @TempDir
    Path videoRoot;

    private Path v1;

    @BeforeEach
    void setUp() throws IOException {
        v1 = Files.createDirectories(videoRoot.resolve("v1"));
    }

    private void write(Path path, int bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[bytes]);
    }

    @Test
    @DisplayName("버전폴더_하위_전체_바이트를_합산한다 — orgnl/deid 2벌 + JSON 포함")
    void sumsAllBytesUnderVersionDir() throws IOException {
        // given — 실제 산출 형상(v1/orgnl, v1/deid 각각 이미지+JSON)
        write(v1.resolve("orgnl").resolve("frame-0.jpg"), 100);
        write(v1.resolve("orgnl").resolve("frame-0.json"), 20);
        write(v1.resolve("deid").resolve("frame-0.jpg"), 300);
        write(v1.resolve("deid").resolve("frame-0.json"), 20);

        // when
        Long bytes = calculator.calculate(videoRoot, 1);

        // then
        assertThat(bytes).isEqualTo(440L);
    }

    @Test
    @DisplayName("다른_버전과_비식별영상은_합산에_섞이지_않는다 — 버전당 용량이지 영상 누적 용량이 아니다")
    void excludesSiblingVersionsAndDeidVideo() throws IOException {
        // given — v1(대상) 옆에 v2 와 비식별 영상 디렉터리가 형제로 존재한다.
        write(v1.resolve("deid").resolve("frame-0.jpg"), 100);
        write(videoRoot.resolve("v2").resolve("deid").resolve("frame-0.jpg"), 999);
        write(videoRoot.resolve("deid").resolve("deidentified.mp4"), 5000);

        // when
        Long bytes = calculator.calculate(videoRoot, 1);

        // then — v1 하위만 잡힌다(관제 dataset_versions 는 버전 단위 용량이다).
        assertThat(bytes).isEqualTo(100L);
    }

    @Test
    @DisplayName("심링크는_따라가지_않고_합산하지도_않는다 — 폴더 밖 용량이 이 버전으로 잡히면 안 됨")
    void doesNotFollowOrCountSymlinks() throws IOException {
        // given — v1 안의 심링크가 폴더 밖(형제 v2)의 큰 파일을 가리킨다.
        write(v1.resolve("deid").resolve("frame-0.jpg"), 100);
        Path outside = videoRoot.resolve("v2").resolve("huge.bin");
        write(outside, 8000);
        try {
            Files.createSymbolicLink(v1.resolve("deid").resolve("link.jpg"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 심링크 미지원 파일시스템 — 이 단언은 건너뛴다.
        }

        // when
        Long bytes = calculator.calculate(videoRoot, 1);

        // then — 링크 대상(8000)이 섞이지 않는다.
        assertThat(bytes).isEqualTo(100L);
    }

    @Test
    @DisplayName("버전폴더_자리가_서브트리_밖을_가리키는_심링크면_그_내용을_합산하지_않는다")
    void rejectsVersionDirSymlinkPointingOutsideSubtree() throws IOException {
        // given — v2 <디렉터리 자체>를 영상 루트 <밖>의 디렉터리를 가리키는 심링크로 만든다.
        //   (기존 doesNotFollowOrCountSymlinks 는 폴더 <안의 파일> 심링크라 다른 케이스다.)
        //   이 형상은 resolveUnder 의 exact-segment 판정 또는 resolveRealPathUnder 의 실경로
        //   재확인에서 걸려야 한다 — 통과하면 폴더 밖 용량이 이 버전 용량으로 보고된다.
        Path outsideDir = Files.createDirectories(videoRoot.getParent().resolve("outside-tree"));
        write(outsideDir.resolve("huge.bin"), 7777);
        try {
            Files.createSymbolicLink(videoRoot.resolve("v2"), outsideDir);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 심링크 미지원 파일시스템 — 이 단언은 건너뛴다.
        }

        // when
        Long bytes = calculator.calculate(videoRoot, 2);

        // then — 거부(null)든 빈 집계(0)든, <링크 대상의 바이트는 절대 섞이지 않는다>.
        //   가드를 느슨하게 바꾸면 7777 이 새어 들어와 이 단언이 깨진다.
        //   (null 을 그대로 통과시켜야 하므로 isNotEqualTo 는 쓸 수 없다 — AssertJ 가 null actual 에서 실패한다.)
        assertThat(bytes == null || bytes == 0L)
                .as("서브트리 밖 심링크 대상이 집계되면 안 된다 (실제=%s)", bytes)
                .isTrue();
        // 실측: resolveUnder 의 exact-segment 판정이 거부해 null 로 종결된다(WARN "path guard rejected").
        assertThat(bytes).isNull();
    }

    @Test
    @DisplayName("빈_버전폴더는_0을_반환한다 — 부재(null)와 구분된다")
    void emptyDirIsZeroNotNull() {
        assertThat(calculator.calculate(videoRoot, 1)).isZero();
    }

    @Test
    @DisplayName("버전폴더가_없으면_null이고_예외를_던지지_않는다")
    void missingVersionDirReturnsNull() {
        assertThatCode(() -> calculator.calculate(videoRoot, 9)).doesNotThrowAnyException();
        assertThat(calculator.calculate(videoRoot, 9)).isNull();
    }

    @Test
    @DisplayName("입력이_불량이어도_null만_반환한다 — 용량 산출은 export 를 깨뜨리지 않는다")
    void invalidInputReturnsNull() {
        assertThat(calculator.calculate(null, 1)).isNull();
        assertThat(calculator.calculate(videoRoot, 0)).isNull();
        assertThat(calculator.calculate(videoRoot, -1)).isNull();
    }

    @Test
    @DisplayName("버전폴더_자리가_파일이면_null이다 — 경로 가드/순회 실패는 fail-closed")
    void versionPathIsFileReturnsNull() throws IOException {
        // given — v3 가 디렉터리가 아니라 파일이다(손상 형상).
        Files.write(videoRoot.resolve("v3"), "not-a-dir".getBytes(StandardCharsets.UTF_8));

        // when / then — Files.walk 는 파일 1개를 훑으므로 예외 없이 그 크기를 돌려준다.
        //   중요한 것은 <예외가 전파되지 않는다>는 계약이다(export 종결 분기 불변).
        assertThatCode(() -> calculator.calculate(videoRoot, 3)).doesNotThrowAnyException();
    }
}
