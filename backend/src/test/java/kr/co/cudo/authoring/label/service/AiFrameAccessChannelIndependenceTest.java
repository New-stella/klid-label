package kr.co.cudo.authoring.label.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.client.dto.YoloTrackRequest;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AutolabelShape;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentResponse;
import kr.co.cudo.authoring.label.dto.YoloTrackResponseDto;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 추론 본체(AI 탐지 · AI 분할 · AI 자동 추적)가 <b>채널 독립 입력</b>({@link AiFrameAccess})으로 호출됨을 못박는다.
 *
 * <p>여기서는 내부 채널 협력자(본인 배정 인가 · 신고 게이트 인코더 · 작업락 · 신고 상태 · 좌표 상한 해석기)를
 * 전부 mock 으로 두고 <b>한 번도 부르지 않는지</b> 확인한다. 본체가 그 협력자를 직접 물고 있으면 다른 채널이
 * 자기 인가·이미지를 넘겨도 내부 판정이 섞여 들어가므로, 이 시험이 그 결합이 다시 생기는 것을 막는다.
 *
 * <p>반대로 채널 독립이어야 하는 본체 규칙(검출 클래스 서버측 재구성 · 좌표 clamp · 교차 영상 400 ·
 * 인가 선행)은 스텁 채널로도 그대로 동작함을 함께 확인한다.
 */
class AiFrameAccessChannelIndependenceTest {

    private static final long RAW_SN = 8100L;
    private static final long SRC_SN = 8101L;

    @TempDir
    Path tmp;

    private AiServerClient aiServerClient;
    private LabelAccessGuard accessGuard;
    private SystemConfigService systemConfigService;
    private WorkLockService workLockService;
    private FrameImageEncoder frameImageEncoder;
    private LabelMasterService labelMasterService;
    private DeidentReportGate deidentReportGate;
    private FrameBoundsResolver frameBoundsResolver;
    private LsDataSrcRepository srcRepository;

    private TokenClaims actor;

    /** 스텁 채널 — 인가·이미지·상한·차단 호출을 기록한다. */
    private static final class StubAccess implements AiFrameAccess {
        final Map<Long, LsDataSrc> frames = new HashMap<>();
        final List<Long> authorized = new ArrayList<>();
        final List<Long> encoded = new ArrayList<>();
        int blockedChecks;
        Path imageFile;
        int[] bounds;
        Long denySrcSn;
        Integer blockFromNthCheck;

        @Override
        public LsDataSrc authorize(Long srcSn) {
            authorized.add(srcSn);
            if (srcSn.equals(denySrcSn)) {
                throw new CustomException(ErrorCode.FORBIDDEN, "스텁 채널 인가 거부");
            }
            LsDataSrc f = frames.get(srcSn);
            if (f == null) {
                throw new CustomException(ErrorCode.NOT_FOUND, "스텁 채널 프레임 부재");
            }
            return f;
        }

        @Override
        public Path resolveImage(LsDataSrc frame) {
            return imageFile;
        }

        @Override
        public String encodeImage(LsDataSrc frame) {
            encoded.add(frame.getSrcSn());
            return "STUB-" + frame.getSrcSn();
        }

        @Override
        public Optional<int[]> resolveBounds(LsDataSrc frame) {
            return Optional.ofNullable(bounds);
        }

        @Override
        public void requireNotBlocked(Long rawSn) {
            blockedChecks++;
            if (blockFromNthCheck != null && blockedChecks >= blockFromNthCheck) {
                throw new CustomException(ErrorCode.CONFLICT, "스텁 채널 차단");
            }
        }
    }

    @BeforeEach
    void setUp() {
        aiServerClient = mock(AiServerClient.class);
        accessGuard = mock(LabelAccessGuard.class);
        systemConfigService = mock(SystemConfigService.class);
        workLockService = mock(WorkLockService.class);
        frameImageEncoder = mock(FrameImageEncoder.class);
        labelMasterService = mock(LabelMasterService.class);
        deidentReportGate = mock(DeidentReportGate.class);
        frameBoundsResolver = mock(FrameBoundsResolver.class);
        srcRepository = mock(LsDataSrcRepository.class);

        when(systemConfigService.getInt(any())).thenReturn(null);
        when(systemConfigService.getDouble(any())).thenReturn(null);
        when(labelMasterService.findLabelIdByDtctType(anyString())).thenReturn(Optional.empty());
        when(labelMasterService.mappedDetectClasses()).thenReturn(new LinkedHashSet<>(List.of("person", "car")));

        actor = new TokenClaims("portal-user-1", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(60));
    }

    private static LsDataSrc frame(long rawSn, long srcSn) {
        LsDataSrc src = LsDataSrc.create(rawSn, (int) (srcSn % 1000), srcSn + ".jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", srcSn);
        return src;
    }

    private AutolabelOnlineService autolabelService() {
        Bulkhead bulkhead = Bulkhead.of("aiOnlineChannelIndependence", BulkheadConfig.custom()
                .maxConcurrentCalls(25).maxWaitDuration(Duration.ZERO).build());
        AutolabelOnlineService service = new AutolabelOnlineService(aiServerClient, accessGuard, systemConfigService,
                workLockService, frameImageEncoder, labelMasterService, deidentReportGate,
                frameBoundsResolver, bulkhead);
        ReflectionTestUtils.setField(service, "polygonTotalBudget", Duration.ofSeconds(30));
        return service;
    }

    private void verifyNoInternalChannelCollaborator() {
        verifyNoInteractions(accessGuard, frameImageEncoder, workLockService, deidentReportGate,
                frameBoundsResolver, srcRepository);
    }

    // ── AI 탐지 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AI_탐지_본체는_스텁_채널의_인가_이미지_상한만으로_동작하고_내부_협력자를_부르지_않는다")
    void autolabelBodyUsesOnlyChannelAccess() {
        StubAccess access = new StubAccess();
        access.frames.put(SRC_SN, frame(RAW_SN, SRC_SN));
        access.bounds = new int[]{100, 80};
        // 경계를 넘긴 박스 — 채널이 준 상한(100x80)으로 clamp 돼야 한다.
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(-5.0, 10.0, 150.0, 90.0), 0.9, 1)))));

        AutolabelOnlineService.AutolabelOutcome out = autolabelService()
                .autolabelWithAccess(access, SRC_SN, actor, List.of("person", "dog"), AutolabelShape.BBOX, null, null);

        assertThat(out.response().labels()).hasSize(1);
        assertThat(out.response().labels().get(0).points()).containsExactly(0.0, 10.0, 100.0, 80.0);
        ArgumentCaptor<YoloTrackRequest> cap = ArgumentCaptor.forClass(YoloTrackRequest.class);
        verify(aiServerClient).predictYoloTrack(cap.capture());
        assertThat(cap.getValue().imageB64()).isEqualTo("STUB-" + SRC_SN);
        // 검출 클래스 서버측 재구성은 채널과 무관하게 본체에서 강제된다(미매핑 'dog' 제외).
        assertThat(cap.getValue().classes()).containsExactly("person");
        assertThat(access.authorized).containsExactly(SRC_SN);
        // 진입 + 마감 두 번 판정한다.
        assertThat(access.blockedChecks).isEqualTo(2);
        verifyNoInternalChannelCollaborator();
    }

    @Test
    @DisplayName("AI_탐지_본체는_채널_인가가_거부되면_ai_server를_부르지_않는다")
    void autolabelBodyStopsOnChannelAuthorizeFailure() {
        StubAccess access = new StubAccess();
        access.denySrcSn = SRC_SN;

        assertThatThrownBy(() -> autolabelService()
                .autolabelWithAccess(access, SRC_SN, actor, null, AutolabelShape.BBOX, null, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(aiServerClient);
        assertThat(access.blockedChecks).isZero();
        verifyNoInternalChannelCollaborator();
    }

    @Test
    @DisplayName("AI_탐지_본체는_AI_호출_뒤_채널_차단_판정에_걸리면_좌표를_돌려주지_않는다")
    void autolabelBodyHonorsChannelBlockAfterAiCall() {
        StubAccess access = new StubAccess();
        access.frames.put(SRC_SN, frame(RAW_SN, SRC_SN));
        access.blockFromNthCheck = 2; // 진입은 통과, 마감에서 차단
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(1.0, 1.0, 20.0, 20.0), 0.9, 1)))));

        assertThatThrownBy(() -> autolabelService()
                .autolabelWithAccess(access, SRC_SN, actor, null, AutolabelShape.BBOX, null, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        verify(aiServerClient).predictYoloTrack(any());
        verifyNoInternalChannelCollaborator();
    }

    @Test
    @DisplayName("AI_탐지_본체의_폴리곤_경로도_박스마다_채널_차단_판정을_다시_본다")
    void autolabelPolygonBodyChecksChannelBlockPerBox() {
        StubAccess access = new StubAccess();
        access.frames.put(SRC_SN, frame(RAW_SN, SRC_SN));
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(1.0, 1.0, 20.0, 20.0), 0.9, 1),
                new YoloResponse.Detection("car", List.of(30.0, 30.0, 60.0, 60.0), 0.8, 2)))));
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(1.0, 1.0), List.of(9.0, 1.0), List.of(9.0, 9.0)), 0.9, false, "model", null)));

        AutolabelOnlineService.AutolabelOutcome out = autolabelService()
                .autolabelWithAccess(access, SRC_SN, actor, null, AutolabelShape.POLYGON, null, null);

        assertThat(out.response().labels()).hasSize(2);
        verify(aiServerClient, times(2)).segment(any());
        // 진입 1 + 박스 2 + 마감 1.
        assertThat(access.blockedChecks).isEqualTo(4);
        // 이미지는 1회만 인코딩해 YOLO·SAM 이 공유한다.
        assertThat(access.encoded).containsExactly(SRC_SN);
        verifyNoInternalChannelCollaborator();
    }

    // ── AI 분할 ──────────────────────────────────────────────────────────────────

    private Sam2SegmentService segmentService() {
        Sam2SegmentService service = new Sam2SegmentService(aiServerClient, srcRepository, accessGuard,
                frameImageEncoder, systemConfigService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "maxImageBytes", 20L * 1024 * 1024);
        return service;
    }

    private Path writePng(String name, int w, int h) throws IOException {
        Path file = tmp.resolve(name);
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    @Test
    @DisplayName("AI_분할_본체는_스텁_채널이_해석한_파일을_추론_입력으로_보내고_내부_협력자를_부르지_않는다")
    void segmentBodyUsesOnlyChannelAccess() throws IOException {
        StubAccess access = new StubAccess();
        access.frames.put(SRC_SN, frame(RAW_SN, SRC_SN));
        access.imageFile = writePng("stub.png", 100, 100);
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(11.0, 12.0), List.of(31.0, 32.0), List.of(11.0, 32.0)), 0.9, false, "model", null)));

        Sam2SegmentResponse res = segmentService()
                .segmentWithAccess(access, new Sam2SegmentRequest(SRC_SN, List.of(List.of(10.0, 10.0)), null));

        assertThat(res.polygon()).hasSize(3);
        ArgumentCaptor<Sam2Request> cap = ArgumentCaptor.forClass(Sam2Request.class);
        verify(aiServerClient).segment(cap.capture());
        assertThat(cap.getValue().imageB64())
                .isEqualTo(Base64.getEncoder().encodeToString(Files.readAllBytes(access.imageFile)));
        assertThat(access.authorized).containsExactly(SRC_SN);
        assertThat(access.blockedChecks).isEqualTo(2);
        verifyNoInternalChannelCollaborator();
    }

    @Test
    @DisplayName("AI_분할_본체의_응답_경계_검증은_채널이_준_파일의_실측_해상도를_기준으로_한다")
    void segmentBodyValidatesAgainstChannelFileDimensions() throws IOException {
        StubAccess access = new StubAccess();
        access.frames.put(SRC_SN, frame(RAW_SN, SRC_SN));
        access.imageFile = writePng("small.png", 20, 20);
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(11.0, 12.0), List.of(31.0, 32.0), List.of(11.0, 32.0)), 0.9, false, "model", null)));

        assertThatThrownBy(() -> segmentService()
                .segmentWithAccess(access, new Sam2SegmentRequest(SRC_SN, List.of(List.of(10.0, 10.0)), null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
        verifyNoInternalChannelCollaborator();
    }

    @Test
    @DisplayName("AI_분할_본체는_채널_인가가_거부되면_ai_server를_부르지_않는다")
    void segmentBodyStopsOnChannelAuthorizeFailure() {
        StubAccess access = new StubAccess();
        access.denySrcSn = SRC_SN;

        assertThatThrownBy(() -> segmentService()
                .segmentWithAccess(access, new Sam2SegmentRequest(SRC_SN, List.of(List.of(10.0, 10.0)), null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(aiServerClient);
        verifyNoInternalChannelCollaborator();
    }

    // ── AI 자동 추적 ─────────────────────────────────────────────────────────────

    private YoloTrackService trackService() {
        return new YoloTrackService(aiServerClient, accessGuard, systemConfigService, frameImageEncoder,
                frameBoundsResolver, labelMasterService);
    }

    private void stubTrackDetection() {
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(new YoloResponse(
                List.of(new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 7)))));
    }

    @Test
    @DisplayName("AI_자동_추적_본체는_시작과_후속_프레임_전부를_채널로_인가한_뒤에_추론한다")
    void trackBodyAuthorizesEveryFrameThroughChannelBeforeInference() {
        StubAccess access = new StubAccess();
        for (long sn : List.of(SRC_SN, SRC_SN + 1, SRC_SN + 2)) {
            access.frames.put(sn, frame(RAW_SN, sn));
        }
        List<Long> authorizedWhenFirstAiCall = new ArrayList<>();
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> {
            if (authorizedWhenFirstAiCall.isEmpty()) {
                authorizedWhenFirstAiCall.addAll(access.authorized);
            }
            return Mono.just(new YoloResponse(List.of(
                    new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 7))));
        });

        YoloTrackResponseDto res = trackService().trackWithAccess(access,
                new kr.co.cudo.authoring.label.dto.YoloTrackRequest(SRC_SN, List.of(SRC_SN + 1, SRC_SN + 2, SRC_SN + 1)));

        assertThat(res.frames()).hasSize(4);
        assertThat(res.truncated()).isFalse();
        // 같은 프레임 중복은 1회만 인가하고, 전부 ai 호출 이전에 끝난다.
        assertThat(authorizedWhenFirstAiCall).containsExactly(SRC_SN, SRC_SN + 1, SRC_SN + 2);
        assertThat(access.authorized).containsExactly(SRC_SN, SRC_SN + 1, SRC_SN + 2);
        assertThat(access.encoded).containsExactly(SRC_SN, SRC_SN + 1, SRC_SN + 2, SRC_SN + 1);
        // 진입 1 + 프레임 4 + 마감 1.
        assertThat(access.blockedChecks).isEqualTo(6);
        verify(aiServerClient, times(4)).predictYoloTrack(any());
        verifyNoInternalChannelCollaborator();
    }

    @Test
    @DisplayName("AI_자동_추적_본체는_채널_인가가_후속_프레임에서_거부되면_ai_server를_부르지_않는다")
    void trackBodyStopsWhenAnyFollowingFrameIsDenied() {
        StubAccess access = new StubAccess();
        access.frames.put(SRC_SN, frame(RAW_SN, SRC_SN));
        access.frames.put(SRC_SN + 1, frame(RAW_SN, SRC_SN + 1));
        access.denySrcSn = SRC_SN + 1;
        stubTrackDetection();

        assertThatThrownBy(() -> trackService().trackWithAccess(access,
                new kr.co.cudo.authoring.label.dto.YoloTrackRequest(SRC_SN, List.of(SRC_SN + 1))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(aiServerClient, never()).predictYoloTrack(any());
        verifyNoInternalChannelCollaborator();
    }

    @Test
    @DisplayName("AI_자동_추적_본체는_채널과_무관하게_다른_영상의_프레임이_섞이면_400이다")
    void trackBodyRejectsCrossVideoFramesRegardlessOfChannel() {
        StubAccess access = new StubAccess();
        access.frames.put(SRC_SN, frame(RAW_SN, SRC_SN));
        access.frames.put(SRC_SN + 1, frame(RAW_SN + 1, SRC_SN + 1));
        stubTrackDetection();

        assertThatThrownBy(() -> trackService().trackWithAccess(access,
                new kr.co.cudo.authoring.label.dto.YoloTrackRequest(SRC_SN, List.of(SRC_SN + 1))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        verifyNoInternalChannelCollaborator();
    }

    @Test
    @DisplayName("AI_자동_추적_본체의_좌표_상한은_채널이_준_프레임별_치수로_clamp한다")
    void trackBodyClampsWithChannelBounds() {
        StubAccess access = new StubAccess();
        access.frames.put(SRC_SN, frame(RAW_SN, SRC_SN));
        access.bounds = new int[]{30, 50};
        stubTrackDetection();

        YoloTrackResponseDto res = trackService().trackWithAccess(access,
                new kr.co.cudo.authoring.label.dto.YoloTrackRequest(SRC_SN, List.of()));

        assertThat(res.frames()).hasSize(1);
        assertThat(res.frames().get(0).detections().get(0).points()).containsExactly(10.0, 10.0, 30.0, 50.0);
        verifyNoInternalChannelCollaborator();
    }

    // ── 내부 채널 구현 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("내부_채널_구현은_기존_협력자를_그대로_위임하고_차단_판정이_없으면_통과한다")
    void internalAccessDelegatesToExistingCollaborators() {
        LsDataSrc f = frame(RAW_SN, SRC_SN);
        TokenClaims worker = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(60));
        when(accessGuard.verifyAndGet(SRC_SN, worker)).thenReturn(f);
        when(frameImageEncoder.encodeFrame(f)).thenReturn("GATED");
        when(frameBoundsResolver.resolve(f)).thenReturn(Optional.of(new int[]{7, 9}));
        Set<Long> checked = new java.util.HashSet<>();

        InternalAiFrameAccess withCheck = new InternalAiFrameAccess(accessGuard, frameImageEncoder,
                frameBoundsResolver, worker, checked::add);
        assertThat(withCheck.authorize(SRC_SN)).isSameAs(f);
        assertThat(withCheck.encodeImage(f)).isEqualTo("GATED");
        assertThat(withCheck.resolveBounds(f)).hasValueSatisfying(b -> assertThat(b).containsExactly(7, 9));
        withCheck.requireNotBlocked(null); // rawSn null 도 언박싱 없이 전달된다
        withCheck.requireNotBlocked(RAW_SN);
        assertThat(checked).containsExactlyInAnyOrder(null, RAW_SN);

        InternalAiFrameAccess noCheck = new InternalAiFrameAccess(accessGuard, frameImageEncoder,
                frameBoundsResolver, worker, null);
        noCheck.requireNotBlocked(RAW_SN);
        verify(frameImageEncoder).encodeFrame(f);
    }
}
