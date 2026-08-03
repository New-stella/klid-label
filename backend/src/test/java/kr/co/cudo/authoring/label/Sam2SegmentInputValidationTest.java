package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentResponse;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.Sam2SegmentService;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SAM2 segment <b>요청</b> 좌표 검증 + 에러 메시지 정보노출 회귀 가드.
 *
 * <p>배경(security-reviewer CONDITIONAL PASS 잔여 이슈):
 * <ul>
 *   <li>이슈4(CWE-20): 대조군인 {@code Sam2TrackService.validatePolygon} 은 요청 {@code prevPolygon} 을
 *       <b>전송 전</b> 에 검증하는데 segment 경로에는 그 검증이 없었다. 잘못된 좌표가 ai-server 의 400 을
 *       유발하고 그것이 BE 에서 502(EXTERNAL_API_ERROR)로 승격돼 사용자에게 나갔다.</li>
 *   <li>이슈5(CWE-209): {@code "SAM2 segment 호출 실패: " + e.getMessage()} 가
 *       {@code WebClientResponseException} 원문(내부 호스트:포트:경로)을 클라이언트 응답에 노출했다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Sam2SegmentInputValidationTest {

    @Mock private AiServerClient aiServerClient;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LabelAccessGuard accessGuard;
    @Mock private SystemConfigService systemConfigService;
    @Mock private kr.co.cudo.authoring.label.service.FrameImageEncoder frameImageEncoder;

    @InjectMocks private Sam2SegmentService service;

    @TempDir Path tmpRawDir;

    private static final long SRC_SN = 6001L;
    private TokenClaims reviewer;

    @BeforeEach
    void setup() throws IOException {
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "storageRawPath", tmpRawDir.toAbsolutePath().toString());
        ReflectionTestUtils.setField(service, "maxImageBytes", 20L * 1024 * 1024);
        when(frameImageEncoder.resolveFrameImageForInference(any())).thenAnswer(inv -> {
            LsDataSrc arg = inv.getArgument(0);
            return tmpRawDir.resolve(arg.getSrcFilePathNm());
        });

        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", tmpRawDir.resolve("0.jpg").toFile());

        LsDataSrc src = LsDataSrc.create(9001L, 0, "0.jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", SRC_SN);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(src));
        when(systemConfigService.getDouble(any())).thenReturn(1.0);

        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    private Sam2SegmentRequest pointsReq(List<List<Double>> points) {
        return new Sam2SegmentRequest(SRC_SN, points, null);
    }

    // ────────────────────────────────────────────────────────────
    // 이슈4 — 요청 좌표 앞단 검증 (400, ai-server 미호출)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("segment_요청_좌표_원소가_2개가_아니면_400을_반환한다")
    void pointWithWrongArityRejected() {
        assertThatThrownBy(() -> service.segment(pointsReq(List.of(List.of(10.0))), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(aiServerClient, never()).segment(any());
    }

    @Test
    @DisplayName("segment_요청_좌표에_NaN_Infinity가_있으면_400을_반환한다")
    void nonFiniteCoordinateRejected() {
        assertThatThrownBy(() -> service.segment(
                pointsReq(List.of(List.of(Double.NaN, 10.0))), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> service.segment(
                pointsReq(List.of(List.of(Double.POSITIVE_INFINITY, 10.0))), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(aiServerClient, never()).segment(any());
    }

    @Test
    @DisplayName("segment_요청_좌표에_null이_있으면_400을_반환한다")
    void nullCoordinateRejected() {
        assertThatThrownBy(() -> service.segment(
                pointsReq(List.of(Arrays.asList(null, 10.0))), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(aiServerClient, never()).segment(any());
    }

    @Test
    @DisplayName("segment_요청_box에_NaN이_있으면_400을_반환한다")
    void boxWithNonFiniteRejected() {
        var req = new Sam2SegmentRequest(SRC_SN, null,
                List.of(Double.NaN, 5.0, 40.0, 40.0));

        assertThatThrownBy(() -> service.segment(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(aiServerClient, never()).segment(any());
    }

    @Test
    @DisplayName("segment_요청_box에_음수좌표가_있으면_400을_반환한다")
    void boxWithNegativeRejected() {
        var req = new Sam2SegmentRequest(SRC_SN, null, List.of(-1.0, 5.0, 40.0, 40.0));

        assertThatThrownBy(() -> service.segment(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(aiServerClient, never()).segment(any());
    }

    @Test
    @DisplayName("정상_segment_요청은_기존과_동일하게_동작한다")
    void validRequestStillWorks() {
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(11.0, 12.0), List.of(31.0, 32.0), List.of(11.0, 32.0)),
                0.92, false, "model", null)));

        Sam2SegmentResponse res = service.segment(pointsReq(List.of(List.of(10.0, 10.0))), reviewer);

        assertThat(res.polygon()).hasSize(3);
        assertThat(res.score()).isEqualTo(0.92);
        verify(aiServerClient).segment(any());
    }

    @Test
    @DisplayName("SAM2_segment_클릭프롬프트_1점_정상_동작")
    void singleClickPromptStillAccepted() {
        // given (회귀 가드) — SAM2 클릭 프롬프트는 <b>1 점이 정상 입력</b>이다. 응답 폴리곤의 최소
        //   정점 수(3) 규칙을 공용 유틸 Sam2CoordinateValidator.validatePolygon 기본 동작에 넣으면
        //   이 클릭 분할이 400 으로 죽는다 → 그 규칙은 응답 전용 메서드로 분리돼 있어야 한다.
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(11.0, 12.0), List.of(31.0, 32.0), List.of(11.0, 32.0)),
                0.77, false, "model", null)));

        // when — points 1 점(클릭)
        Sam2SegmentResponse res = service.segment(pointsReq(List.of(List.of(10.0, 10.0))), reviewer);

        // then — 400 이 아니라 정상 분할 결과가 나오고 ai-server 도 실제로 호출된다.
        assertThat(res.polygon()).hasSize(3);
        assertThat(res.score()).isEqualTo(0.77);
        verify(aiServerClient).segment(any());
    }

    @Test
    @DisplayName("정상_box_segment_요청은_기존과_동일하게_동작한다")
    void validBoxRequestStillWorks() {
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(6.0, 6.0), List.of(39.0, 39.0), List.of(6.0, 39.0)),
                0.81, false, "model", null)));

        var req = new Sam2SegmentRequest(SRC_SN, null, List.of(5.0, 5.0, 40.0, 40.0));
        Sam2SegmentResponse res = service.segment(req, reviewer);

        assertThat(res.polygon()).hasSize(3);
    }

    // ────────────────────────────────────────────────────────────
    // 이슈5 — 외부 호출 실패 메시지에 내부 URL 비노출 (CWE-209)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("segment_외부호출_실패시_내부URL이_노출되지_않는다")
    void internalUrlNotLeakedOnFailure() {
        String internalDetail =
                "500 Internal Server Error from POST http://ai-server.internal:9300/infer/sam2/segment";
        when(aiServerClient.segment(any())).thenReturn(Mono.error(new RuntimeException(internalDetail)));

        assertThatThrownBy(() -> service.segment(pointsReq(List.of(List.of(10.0, 10.0))), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_ERROR);

        try {
            service.segment(pointsReq(List.of(List.of(10.0, 10.0))), reviewer);
        } catch (CustomException e) {
            assertThat(e.getMessage()).doesNotContain("ai-server.internal");
            assertThat(e.getMessage()).doesNotContain("9300");
            assertThat(e.getMessage()).doesNotContain("/infer/sam2/segment");
            assertThat(e.getMessage()).isEqualTo("SAM2 분할 호출에 실패했습니다.");
        }
    }
}
