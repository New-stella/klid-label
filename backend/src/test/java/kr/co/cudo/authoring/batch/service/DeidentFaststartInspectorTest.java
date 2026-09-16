package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.service.DeidentFaststartInspector.Layout;
import kr.co.cudo.authoring.support.TestVideoFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재생 인덱스 위치 판정기 — 합성 바이트로 박스 배치별 판정을 고정한다.
 *
 * @design ADR-072
 */
class DeidentFaststartInspectorTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("ftyp_moov_mdat_순서면_인덱스가_앞이다")
    void moovBeforeMdatIsFaststart() throws Exception {
        Path f = write(concat(box("ftyp", 12), box("moov", 40), box("mdat", 100)));
        assertThat(DeidentFaststartInspector.inspect(f)).isEqualTo(Layout.FASTSTART);
    }

    @Test
    @DisplayName("ftyp_mdat_moov_순서면_인덱스가_끝이다")
    void moovAfterMdatIsAtEnd() throws Exception {
        Path f = write(concat(box("ftyp", 12), box("free", 0), box("mdat", 100), box("moov", 40)));
        assertThat(DeidentFaststartInspector.inspect(f)).isEqualTo(Layout.MOOV_AT_END);
    }

    @Test
    @DisplayName("64비트_largesize_mdat_뒤의_moov도_끝으로_판정한다")
    void largesizeMdatThenMoov() throws Exception {
        Path f = write(concat(box("ftyp", 12), largeBox("mdat", 200), box("moov", 40)));
        assertThat(DeidentFaststartInspector.inspect(f)).isEqualTo(Layout.MOOV_AT_END);
    }

    @Test
    @DisplayName("largesize_헤더가_잘리면_판정하지_않는다")
    void truncatedLargesizeHeaderIsUndetermined() throws Exception {
        byte[] large = largeBox("mdat", 50);
        Path f = write(concat(box("ftyp", 12), Arrays.copyOf(large, 12)));
        assertThat(DeidentFaststartInspector.inspect(f)).isEqualTo(Layout.UNDETERMINED);
    }

    @Test
    @DisplayName("largesize_값이_16미만이면_판정하지_않는다")
    void largesizeBelowHeaderIsUndetermined() throws Exception {
        byte[] large = largeBox("mdat", 50);
        ByteBuffer.wrap(large).putLong(8, 8L);
        Path f = write(concat(box("ftyp", 12), large, box("moov", 40)));
        assertThat(DeidentFaststartInspector.inspect(f)).isEqualTo(Layout.UNDETERMINED);
    }

    @Test
    @DisplayName("박스가_파일끝을_넘는_잘린_파일은_판정하지_않는다")
    void truncatedFileIsUndetermined() throws Exception {
        byte[] all = concat(box("ftyp", 12), box("mdat", 100), box("moov", 40));
        Path f = write(Arrays.copyOf(all, all.length - 10));
        assertThat(DeidentFaststartInspector.inspect(f)).isEqualTo(Layout.UNDETERMINED);
    }

    @Test
    @DisplayName("첫_박스가_ftyp가_아니면_판정하지_않는다")
    void notIsoBmffIsUndetermined() throws Exception {
        Path f = write(concat(box("moov", 40), box("mdat", 100)));
        assertThat(DeidentFaststartInspector.inspect(f)).isEqualTo(Layout.UNDETERMINED);
    }

    @Test
    @DisplayName("moov가_없으면_판정하지_않는다")
    void missingMoovIsUndetermined() throws Exception {
        Path f = write(concat(box("ftyp", 12), box("mdat", 100)));
        assertThat(DeidentFaststartInspector.inspect(f)).isEqualTo(Layout.UNDETERMINED);
    }

    @Test
    @DisplayName("크기_0은_파일끝까지인_마지막_박스다")
    void sizeZeroExtendsToEof() throws Exception {
        byte[] mdat = box("mdat", 100);
        ByteBuffer.wrap(mdat).putInt(0, 0);
        Path moovFirst = write(concat(box("ftyp", 12), box("moov", 40), mdat));
        assertThat(DeidentFaststartInspector.inspect(moovFirst)).isEqualTo(Layout.FASTSTART);
        Path mdatLast = tmp.resolve("b.mp4");
        Files.write(mdatLast, concat(box("ftyp", 12), mdat));
        assertThat(DeidentFaststartInspector.inspect(mdatLast)).isEqualTo(Layout.UNDETERMINED);
    }

    @Test
    @DisplayName("박스크기가_8미만이거나_타입이_비문자면_판정하지_않는다")
    void corruptHeaderIsUndetermined() throws Exception {
        byte[] bad = box("mdat", 100);
        ByteBuffer.wrap(bad).putInt(0, 4);
        assertThat(DeidentFaststartInspector.inspect(write(concat(box("ftyp", 12), bad))))
                .isEqualTo(Layout.UNDETERMINED);
        byte[] binaryType = box("mdat", 100);
        binaryType[5] = 0x01;
        Path f2 = tmp.resolve("c.mp4");
        Files.write(f2, concat(box("ftyp", 12), binaryType, box("moov", 20)));
        assertThat(DeidentFaststartInspector.inspect(f2)).isEqualTo(Layout.UNDETERMINED);
    }

    @Test
    @DisplayName("빈_파일_없는_파일_텍스트_파일_링크는_판정하지_않는다")
    void nonVideoInputsAreUndetermined() throws Exception {
        Path empty = Files.createFile(tmp.resolve("empty.mp4"));
        assertThat(DeidentFaststartInspector.inspect(empty)).isEqualTo(Layout.UNDETERMINED);
        assertThat(DeidentFaststartInspector.inspect(tmp.resolve("none.mp4"))).isEqualTo(Layout.UNDETERMINED);
        Path text = Files.writeString(tmp.resolve("t.mp4"), "not a video at all but long enough text");
        assertThat(DeidentFaststartInspector.inspect(text)).isEqualTo(Layout.UNDETERMINED);
        assertThat(DeidentFaststartInspector.inspect((Path) null)).isEqualTo(Layout.UNDETERMINED);

        Path real = write(concat(box("ftyp", 12), box("mdat", 100), box("moov", 40)));
        Path link = tmp.resolve("link.mp4");
        Files.createSymbolicLink(link, real);
        assertThat(DeidentFaststartInspector.inspect(link)).isEqualTo(Layout.UNDETERMINED);
    }

    @Test
    @DisplayName("ffmpeg가_만든_실제_픽스처는_인덱스가_끝이다")
    void realFixtureHasMoovAtEnd() {
        Path f = TestVideoFixtures.writeTinyMp4(tmp.resolve("tiny.mp4"));
        assertThat(DeidentFaststartInspector.inspect(f)).isEqualTo(Layout.MOOV_AT_END);
    }

    // ── helpers ──

    private Path write(byte[] bytes) throws Exception {
        Path f = tmp.resolve("a-" + System.nanoTime() + ".mp4");
        Files.write(f, bytes);
        return f;
    }

    /** 32비트 크기 박스 — 헤더 8바이트 + payload. */
    static byte[] box(String type, int payload) {
        ByteBuffer b = ByteBuffer.allocate(8 + payload);
        b.putInt(8 + payload);
        b.put(type.getBytes(StandardCharsets.US_ASCII));
        return b.array();
    }

    /** 64비트 largesize 박스 — 헤더 16바이트 + payload. */
    static byte[] largeBox(String type, int payload) {
        ByteBuffer b = ByteBuffer.allocate(16 + payload);
        b.putInt(1);
        b.put(type.getBytes(StandardCharsets.US_ASCII));
        b.putLong(16L + payload);
        return b.array();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
