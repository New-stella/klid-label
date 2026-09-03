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
        int intervalFrames = 5;
        String marksJson = "[{\"frameIndex\":0,\"timestamp\":\"00:00\"}]";
        String createdBy = "100";

        // when
        LsMarking marking = LsMarking.createAuto(rawSn, intervalFrames, marksJson, createdBy);

        // then
        assertThat(marking.getRawSn()).isEqualTo(rawSn);
        assertThat(marking.getMarkModeCd()).isEqualTo(LsMarking.MODE_AUTO);
        assertThat(marking.getFrmeIntvNocs()).isEqualTo(intervalFrames);
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
        String marksJson = "[{\"frameIndex\":10,\"timestamp\":\"00:05\"}]";
        String createdBy = "200";

        // when
        LsMarking marking = LsMarking.createManual(rawSn, marksJson, createdBy);

        // then
        assertThat(marking.getRawSn()).isEqualTo(rawSn);
        assertThat(marking.getMarkModeCd()).isEqualTo(LsMarking.MODE_MANUAL);
        assertThat(marking.getFrmeIntvNocs()).isNull();
        assertThat(marking.getMarkCn()).isEqualTo(marksJson);
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);
        assertThat(marking.getCreatedBy()).isEqualTo(createdBy);
    }

    @Test
    @DisplayName("createAuto_6인자_오버로드는_fps_null_하위호환")
    void createAuto6arg_fpsNull() {
        // 구 6-인자 호출부(테스트/레거시) 는 fps 를 pin 하지 않는다 → null (추출이 resolveFps 폴백).
        LsMarking marking = LsMarking.createAuto(1L, 5, "[]", "1");
        assertThat(marking.getFps()).isNull();
    }

    @Test
    @DisplayName("createAuto_7인자_오버로드는_fps를_pin한다")
    void createAuto7arg_pinsFps() {
        // TOCTOU 제거: 마킹 시점 fps 를 저장(pin) → 추출이 재조회 없이 사용.
        LsMarking marking = LsMarking.createAuto(1L, 5, "[]", "1", 29.97);
        assertThat(marking.getFps()).isEqualTo(29.97);
    }

    @Test
    @DisplayName("createManual_6인자_fps_null_7인자_fps_pin")
    void createManual_fpsPin() {
        LsMarking noPin = LsMarking.createManual(2L, "[]", "2");
        assertThat(noPin.getFps()).isNull();

        LsMarking pinned = LsMarking.createManual(2L, "[]", "2", 60.0);
        assertThat(pinned.getFps()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("markVlmRequested_상태전이")
    void markVlmRequestedTransition() {
        // given
        LsMarking marking = LsMarking.createAuto(1L, 5, "[]", "1");
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
        LsMarking pending = LsMarking.createAuto(1L, 5, "[]", "1");

        // when
        boolean t1 = pending.markVlmRequested();

        // then
        assertThat(t1).isTrue();
        assertThat(pending.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_REQUESTED);

        // given — 이미 VLM_COMPLETED 는 역행하지 않음(no-op)
        LsMarking completed = LsMarking.createAuto(1L, 5, "[]", "1");
        completed.markVlmRequested();
        completed.markVlmCompleted();

        // when
        boolean t2 = completed.markVlmRequested();

        // then
        assertThat(t2).isFalse();
        assertThat(completed.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);

        // given — 이미 VLM_REQUESTED 는 재대입 no-op
        LsMarking requested = LsMarking.createAuto(1L, 5, "[]", "1");
        requested.markVlmRequested();

        // when
        boolean t3 = requested.markVlmRequested();

        // then
        assertThat(t3).isFalse();
        assertThat(requested.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_REQUESTED);

        // given — 이미 VLM_FAILED 는 역행하지 않음(no-op)
        LsMarking failed = LsMarking.createAuto(1L, 5, "[]", "1");
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
        LsMarking marking = LsMarking.createAuto(1L, 5, "[]", "1");
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
                LsMarking.createAuto(1L, 0, "[]", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalFrames");

        assertThatThrownBy(() ->
                LsMarking.createAuto(1L, -1, "[]", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalFrames");
    }

    @Test
    @DisplayName("createAuto_rawSn_null_예외")
    void createAutoNullRawSn() {
        // given / when / then
        assertThatThrownBy(() ->
                LsMarking.createAuto(null, 5, "[]", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rawSn");
    }

    @Test
    @DisplayName("★마킹_원장은_이벤트유형코드와_영상경로를_보관하지_않는다 — 되살리기_방지 (V27)")
    void 중복_두_칸이_되살아나지_않는다() {
        // 두 값은 영상 행(LS_DATA_RAW.EVNT_TYPE_CD · RAW_FILE_PATH_NM)에 이미 있는 것을 마킹 행에
        // 베껴 두던 중복이었다. 응답에는 그대로 실리지만(MarkingResponse.from 이 영상 행에서 받는다)
        // 원장에는 두지 않는다 — 되살리면 영상 쪽이 바뀔 때 두 값이 어긋나고, 포털 업로드 경로는
        // 둘 다 채울 값이 없어 저장 자체가 다시 막힌다.
        assertThat(java.util.Arrays.stream(LsMarking.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .doesNotContain("evntNm", "videoFilePathNm");
    }

    @Test
    @DisplayName("★등록사용자번호는_문자다 — 포털_주체를_담기_위해 (V27)")
    void 등록사용자번호는_문자다() throws Exception {
        // 숫자로 되돌리면 포털이 발급한 비숫자 주체가 파싱 실패로 조용히 null 이 되어
        // 소유자 없는 마킹이 저장된다(인가 판정의 키를 잃는다).
        assertThat(LsMarking.class.getDeclaredField("createdBy").getType()).isEqualTo(String.class);
        assertThat(LsMarking.createManual(1L, "[]", "portal-user-abc").getCreatedBy())
                .isEqualTo("portal-user-abc");
    }

    @Test
    @DisplayName("createReserved_예약_생성 — 시작상태_RESERVED_모드는_MANUAL_간격은_null (ADR-052)")
    void createReservedNormal() {
        // given — 외부에서 이벤트 마킹까지 끝난 영상을 적재하는 경로
        String marksJson = "[{\"frameIndex\":120,\"timestamp\":\"00:04\"}]";

        // when
        LsMarking marking = LsMarking.createReserved(77L, marksJson, "admin-1", 29.97);

        // then
        assertThat(marking.getRawSn()).isEqualTo(77L);
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_RESERVED);
        // 외부가 준 시점 배열의 구조가 사람이 찍은 마킹과 같아 MANUAL 을 쓴다(ADR-052).
        assertThat(marking.getMarkModeCd()).isEqualTo(LsMarking.MODE_MANUAL);
        // 간격으로 생성한 값이 아니므로 프레임 간격은 비어 있어야 한다.
        assertThat(marking.getFrmeIntvNocs()).isNull();
        assertThat(marking.getMarkCn()).isEqualTo(marksJson);
        assertThat(marking.getFps()).isEqualTo(29.97);
        assertThat(marking.getCreatedBy()).isEqualTo("admin-1");
        assertThat(marking.getVrfcEvntQstnSn()).isNull();
    }

    @Test
    @DisplayName("★예약은_활성_마킹으로_세지_않는다 — 예약이_있어도_사람이_다시_마킹할_수_있다 (ADR-052)")
    void 예약은_활성집합에_들어가지_않는다() {
        // 활성 부분 유니크(UK_LS_MARKING_RAW_ACTVTN)가 보는 값은 PENDING·VLM_REQUESTED 뿐이다.
        // 여기에 RESERVED 를 더하면 예약이 영상당 활성 마킹 1건 제약을 점유해, 비식별이 실패했을 때
        // 그 영상을 다시 마킹할 수 없게 된다 — ADR-052 가 「업로드 시점 즉시 활성화」를 기각한 근거다.
        assertThat(LsMarking.ACTIVE_STATUSES)
                .containsExactly(LsMarking.STATUS_PENDING, LsMarking.STATUS_VLM_REQUESTED)
                .doesNotContain(LsMarking.STATUS_RESERVED);
    }

    @Test
    @DisplayName("★RESERVED_PENDING_전이_메서드를_엔티티에_두지_않는다 — 원자_클레임_우회로_방지")
    void 예약_활성화_전이는_엔티티에_없다() {
        // 그 전이는 2노드 중 한쪽만 집어 가야 하는 원자 클레임이라 조건부 UPDATE(리포지토리)로만 한다.
        // 엔티티에 전이 메서드를 두면 조회 후 변경 경로가 열려 두 노드가 둘 다 통과한다(CWE-362).
        assertThat(java.util.Arrays.stream(LsMarking.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("activate", "activateReservation", "markPendingFromReserved");
    }

    @Test
    @DisplayName("createReserved_필수값_누락_예외 — rawSn_marksJson")
    void createReservedRequiresValues() {
        assertThatThrownBy(() -> LsMarking.createReserved(null, "[]", "1", 30.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rawSn");

        // 담을 시점 배열이 없으면 예약할 내용이 없다(MARK_CN 은 NOT NULL 이라 DB 오류로 새면 안 된다).
        assertThatThrownBy(() -> LsMarking.createReserved(1L, "  ", "1", 30.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("marksJson");
        assertThatThrownBy(() -> LsMarking.createReserved(1L, null, "1", 30.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("marksJson");
    }
}
