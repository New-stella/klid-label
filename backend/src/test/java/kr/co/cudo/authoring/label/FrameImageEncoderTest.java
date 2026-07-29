package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
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
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FrameImageEncoder 단위 테스트 (Spring 컨텍스트 없이 생성자 직접 주입).
 * 정상 인코딩 / 경로 순회 차단(CWE-22) / 파일 없음 / 빈 경로 검증.
 *
 * <p>S7 — 구 {@code encodeToBase64(String relativePath)}(게이트 없는 경로 문자열 오버로드)는 폐지됐다.
 * 같은 검증(경로 순회·파일 부재·빈 경로·내부 경로 미노출)을 <b>살아남은 진입점</b>
 * {@link FrameImageEncoder#encodeFrame}(프레임 엔티티 기준, 게이트 경유)에 그대로 건다.
 */
class FrameImageEncoderTest {

    @TempDir
    Path baseDir;

    private FrameImageEncoder encoder;
    private kr.co.cudo.authoring.video.service.DeidentReportGate deidentReportGate;

    @BeforeEach
    void setup() {
        // 신고 게이트는 기본 통과(정상 영상) — 차단 동작은 AiInferenceDeidentReportGateTest 에서 검증한다.
        deidentReportGate = org.mockito.Mockito.mock(kr.co.cudo.authoring.video.service.DeidentReportGate.class);
        encoder = new FrameImageEncoder(baseDir.toAbsolutePath().toString(), baseDir.toAbsolutePath().toString(),
                deidentReportGate);
    }

    /** 원본 경로만 가진 프레임(비식별 경로 없음) — 기존 relativePath 케이스와 동일 입력면. */
    private LsDataSrc frame(String srcFilePathNm) {
        return LsDataSrc.create(1L, 0, srcFilePathNm, LocalDateTime.now());
    }

    @Test
    @DisplayName("정상_이미지_base64_인코딩")
    void encodesValidImage() throws IOException {
        byte[] content = new byte[]{0x01, 0x02, 0x03};
        Files.write(baseDir.resolve("frame.jpg"), content);

        String result = encoder.encodeFrame(frame("frame.jpg"));

        assertThat(result).isEqualTo(Base64.getEncoder().encodeToString(content));
    }

    @Test
    @DisplayName("경로_순회_시도는_INVALID_INPUT_차단")
    void pathTraversalRejected() {
        assertThatThrownBy(() -> encoder.encodeFrame(frame("../../etc/passwd")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("존재하지_않는_파일은_NOT_FOUND")
    void missingFileNotFound() {
        assertThatThrownBy(() -> encoder.encodeFrame(frame("nope.jpg")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("빈_경로는_NOT_FOUND_로_차단된다")
    void blankPathRejected() {
        // 프레임 엔티티 기준에서는 "원본·비식별 두 경로 모두 결측" 규약이 적용되어 NOT_FOUND 다
        // (구 문자열 오버로드의 INVALID_INPUT 과 코드만 다를 뿐 동일하게 fail-closed·경로 미노출).
        assertThatThrownBy(() -> encoder.encodeFrame(frame("  ")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("예외_메시지에_내부_경로_미노출_CWE_209")
    void exceptionMessageHidesInternalPath() {
        assertThatThrownBy(() -> encoder.encodeFrame(frame("secret-internal.jpg")))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain(baseDir.toString())
                        .doesNotContain("secret-internal.jpg"));
    }

    @Test
    @DisplayName("비식별본_전용_진입점은_원본으로_폴백하지_않고_NOT_FOUND")
    void deidentifiedOnlyEntryPointDoesNotFallBackToOriginal() throws IOException {
        // given — 원본 파일은 실재하지만 비식별 경로가 없는 프레임(포털 규약: 원본 폴백 금지).
        Files.write(baseDir.resolve("orig.jpg"), new byte[]{0x01});

        assertThatThrownBy(() -> encoder.encodeDeidentifiedFrameForInference(frame("orig.jpg")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }
}
