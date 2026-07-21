package kr.co.cudo.authoring.version.entity;

import kr.co.cudo.authoring.version.util.LabelHistoryDiffSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V114 저장 이벤트 모델 순수 단위 테스트 — 팩토리 집계 + diff 직렬화 왕복(Spring 미기동).
 */
class LabelHistorySaveEventTest {

    private LabelSnapshot snap(String name) {
        return new LabelSnapshot("BBOX", 7L, name, "[[1,1],[2,2]]");
    }

    @Test
    @DisplayName("저장이벤트_팩토리는_종류별_건수를_집계한다")
    void aggregatesCountsByKind() {
        List<LabelChange> changes = List.of(
                LabelChange.added(1L, "a", snap("a")),
                LabelChange.added(2L, "b", snap("b")),
                LabelChange.updated(3L, "c", snap("c0"), snap("c1")),
                LabelChange.deleted(4L, "d", snap("d")));

        LsDataLblHstry h = LsDataLblHstry.recordSaveEvent(40L, "100", changes);

        assertThat(h.getSrcSn()).isEqualTo(40L);
        assertThat(h.getRegId()).isEqualTo("100");
        assertThat(h.getRegDt()).isNotNull();
        assertThat(h.getAddCnt()).isEqualTo(2);
        assertThat(h.getMdfcnCnt()).isEqualTo(1);
        assertThat(h.getDelCnt()).isEqualTo(1);
        assertThat(h.getChgDtlCn()).isNotBlank();
    }

    @Test
    @DisplayName("변경목록이_비면_이벤트를_만들지_않는다")
    void emptyChangesRejected() {
        // 호출측은 비면 스킵하지만, 팩토리도 방어적으로 무변경 이벤트 생성을 거부한다(R7).
        assertThatThrownBy(() -> LsDataLblHstry.recordSaveEvent(40L, "100", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LsDataLblHstry.recordSaveEvent(40L, "100", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("diff_직렬화_역직렬화_왕복이_동일하다")
    void diffRoundTripEquals() {
        List<LabelChange> original = List.of(
                LabelChange.added(1L, "person", snap("person")),
                LabelChange.updated(2L, "car", snap("car-before"), snap("car-after")),
                LabelChange.deleted(3L, "dog", snap("dog")));

        String json = LabelHistoryDiffSerializer.serialize(original);
        List<LabelChange> restored = LabelHistoryDiffSerializer.deserialize(json);

        // record 값 동등성 — 왕복 후 완전 동일(kind enum·중첩 스냅샷 포함).
        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("diff_null과_빈문자열은_빈목록으로_역직렬화된다")
    void diffNullOrBlankYieldsEmpty() {
        assertThat(LabelHistoryDiffSerializer.deserialize(null)).isEmpty();
        assertThat(LabelHistoryDiffSerializer.deserialize("  ")).isEmpty();
        assertThat(LabelHistoryDiffSerializer.deserialize("[]")).isEmpty();
    }
}
