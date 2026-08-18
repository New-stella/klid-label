package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.AiMockMeta;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.controller.LabelController;
import kr.co.cudo.authoring.label.dto.AutolabelShape;
import kr.co.cudo.authoring.label.dto.Sam2TrackOutcome;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.YoloTrackRequest;
import kr.co.cudo.authoring.label.dto.YoloTrackResponseDto;
import kr.co.cudo.authoring.label.service.FrameImageEncoder;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.label.service.Sam2SegmentService;
import kr.co.cudo.authoring.label.service.Sam2TrackService;
import kr.co.cudo.authoring.label.service.YoloTrackService;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 추적 응답의 <b>안내 문구 통로</b>({@code ApiResponse.message}) 계약.
 *
 * <h3>무엇을 고정하는가</h3>
 * <p>한 통로를 두 종류가 나눠 쓰고 있었다 — <b>예산 절단 안내</b>와 <b>mock 안내</b>. 화면은 절단을
 * 보고한 응답의 문구를 쓰지 않는데(그 문구는 «요청 하나» 기준이라 실행 전체의 남은 수와 어긋난다),
 * 한 응답이 <b>절단이면서 mock 이기도 하면</b> 같이 실린 mock 안내까지 함께 버려졌다.
 *
 * <p><b>mock 프레임은 결과 목록에서 빠지므로 그 안내가 유일한 신호다</b> — 못 보면 사용자는 왜 그
 * 구간에 라벨이 없는지 알 수 없다. 그래서 절단은 <b>구조화된 값</b>({@code truncated}/{@code resume})
 * 으로만 말하고, 문구 통로는 mock 안내에 내준다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrackNoticeChannelTest {

    @Mock private AiServerClient aiServerClient;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LabelAccessGuard accessGuard;
    @Mock private SystemConfigService systemConfigService;
    @Mock private FrameImageEncoder frameImageEncoder;

    private Sam2TrackService service;
    private TokenClaims reviewer;

    private static final Long RAW_SN = 9001L;
    private static final Long SRC0 = 5000L;
    private static final Long SRC1 = 5001L;
    private static final Long SRC2 = 5002L;
    private static final Long SRC3 = 5003L;

    @BeforeEach
    void setUp() {
        service = new Sam2TrackService(aiServerClient, srcRepository, accessGuard,
                systemConfigService, frameImageEncoder);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));

        when(srcRepository.findById(any())).thenAnswer(inv -> Optional.of(frame((Long) inv.getArgument(0))));
        when(frameImageEncoder.encodeFrame(any())).thenReturn("b64");
        when(systemConfigService.getDouble(any())).thenReturn(1.0);
    }

    // ── SAM2 추적 — 절단과 mock 이 겹칠 때 ─────────────────────────────────────────

    @Test
    @Timeout(30)
    @DisplayName("절단이면서_일부가_mock_인_응답에도_mock_안내가_그대로_남는다")
    void partialMockNoticeSurvivesTruncation() {
        // given: 첫 프레임은 mock(결과에서 제외), 둘째는 실모델, 셋째는 예산이 다해 처리하지 못한다.
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(2_000));
        encodeTakes(1_200);
        when(aiServerClient.track(any()))
                .thenReturn(Mono.just(mockResponse()))
                .thenReturn(Mono.just(modelResponse()));

        // when
        Sam2TrackOutcome outcome = service.track(request(List.of(SRC1, SRC2, SRC3)), reviewer);

        // then: 절단 사실은 구조화된 값이 말하고, 문구 통로에는 mock 안내만 남는다.
        assertThat(outcome.response().truncated()).isTrue();
        assertThat(outcome.mock()).isTrue();
        assertThat(outcome.message()).isEqualTo(Sam2TrackOutcome.PARTIAL_MOCK_MESSAGE);
    }

    @Test
    @Timeout(20)
    @DisplayName("절단이면서_처리분이_전량_mock_이면_모델미로드_안내가_그대로_남는다")
    void unavailableMockNoticeSurvivesTruncation() {
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(500));
        encodeTakes(600);
        when(aiServerClient.track(any())).thenAnswer(inv -> Mono.just(mockResponse()));

        Sam2TrackOutcome outcome = service.track(request(List.of(SRC1, SRC2, SRC3)), reviewer);

        assertThat(outcome.response().truncated()).isTrue();
        assertThat(outcome.response().tracked()).isEmpty();
        assertThat(outcome.message()).isEqualTo(Sam2TrackOutcome.MOCK_UNAVAILABLE_MESSAGE);
    }

    @Test
    @Timeout(20)
    @DisplayName("mock_이_없는_절단_응답은_안내_문구를_싣지_않는다")
    void truncationAloneCarriesNoNotice() {
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(500));
        encodeTakes(600);
        when(aiServerClient.track(any())).thenAnswer(inv -> Mono.just(modelResponse()));

        Sam2TrackOutcome outcome = service.track(request(List.of(SRC1, SRC2, SRC3)), reviewer);

        assertThat(outcome.response().truncated()).isTrue();
        assertThat(outcome.message()).isNull();
    }

    // ── AI 자동 추적(YOLO) — 절단 응답의 문구 통로 ──────────────────────────────────

    @Test
    @DisplayName("자동추적의_절단_응답도_안내_문구를_싣지_않는다")
    void autoTrackTruncationCarriesNoNotice() {
        YoloTrackService yoloTrackService = mock(YoloTrackService.class);
        YoloTrackResponseDto truncated = new YoloTrackResponseDto(
                List.of(new YoloTrackResponseDto.FrameDetections(SRC0, 0, List.of())),
                true,
                new YoloTrackResponseDto.Resume(SRC1, List.of(SRC2)));
        when(yoloTrackService.track(any(), any())).thenReturn(truncated);

        LabelController controller = new LabelController(mock(LabelService.class),
                mock(Sam2TrackService.class), mock(Sam2SegmentService.class), yoloTrackService);

        ApiResponse<YoloTrackResponseDto> res = controller.yoloTrack(
                SRC0, new YoloTrackRequest(SRC0, List.of(SRC1, SRC2)), reviewer);

        assertThat(res.success()).isTrue();
        assertThat(res.data().truncated()).isTrue();
        assertThat(res.message()).isNull();
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────────

    private static LsDataSrc frame(Long srcSn) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, srcSn, srcSn + ".jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", srcSn);
        return src;
    }

    private static List<List<Double>> square(double x1, double y1, double x2, double y2) {
        return List.of(List.of(x1, y1), List.of(x2, y1), List.of(x2, y2), List.of(x1, y2));
    }

    private Sam2TrackRequest request(List<Long> next) {
        return new Sam2TrackRequest(SRC0, "track-A", square(10, 10, 30, 30), "person",
                next, AutolabelShape.POLYGON);
    }

    /** 실모델 응답 — 긍정 증명({@code source="model"})을 명시한다. */
    private static Sam2TrackResponse modelResponse() {
        return new Sam2TrackResponse("track-A", square(11, 11, 31, 31), 0.9,
                false, AiMockMeta.SOURCE_MODEL, null);
    }

    /** mock 응답 — 서비스가 이 프레임을 결과에서 제외한다(안내 문구가 유일한 신호). */
    private static Sam2TrackResponse mockResponse() {
        return new Sam2TrackResponse("track-A", square(11, 11, 31, 31), 0.9,
                true, "mock", "weights_missing");
    }

    /** 프레임 인코딩이 매번 {@code millis} 만큼 걸리게 한다 — 예산을 결정적으로 소진시키는 손잡이. */
    private void encodeTakes(long millis) {
        when(frameImageEncoder.encodeFrame(any())).thenAnswer(inv -> {
            Thread.sleep(millis);
            return "b64";
        });
    }
}
