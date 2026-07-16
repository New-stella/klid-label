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
        assertThat(marking.getEvntNm()).isEqualTo(eventName);
        assertThat(marking.getMarkModeCd()).isEqualTo(LsMarking.MODE_AUTO);
        assertThat(marking.getFrmeIntvNocs()).isEqualTo(intervalFrames);
        assertThat(marking.getVideoFilePathNm()).isEqualTo(videoPath);
        assertThat(marking.getMarkCn()).isEqualTo(marksJson);
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);
        assertThat(marking.getCreatedBy()).isEqualTo(createdBy);
        assertThat(marking.getRegDt()).isNotNull();
        assertThat(marking.getMdfcnDt()).isNotNull();
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
        assertThat(marking.getEvntNm()).isEqualTo(eventName);
        assertThat(marking.getMarkModeCd()).isEqualTo(LsMarking.MODE_MANUAL);
        assertThat(marking.getFrmeIntvNocs()).isNull();
        assertThat(marking.getVideoFilePathNm()).isEqualTo(videoPath);
        assertThat(marking.getMarkCn()).isEqualTo(marksJson);
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);
        assertThat(marking.getCreatedBy()).isEqualTo(createdBy);
    }

    @Test
    @DisplayName("createAuto_6인자_오버로드는_fps_null_하위호환")
    void createAuto6arg_fpsNull() {
        // 구 6-인자 호출부(테스트/레거시) 는 fps 를 pin 하지 않는다 → null (추출이 resolveFps 폴백).
        LsMarking marking = LsMarking.createAuto(1L, "화재", 5, "/path", "[]", 1L);
        assertThat(marking.getFps()).isNull();
    }

    @Test
    @DisplayName("createAuto_7인자_오버로드는_fps를_pin한다")
    void createAuto7arg_pinsFps() {
        // TOCTOU 제거: 마킹 시점 fps 를 저장(pin) → 추출이 재조회 없이 사용.
        LsMarking marking = LsMarking.createAuto(1L, "화재", 5, "/path", "[]", 1L, 29.97);
        assertThat(marking.getFps()).isEqualTo(29.97);
    }

    @Test
    @DisplayName("createManual_6인자_fps_null_7인자_fps_pin")
    void createManual_fpsPin() {
        LsMarking noPin = LsMarking.createManual(2L, "침입", "/path", "[]", 2L);
        assertThat(noPin.getFps()).isNull();

        LsMarking pinned = LsMarking.createManual(2L, "침입", "/path", "[]", 2L, 60.0);
        assertThat(pinned.getFps()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("markVlmRequested_상태전이")
    void markVlmRequestedTransition() {
        // given
        LsMarking marking = LsMarking.createAuto(1L, "화재", 5, "/path", "[]", 1L);
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);

        // when
        marking.markVlmRequested();

        // then
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_REQUESTED);
    }

    @Test
    @DisplayName("PENDING에서만_VLM_REQUESTED로_전이_그외_no_op")
    void markVlmRequestedGuard() {
        // given — PENDING 이면 전이(true)
        LsMarking pending = LsMarking.createAuto(1L, "화재", 5, "/path", "[]", 1L);

        // when
        boolean t1 = pending.markVlmRequested();

        // then
        assertThat(t1).isTrue();
        assertThat(pending.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_REQUESTED);

        // given — 이미 VLM_COMPLETED 는 역행하지 않음(no-op)
        LsMarking completed = LsMarking.createAuto(1L, "화재", 5, "/path", "[]", 1L);
        completed.markVlmRequested();
        completed.markVlmCompleted();

        // when
        boolean t2 = completed.markVlmRequested();

        // then
        assertThat(t2).isFalse();
        assertThat(completed.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);

        // given — 이미 VLM_REQUESTED 는 재대입 no-op
        LsMarking requested = LsMarking.createAuto(1L, "화재", 5, "/path", "[]", 1L);
        requested.markVlmRequested();

        // when
        boolean t3 = requested.markVlmRequested();

        // then
        assertThat(t3).isFalse();
        assertThat(requested.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_REQUESTED);

        // given — 이미 VLM_FAILED 는 역행하지 않음(no-op)
        LsMarking failed = LsMarking.createAuto(1L, "화재", 5, "/path", "[]", 1L);
        failed.markVlmRequested();
        failed.markVlmFailed();

        // when
        boolean t4 = failed.markVlmRequested();

        // then
        assertThat(t4).isFalse();
        assertThat(failed.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_FAILED);
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
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
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
