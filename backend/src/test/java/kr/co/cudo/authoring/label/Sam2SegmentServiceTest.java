package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
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
import org.mockito.ArgumentCaptor;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4 — SAM2 클릭/박스 분할 프록시 서비스 단위 테스트 (Docker 불필요, 순수 Mockito).
 *
 * 시나리오 방어:
 *  - IDOR(CWE-639): 타 배정 프레임 segment 요청 시 403, ai-server 미호출.
 *  - mock 전파: ai-server mock=true → BE 응답 mock=true.
 *  - 좌표 상한(CWE-20): 응답 폴리곤이 이미지 경계 초과 시 EXTERNAL_API_ERROR.
 *  - 3점 미만 폴리곤 → EXTERNAL_API_ERROR.
 *  - 이미지 크기 상한 초과 → 400.
 *  - ai-server 타임아웃 → EXTERNAL_API_ERROR 매핑.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Sam2SegmentServiceTest {

    @Mock private AiServerClient aiServerClient;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LabelAccessGuard accessGuard;
    @Mock private SystemConfigService systemConfigService;

    @InjectMocks private Sam2SegmentService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir Path tmpRawDir;

    private static final long SRC_SN = 5001L;
    private TokenClaims reviewer;

    @BeforeEach
    void setup() throws IOException {
        // ObjectMapper 는 @InjectMocks 대상이 아니므로 수동 주입.
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "storageRawPath", tmpRawDir.toAbsolutePath().toString());
        ReflectionTestUtils.setField(service, "maxImageBytes", 20L * 1024 * 1024);

        // 100x100 실제 PNG 생성 (ImageIO 로 width/height 측정 가능해야 함).
        writePng("0.jpg", 100, 100);

        LsDataSrc src = LsDataSrc.create(9001L, 0, "0.jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", SRC_SN);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(src));

        Instant exp = Instant.now().plusSeconds(60);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, exp);

        when(systemConfigService.getDouble(any())).thenReturn(1.0);
    }

    private void writePng(String name, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", tmpRawDir.resolve(name).toFile());
    }

    private Sam2SegmentRequest pointReq() {
        return new Sam2SegmentRequest(SRC_SN, List.of(List.of(10.0, 10.0)), null);
    }

    private Sam2SegmentRequest boxReq() {
        return new Sam2SegmentRequest(SRC_SN, null, List.of(5.0, 5.0, 40.0, 40.0));
    }

    @Test
    @DisplayName("클릭_프롬프트로_폴리곤과_신뢰도가_반환됨")
    void segmentWithPointReturnsPolygonAndScore() {
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(11.0, 12.0), List.of(31.0, 32.0), List.of(11.0, 32.0)),
                0.92, false, "model", null)));

        Sam2SegmentResponse res = service.segment(pointReq(), reviewer);

        assertThat(res.polygon()).hasSize(3);
        assertThat(res.score()).isEqualTo(0.92);

        // ai-server 요청에는 points 가 실리고 box 는 null.
        ArgumentCaptor<Sam2Request> cap = ArgumentCaptor.forClass(Sam2Request.class);
        verify(aiServerClient).segment(cap.capture());
        assertThat(cap.getValue().points()).isNotNull();
        assertThat(cap.getValue().box()).isNull();
        assertThat(cap.getValue().imageB64()).isNotBlank();
    }

    @Test
    @DisplayName("드래그_박스_프롬프트로_폴리곤_반환됨")
    void segmentWithBoxReturnsPolygon() {
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(6.0, 6.0), List.of(39.0, 39.0), List.of(6.0, 39.0)),
                0.81, false, "model", null)));

        Sam2SegmentResponse res = service.segment(boxReq(), reviewer);

        assertThat(res.polygon()).hasSize(3);
        ArgumentCaptor<Sam2Request> cap = ArgumentCaptor.forClass(Sam2Request.class);
        verify(aiServerClient).segment(cap.capture());
        assertThat(cap.getValue().box()).containsExactly(5.0, 5.0, 40.0, 40.0);
        assertThat(cap.getValue().points()).isNull();
    }

    @Test
    @DisplayName("타_배정_프레임_segment_요청시_403")
    void otherFrameForbidden() {
        // accessGuard 가 FORBIDDEN 던지도록 stub → ai-server 호출 전 차단.
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.FORBIDDEN, "본인 배정 아님"))
                .when(accessGuard).verifyAccess(any(), any());

        assertThatThrownBy(() -> service.segment(pointReq(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        verify(aiServerClient, never()).segment(any());
    }

    @Test
    @DisplayName("응답_폴리곤_좌표_이미지경계_초과시_오류")
    void polygonOutOfImageBoundsRejected() {
        // 이미지 100x100 인데 응답 x=150 → 경계 초과 → EXTERNAL_API_ERROR.
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(10.0, 10.0), List.of(150.0, 20.0), List.of(10.0, 30.0)),
                0.9, false, "model", null)));

        assertThatThrownBy(() -> service.segment(pointReq(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("3점_미만_폴리곤_응답시_오류")
    void lessThanThreePointsRejected() {
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(10.0, 10.0), List.of(20.0, 20.0)),
                0.9, false, "model", null)));

        assertThatThrownBy(() -> service.segment(pointReq(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("Sam2Segment_내부mock이면_빈폴리곤과_안내메시지_반환")
    void mockResponseReturnsEmptyPolygon() {
        // ai-server 내부 mock 응답(모델 미로드) — 좌표는 신뢰 불가하므로 빈 폴리곤으로 반환하여
        // FE 자동적용을 차단한다(안내 message 는 컨트롤러가 ApiResponse.message 로 세팅).
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(10.0, 10.0), List.of(20.0, 20.0), List.of(10.0, 20.0)),
                0.5, true, "mock", "weights_missing")));

        Sam2SegmentResponse res = service.segment(pointReq(), reviewer);

        assertThat(res.polygon()).isEmpty();
        assertThat(res.score()).isZero();
    }

    @Test
    @DisplayName("Sam2SegmentResponse에_mock필드가_없다")
    void sam2SegmentResponseHasNoMockField() {
        boolean hasMock = java.util.Arrays.stream(Sam2SegmentResponse.class.getRecordComponents())
                .anyMatch(rc -> rc.getName().equals("mock"));
        assertThat(hasMock).isFalse();
    }

    @Test
    @DisplayName("이미지_크기_상한_초과시_400")
    void imageTooLargeRejected() {
        // 상한을 1바이트로 낮춰 100x100 PNG 가 초과되도록.
        ReflectionTestUtils.setField(service, "maxImageBytes", 1L);

        assertThatThrownBy(() -> service.segment(pointReq(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);

        verify(aiServerClient, never()).segment(any());
    }

    @Test
    @DisplayName("ai서버_타임아웃시_외부API_오류_매핑")
    void aiServerTimeoutMapped() {
        when(aiServerClient.segment(any()))
                .thenReturn(Mono.error(new RuntimeException("read timeout")));

        assertThatThrownBy(() -> service.segment(pointReq(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("ai서버_빈_응답시_외부API_오류")
    void aiServerEmptyResponse() {
        when(aiServerClient.segment(any())).thenReturn(Mono.empty());

        assertThatThrownBy(() -> service.segment(pointReq(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }
}
