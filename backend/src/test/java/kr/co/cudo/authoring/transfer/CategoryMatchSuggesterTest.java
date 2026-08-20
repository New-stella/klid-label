package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.transfer.entity.LsOtsdCtgryMpng;
import kr.co.cudo.authoring.transfer.service.CategoryMatchSuggester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 분류 추천이 <b>애매하면 비운다</b>는 성질을 고정한다.
 *
 * <h3>왜 이 성질이 핵심인가</h3>
 * <p>추천이 하나 떠 있으면 사람은 그것을 "서버가 고른 답"으로 읽고 그대로 누르기 쉽다. 그래서 후보가
 * 유일할 때만 제시해야 한다 — 동점을 여럿 늘어놓으면 그중 하나가 눌리는 순간 이 안전장치는 자동
 * 확정과 같아지고, 저장된 뒤에는 어느 것이 짐작이었는지 구분할 수 없다.
 *
 * @design DOMAIN-017
 * @design API-205
 * @design AC-042
 */
class CategoryMatchSuggesterTest {

    private final LsLabelRepository labelRepository = mock(LsLabelRepository.class);
    private final EventTypeService eventTypeService = mock(EventTypeService.class);
    private final CategoryMatchSuggester suggester =
            new CategoryMatchSuggester(labelRepository, eventTypeService);

    private static LsLabel label(long id, String name) {
        LsLabel label = mock(LsLabel.class);
        when(label.getLabelId()).thenReturn(id);
        when(label.getLabelNm()).thenReturn(name);
        return label;
    }

    private CategoryMatchSuggester.Snapshot snapshotOf(List<LsLabel> labels, Map<String, String> eventTypes) {
        when(labelRepository.findByUseYnOrderBySortSeqAsc(anyString())).thenReturn(labels);
        when(eventTypeService.codeLabelMap()).thenReturn(eventTypes);
        return suggester.snapshot();
    }

    @Test
    @DisplayName("이름이_정확히_같은_라벨이_하나면_그_하나를_추천한다")
    void 이름이_정확히_같은_라벨이_하나면_그_하나를_추천한다() {
        CategoryMatchSuggester.Snapshot snapshot =
                snapshotOf(List.of(label(11L, "도로"), label(12L, "사람")), Map.of());

        List<CategoryMatchSuggester.Suggestion> suggestions =
                suggester.suggest(snapshot, LsOtsdCtgryMpng.MPNG_KND_LABEL, "도로");

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).targetId()).isEqualTo("11");
        assertThat(suggestions.get(0).targetName()).isEqualTo("도로");
    }

    @Test
    @DisplayName("대소문자와_공백만_다른_이름도_같은_이름으로_본다")
    void 대소문자와_공백만_다른_이름도_같은_이름으로_본다() {
        CategoryMatchSuggester.Snapshot snapshot =
                snapshotOf(List.of(label(21L, "Fire Hydrant")), Map.of());

        assertThat(suggester.suggest(snapshot, LsOtsdCtgryMpng.MPNG_KND_LABEL, "fire_hydrant"))
                .extracting(CategoryMatchSuggester.Suggestion::targetId)
                .containsExactly("21");
    }

    @Test
    @DisplayName("같은_이름_후보가_둘이면_비운다")
    void 같은_이름_후보가_둘이면_비운다() {
        CategoryMatchSuggester.Snapshot snapshot =
                snapshotOf(List.of(label(31L, "도로"), label(32L, "도 로")), Map.of());

        // 동점이면 서버가 고를 근거가 없다 — 사람이 고른다.
        assertThat(suggester.suggest(snapshot, LsOtsdCtgryMpng.MPNG_KND_LABEL, "도로")).isEmpty();
    }

    @Test
    @DisplayName("이름이_비슷한_후보가_하나뿐이면_추천하고_여럿이면_비운다")
    void 이름이_비슷한_후보가_하나뿐이면_추천하고_여럿이면_비운다() {
        CategoryMatchSuggester.Snapshot one =
                snapshotOf(List.of(label(41L, "도로 표면"), label(42L, "사람")), Map.of());
        assertThat(suggester.suggest(one, LsOtsdCtgryMpng.MPNG_KND_LABEL, "도로"))
                .extracting(CategoryMatchSuggester.Suggestion::targetId)
                .containsExactly("41");

        CategoryMatchSuggester.Snapshot many =
                snapshotOf(List.of(label(51L, "도로 표면"), label(52L, "도로 경계")), Map.of());
        assertThat(suggester.suggest(many, LsOtsdCtgryMpng.MPNG_KND_LABEL, "도로")).isEmpty();
    }

    @Test
    @DisplayName("걸리는_후보가_없으면_비운다")
    void 걸리는_후보가_없으면_비운다() {
        CategoryMatchSuggester.Snapshot snapshot =
                snapshotOf(List.of(label(61L, "사람")), Map.of());

        assertThat(suggester.suggest(snapshot, LsOtsdCtgryMpng.MPNG_KND_LABEL, "asphalt")).isEmpty();
    }

    @Test
    @DisplayName("한_글자는_아무_이름에나_걸리므로_품기_판정을_적용하지_않는다")
    void 한_글자는_아무_이름에나_걸리므로_품기_판정을_적용하지_않는다() {
        CategoryMatchSuggester.Snapshot snapshot =
                snapshotOf(List.of(label(71L, "물웅덩이")), Map.of());

        assertThat(suggester.suggest(snapshot, LsOtsdCtgryMpng.MPNG_KND_LABEL, "물")).isEmpty();
    }

    @Test
    @DisplayName("이벤트_유형_축은_유형_코드를_후보로_돌려준다")
    void 이벤트_유형_축은_유형_코드를_후보로_돌려준다() {
        Map<String, String> eventTypes = new LinkedHashMap<>();
        eventTypes.put("EV0100010101", "도로침수");
        eventTypes.put("EV0100010202", "화재");
        CategoryMatchSuggester.Snapshot snapshot = snapshotOf(List.of(label(81L, "도로침수")), eventTypes);

        List<CategoryMatchSuggester.Suggestion> suggestions =
                suggester.suggest(snapshot, LsOtsdCtgryMpng.MPNG_KND_EVNT_TYPE, "도로침수");

        // 라벨 축의 동명 라벨이 섞이면 안 된다 — 축마다 후보 묶음이 다르다.
        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).targetId()).isEqualTo("EV0100010101");
    }

    @Test
    @DisplayName("이름이_비어_있으면_비운다")
    void 이름이_비어_있으면_비운다() {
        CategoryMatchSuggester.Snapshot snapshot = snapshotOf(List.of(label(91L, "도로")), Map.of());

        assertThat(suggester.suggest(snapshot, LsOtsdCtgryMpng.MPNG_KND_LABEL, null)).isEmpty();
        assertThat(suggester.suggest(snapshot, LsOtsdCtgryMpng.MPNG_KND_LABEL, "   ")).isEmpty();
        assertThat(suggester.suggest(null, LsOtsdCtgryMpng.MPNG_KND_LABEL, "도로")).isEmpty();
    }

    @Test
    @DisplayName("이름이_없는_라벨은_후보에서_빠진다")
    void 이름이_없는_라벨은_후보에서_빠진다() {
        LsLabel nameless = mock(LsLabel.class);
        when(nameless.getLabelId()).thenReturn(101L);
        when(nameless.getLabelNm()).thenReturn(null);
        CategoryMatchSuggester.Snapshot snapshot = snapshotOf(List.of(nameless), Map.of());

        assertThat(suggester.suggest(snapshot, LsOtsdCtgryMpng.MPNG_KND_LABEL, "도로")).isEmpty();
    }
}
