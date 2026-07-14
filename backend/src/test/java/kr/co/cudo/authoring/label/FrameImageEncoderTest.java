package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.service.FrameImageEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FrameImageEncoder 단위 테스트 (Spring 컨텍스트 없이 생성자 직접 주입).
 * 정상 인코딩 / 경로 순회 차단(CWE-22) / 파일 없음 / 빈 경로 검증.
 */
class FrameImageEncoderTest {

    @TempDir
    Path baseDir;

    private FrameImageEncoder encoder;

    @BeforeEach
    void setup() {
        encoder = new FrameImageEncoder(baseDir.toAbsolutePath().toString());
    }

    @Test
    @DisplayName("정상_이미지_base64_인코딩")
    void encodesValidImage() throws IOException {
        byte[] content = new byte[]{0x01, 0x02, 0x03};
        Files.write(baseDir.resolve("frame.jpg"), content);

        String result = encoder.encodeToBase64("frame.jpg");

        assertThat(result).isEqualTo(Base64.getEncoder().encodeToString(content));
    }

    @Test
    @DisplayName("경로_순회_시도는_INVALID_INPUT_차단")
    void pathTraversalRejected() {
        assertThatThrownBy(() -> encoder.encodeToBase64("../../etc/passwd"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("존재하지_않는_파일은_NOT_FOUND")
    void missingFileNotFound() {
        assertThatThrownBy(() -> encoder.encodeToBase64("nope.jpg"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("빈_경로는_INVALID_INPUT")
    void blankPathRejected() {
        assertThatThrownBy(() -> encoder.encodeToBase64("  "))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("예외_메시지에_내부_경로_미노출_CWE_209")
    void exceptionMessageHidesInternalPath() {
        assertThatThrownBy(() -> encoder.encodeToBase64("secret-internal.jpg"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain(baseDir.toString())
                        .doesNotContain("secret-internal.jpg"));
    }
}
