package kr.co.cudo.authoring.preset.entity;

import kr.co.cudo.authoring.preset.entity.LsLabelPreset.LabelCodeSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aggregate Root {@link LsLabelPreset#replaceCodes(List)} 의 전체 교체(diff) 시맨틱 단위 테스트.
 *
 * <p>인메모리 diff(labelId/legacy 코드 기준)만 검증한다 — 영속 없이 컬렉션 상태를 직접 관찰한다.
 * DB 부분 유니크 인덱스(V119)의 동시성 방어는 {@code LsLabelPresetCodeLabelIdUniqueIT} 가 실증한다.
 */
class LsLabelPresetTest {

    /** 프리셋은 이벤트유형 1건에 대응한다(V17 이후 필수) — diff 시맨틱과 무관한 고정값. */
    private static final String EVENT_TYPE_CD = "EV01000101";

    private static LsLabelPreset presetOf(LabelCodeSpec... specs) {
        return LsLabelPreset.createWithOptions(List.of(specs), EVENT_TYPE_CD);
    }

    private static LabelCodeSpec id(long labelId) {
        return new LabelCodeSpec(labelId, null);
    }

    private static LabelCodeSpec legacy(String code) {
        return new LabelCodeSpec(null, code);
    }

    @Test
    @DisplayName("replaceCodes_동일_labelId_중복입력시_1건으로_dedup된다")
    void duplicateLabelIdDedupedToOne() {
        LsLabelPreset preset = presetOf();

        preset.replaceCodes(List.of(id(10L), id(10L), id(10L)));

        assertThat(preset.getCodes()).hasSize(1);
        assertThat(preset.getCodes().get(0).getLabelId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("replaceCodes_요청에서_제외된_labelId는_orphanRemoval로_삭제된다")
    void omittedLabelIdRemoved() {
        LsLabelPreset preset = presetOf(id(10L), id(11L));

        // 11 을 뺀 목록으로 교체 → 11 은 컬렉션에서 제거(orphanRemoval → DELETE)돼야 한다.
        preset.replaceCodes(List.of(id(10L)));

        assertThat(preset.getCodes()).hasSize(1);
        assertThat(preset.getCodes().get(0).getLabelId()).isEqualTo(10L);
        assertThat(preset.getCodes()).noneMatch(c -> c.getLabelId() != null && c.getLabelId() == 11L);
    }

    @Test
    @DisplayName("replaceCodes_순서변경시_sortOrder가_갱신된다")
    void reorderUpdatesSortOrder() {
        LsLabelPreset preset = presetOf(id(10L), id(11L));

        // 역순으로 교체 → sortOrder 는 새 위치(11=0, 10=1)로 갱신돼야 한다.
        preset.replaceCodes(List.of(id(11L), id(10L)));

        LsLabelPresetCode first = findByLabelId(preset, 11L);
        LsLabelPresetCode second = findByLabelId(preset, 10L);
        assertThat(first.getSortOrder()).isZero();
        assertThat(second.getSortOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("replaceCodes_labelId행과_legacy코드행_혼재시_각각_독립_diff")
    void mixedLabelIdAndLegacyCodesDiffIndependently() {
        LsLabelPreset preset = presetOf(id(10L), legacy("OLD"));

        // labelId=10 유지, legacy OLD 제거 + legacy NEW 추가.
        preset.replaceCodes(List.of(id(10L), legacy("NEW")));

        assertThat(preset.getCodes()).hasSize(2);
        assertThat(preset.getCodes()).anyMatch(c -> c.getLabelId() != null && c.getLabelId() == 10L);
        assertThat(preset.codeValues()).containsExactly("NEW");
        assertThat(preset.codeValues()).doesNotContain("OLD");
    }

    private static LsLabelPresetCode findByLabelId(LsLabelPreset preset, long labelId) {
        return preset.getCodes().stream()
                .filter(c -> c.getLabelId() != null && c.getLabelId() == labelId)
                .findFirst()
                .orElseThrow();
    }
}
