package kr.co.cudo.authoring.marking;

import kr.co.cudo.authoring.marking.entity.LsMarking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LsMarking Entity 순수 단위 테스트.
 */
class LsMarkingEntityTest {

    @Test
    @DisplayName("createAuto_정상_생성_STATUS_PENDING")
    void createAutoNormal() {
        // given
        Long rawSn = 1L;
        String eventName = "화재";
        int intervalFrames = 5;
        String videoPath = "/nas/video/001.mp4";
        String marksJson = "[{\"frameIndex\":0,\"timestamp\":\"00:00\"}]";
        Long createdBy = 100L;

        // when
        LsMarking marking = LsMarking.createAuto(rawSn, eventName, intervalFrames, videoPath, marksJson, createdBy);

        // then
        assertThat(marking.getRawSn()).isEqualTo(rawSn);
        assertThat(marking.getEventName()).isEqualTo(eventName);
        assertThat(marking.getMarkingMode()).isEqualTo(LsMarking.MODE_AUTO);
        assertThat(marking.getIntervalFrames()).isEqualTo(intervalFrames);
        assertThat(marking.getVideoPath()).isEqualTo(videoPath);
        assertThat(marking.getMarks()).isEqualTo(marksJson);
        assertThat(marking.getStatus()).isEqualTo(LsMarking.STATUS_PENDING);
        assertThat(marking.getCreatedBy()).isEqualTo(createdBy);
        assertThat(marking.getCreatedAt()).isNotNull();
        assertThat(marking.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("createManual_정상_생성")
    void createManualNormal() {
        // given
        Long rawSn = 2L;
        String eventName = "침입";
        String videoPath = "/nas/video/002.mp4";
        String marksJson = "[{\"frameIndex\":10,\"timestamp\":\"00:05\"}]";
        Long createdBy = 200L;

        // when
        LsMarking marking = LsMarking.createManual(rawSn, eventName, videoPath, marksJson, createdBy);

        // then
        assertThat(marking.getRawSn()).isEqualTo(rawSn);
        assertThat(marking.getEventName()).isEqualTo(eventName);
        assertThat(marking.getMarkingMode()).isEqualTo(LsMarking.MODE_MANUAL);
        assertThat(marking.getIntervalFrames()).isNull();
        assertThat(marking.getVideoPath()).isEqualTo(videoPath);
        assertThat(marking.getMarks()).isEqualTo(marksJson);
        assertThat(marking.getStatus()).isEqualTo(LsMarking.STATUS_PENDING);
        assertThat(marking.getCreatedBy()).isEqualTo(createdBy);
    }

    @Test
    @DisplayName("markVlmRequested_상태전이")
    void markVlmRequestedTransition() {
        // given
        LsMarking marking = LsMarking.createAuto(1L, "화재", 5, "/path", "[]", 1L);
        assertThat(marking.getStatus()).isEqualTo(LsMarking.STATUS_PENDING);

        // when
        marking.markVlmRequested();

        // then
        assertThat(marking.getStatus()).isEqualTo(LsMarking.STATUS_VLM_REQUESTED);
    }

    @Test
    @DisplayName("markVlmCompleted_상태전이")
    void markVlmCompletedTransition() {
        // given
        LsMarking marking = LsMarking.createAuto(1L, "화재", 5, "/path", "[]", 1L);
        marking.markVlmRequested();

        // when
        marking.markVlmCompleted();

        // then
        assertThat(marking.getStatus()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
    }

    @Test
    @DisplayName("createAuto_intervalFrames_0이하_예외")
    void createAutoInvalidIntervalSec() {
        // given / when / then
        assertThatThrownBy(() ->
                LsMarking.createAuto(1L, "화재", 0, "/path", "[]", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalFrames");

        assertThatThrownBy(() ->
                LsMarking.createAuto(1L, "화재", -1, "/path", "[]", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalFrames");
    }

    @Test
    @DisplayName("createManual_eventName_null_예외")
    void createManualNullEventName() {
        // given / when / then
        assertThatThrownBy(() ->
                LsMarking.createManual(1L, null, "/path", "[]", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventName");
    }

    @Test
    @DisplayName("createAuto_rawSn_null_예외")
    void createAutoNullRawSn() {
        // given / when / then
        assertThatThrownBy(() ->
                LsMarking.createAuto(null, "화재", 5, "/path", "[]", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rawSn");
    }

    @Test
    @DisplayName("createManual_videoPath_blank_예외")
    void createManualBlankVideoPath() {
        // given / when / then
        assertThatThrownBy(() ->
                LsMarking.createManual(1L, "화재", "", "[]", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("videoPath");
    }
}
