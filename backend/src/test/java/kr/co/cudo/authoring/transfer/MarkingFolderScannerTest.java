package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.transfer.config.MarkingImportProperties;
import kr.co.cudo.authoring.transfer.service.MarkingFolderScan;
import kr.co.cudo.authoring.transfer.service.MarkingFolderScanner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 폴더 훑기가 <b>무엇을 담고 무엇을 담지 않는지</b>를 실제 파일시스템 위에서 고정한다.
 *
 * <h3>왜 진짜 파일시스템인가</h3>
 * <p>여기서 지키는 규칙은 대부분 <b>담지 않는 것</b>이다 — 밖을 가리키는 바로가기, 상한을 넘는 깊이,
 * 상한을 넘는 파일 수. 가짜 파일시스템으로 대신하면 그 규칙이 진짜 링크 위에서 성립하는지는 아무도
 * 확인하지 않은 채 통과한다.
 *
 * <h3>상한은 시험용으로 작게 넣는다</h3>
 * <p>운영 기본값으로 상한을 재현하려면 수만 개의 파일을 만들어야 한다. 시험이 그 값 자체를 고정하지는
 * 않는다 — 근거 없는 숫자라 설정으로 뽑아 둔 것이고, 시험이 붙잡으면 배포로 조정할 수 없게 된다.
 *
 * @design DOMAIN-017
 * @design API-216
 * @design SEQ-030
 * @design AC-1032
 */
class MarkingFolderScannerTest {

    @TempDir
    Path root;

    /** 허용 범위 <b>밖</b>. 바로가기가 여기를 가리켜도 담기면 안 된다. */
    @TempDir
    Path outside;

    // ------------------------------------------------------------------ 재귀 짝짓기

    @Test
    @DisplayName("문서와_영상이_서로_다른_자리에_있어도_이름으로_짝이_된다")
    void 문서와_영상이_서로_다른_자리에_있어도_이름으로_짝이_된다() throws IOException {
        // 짝짓기 기준은 폴더 구조가 아니라 문서가 적어 둔 이름이다(API-216).
        Path docs = Files.createDirectories(root.resolve("reports"));
        Path videos = Files.createDirectories(root.resolve("clips/2026/07"));
        Files.writeString(docs.resolve("a.json"), "[]");
        Files.writeString(videos.resolve("a.mp4"), "x");

        MarkingFolderScan scan = scanner(properties()).scan(root);

        assertThat(scan.markingDocuments()).hasSize(1);
        assertThat(scan.uniqueVideo("a.mp4")).isEqualTo(videos.resolve("a.mp4"));
        assertThat(scan.scannedFileCount()).isEqualTo(2);
        assertThat(scan.truncated()).isFalse();
    }

    @Test
    @DisplayName("같은_이름의_영상이_둘_이상이면_어느_쪽인지_정하지_않는다")
    void 같은_이름의_영상이_둘_이상이면_어느_쪽인지_정하지_않는다() throws IOException {
        // 짐작해 하나를 고르면 다른 영상의 마킹이 엉뚱한 영상에 붙고, 저장된 뒤에는 구분할 수 없다.
        Files.writeString(Files.createDirectories(root.resolve("x")).resolve("a.mp4"), "x");
        Files.writeString(Files.createDirectories(root.resolve("y")).resolve("a.mp4"), "y");

        MarkingFolderScan scan = scanner(properties()).scan(root);

        assertThat(scan.uniqueVideo("a.mp4")).isNull();
        assertThat(scan.isAmbiguous("a.mp4")).isTrue();
    }

    @Test
    @DisplayName("영상으로_보지_않는_확장자는_짝_후보에_들어가지_않는다")
    void 영상으로_보지_않는_확장자는_짝_후보에_들어가지_않는다() throws IOException {
        Files.writeString(root.resolve("a.mp4"), "x");
        Files.writeString(root.resolve("a.txt"), "x");
        Files.writeString(root.resolve("readme"), "x");

        MarkingFolderScan scan = scanner(properties()).scan(root);

        assertThat(scan.videosByName()).containsOnlyKeys("a.mp4");
        // 다만 <훑은 파일 수>에는 셋 다 들어간다 — 이름을 집어 판정한 것은 셋 다이기 때문이다.
        assertThat(scan.scannedFileCount()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 바로가기

    @Test
    @DisplayName("허용_범위_밖을_가리키는_바로가기_영상을_따라가지_않고_건너뛴다")
    void 허용_범위_밖을_가리키는_바로가기_영상을_따라가지_않고_건너뛴다() throws IOException {
        Path target = outside.resolve("secret.mp4");
        Files.writeString(target, "x");
        Assumptions.assumeTrue(trySymlink(root.resolve("a.mp4"), target),
                "이 파일시스템은 바로가기를 만들 수 없다");

        MarkingFolderScan scan = scanner(properties()).scan(root);

        // 폴더 하나를 통과시키면 그 아래 링크로 아무 파일이나 읽힌다(CWE-22/59/367).
        assertThat(scan.videosByName()).isEmpty();
        assertThat(scan.symlinkSkipped()).isEqualTo(1);
        // 건너뛴 사실을 알리지 않으면 항목이 이유 없이 사라진 것이 된다.
        assertThat(scan.scannedFileCount()).isZero();
    }

    @Test
    @DisplayName("바로가기_폴더도_따라가지_않는다")
    void 바로가기_폴더도_따라가지_않는다() throws IOException {
        Files.writeString(Files.createDirectories(outside.resolve("deep")).resolve("b.json"), "[]");
        Assumptions.assumeTrue(trySymlink(root.resolve("link"), outside.resolve("deep")),
                "이 파일시스템은 바로가기를 만들 수 없다");

        MarkingFolderScan scan = scanner(properties()).scan(root);

        assertThat(scan.markingDocuments()).isEmpty();
        assertThat(scan.symlinkSkipped()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 상한

    @Test
    @DisplayName("깊이_상한을_넘는_하위_폴더는_훑지_않고_일부만_봤다고_알린다")
    void 깊이_상한을_넘는_하위_폴더는_훑지_않고_일부만_봤다고_알린다() throws IOException {
        Path deep = Files.createDirectories(root.resolve("a/b/c"));
        Files.writeString(deep.resolve("deep.json"), "[]");
        Files.writeString(root.resolve("top.json"), "[]");

        MarkingFolderScan scan = scanner(properties(1, 100, 100)).scan(root);

        // 깊이 1까지만 내려가므로 a 안은 보지만 a/b 아래는 보지 않는다.
        assertThat(scan.markingDocuments()).extracting(p -> p.getFileName().toString())
                .containsExactly("top.json");
        // 조용히 자르면 「덜 들어온 것」과 「원래 그만큼인 것」이 구분되지 않는다.
        assertThat(scan.truncated()).isTrue();
    }

    @Test
    @DisplayName("파일_수_상한에_걸리면_읽기를_그만두고_일부만_봤다고_알린다")
    void 파일_수_상한에_걸리면_읽기를_그만두고_일부만_봤다고_알린다() throws IOException {
        for (int i = 0; i < 5; i++) {
            Files.writeString(root.resolve("f" + i + ".json"), "[]");
        }

        MarkingFolderScan scan = scanner(properties(8, 3, 100)).scan(root);

        assertThat(scan.scannedFileCount()).isEqualTo(3);
        assertThat(scan.truncated()).isTrue();
    }

    @Test
    @DisplayName("문서_수_상한에_걸리면_읽기를_그만두고_일부만_봤다고_알린다")
    void 문서_수_상한에_걸리면_읽기를_그만두고_일부만_봤다고_알린다() throws IOException {
        for (int i = 0; i < 5; i++) {
            Files.writeString(root.resolve("f" + i + ".json"), "[]");
        }

        MarkingFolderScan scan = scanner(properties(8, 100, 2)).scan(root);

        assertThat(scan.markingDocuments()).hasSize(2);
        assertThat(scan.truncated()).isTrue();
    }

    @Test
    @DisplayName("상한에_걸리지_않으면_일부만_봤다고_말하지_않는다")
    void 상한에_걸리지_않으면_일부만_봤다고_말하지_않는다() throws IOException {
        // 거짓 경보도 결함이다 — 사람이 매번 「일부만 들어왔다」를 보면 그 표시를 무시하게 된다.
        Files.writeString(root.resolve("a.json"), "[]");
        Files.writeString(root.resolve("a.mp4"), "x");

        assertThat(scanner(properties()).scan(root).truncated()).isFalse();
    }

    // ------------------------------------------------------------------ 순서

    @Test
    @DisplayName("문서_목록의_순서를_이름으로_고정한다")
    void 문서_목록의_순서를_이름으로_고정한다() throws IOException {
        // 파일시스템이 주는 순서에는 보장이 없다. 그대로 두면 어느 항목이 상한에 걸려 잘리는지가
        // 실행마다 달라져 같은 폴더를 두 번 검사했을 때 결과가 달라진다.
        for (String name : List.of("c.json", "a.json", "b.json")) {
            Files.writeString(root.resolve(name), "[]");
        }

        MarkingFolderScan scan = scanner(properties()).scan(root);

        assertThat(scan.markingDocuments()).extracting(p -> p.getFileName().toString())
                .containsExactly("a.json", "b.json", "c.json");
    }

    // ------------------------------------------------------------------ 보조

    private static MarkingFolderScanner scanner(MarkingImportProperties properties) {
        return new MarkingFolderScanner(properties);
    }

    private static MarkingImportProperties properties() {
        return properties(8, 1000, 1000);
    }

    private static MarkingImportProperties properties(int maxDepth, int maxEntries, int maxItems) {
        return new MarkingImportProperties(maxDepth, maxEntries, maxItems, 100, 500,
                List.of("mp4", "mov"), 2, 3, 0.5, 30);
    }

    private static boolean trySymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
