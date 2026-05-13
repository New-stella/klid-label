package kr.co.cudo.authoring.batch.step;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloRequest;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YoloAutolabelStepTest {

    private AiServerClient aiServerClient;
    private LsDataSrcRepository srcRepository;
    private LsDataLblRepository lblRepository;
    private VideoRepository videoRepository;
    private PresetLabelLookupService presetLabelLookup;
    private YoloAutolabelStep step;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger stepLogger;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        aiServerClient = mock(AiServerClient.class);
        srcRepository = mock(LsDataSrcRepository.class);
        lblRepository = mock(LsDataLblRepository.class);
        videoRepository = mock(VideoRepository.class);
        presetLabelLookup = mock(PresetLabelLookupService.class);

        // Create temp directory and dummy image files
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        Files.write(rawDir.resolve("10.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8}); // JPEG header
        Files.write(rawDir.resolve("11.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        Files.write(rawDir.resolve("20.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        Files.write(rawDir.resolve("30.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        Files.write(rawDir.resolve("40.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        Files.write(rawDir.resolve("50.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});

        // 기본은 fail-safe (필터 미적용) 동작을 위해 빈 Optional
        when(videoRepository.findById(anyLong())).thenReturn(Optional.empty());
        // PresetLabelLookupService 기본 동작: 매핑 없음(fail-safe).
        when(presetLabelLookup.labelsFor(any())).thenReturn(Optional.empty());
        when(presetLabelLookup.togglesFor(any())).thenReturn(Optional.empty());

        step = new YoloAutolabelStep(aiServerClient, srcRepository, lblRepository, videoRepository,
                presetLabelLookup, new ObjectMapper(), rawDir.toString());

        // Logback ListAppender 부착 — mock 응답 감지 시 WARN 로그를 검증
        stepLogger = (Logger) LoggerFactory.getLogger(YoloAutolabelStep.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        stepLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (stepLogger != null && logAppender != null) {
            stepLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    private LsDataSrc newSrc(Long srcSn) {
        LsDataSrc src = LsDataSrc.create(1L, srcSn.intValue(), srcSn + ".jpg", null);
        try {
            Field f = LsDataSrc.class.getDeclaredField("srcSn");
            f.setAccessible(true);
            f.set(src, srcSn);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        return src;
    }

    private LsDataRaw rawWithEvent(String evntTypeCd) {
        LsDataRaw raw = mock(LsDataRaw.class);
        when(raw.getEvntTypeCd()).thenReturn(evntTypeCd);
        return raw;
    }

    @Test
    @DisplayName("YOLO_검출_결과는_AUTO_LBL_YN_Y_+_BBOX_타입_+_score_0_1_저장")
    void detectionsSavedAsAutoBbox() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(1L))
                .thenReturn(List.of(newSrc(10L), newSrc(11L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92),
                        new YoloResponse.Detection("car", List.of(5.0, 6.0, 7.0, 8.0), 0.81)
                ))));

        List<BbHint> hints = step.run(1L);

        // fail-safe (togglesFor empty) → BOTH 라벨로 처리. 2 frames × 2 detections = 4 BBOX save + 4 hints.
        assertThat(hints).hasSize(4);

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(4)).save(captor.capture());
        captor.getAllValues().forEach(lbl -> {
            assertThat(lbl.getAutoLblYn()).isEqualTo("Y");
            assertThat(lbl.getLblTypeCd()).isEqualTo("BBOX");
            assertThat(lbl.getConfScore()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        });
    }

    @Test
    @DisplayName("YOLO_검출_결과_없으면_라벨_미저장")
    void noDetectionsNoSaves() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(2L))
                .thenReturn(List.of(newSrc(20L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of())));

        List<BbHint> hints = step.run(2L);

        assertThat(hints).isEmpty();
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("rawSn_null이면_INVALID_INPUT")
    void nullRawSnRejected() {
        assertThatThrownBy(() -> step.run(null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("ai_server_예외시_EXTERNAL_API_ERROR")
    void externalErrorWrapped() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(3L))
                .thenReturn(List.of(newSrc(30L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("ai-server down")));

        assertThatThrownBy(() -> step.run(3L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("EXTERNAL_API_ERROR");
    }

    @Test
    @DisplayName("EVT_FALL_영상은_person만_저장_car는_필터링")
    void evtFallFiltersToPersonOnly() {
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(4L)).thenReturn(Optional.of(rawMock));
        // Phase 2: 토글 맵으로 매핑된 라벨(person=BOTH)만 통과
        when(presetLabelLookup.togglesFor("EVT_FALL"))
                .thenReturn(Optional.of(Map.of("person", AnnotationToggle.BOTH)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(4L))
                .thenReturn(List.of(newSrc(40L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92),
                        new YoloResponse.Detection("car", List.of(5.0, 6.0, 7.0, 8.0), 0.81),
                        new YoloResponse.Detection("Person", List.of(9.0, 10.0, 11.0, 12.0), 0.75)
                ))));

        List<BbHint> hints = step.run(4L);

        // person 2건 통과 (대소문자 정규화), car 1건 필터링 — BOTH 라벨이므로 BBOX 저장 + hint 발행
        assertThat(hints).hasSize(2);

        ArgumentCaptor<LsDataLbl> captor = ArgumentCaptor.forClass(LsDataLbl.class);
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        captor.getAllValues().forEach(lbl ->
                assertThat(lbl.getLabel().toLowerCase()).isEqualTo("person"));
    }

    @Test
    @DisplayName("EVT_ACCIDENT_영상은_차량_사람_오토바이_통과")
    void evtAccidentAllowsVehiclesAndPerson() {
        LsDataRaw rawMock = rawWithEvent("EVT_ACCIDENT");
        when(videoRepository.findById(5L)).thenReturn(Optional.of(rawMock));
        // Phase 2: 토글 맵으로 매핑된 라벨(car/motorcycle/person/truck/bus/bicycle=BOTH)만 통과
        Map<String, AnnotationToggle> allowed = new LinkedHashMap<>();
        for (String label : List.of("car", "motorcycle", "person", "truck", "bus", "bicycle")) {
            allowed.put(label, AnnotationToggle.BOTH);
        }
        when(presetLabelLookup.togglesFor("EVT_ACCIDENT")).thenReturn(Optional.of(allowed));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(5L))
                .thenReturn(List.of(newSrc(50L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("car", List.of(1.0, 2.0, 3.0, 4.0), 0.92),
                        new YoloResponse.Detection("motorcycle", List.of(5.0, 6.0, 7.0, 8.0), 0.81),
                        new YoloResponse.Detection("person", List.of(9.0, 10.0, 11.0, 12.0), 0.75),
                        new YoloResponse.Detection("trash", List.of(13.0, 14.0, 15.0, 16.0), 0.55)
                ))));

        List<BbHint> hints = step.run(5L);

        // car/motorcycle/person 통과 (BOTH), trash 필터링
        assertThat(hints).hasSize(3);
    }

    @Test
    @DisplayName("ai_server_mock_응답_감지시_WARN_로그_출력_및_파이프라인_계속_진행")
    void mockResponseTriggersWarnLog() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(7L))
                .thenReturn(List.of(newSrc(10L)));
        // mock=true, source="mock", mock_reason="env_mock" 으로 응답
        YoloResponse mockResp = new YoloResponse(
                List.of(new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)),
                true,
                "mock",
                "env_mock"
        );
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(mockResp));

        List<BbHint> hints = step.run(7L);

        // mock 응답이라도 파이프라인은 진행 — 라벨 1건 저장됨
        assertThat(hints).hasSize(1);

        // WARN 로그가 최소 1회 출력되고 메시지에 "mock response detected" 포함
        long warnCount = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("mock response detected"))
                .count();
        assertThat(warnCount).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("ai_server_정상_응답시_mock_WARN_로그_없음")
    void realResponseNoWarnLog() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(8L))
                .thenReturn(List.of(newSrc(10L)));
        // mock=false (실제 모델 응답)
        YoloResponse realResp = new YoloResponse(
                List.of(new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)),
                false,
                "model",
                null
        );
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(realResp));

        step.run(8L);

        long mockWarnCount = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("mock response detected"))
                .count();
        assertThat(mockWarnCount).isZero();
    }

    @Test
    @DisplayName("eventTypeCd_null이면_fail_safe로_전체_통과")
    void nullEventTypeFailSafeAllowsAll() {
        // videoRepository.findById -> Optional.empty() (setUp 기본값)
        when(srcRepository.findByRawSnOrderByFrameNoAsc(6L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92),
                        new YoloResponse.Detection("car", List.of(5.0, 6.0, 7.0, 8.0), 0.81),
                        new YoloResponse.Detection("trash", List.of(9.0, 10.0, 11.0, 12.0), 0.55)
                ))));

        List<BbHint> hints = step.run(6L);

        // 매핑 없음 → 전체 통과 (BOTH default — BBOX 저장 + hint 발행)
        assertThat(hints).hasSize(3);
    }

    // ─── Phase 2: 라벨별 BBOX/POLYGON 토글 분기 ───

    @Test
    @DisplayName("YoloStep_bboxEnabled_false_라벨은_LS_DATA_LBL_에_BBOX_저장하지_않음")
    void bboxDisabledLabelDoesNotPersist() {
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(10L)).thenReturn(Optional.of(rawMock));
        // person 은 polygon-only (bbox=false, polygon=true) — BBOX 저장은 skip, hint 만 발행
        when(presetLabelLookup.togglesFor("EVT_FALL"))
                .thenReturn(Optional.of(Map.of("person", new AnnotationToggle(false, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(10L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(10L);

        // BBOX 저장은 skip — lblRepository.save 호출 0회
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.never()).save(any());
        // polygon=true 이므로 hint 는 발행되어야 함
        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).label()).isEqualTo("person");
    }

    @Test
    @DisplayName("YoloStep_polygonEnabled_true_라벨은_BbHint_로_반환")
    void polygonEnabledLabelReturnsHint() {
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(11L)).thenReturn(Optional.of(rawMock));
        when(presetLabelLookup.togglesFor("EVT_FALL"))
                .thenReturn(Optional.of(Map.of("person", new AnnotationToggle(true, true))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(11L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(11L);

        assertThat(hints).hasSize(1);
        BbHint h = hints.get(0);
        assertThat(h.srcSn()).isEqualTo(10L);
        assertThat(h.label()).isEqualTo("person");
        assertThat(h.points()).containsExactly(1.0, 2.0, 3.0, 4.0);
        assertThat(h.score()).isEqualTo(0.92);
    }

    @Test
    @DisplayName("YoloStep_polygonEnabled_false_라벨은_BbHint_미발행")
    void polygonDisabledLabelOmitsHint() {
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(12L)).thenReturn(Optional.of(rawMock));
        // person 은 bbox-only (bbox=true, polygon=false) — BBOX 저장만, hint 없음
        when(presetLabelLookup.togglesFor("EVT_FALL"))
                .thenReturn(Optional.of(Map.of("person", new AnnotationToggle(true, false))));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(12L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(12L);

        // BBOX 1건 저장
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(any());
        // hint 발행 없음
        assertThat(hints).isEmpty();
    }

    @Test
    @DisplayName("YoloStep_BOTH_라벨은_BBOX_저장_그리고_BbHint_도_반환")
    void bothToggleSavesBboxAndReturnsHint() {
        LsDataRaw rawMock = rawWithEvent("EVT_FALL");
        when(videoRepository.findById(13L)).thenReturn(Optional.of(rawMock));
        when(presetLabelLookup.togglesFor("EVT_FALL"))
                .thenReturn(Optional.of(Map.of("person", AnnotationToggle.BOTH)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(13L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(13L);

        // BBOX 1건 저장 + hint 1건 발행 (둘 다)
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(any());
        assertThat(hints).hasSize(1);
    }

    @Test
    @DisplayName("YoloStep_매핑_없는_라벨은_BOTH_fail_safe_로_BBOX_저장_및_hint_발행")
    void unmappedLabelDefaultsToBothFailSafe() {
        // 토글 맵에 person 만 등록되어 있는데 ai-server 가 car 를 검출한 경우 — 매핑 없으면 통과 (Phase 1 fail-safe)
        // 단, YoloStep 의 isLabelAllowed 가 set 외 라벨을 필터링하므로 본 케이스에서는 car 가 필터링됨.
        // 본 테스트는 togglesFor=empty (전체 fail-safe) 일 때 BOTH 처럼 동작하는지 검증.
        when(srcRepository.findByRawSnOrderByFrameNoAsc(14L))
                .thenReturn(List.of(newSrc(10L)));
        when(aiServerClient.predictYolo(any(YoloRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("car", List.of(1.0, 2.0, 3.0, 4.0), 0.92)
                ))));

        List<BbHint> hints = step.run(14L);

        // togglesFor empty (no preset) → fail-safe BOTH → BBOX 저장 + hint 발행
        org.mockito.Mockito.verify(lblRepository, org.mockito.Mockito.times(1)).save(any());
        assertThat(hints).hasSize(1);
    }
}
