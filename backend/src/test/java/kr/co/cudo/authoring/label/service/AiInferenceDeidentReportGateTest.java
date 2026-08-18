package kr.co.cudo.authoring.label.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AutolabelShape;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentResponse;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S7 (HIGH · CWE-359) — <b>AI 추론 경로의 비식별 누락 신고 게이트</b> 단위 테스트.
 *
 * <p>대상: SAM2 분할({@link Sam2SegmentService}) · SAM2 추적({@link Sam2TrackService}) ·
 * 온라인 오토라벨({@link AutolabelOnlineService}). 이 세 경로는 프레임 이미지를 <b>base64 로 인코딩해
 * ai-server(외부 프로세스)로 전송</b>하므로, 신고 구간에서는 마스킹 실패 픽셀(PII) 자체가 앱 밖으로
 * 이동한다 — 응답이 좌표뿐이라는 사실과 무관하게 차단 대상이다.
 *
 * <p><b>검증의 핵심은 "차단됐지만 이미 보냈다"를 잡는 것</b>이다. 예외 발생만 확인하면 요청을 보내고
 * 결과만 버리는 구현도 통과하므로, 모든 차단 케이스에서 <b>{@code verifyNoInteractions(aiServerClient)}</b>
 * 로 확인한다. 메서드를 지정한 {@code verify(client, never()).segment(any())} 는 감시 범위가 그 메서드
 * 하나뿐이라 {@code predictYolo}·{@code verifyObjects} 처럼 <b>같은 이미지를 운반하는 다른 메서드</b>로
 * 새는 퇴행을 잡지 못한다(적대검증 지적 E).
 *
 * <p>판정은 {@link DeidentReportGate} 실제 구현체를 끼워 <b>그 영상 행의 {@code DE_IDNTF_YN} 하나</b>가
 * 판정에 그대로 반영되게 한다. 파생영상(증강·해상도)은 원본 신고와 <b>무관하게</b> 다루는 것이 확정
 * 정책(2026-07-29)이라, 파생본은 자기 행이 {@code 'F'} 일 때만 막힌다.
 *
 * <p>포털(외부 채널) SAM2 는 ADR-013 위반으로 <b>제거</b>됐다(엔드포인트·서비스 삭제) — 검증 대상은
 * 내부 경로뿐이며, 포털 경로 부재는 {@code PortalSam2RemovedTest} 가 고정한다.
 */
class AiInferenceDeidentReportGateTest {

    /** 원본 영상(신고 대상이 될 부모). */
    private static final long PARENT_RAW_SN = 9001L;
    /** 부모의 해상도/증강 파생영상 — 자기 행은 항상 {@code 'Y'} 다. */
    private static final long DERIVED_RAW_SN = 9002L;
    /** 같은 부모의 <b>다른</b> 파생영상 — 형제 신고가 이쪽을 막는지 검증용. */
    private static final long SIBLING_RAW_SN = 9003L;
    private static final long PARENT_SRC_SN = 5001L;
    private static final long DERIVED_SRC_SN = 5002L;
    private static final long PARENT_NEXT_SRC_SN = 5011L;
    private static final long DERIVED_NEXT_SRC_SN = 5012L;

    @TempDir
    Path storageDir;

    private AiServerClient aiServerClient;
    private VideoRepository videoRepository;
    private LsDataSrcRepository srcRepository;
    private LabelAccessGuard accessGuard;
    private SystemConfigService systemConfigService;
    private WorkLockService workLockService;
    private LabelMasterService labelMasterService;

    private Sam2SegmentService segmentService;
    private Sam2TrackService trackService;
    private AutolabelOnlineService autolabelService;
    private YoloTrackService yoloTrackService;

    private TokenClaims reviewer;

    @BeforeEach
    void setUp() throws IOException {
        aiServerClient = mock(AiServerClient.class);
        videoRepository = mock(VideoRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        systemConfigService = mock(SystemConfigService.class);
        workLockService = mock(WorkLockService.class);
        labelMasterService = mock(LabelMasterService.class);

        // 게이트 + 인코더는 실제 구현체 — 신고 판정이 인코딩·전송 이전에 실제로 걸리는지 확인해야 한다.
        DeidentReportGate gate = new DeidentReportGate(videoRepository);
        FrameImageEncoder encoder = new FrameImageEncoder(
                storageDir.toAbsolutePath().toString(), storageDir.toAbsolutePath().toString(), gate);

        segmentService = new Sam2SegmentService(aiServerClient, srcRepository, accessGuard, encoder,
                systemConfigService, new ObjectMapper());
        ReflectionTestUtils.setField(segmentService, "storageRawPath", storageDir.toAbsolutePath().toString());
        ReflectionTestUtils.setField(segmentService, "maxImageBytes", 20L * 1024 * 1024);

        trackService = new Sam2TrackService(aiServerClient, srcRepository, accessGuard,
                systemConfigService, encoder);

        Bulkhead bulkhead = Bulkhead.of("aiOnlineGateTest", BulkheadConfig.custom()
                .maxConcurrentCalls(25).maxWaitDuration(Duration.ZERO).build());
        autolabelService = new AutolabelOnlineService(aiServerClient, accessGuard, systemConfigService,
                workLockService, encoder, labelMasterService, gate,
                mock(kr.co.cudo.authoring.label.service.FrameBoundsResolver.class), bulkhead);

        // DEV_FIX(H-1) — 좌표 정규화가 배치·AI 탐지와 동일한 공용 규칙(DetectionBoxNormalizer)을 타면서
        //   clamp 상한 기준(FrameBoundsResolver)이 협력자로 추가됐다. 본 테스트는 신고 게이트가 전송
        //   이전에 걸리는지를 보므로 실측 해상도는 관심사가 아니다 → 측정 실패(Optional.empty) 모킹.
        //   labelMasterService 는 검출 결과에 labelId 를 싣기 위한 협력자(API-123)이며, 이 테스트는
        //   전송 이전 차단만 보므로 검출 매핑도 관심사가 아니다 → 기존 mock 을 그대로 넘긴다.
        yoloTrackService = new YoloTrackService(aiServerClient, srcRepository, accessGuard,
                systemConfigService, encoder,
                mock(kr.co.cudo.authoring.label.service.FrameBoundsResolver.class),
                labelMasterService);

        // 실제 이미지 파일 — 정상 경로가 인코딩·치수 측정까지 통과하도록 준비한다.
        writePng("0.jpg", 100, 100);
        writePng("1.jpg", 100, 100);

        LsDataSrc parentFrame = frame(PARENT_SRC_SN, PARENT_RAW_SN, "0.jpg");
        LsDataSrc parentNextFrame = frame(PARENT_NEXT_SRC_SN, PARENT_RAW_SN, "1.jpg");
        LsDataSrc derivedFrame = frame(DERIVED_SRC_SN, DERIVED_RAW_SN, "0.jpg");
        LsDataSrc derivedNextFrame = frame(DERIVED_NEXT_SRC_SN, DERIVED_RAW_SN, "1.jpg");
        when(srcRepository.findById(PARENT_SRC_SN)).thenReturn(Optional.of(parentFrame));
        when(srcRepository.findById(PARENT_NEXT_SRC_SN)).thenReturn(Optional.of(parentNextFrame));
        when(srcRepository.findById(DERIVED_SRC_SN)).thenReturn(Optional.of(derivedFrame));
        when(srcRepository.findById(DERIVED_NEXT_SRC_SN)).thenReturn(Optional.of(derivedNextFrame));
        when(accessGuard.verifyAndGet(eq(PARENT_SRC_SN), any())).thenReturn(parentFrame);
        when(accessGuard.verifyAndGet(eq(DERIVED_SRC_SN), any())).thenReturn(derivedFrame);

        when(systemConfigService.getDouble(any())).thenReturn(1.0);
        when(systemConfigService.getInt(any())).thenReturn(null);
        when(labelMasterService.mappedDetectClasses()).thenReturn(Set.of("person"));
        when(labelMasterService.findLabelIdByDtctType(anyString())).thenReturn(Optional.empty());
        when(workLockService.isRawLocked(any())).thenReturn(false);

        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    // ─────────────────────────── #18 SAM2 분할 ───────────────────────────

    @Test
    @DisplayName("SAM2_분할_신고된_영상은_412로_차단되고_ai_server로_이미지가_전송되지_않는다")
    void segmentBlockedWhenVideoReported() {
        // given — 신고로 DE_IDNTF_YN='F' 내려간 원본 영상.
        stubGate(PARENT_RAW_SN, "F");

        // when / then — 라벨 계열 관례와 동일한 412.
        assertThatThrownBy(() -> segmentService.segment(pointReq(PARENT_SRC_SN), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // ★ 핵심 — 마스킹 실패 픽셀이 외부 프로세스로 나가지 않았다(요청 후 폐기가 아니라 전송 자체가 0건).
        //    메서드 지정 never() 가 아니라 전체 무상호작용으로 확인한다(다른 전송 메서드로 새는 퇴행까지 차단).
        verifyNoInteractions(aiServerClient);
    }

    @Test
    @DisplayName("SAM2_분할_파생영상_자기행이_F면_차단되고_ai_server_호출이_0건이다")
    void segmentBlockedWhenDerivativeItselfReported() {
        // given — 판정 축은 자기 행 하나다(2026-07-29 확정). 파생본 자체가 'F' 면 막힌다.
        stubGate(DERIVED_RAW_SN, "F");

        assertThatThrownBy(() -> segmentService.segment(pointReq(DERIVED_SRC_SN), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        verifyNoInteractions(aiServerClient);
    }

    // ─────────────────────────── #18 SAM2 추적 ───────────────────────────

    @Test
    @DisplayName("SAM2_추적_신고된_영상은_412로_차단되고_ai_server_호출이_0건이다")
    void trackBlockedWhenVideoReported() {
        // given — 추적은 N 프레임을 연속 전송하므로 노출량이 더 크다.
        stubGate(PARENT_RAW_SN, "F");

        assertThatThrownBy(() -> trackService.track(trackReq(PARENT_SRC_SN, PARENT_NEXT_SRC_SN), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        verifyNoInteractions(aiServerClient);
    }

    @Test
    @DisplayName("SAM2_추적_파생영상_자기행이_F면_차단되고_ai_server_호출이_0건이다")
    void trackBlockedWhenDerivativeItselfReported() {
        stubGate(DERIVED_RAW_SN, "F");

        assertThatThrownBy(() -> trackService.track(trackReq(DERIVED_SRC_SN, DERIVED_NEXT_SRC_SN), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        verifyNoInteractions(aiServerClient);
    }

    // ─────────────────────── #19 온라인 오토라벨(파생 락) ───────────────────────

    @Test
    @DisplayName("온라인_오토라벨_작업락이_없어도_자기행이_F면_412로_차단된다")
    void autolabelBlockedByGateWithoutWorkLock() {
        // given — 작업락 축과 'F' 축은 해제 경로가 달라 어긋날 수 있다(배치 비식별 실패도 'F' 를 만든다).
        //         락이 없어도 게이트가 막는지 고정한다.
        stubGate(DERIVED_RAW_SN, "F");
        when(workLockService.isRawLocked(DERIVED_RAW_SN)).thenReturn(false);

        assertThatThrownBy(() -> autolabelService.autolabel(DERIVED_SRC_SN, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        verifyNoInteractions(aiServerClient);
    }

    @Test
    @DisplayName("온라인_오토라벨_폴리곤_경로도_신고구간에서는_SAM_분할을_호출하지_않는다")
    void autolabelPolygonBlockedWhenReported() {
        stubGate(DERIVED_RAW_SN, "F");

        assertThatThrownBy(() -> autolabelService.autolabel(
                DERIVED_SRC_SN, reviewer, List.of("person"), AutolabelShape.POLYGON))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        verifyNoInteractions(aiServerClient);
    }

    @Test
    @DisplayName("온라인_오토라벨_자기영상이_잠긴_경우는_기존대로_409를_유지한다")
    void autolabelKeepsConflictForOwnWorkLock() {
        // given — 자기 영상 신고(락 + 'F') 상태. 기존 응답 규약(409)이 바뀌면 FE 분기가 깨진다.
        stubGate(PARENT_RAW_SN, "F");
        when(workLockService.isRawLocked(PARENT_RAW_SN)).thenReturn(true);

        assertThatThrownBy(() -> autolabelService.autolabel(PARENT_SRC_SN, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verifyNoInteractions(aiServerClient);
    }

    // ────────────── ★ 파생영상은 원본 신고와 무관하다 (2026-07-29 사용자 확정) ──────────────
    //
    // 파생본은 원본 비식별 산출물의 사본이라 <b>재비식별 수단이 원본에만</b> 있다. 그래서 ①파생에서는
    // 신고를 접수하지 않고(DeidentReportService.report 가 412) ②원본 신고도 파생에 전파하지 않는다.
    // 구 구현(조상 체인 판정 + 자손 팬아웃)은 차단↔복구 비대칭·팬아웃 상한 초과 시 정상 트리 영구
    // 차단·막다른 안내를 낳아 철회됐다. 아래 테스트가 그 철회를 회귀 고정한다.

    @Test
    @DisplayName("★원본이_신고중이어도_파생영상_SAM2_분할은_정상_동작한다 — 조상 순회 철회 고정")
    void segmentNotBlockedByOriginReport() {
        // given — 파생 D 자기 행은 'Y', 부모 P 는 'F'(신고 중).
        stubGate(DERIVED_RAW_SN, "Y");
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(11.0, 12.0), List.of(31.0, 32.0), List.of(11.0, 32.0)),
                0.92, false, "model", null)));

        // when / then — 파생본은 계속 추론 가능하며, 부모 행은 조회조차 하지 않는다.
        assertThat(segmentService.segment(pointReq(DERIVED_SRC_SN), reviewer).polygon()).hasSize(3);
        verify(videoRepository, org.mockito.Mockito.never()).findDeIdntfYnByRawSn(PARENT_RAW_SN);
    }

    // ─────────────── YOLO 추적 — 인코더 경유 배선이 사라지면 실패한다 ───────────────

    @Test
    @DisplayName("YOLO_추적_신고된_영상은_412로_차단되고_ai_server_호출이_0건이다")
    void yoloTrackBlockedWhenVideoReported() {
        // given — YOLO 추적은 최대 50 프레임을 연속 전송한다(노출량 최대 경로). 이 경로의 유일한
        //         차단 지점은 FrameImageEncoder.encodeFrame 배선이다.
        stubGate(PARENT_RAW_SN, "F");

        assertThatThrownBy(() -> yoloTrackService.track(
                new kr.co.cudo.authoring.label.dto.YoloTrackRequest(
                        PARENT_SRC_SN, List.of(PARENT_NEXT_SRC_SN)), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        verifyNoInteractions(aiServerClient);
    }

    @Test
    @DisplayName("YOLO_추적_신고가_없으면_기존대로_프레임별_검출을_반환한다")
    void yoloTrackWorksWhenNotReported() {
        stubGate(PARENT_RAW_SN, "Y");
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(new YoloResponse(
                List.of(new YoloResponse.Detection("person", List.of(1.0, 2.0, 30.0, 40.0), 0.9)))));

        var res = yoloTrackService.track(new kr.co.cudo.authoring.label.dto.YoloTrackRequest(
                PARENT_SRC_SN, List.of(PARENT_NEXT_SRC_SN)), reviewer);

        assertThat(res.frames()).hasSize(2);
    }

    // ─────────────────────────── 정상 경로 회귀 ───────────────────────────

    @Test
    @DisplayName("신고가_없으면_SAM2_분할은_기존대로_폴리곤을_반환한다")
    void segmentWorksWhenNotReported() {
        stubGate(PARENT_RAW_SN, "Y");
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(11.0, 12.0), List.of(31.0, 32.0), List.of(11.0, 32.0)),
                0.92, false, "model", null)));

        Sam2SegmentResponse res = segmentService.segment(pointReq(PARENT_SRC_SN), reviewer);

        assertThat(res.polygon()).hasSize(3);
        verify(aiServerClient).segment(any());
    }

    @Test
    @DisplayName("정상_파생영상의_SAM2_분할은_기존대로_동작한다")
    void segmentWorksForDerivative() {
        stubGate(DERIVED_RAW_SN, "Y");
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(11.0, 12.0), List.of(31.0, 32.0), List.of(11.0, 32.0)),
                0.92, false, "model", null)));

        Sam2SegmentResponse res = segmentService.segment(pointReq(DERIVED_SRC_SN), reviewer);

        assertThat(res.polygon()).hasSize(3);
        verify(aiServerClient).segment(any());
    }

    @Test
    @DisplayName("신고가_없으면_SAM2_추적은_기존대로_후속_프레임_좌표를_반환한다")
    void trackWorksWhenNotReported() {
        stubGate(PARENT_RAW_SN, "Y");
        when(aiServerClient.track(any())).thenReturn(Mono.just(new Sam2TrackResponse(
                "t-1", List.of(List.of(10.0, 10.0), List.of(30.0, 10.0), List.of(30.0, 30.0)), 0.8)));

        Sam2TrackResponseDto res = trackService.track(
                trackReq(PARENT_SRC_SN, PARENT_NEXT_SRC_SN), reviewer).response();

        assertThat(res.tracked()).hasSize(1);
        verify(aiServerClient).track(any());
    }

    @Test
    @DisplayName("신고가_없으면_온라인_오토라벨은_기존대로_검출_좌표를_반환한다")
    void autolabelWorksWhenNotReported() {
        stubGate(PARENT_RAW_SN, "Y");
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(new YoloResponse(
                List.of(new YoloResponse.Detection("person", List.of(1.0, 2.0, 30.0, 40.0), 0.9)))));

        var outcome = autolabelService.autolabel(PARENT_SRC_SN, reviewer);

        assertThat(outcome.response().labels()).hasSize(1);
        verify(aiServerClient).predictYoloTrack(any());
    }

    // ─────────────────────────── helpers ───────────────────────────

    private Sam2SegmentRequest pointReq(long srcSn) {
        return new Sam2SegmentRequest(srcSn, List.of(List.of(10.0, 10.0)), null);
    }

    private Sam2TrackRequest trackReq(long srcSn, long nextSrcSn) {
        return new Sam2TrackRequest(srcSn, "t-1",
                List.of(List.of(10.0, 10.0), List.of(30.0, 10.0), List.of(30.0, 30.0)),
                "person", List.of(nextSrcSn));
    }

    /** 게이트 판정 stub — 그 영상 행의 {@code DE_IDNTF_YN} 하나가 판정값이다(자기 행 전용 판정). */
    private void stubGate(long rawSn, String deIdntfYn) {
        when(videoRepository.findDeIdntfYnByRawSn(rawSn)).thenReturn(Optional.ofNullable(deIdntfYn));
    }

    private LsDataSrc frame(long srcSn, long rawSn, String fileName) {
        LsDataSrc src = LsDataSrc.create(rawSn, 0, fileName, LocalDateTime.now());
        setField(src, "srcSn", srcSn);
        return src;
    }

    private void writePng(String name, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", storageDir.resolve(name).toFile());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
