package kr.co.cudo.authoring.batch.test;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.step.BbHint;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
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

import java.util.List;
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
    private FfmpegFrameExtractor frameExtractor;
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
        assertThat(response.skipped()).containsExactly("DEIDENTIFY", "VLM_VERIFY");
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

        verify(frameExtractor, never()).extract(any());
        verify(yoloStep, never()).run(any());
        verify(sam2Step, never()).run(any(), any());
    }

    @Test
    @DisplayName("runFull_프레임없으면_추출후_YOLO_SAM2_실행")
    void runFull_프레임없으면_추출후_YOLO_SAM2_실행() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(0L);
        given(frameExtractor.extract(raw)).willReturn(List.of(mock(LsDataSrc.class), mock(LsDataSrc.class), mock(LsDataSrc.class)));
        given(yoloStep.run(RAW_SN)).willReturn(twoHints());
        given(sam2Step.run(eq(RAW_SN), any())).willReturn(2);

        AutolabelRunResponse response = service.runFull(RAW_SN);

        assertThat(response.rawSn()).isEqualTo(RAW_SN);
        assertThat(response.framesFound()).isEqualTo(3L);
        assertThat(response.frameExtracted()).isTrue();
        assertThat(response.yoloLabels()).isEqualTo(2);
        assertThat(response.sam2Labels()).isEqualTo(2);
        verify(frameExtractor, times(1)).extract(raw);
    }

    @Test
    @DisplayName("runFull_프레임있으면_추출_스킵")
    void runFull_프레임있으면_추출_스킵() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(7L);
        given(yoloStep.run(RAW_SN)).willReturn(fourHints());
        given(sam2Step.run(eq(RAW_SN), any())).willReturn(4);

        AutolabelRunResponse response = service.runFull(RAW_SN);

        assertThat(response.framesFound()).isEqualTo(7L);
        assertThat(response.frameExtracted()).isFalse();
        verify(frameExtractor, never()).extract(any());
    }

    @Test
    @DisplayName("runFull_추출후_0건이면_INVALID_INPUT_예외")
    void runFull_추출후_0건이면_INVALID_INPUT_예외() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(0L);
        given(frameExtractor.extract(raw)).willReturn(List.of());

        assertThatThrownBy(() -> service.runFull(RAW_SN))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(lblRepository, never()).deleteByRawSnAutoLbl(any());
        verify(yoloStep, never()).run(any());
        verify(sam2Step, never()).run(any(), any());
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
