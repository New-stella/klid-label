package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.service.PortalMaterialsUnpacker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 압축본 복사·해제의 신뢰 경계를 고정한다.
 *
 * <h3>고정하는 축</h3>
 * <ol>
 *   <li><b>원본은 불변</b> — 내용·크기·수정시각이 조달 전후로 같다. 포털이 소유·관리하는 원본이라
 *       건드리면 그쪽 배포본과 무결성 값이 깨진다.</li>
 *   <li><b>경로 탈출 거부</b> — 대상 밖을 가리키는 항목이 있으면 <b>그 자리에서 중단</b>한다.</li>
 *   <li><b>자원 상한</b> — 항목 수와 해제 총 바이트. 총량은 <b>실제로 쓴 바이트</b>로만 판정하고
 *       항목이 선언한 크기는 보지 않는다(거짓일 수 있고, 앞에 덧대면 진짜 방어선이 실행되지 않는다).</li>
 * </ol>
 *
 * @design INT-014
 */
class PortalMaterialsUnpackerTest {

    /** 상한을 넉넉히 둔 기본 해제기 — 상한 시험만 좁은 값을 따로 만든다. */
    private final PortalMaterialsUnpacker unpacker =
            new PortalMaterialsUnpacker(1000, 10L * 1024 * 1024);

    @Test
    @DisplayName("정상_압축본을_풀고_사본은_남기지_않는다")
    void unpacksAndRemovesLocalCopy(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp.resolve("d.zip"), Map.of(
                "meta.json", "{}",
                "images/0001.jpg", "img"));
        Path staging = Files.createDirectory(tmp.resolve("staging"));

        PortalMaterialsUnpacker.UnpackResult result = unpacker.copyAndUnpack(zip, staging);

        Path content = staging.resolve(PortalMaterialsUnpacker.CONTENT_DIR);
        assertThat(Files.readString(content.resolve("meta.json"))).isEqualTo("{}");
        assertThat(Files.readString(content.resolve("images/0001.jpg"))).isEqualTo("img");
        assertThat(result.entryCount()).isGreaterThanOrEqualTo(2);
        assertThat(result.totalBytes()).isEqualTo(5); // "{}" + "img"
        // 사본은 해제가 끝나면 지운다 — 압축본 크기만큼 두 번 차지하지 않는다.
        assertThat(Files.exists(staging.resolve(PortalMaterialsUnpacker.LOCAL_COPY_NAME))).isFalse();
    }

    @Test
    @DisplayName("★★원본_압축본은_내용도_크기도_수정시각도_바뀌지_않는다")
    void sourceArchive_isNeverModified(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp.resolve("d.zip"), Map.of("a.txt", "hello"));
        // 수정시각을 과거로 못박아 「우연히 같아 보이는」 상태를 배제한다.
        FileTime before = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(zip, before);
        byte[] bytesBefore = Files.readAllBytes(zip);
        long sizeBefore = Files.size(zip);

        Path staging = Files.createDirectory(tmp.resolve("staging"));
        unpacker.copyAndUnpack(zip, staging);

        assertThat(Files.exists(zip)).isTrue();
        assertThat(Files.readAllBytes(zip)).isEqualTo(bytesBefore);
        assertThat(Files.size(zip)).isEqualTo(sizeBefore);
        assertThat(Files.getLastModifiedTime(zip)).isEqualTo(before);
    }

    @Test
    @DisplayName("★상위이동_항목은_거부하고_대상_밖에_아무것도_쓰지_않는다")
    void zipSlipParentTraversal_isRejected(@TempDir Path tmp) throws IOException {
        Path zip = zip(tmp.resolve("evil.zip"), Map.of(
                "ok.txt", "ok",
                "../escaped.txt", "pwned"));
        Path staging = Files.createDirectory(tmp.resolve("staging"));

        assertThatThrownBy(() -> unpacker.copyAndUnpack(zip, staging))
                .isInstanceOf(PortalMaterialsUnpacker.UnpackException.class)
                .extracting(e -> ((PortalMaterialsUnpacker.UnpackException) e).getReason())
                .isEqualTo(PortalMaterialsUnpacker.Reason.PATH_ESCAPE);

        // 대상 디렉터리의 부모(작업 중 자리)에도, 그 위에도 항목이 쓰이지 않았다.
        assertThat(Files.exists(staging.resolve("escaped.txt"))).isFalse();
        assertThat(Files.exists(tmp.resolve("escaped.txt"))).isFalse();
    }

    @Test
    @DisplayName("★절대경로_항목도_같은_판정에_걸린다")
    void zipSlipAbsolutePath_isRejected(@TempDir Path tmp) throws IOException {
        Path outside = tmp.resolve("absolute-target.txt");
        Path zip = zip(tmp.resolve("evil2.zip"), Map.of(outside.toString(), "pwned"));
        Path staging = Files.createDirectory(tmp.resolve("staging"));

        assertThatThrownBy(() -> unpacker.copyAndUnpack(zip, staging))
                .isInstanceOf(PortalMaterialsUnpacker.UnpackException.class);
        assertThat(Files.exists(outside)).isFalse();
    }

    @Test
    @DisplayName("★항목_수_상한을_넘으면_거부한다")
    void tooManyEntries_isRejected(@TempDir Path tmp) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            entries.put("f" + i + ".txt", "x");
        }
        Path zip = zip(tmp.resolve("many.zip"), entries);
        Path staging = Files.createDirectory(tmp.resolve("staging"));

        PortalMaterialsUnpacker tight = new PortalMaterialsUnpacker(3, 10_000);

        assertThatThrownBy(() -> tight.copyAndUnpack(zip, staging))
                .isInstanceOf(PortalMaterialsUnpacker.UnpackException.class)
                .extracting(e -> ((PortalMaterialsUnpacker.UnpackException) e).getReason())
                .isEqualTo(PortalMaterialsUnpacker.Reason.TOO_MANY_ENTRIES);
    }

    @Test
    @DisplayName("★★해제_총량_상한은_실제로_쓴_바이트로_막는다_선언값을_보지_않는다")
    void tooLargeTotal_isRejectedByActualBytes(@TempDir Path tmp) throws IOException {
        // ★ 이 시험이 실제로 무엇을 지키는지 <변이로 확인했다>: 총량 판정을 무력화하면 정확히
        //   이 시험이 죽는다. 처음에는 선언 크기 사전 검사를 앞에 두었는데, 그때는 그쪽이 먼저
        //   걸려 이 시험이 <진짜 방어선을 한 번도 실행하지 않았고> 그 방어선을 지워도 0 RED 였다.
        //   그래서 사전 검사를 걷어내 방어선을 하나로 만들었다.
        Path zip = zip(tmp.resolve("bomb.zip"), Map.of("big.bin", "A".repeat(4096)));
        Path staging = Files.createDirectory(tmp.resolve("staging"));

        PortalMaterialsUnpacker tight = new PortalMaterialsUnpacker(1000, 100);

        assertThatThrownBy(() -> tight.copyAndUnpack(zip, staging))
                .isInstanceOf(PortalMaterialsUnpacker.UnpackException.class)
                .extracting(e -> ((PortalMaterialsUnpacker.UnpackException) e).getReason())
                .isEqualTo(PortalMaterialsUnpacker.Reason.TOO_LARGE);
    }

    @Test
    @DisplayName("압축본이_손상되면_사유를_구분해_거부한다")
    void corruptArchive_isRejected(@TempDir Path tmp) throws IOException {
        Path notZip = Files.writeString(tmp.resolve("broken.zip"), "not a zip at all");
        Path staging = Files.createDirectory(tmp.resolve("staging"));

        assertThatThrownBy(() -> unpacker.copyAndUnpack(notZip, staging))
                .isInstanceOf(PortalMaterialsUnpacker.UnpackException.class)
                .extracting(e -> ((PortalMaterialsUnpacker.UnpackException) e).getReason())
                .isEqualTo(PortalMaterialsUnpacker.Reason.CORRUPT);
    }

    /** 지정한 항목만 담은 압축본을 만든다(디렉터리 항목은 넣지 않는다 — 해제기가 만든다). */
    private static Path zip(Path target, Map<String, String> entries) throws IOException {
        try (OutputStream os = Files.newOutputStream(target);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return target;
    }
}
