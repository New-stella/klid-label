package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.transfer.service.ImportFileStager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
 *   <li><b>덩어리 경계를 넘는 파일도 바이트가 한 치도 달라지지 않는다</b> — 진행 신호를 흘리는
 *       갈래는 손수 만든 읽기·쓰기 루프라, 그 경로가 시험에서 밟히지 않으면 덩어리 누락·중복·
 *       뒤바뀜이 있어도 전부 초록으로 남는다. 이 기능이 다루는 것은 수백 MB~수 GB 영상이다.</li>
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

    @Test
    @DisplayName("★덩어리_경계를_넘는_파일도_바이트가_한_치도_다르지_않게_옮겨진다")
    void 덩어리_경계를_넘는_파일도_바이트가_한_치도_다르지_않게_옮겨진다(@TempDir Path temp) throws IOException {
        // ★표본은 덩어리 크기에서 <파생>한다. 크기를 따로 적으면 그 값을 바꿨을 때 표본이 경계
        //   안으로 들어와, 여러 덩어리를 도는 경로가 한 번도 밟히지 않은 채 초록으로 남는다.
        byte[] payload = new byte[ImportFileStager.PROGRESS_CHUNK_BYTES * 3 + 1_234];
        // ★같은 값으로 채우지 않는다. 0 으로 채우면 덩어리가 뒤바뀌거나 중복돼도 결과가 같아
        //   이 시험이 통째로 공회전한다. 자리마다 값이 다른 바이트를 쓴다(씨앗 고정 — 재현 가능).
        new Random(20260903L).nextBytes(payload);
        Path source = Files.write(temp.resolve("big.mp4"), payload);
        Path destination = temp.resolve("dest").resolve("big.mp4");
        List<Long> beats = new ArrayList<>();

        stager.copy(source, destination.toString(), beats::add);

        // ① 옮긴 결과가 원본과 <바이트 단위로> 같다.
        assertThat(Files.readAllBytes(destination)).isEqualTo(payload);
        // ② 덩어리가 실제로 여러 번 돌았다 — 한 번이면 경계를 넘지 않은 것이라 ①이 아무것도 지키지 못한다.
        assertThat(beats).hasSizeGreaterThan(2);
        // ③ 맨 앞의 신호는 0 이다. 이 한 번이 있어야 작은 파일에서도 신호가 최소 한 번은 나간다.
        assertThat(beats.get(0)).isZero();
        // ④ 누적값이 실제로 전량을 셌다 — 덜 세면 호출부가 진행을 잘못 읽는다.
        assertThat(beats.get(beats.size() - 1)).isEqualTo((long) payload.length);
    }
}
