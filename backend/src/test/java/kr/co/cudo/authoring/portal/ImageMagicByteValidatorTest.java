package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.service.ImageMagicByteValidator;
import kr.co.cudo.authoring.portal.service.ImageMagicByteValidator.ImageFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V107 이미지 매직바이트 검증기 단위 테스트 — JPEG/PNG 만 허용, 그 외(SVG/GIF/BMP/WEBP/HTML) 거부.
 */
class ImageMagicByteValidatorTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, (byte) 0xFF, (byte) 0xD9};
    /** 8바이트 시그니처 + IHDR 청크(길이 13 + "IHDR") — header-only 위조 방어를 위해 IHDR 까지 유효. */
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    @Test
    @DisplayName("JPEG_시그니처는_JPEG로_감지")
    void detectsJpeg() {
        Optional<ImageFormat> f = ImageMagicByteValidator.detect(JPEG);
        assertThat(f).contains(ImageFormat.JPEG);
        assertThat(f.get().mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("PNG_시그니처는_PNG로_감지")
    void detectsPng() {
        Optional<ImageFormat> f = ImageMagicByteValidator.detect(PNG);
        assertThat(f).contains(ImageFormat.PNG);
        assertThat(f.get().mimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("SVG_XML_시그니처는_거부")
    void rejectsSvgXml() {
        assertThat(ImageMagicByteValidator.detect("<?xml version=\"1.0\"?><svg".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
        assertThat(ImageMagicByteValidator.detect("<svg xmlns=".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
    }

    @Test
    @DisplayName("GIF_BMP_WEBP_HTML은_거부")
    void rejectsOtherFormats() {
        assertThat(ImageMagicByteValidator.detect("GIF89a".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageMagicByteValidator.detect("BM....".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageMagicByteValidator.detect("RIFF....WEBP".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageMagicByteValidator.detect("<html>".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    @DisplayName("null_또는_짧은_헤더는_거부")
    void rejectsNullOrShort() {
        assertThat(ImageMagicByteValidator.detect(null)).isEmpty();
        assertThat(ImageMagicByteValidator.detect(new byte[]{(byte) 0x89, 0x50})).isEmpty();
    }

    @Test
    @DisplayName("PNG_시그니처만_있고_IHDR_없으면_거부_header_only_truncated")
    void rejectsPngSignatureWithoutIhdr() {
        // 8바이트 시그니처 + 임의 8바이트(IHDR 아님) → header-only 위조로 간주해 거부.
        byte[] sigOnly = {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x00, 0x00, 0x00, 0x00};
        assertThat(ImageMagicByteValidator.detect(sigOnly)).isEmpty();
    }

    @Test
    @DisplayName("JPEG_EOI_판정_FF_D9로_끝나면_true_아니면_false")
    void jpegEoiDetection() {
        assertThat(ImageMagicByteValidator.endsWithJpegEoi(new byte[]{0, 0, (byte) 0xFF, (byte) 0xD9})).isTrue();
        assertThat(ImageMagicByteValidator.endsWithJpegEoi(new byte[]{(byte) 0xFF, (byte) 0xD9})).isTrue();
        assertThat(ImageMagicByteValidator.endsWithJpegEoi(new byte[]{0, 0, 0, 0})).isFalse();
        assertThat(ImageMagicByteValidator.endsWithJpegEoi(new byte[]{(byte) 0xD9})).isFalse();
        assertThat(ImageMagicByteValidator.endsWithJpegEoi(null)).isFalse();
    }

    @Test
    @DisplayName("확장자_정합_JPEG는_jpg_jpeg_PNG는_png")
    void extensionMatch() {
        assertThat(ImageFormat.JPEG.matchesExtension("jpg")).isTrue();
        assertThat(ImageFormat.JPEG.matchesExtension("jpeg")).isTrue();
        assertThat(ImageFormat.JPEG.matchesExtension("png")).isFalse();
        assertThat(ImageFormat.PNG.matchesExtension("png")).isTrue();
        assertThat(ImageFormat.PNG.matchesExtension("jpg")).isFalse();
        assertThat(ImageFormat.PNG.matchesExtension(null)).isFalse();
    }
}
