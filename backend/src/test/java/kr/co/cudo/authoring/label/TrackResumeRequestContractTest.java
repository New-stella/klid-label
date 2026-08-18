package kr.co.cudo.authoring.label;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AutolabelShape;
import kr.co.cudo.authoring.label.dto.Sam2TrackOutcome;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import kr.co.cudo.authoring.label.dto.YoloTrackRequest;
import kr.co.cudo.authoring.label.dto.YoloTrackResponseDto;
import kr.co.cudo.authoring.label.service.FrameBoundsResolver;
import kr.co.cudo.authoring.label.service.FrameImageEncoder;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.label.service.Sam2TrackService;
import kr.co.cudo.authoring.label.service.YoloTrackService;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 추적 두 경로의 <b>이어 보내기 계약</b> — 서버가 발행한 {@code resume} 로 만든 요청은
 * <b>반드시 서버 자신이 받아들여야 한다</b>.
 *
 * <h3>왜 이 성질이 핵심인가</h3>
 * <p>예산이 다하면 서버는 «그때까지의 결과 + 이어 보낼 값» 을 돌려주고, 화면은 그 값을 <b>그대로</b>
 * 다음 요청 본문에 옮겨 담는다. 그런데 서버가 스스로 받지 못할 값을 발행하면 그 이어 보내기는
 * 요청 검증에서 400 으로 거부되고, 그 경로에는 부분 결과 보존이 없어 <b>이미 계산된 검출이 통째로
 * 버려진다</b>. 즉 «발행한 값이 규격을 만족하는가» 는 편의가 아니라 <b>데이터 보존의 전제</b>다.
 *
 * <p>그래서 여기서는 응답 필드를 눈으로 확인하는 대신 <b>발행된 값으로 실제 요청 객체를 만들어
 * Bean Validation 을 태운다</b> — 컨트롤러 {@code @Valid} 가 하는 일과 같은 판정이다.
 *
 * <h3>시간을 «인코딩» 으로 흘려보내는 이유</h3>
 * <p>루프가 데드라인을 <b>반복 진입 직전</b>에 보므로, 그 검사 뒤에 오는 부대 작업(프레임 인코딩)에서
 * 시간을 쓰면 «다음 반복에서 예산이 다한» 상황이 제한시간 경합 없이 결정적으로 재현된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrackResumeRequestContractTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @Mock private AiServerClient aiServerClient;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LabelAccessGuard accessGuard;
    @Mock private SystemConfigService systemConfigService;
    @Mock private FrameImageEncoder frameImageEncoder;
    @Mock private FrameBoundsResolver frameBoundsResolver;
    @Mock private LabelMasterService labelMasterService;

    private YoloTrackService yoloService;
    private Sam2TrackService sam2Service;

    private static final Long RAW_SN = 9301L;
    private static final Long SRC0 = 7000L;
    private static final Long SRC1 = 7001L;
    private static final Long SRC2 = 7002L;

    private TokenClaims reviewer;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @BeforeEach
    void setUp() {
        yoloService = new YoloTrackService(aiServerClient, accessGuard,
                systemConfigService, frameImageEncoder, frameBoundsResolver, labelMasterService);
        sam2Service = new Sam2TrackService(aiServerClient, srcRepository, accessGuard,
                systemConfigService, frameImageEncoder);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));

        when(srcRepository.findById(any())).thenAnswer(inv -> Optional.of(frame((Long) inv.getArgument(0))));
        when(accessGuard.verifyAndGet(any(), any())).thenAnswer(inv -> frame((Long) inv.getArgument(0)));
        when(frameImageEncoder.encodeFrame(any())).thenReturn("b64");
        when(frameBoundsResolver.resolve(any())).thenReturn(Optional.empty());
        when(labelMasterService.findLabelIdByDtctType(any())).thenReturn(Optional.empty());
        when(systemConfigService.getInt(any())).thenReturn(null);
        when(systemConfigService.getDouble(any())).thenReturn(1.0);
    }

    private static LsDataSrc frame(Long srcSn) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, srcSn, srcSn + ".jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", srcSn);
        return src;
    }

    /** 프레임 인코딩이 매번 {@code millis} 만큼 걸리게 한다 — 예산을 결정적으로 소진시키는 손잡이. */
    private void encodeTakes(long millis) {
        when(frameImageEncoder.encodeFrame(any())).thenAnswer(inv -> {
            Thread.sleep(millis);
            return "b64";
        });
    }

    private static List<List<Double>> square(double x1, double y1, double x2, double y2) {
        return List.of(List.of(x1, y1), List.of(x2, y1), List.of(x2, y2), List.of(x1, y2));
    }

    // ── AI 자동 추적 (YOLO) ───────────────────────────────────────────────────────

    @Test
    @Timeout(30)
    @DisplayName("남은_프레임이_하나뿐인_이어보내기_값도_요청_규격을_통과한다")
    void yoloResumeWithSingleRemainingFrameIsAValidRequest() {
        // given — 예산 1000ms, 인코딩 600ms. 시퀀스 [SRC0, SRC1, SRC2] 중 앞 둘만 들어가고
        //         마지막 한 프레임이 남는다(1200ms > 1000ms).
        ReflectionTestUtils.setField(yoloService, "trackTotalBudget", Duration.ofMillis(1000));
        encodeTakes(600);
        stubYoloDetect();

        // when
        YoloTrackResponseDto res = yoloService.track(new YoloTrackRequest(SRC0, List.of(SRC1, SRC2)), reviewer);

        // then — 남은 프레임이 정확히 하나인 상황을 실제로 재현했는지 먼저 확인한다.
        assertThat(res.truncated()).isEqualTo(true);
        assertThat(res.resume().srcSn()).isEqualTo(SRC2);
        assertThat(res.resume().nextSrcSns()).isEmpty();

        // 그리고 그 값으로 만든 요청이 서버의 요청 검증을 통과해야 한다 — 통과하지 못하면
        // 화면이 서버가 준 값을 그대로 되실었는데 400 이 되고, 이미 계산한 검출이 통째로 버려진다.
        Set<ConstraintViolation<YoloTrackRequest>> violations = validator.validate(
                new YoloTrackRequest(res.resume().srcSn(), res.resume().nextSrcSns()));
        assertThat(violations).isEmpty();
    }

    @Test
    @Timeout(30)
    @DisplayName("진행이_0인_이어보내기_값도_요청_규격을_통과한다")
    void yoloResumeWithNoProgressIsAValidRequest() {
        // given — 한 프레임도 담지 못하는 예산.
        ReflectionTestUtils.setField(yoloService, "trackTotalBudget", Duration.ZERO);
        stubYoloDetect();

        // when
        YoloTrackResponseDto res = yoloService.track(new YoloTrackRequest(SRC0, List.of(SRC1, SRC2)), reviewer);

        // then
        assertThat(res.resume().srcSn()).isEqualTo(SRC0);
        Set<ConstraintViolation<YoloTrackRequest>> violations = validator.validate(
                new YoloTrackRequest(res.resume().srcSn(), res.resume().nextSrcSns()));
        assertThat(violations).isEmpty();
    }

    // ── SAM2 추적 ────────────────────────────────────────────────────────────────

    @Test
    @Timeout(30)
    @DisplayName("SAM2_이어보내기_값도_요청_규격을_통과하고_남은_프레임_목록이_비지_않는다")
    void sam2ResumeIsAValidRequest() {
        // given — 예산 500ms, 인코딩 600ms. 후속 [SRC1, SRC2] 중 하나만 처리된다
        //         (SAM2 는 시작 프레임 인코딩이 예산 시계 밖이라 첫 반복은 언제나 들어간다).
        ReflectionTestUtils.setField(sam2Service, "trackTotalBudget", Duration.ofMillis(500));
        encodeTakes(600);
        stubSam2Ok(square(11, 11, 31, 31));

        // when
        Sam2TrackOutcome outcome = sam2Service.track(sam2Request(List.of(SRC1, SRC2)), reviewer);
        Sam2TrackResponseDto.Resume resume = outcome.response().resume();

        // then — SAM2 의 이어 보내기는 «마지막으로 처리한 프레임» 이 시작이라 남은 목록이 구조적으로
        // 비지 않는다. 그 불변식을 시험으로 못박아 둔다(YOLO 축과 달라 함께 무너지지 않는다).
        assertThat(outcome.response().truncated()).isEqualTo(true);
        assertThat(resume.nextSrcSns()).isNotEmpty();
        assertThat(validator.validate(sam2RequestFrom(resume))).isEmpty();
    }

    @Test
    @Timeout(30)
    @DisplayName("추론서버가_점수_상한을_넘는_폴리곤을_줘도_이어보내기_시드는_요청_규격을_통과한다")
    void sam2ResumeSeedStaysWithinRequestPointCap() {
        // given — 추론 서버는 윤곽점 수를 제한하지 않는다. 요청 규격의 상한을 넘는 폴리곤이 오면
        //         그것이 그대로 이어 보내기 시드가 되어 다음 요청에서 400 이 된다(목서버로는 재현되지
        //         않는다 — 목은 입력을 그대로 돌려주므로 항상 상한 안이다).
        ReflectionTestUtils.setField(sam2Service, "trackTotalBudget", Duration.ofMillis(500));
        encodeTakes(600);
        stubSam2Ok(hugePolygon(Sam2TrackRequest.MAX_POLYGON_POINTS + 500));

        // when
        Sam2TrackOutcome outcome = sam2Service.track(sam2Request(List.of(SRC1, SRC2)), reviewer);
        Sam2TrackResponseDto.Resume resume = outcome.response().resume();

        // then
        assertThat(outcome.response().truncated()).isEqualTo(true);
        assertThat(resume.prevPolygon().size()).isLessThanOrEqualTo(Sam2TrackRequest.MAX_POLYGON_POINTS);
        assertThat(validator.validate(sam2RequestFrom(resume))).isEmpty();
    }

    // ── 도우미 ───────────────────────────────────────────────────────────────────

    private void stubYoloDetect() {
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(new YoloResponse(
                List.of(new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 7)))));
    }

    private void stubSam2Ok(List<List<Double>> polygon) {
        when(aiServerClient.track(any())).thenAnswer(inv ->
                Mono.just(new Sam2TrackResponse("track-A", polygon, 0.9)));
    }

    private Sam2TrackRequest sam2Request(List<Long> next) {
        return new Sam2TrackRequest(SRC0, "track-A", square(10, 10, 30, 30), "person",
                next, AutolabelShape.POLYGON);
    }

    private Sam2TrackRequest sam2RequestFrom(Sam2TrackResponseDto.Resume resume) {
        return new Sam2TrackRequest(resume.srcSn(), "track-A", resume.prevPolygon(), "person",
                resume.nextSrcSns(), AutolabelShape.POLYGON);
    }

    /**
     * 상한을 넘는 정점 수의 폴리곤 — 단순화(Douglas-Peucker)로 <b>줄지 않는</b> 모양이어야 한다.
     * 직선 위의 점을 늘어놓으면 단순화가 두 점으로 접어 버려 이 시험이 무의미해지므로,
     * 정점마다 방향이 꺾이는 톱니로 만든다.
     */
    private static List<List<Double>> hugePolygon(int points) {
        List<List<Double>> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            out.add(List.of(10.0 + i, 10.0 + (i % 2) * 20.0));
        }
        return out;
    }
}
