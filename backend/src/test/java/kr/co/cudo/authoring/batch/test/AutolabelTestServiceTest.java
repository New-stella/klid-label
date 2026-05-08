package kr.co.cudo.authoring.batch.test;

import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.batch.test.dto.AutolabelRunResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
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
        verify(sam2Step, never()).run(any());
    }

    @Test
    @DisplayName("YOLO_SAM2_정상실행시_카운트_반환")
    void YOLO_SAM2_정상실행시_카운트_반환() {
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(5L);
        given(yoloStep.run(RAW_SN)).willReturn(3);
        given(sam2Step.run(RAW_SN)).willReturn(3);

        AutolabelRunResponse response = service.run(RAW_SN);

        assertThat(response.rawSn()).isEqualTo(RAW_SN);
        assertThat(response.framesFound()).isEqualTo(5L);
        assertThat(response.yoloLabels()).isEqualTo(3);
        assertThat(response.sam2Labels()).isEqualTo(3);
        assertThat(response.skipped()).containsExactly("DEIDENTIFY", "VLM_VERIFY");
        assertThat(response.durationMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("기존_auto라벨_삭제_호출_검증")
    void 기존_auto라벨_삭제_호출_검증() {
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(2L);
        given(yoloStep.run(RAW_SN)).willReturn(1);
        given(sam2Step.run(RAW_SN)).willReturn(1);

        service.run(RAW_SN);

        InOrder order = inOrder(lblRepository, yoloStep, sam2Step);
        order.verify(lblRepository, times(1)).deleteByRawSnAutoLbl(RAW_SN);
        order.verify(yoloStep, times(1)).run(RAW_SN);
        order.verify(sam2Step, times(1)).run(RAW_SN);
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
        verify(sam2Step, never()).run(any());
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
