package kr.co.cudo.authoring.batch.test;

import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.step.BbHint;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.batch.test.dto.AutolabelRunResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AutolabelTestServiceTest {

    @Mock
    private YoloAutolabelStep yoloStep;
    @Mock
    private Sam2SegmentStep sam2Step;
    @Mock
    private LsDataSrcRepository srcRepository;
    @Mock
    private LsDataLblRepository lblRepository;
    @Mock
    private DeidentifyStep deidentifyStep;
    @Mock
    private VideoRepository videoRepository;
    @Mock
    private kr.co.cudo.authoring.batch.status.BatchStatusService statusService;

    @InjectMocks
    private AutolabelTestService service;

    private static final Long RAW_SN = 100L;

    @BeforeEach
    void setUp() {
        // no-op
    }

    @Test
    @DisplayName("rawSn_프레임_없으면_INVALID_INPUT_예외")
    void rawSn_프레임_없으면_INVALID_INPUT_예외() {
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(0L);

        assertThatThrownBy(() -> service.run(RAW_SN))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(lblRepository, never()).deleteByRawSnAutoLbl(any());
        verify(yoloStep, never()).run(any());
        verify(sam2Step, never()).run(any(), any());
    }

    @Test
    @DisplayName("YOLO_SAM2_정상실행시_카운트_반환")
    void YOLO_SAM2_정상실행시_카운트_반환() {
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(5L);
        given(yoloStep.run(RAW_SN)).willReturn(threeHints());
        given(sam2Step.run(eq(RAW_SN), any())).willReturn(3);

        AutolabelRunResponse response = service.run(RAW_SN);

        assertThat(response.rawSn()).isEqualTo(RAW_SN);
        assertThat(response.framesFound()).isEqualTo(5L);
        assertThat(response.frameExtracted()).isFalse();
        assertThat(response.yoloLabels()).isEqualTo(3);
        assertThat(response.sam2Labels()).isEqualTo(3);
        // run() 은 DEIDENTIFY 를 실행하지 않으므로 DEIDENTIFY + 영구SKIP(VLM_VERIFY) 모두 포함.
        assertThat(response.skipped()).containsExactlyInAnyOrder("DEIDENTIFY", "VLM_VERIFY");
        assertThat(response.durationMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("기존_auto라벨_삭제_호출_검증")
    void 기존_auto라벨_삭제_호출_검증() {
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(2L);
        given(yoloStep.run(RAW_SN)).willReturn(oneHint());
        given(sam2Step.run(eq(RAW_SN), any())).willReturn(1);

        service.run(RAW_SN);

        InOrder order = inOrder(lblRepository, yoloStep, sam2Step);
        order.verify(lblRepository, times(1)).deleteByRawSnAutoLbl(RAW_SN);
        order.verify(yoloStep, times(1)).run(RAW_SN);
        order.verify(sam2Step, times(1)).run(eq(RAW_SN), any());
    }

    @Test
    @DisplayName("YOLO_Step_예외시_예외_전파")
    void YOLO_Step_예외시_예외_전파() {
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(2L);
        given(yoloStep.run(RAW_SN)).willThrow(new RuntimeException("yolo boom"));

        assertThatThrownBy(() -> service.run(RAW_SN))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("yolo boom");

        verify(lblRepository, times(1)).deleteByRawSnAutoLbl(RAW_SN);
        verify(sam2Step, never()).run(any(), any());
    }

    @Test
    @DisplayName("runFull_rawSn_없으면_INVALID_INPUT_예외")
    void runFull_rawSn_없으면_INVALID_INPUT_예외() {
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.runFull(RAW_SN))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(yoloStep, never()).run(any());
        verify(sam2Step, never()).run(any(), any());
    }

    @Test
    @DisplayName("V2_runFull_프레임없으면_배치_선행_필요_INVALID_INPUT_예외")
    void runFull_프레임없으면_배치_선행_필요_INVALID_INPUT_예외() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(0L);

        assertThatThrownBy(() -> service.runFull(RAW_SN))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(yoloStep, never()).run(any());
        verify(sam2Step, never()).run(any(), any());
    }

    @Test
    @DisplayName("runFull_프레임있으면_YOLO_SAM2_실행")
    void runFull_프레임있으면_YOLO_SAM2_실행() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(7L);
        given(yoloStep.run(RAW_SN)).willReturn(fourHints());
        given(sam2Step.run(eq(RAW_SN), any())).willReturn(4);

        AutolabelRunResponse response = service.runFull(RAW_SN);

        assertThat(response.framesFound()).isEqualTo(7L);
        assertThat(response.frameExtracted()).isFalse();
    }

    @Test
    @DisplayName("runFull_토글_YOLO_false면_YoloStep_호출안함")
    void runFull_YOLO_off_시_yoloStep_미호출() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(5L);

        Map<String, Boolean> toggles = new HashMap<>();
        toggles.put("FRAME_EXTRACT", true);
        toggles.put("DEIDENTIFY", false);
        toggles.put("YOLO", false);
        toggles.put("SAM2", false);

        AutolabelRunResponse response = service.runFull(RAW_SN, toggles);

        assertThat(response.yoloLabels()).isEqualTo(0);
        assertThat(response.sam2Labels()).isEqualTo(0);
        assertThat(response.skipped()).contains("YOLO", "SAM2", "DEIDENTIFY");
        verify(yoloStep, never()).run(any());
        verify(sam2Step, never()).run(any(), any());
        verify(deidentifyStep, never()).run(any());
    }

    @Test
    @DisplayName("runFull_토글_DEIDENTIFY_true면_DeidentifyStep_호출")
    void runFull_DEIDENTIFY_on_시_deidentifyStep_호출() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(5L);
        given(yoloStep.run(RAW_SN)).willReturn(twoHints());
        given(sam2Step.run(eq(RAW_SN), any())).willReturn(2);

        Map<String, Boolean> toggles = new HashMap<>();
        toggles.put("FRAME_EXTRACT", true);
        toggles.put("DEIDENTIFY", true);
        toggles.put("YOLO", true);
        toggles.put("SAM2", true);

        service.runFull(RAW_SN, toggles);

        verify(deidentifyStep, times(1)).run(raw);
        verify(yoloStep, times(1)).run(RAW_SN);
        verify(sam2Step, times(1)).run(eq(RAW_SN), any());
    }

    @Test
    @DisplayName("runFull_토글_FRAME_EXTRACT_false면_프레임추출_안함")
    void runFull_FRAME_EXTRACT_off_시_프레임추출_안함() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        // 프레임이 0건이어도 토글이 off 면 추출하지 않는다 — 사용자 선택 우선.
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(0L);

        Map<String, Boolean> toggles = new HashMap<>();
        toggles.put("FRAME_EXTRACT", false);
        toggles.put("DEIDENTIFY", false);
        toggles.put("YOLO", false);
        toggles.put("SAM2", false);

        AutolabelRunResponse response = service.runFull(RAW_SN, toggles);

        assertThat(response.skipped()).contains("FRAME_EXTRACT");
    }

    @Test
    @DisplayName("runFull_무인자_시_4단계_전부_실행_back_compat")
    void runFull_무인자_시_4단계_전부() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(3L);
        given(yoloStep.run(RAW_SN)).willReturn(twoHints());
        given(sam2Step.run(eq(RAW_SN), any())).willReturn(2);

        AutolabelRunResponse response = service.runFull(RAW_SN);

        verify(deidentifyStep, times(1)).run(raw);
        verify(yoloStep, times(1)).run(RAW_SN);
        verify(sam2Step, times(1)).run(eq(RAW_SN), any());
        // DEIDENTIFY 가 실행됐으므로 skipped 에 포함되면 안 됨 — VLM_VERIFY 만 영구 SKIP.
        assertThat(response.skipped()).containsExactly("VLM_VERIFY");
        assertThat(response.skipped()).doesNotContain("DEIDENTIFY");
    }

    @Test
    @DisplayName("runFull_DEIDENTIFY_on_시_응답_skipped_에_DEIDENTIFY_없음")
    void runFull_DEIDENTIFY_on_시_skipped_에_DEIDENTIFY_없음() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(5L);
        given(yoloStep.run(RAW_SN)).willReturn(twoHints());
        given(sam2Step.run(eq(RAW_SN), any())).willReturn(2);

        Map<String, Boolean> toggles = new HashMap<>();
        toggles.put("FRAME_EXTRACT", true);
        toggles.put("DEIDENTIFY", true);
        toggles.put("YOLO", true);
        toggles.put("SAM2", true);

        AutolabelRunResponse response = service.runFull(RAW_SN, toggles);

        assertThat(response.skipped()).doesNotContain("DEIDENTIFY");
        assertThat(response.skipped()).containsExactly("VLM_VERIFY");
    }

    @Test
    @DisplayName("runFull_YOLO_off_시_응답_skipped_에_YOLO_포함")
    void runFull_YOLO_off_시_skipped_에_YOLO_포함() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(5L);
        given(sam2Step.run(eq(RAW_SN), any())).willReturn(0);

        Map<String, Boolean> toggles = new HashMap<>();
        toggles.put("FRAME_EXTRACT", true);
        toggles.put("DEIDENTIFY", true);
        toggles.put("YOLO", false);
        toggles.put("SAM2", true); // YOLO 없이 SAM2 실행 — 빈 hints 로 호출

        AutolabelRunResponse response = service.runFull(RAW_SN, toggles);

        assertThat(response.skipped()).contains("YOLO");
        assertThat(response.skipped()).doesNotContain("DEIDENTIFY");
        assertThat(response.skipped()).contains("VLM_VERIFY");
    }

    @Test
    @DisplayName("runFull_YOLO_off_SAM2_on_시_SAM2는_빈_hints로_호출")
    void runFull_YOLO_off_SAM2_on_시_빈_hints_호출() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(5L);
        given(sam2Step.run(eq(RAW_SN), any())).willReturn(0);

        Map<String, Boolean> toggles = new HashMap<>();
        toggles.put("FRAME_EXTRACT", true);
        toggles.put("DEIDENTIFY", false);
        toggles.put("YOLO", false);
        toggles.put("SAM2", true);

        AutolabelRunResponse response = service.runFull(RAW_SN, toggles);

        verify(yoloStep, never()).run(any());
        verify(sam2Step, times(1)).run(eq(RAW_SN), eq(List.of()));
        assertThat(response.yoloLabels()).isEqualTo(0);
        assertThat(response.sam2Labels()).isEqualTo(0);
    }

    private static List<BbHint> oneHint() {
        return List.of(new BbHint(1L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.9, null));
    }

    private static List<BbHint> twoHints() {
        return List.of(
                new BbHint(1L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.9, null),
                new BbHint(2L, "car", List.of(5.0, 6.0, 7.0, 8.0), 0.8, null));
    }

    private static List<BbHint> threeHints() {
        return List.of(
                new BbHint(1L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.9, null),
                new BbHint(2L, "car", List.of(5.0, 6.0, 7.0, 8.0), 0.8, null),
                new BbHint(3L, "bus", List.of(9.0, 10.0, 11.0, 12.0), 0.7, null));
    }

    private static List<BbHint> fourHints() {
        return List.of(
                new BbHint(1L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.9, null),
                new BbHint(2L, "car", List.of(5.0, 6.0, 7.0, 8.0), 0.8, null),
                new BbHint(3L, "bus", List.of(9.0, 10.0, 11.0, 12.0), 0.7, null),
                new BbHint(4L, "truck", List.of(13.0, 14.0, 15.0, 16.0), 0.6, null));
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
