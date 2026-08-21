package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.transfer.service.ImportFileStager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 산출물 파일 옮기기와 <b>실패 보상</b>의 경계 시험.
 *
 * <h3>무엇을 고정하는가</h3>
 * <ul>
 *   <li>보상은 <b>이번 이관이 만들기로 한 파일</b>만 지운다. 디렉터리를 통째로 지우면 같은 산출물을
 *       동시에 가져온 <b>다른 요청의 성공분</b>이 함께 사라진다 — 그 디렉터리 이름은 이관 식별자라
 *       두 요청이 같은 자리를 쓴다.</li>
 *   <li>목적지가 바로가기여도 <b>따라가 쓰지 않는다</b> — 따라가면 저장소 밖 파일이 덮인다
 *       (CWE-59/367).</li>
 *   <li>다 쓰지 못한 임시 파일을 남기지 않는다.</li>
 * </ul>
 *
 * @design DOMAIN-017
 * @design ERD-031
 * @design AC-048
 */
class ImportFileStagerTest {

    private final ImportFileStager stager = new ImportFileStager();

    @Test
    @DisplayName("보상은_이번에_만든_파일만_지우고_같은_자리의_남의_성공분은_남긴다")
    void 보상은_이번에_만든_파일만_지우고_같은_자리의_남의_성공분은_남긴다(@TempDir Path temp) throws IOException {
        Path shared = Files.createDirectories(temp.resolve("frames").resolve("deid").resolve("IMPORT-A-1"));
        Path mine = Files.write(shared.resolve("mine.jpg"), new byte[]{1});
        Path theirs = Files.write(shared.resolve("theirs.jpg"), new byte[]{2});

        stager.cleanupQuietly(List.of(mine.toString()));

        assertThat(Files.exists(mine)).isFalse();
        // ★ 이 단언이 이 시험의 중심이다. 디렉터리를 훑어 지우면 여기가 깨지고, 실제로는 먼저
        //   커밋에 성공한 이관의 파일이 사라진다(DB 행만 남고 산출물이 비는 상태).
        assertThat(Files.exists(theirs)).isTrue();
        assertThat(Files.isDirectory(shared)).isTrue();
    }

    @Test
    @DisplayName("비워진_디렉터리는_비어_있을_때만_함께_걷어낸다")
    void 비워진_디렉터리는_비어_있을_때만_함께_걷어낸다(@TempDir Path temp) throws IOException {
        Path dir = Files.createDirectories(temp.resolve("frames").resolve("raw").resolve("IMPORT-B-2"));
        Path only = Files.write(dir.resolve("only.jpg"), new byte[]{1});

        stager.cleanupQuietly(List.of(only.toString()));

        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    @DisplayName("목적지가_바로가기여도_따라가_쓰지_않는다")
    void 목적지가_바로가기여도_따라가_쓰지_않는다(@TempDir Path temp) throws IOException {
        Path source = Files.write(temp.resolve("source.jpg"), new byte[]{7, 7, 7});
        Path outside = Files.write(temp.resolve("outside.jpg"), new byte[]{9});
        Path destinationDir = Files.createDirectories(temp.resolve("dest"));
        Path destination = destinationDir.resolve("frame.jpg");
        Files.createSymbolicLink(destination, outside);

        stager.copy(source, destination.toString());

        // 바로가기를 따라갔다면 저장소 밖 파일이 덮였을 것이다.
        assertThat(Files.readAllBytes(outside)).containsExactly(9);
        assertThat(Files.isSymbolicLink(destination)).isFalse();
        assertThat(Files.readAllBytes(destination)).containsExactly(7, 7, 7);
    }

    @Test
    @DisplayName("옮기고_나면_다_쓰지_못한_임시_파일이_남지_않는다")
    void 옮기고_나면_다_쓰지_못한_임시_파일이_남지_않는다(@TempDir Path temp) throws IOException {
        Path source = Files.write(temp.resolve("source.jpg"), new byte[]{1, 2});
        Path destination = temp.resolve("dest").resolve("frame.jpg");

        stager.copy(source, destination.toString());

        try (Stream<Path> entries = Files.list(destination.getParent())) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .containsExactly("frame.jpg");
        }
    }
}
