package kr.co.cudo.authoring.evntanno;

import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload;
import kr.co.cudo.authoring.evntanno.service.DescriptionSituationExtractor;
import kr.co.cudo.authoring.support.VlmKlidLiveFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DescriptionSituationExtractor} 단위 시험 — 묘사 전문에서 「상황」 라벨 줄만 뽑는 파서.
 *
 * <p>이 파서가 <b>외부 문구 형식에 붙는 유일한 지점</b>이라 형식 변형을 여기서 직접 겨눈다.
 * 라벨 목록에 의존하지 않는 성질(사업자가 항목을 늘려도 깨지지 않는다)도 함께 고정한다.
 */
class DescriptionSituationExtractorTest {

    /** 규격 콜백 예시와 같은 모양 — 항목 순서·개수는 규격이 정의하지 않는다. */
    private static final String SPEC_SHAPED =
            "- 장소: 주택가 골목\n- 날씨: 흐림\n- 상황: 불꽃은 확인되지 않음\n- 인원: 2명";

    @Test
    @DisplayName("규격_예시_형식에서_상황_줄의_값만_뽑는다")
    void extractsSituationValueFromSpecShapedDescription() {
        assertThat(DescriptionSituationExtractor.extract(SPEC_SHAPED))
                .contains("불꽃은 확인되지 않음");
    }

    @Test
    @DisplayName("상황_값은_그_줄의_줄바꿈까지만_담기고_다음_라벨_줄로_이어붙이지_않는다")
    void stopsAtLineBreakAndDoesNotSwallowNextLabelLine() {
        Optional<String> value = DescriptionSituationExtractor.extract(SPEC_SHAPED);

        assertThat(value).isPresent();
        assertThat(value.get()).doesNotContain("인원").doesNotContain("\n");
    }

    @Test
    @DisplayName("상황_라벨_줄이_없으면_빈_문자열이_아니라_비어있음을_돌려준다")
    void returnsEmptyWhenLabelMissing() {
        assertThat(DescriptionSituationExtractor.extract("- 장소: 주택가 골목\n- 날씨: 흐림"))
                .isEmpty();
    }

    @Test
    @DisplayName("모르는_항목이_늘어나도_상황_줄만_찾아_깨지지_않는다")
    void toleratesUnknownLabels() {
        String withNewLabels = "- 조도: 낮음\n- 소음: 있음\n- 상황: 연기 다량\n- 신규항목: 값";

        assertThat(DescriptionSituationExtractor.extract(withNewLabels)).contains("연기 다량");
    }

    @Test
    @DisplayName("같은_라벨이_여러_번_나오면_첫_번째_줄을_쓴다")
    void usesFirstOccurrenceForDeterminism() {
        String duplicated = "- 상황: 첫 번째\n- 날씨: 흐림\n- 상황: 두 번째";

        assertThat(DescriptionSituationExtractor.extract(duplicated)).contains("첫 번째");
    }

    @Test
    @DisplayName("선행공백_대시뒤_공백수_콜론_앞뒤_공백을_관용_처리한다")
    void toleratesSpacingVariants() {
        assertThat(DescriptionSituationExtractor.extract("   -    상황   :    연기 다량   "))
                .contains("연기 다량");
        assertThat(DescriptionSituationExtractor.extract("상황:연기 다량")).contains("연기 다량");
        assertThat(DescriptionSituationExtractor.extract("  상황 : 연기 다량")).contains("연기 다량");
    }

    @Test
    @DisplayName("개행이_CRLF_나_CR_이어도_줄을_가른다")
    void splitsOnAnyLineTerminator() {
        assertThat(DescriptionSituationExtractor.extract("- 날씨: 흐림\r\n- 상황: 연기 다량"))
                .contains("연기 다량");
        assertThat(DescriptionSituationExtractor.extract("- 날씨: 흐림\r- 상황: 연기 다량"))
                .contains("연기 다량");
    }

    @Test
    @DisplayName("상황_값이_비어있거나_공백뿐이면_미입력으로_다룬다")
    void treatsBlankValueAsMissing() {
        assertThat(DescriptionSituationExtractor.extract("- 상황:")).isEmpty();
        assertThat(DescriptionSituationExtractor.extract("- 상황:    ")).isEmpty();
        assertThat(DescriptionSituationExtractor.extract("- 상황: \n- 날씨: 흐림")).isEmpty();
    }

    @Test
    @DisplayName("다른_항목_값_속의_상황_문구는_라벨로_잡히지_않는다")
    void requiresLabelAtLineHead() {
        assertThat(DescriptionSituationExtractor.extract("- 날씨: 현재 상황: 흐림")).isEmpty();
    }

    @Test
    @DisplayName("상한을_넘는_값도_파서는_자르지_않고_원문_그대로_돌려준다")
    void doesNotTruncateOverLimitValue() {
        int overLimit = EventAnnotationPayload.MAX_COT_STEP + 10;
        String longValue = "가".repeat(overLimit);

        Optional<String> value = DescriptionSituationExtractor.extract("- 상황: " + longValue);

        assertThat(value).isPresent();
        assertThat(value.get()).hasSize(overLimit);
    }

    @Test
    @DisplayName("null_이나_공백_전문은_비어있음을_돌려준다")
    void returnsEmptyForBlankInput() {
        assertThat(DescriptionSituationExtractor.extract(null)).isEmpty();
        assertThat(DescriptionSituationExtractor.extract("")).isEmpty();
        assertThat(DescriptionSituationExtractor.extract("   \n  ")).isEmpty();
    }

    // ------------------------------------------------------- 사업자 실응답 원문 (2026-09-14)
    // 원문 출처·보존 규칙은 fixtures/vlm-klid-live-20260914/README.md. 사업자 형식이 바뀌면 여기가 먼저 깨진다.
    // [design: CDIAG-014] [design: INTSPEC-002]

    @Test
    @DisplayName("실응답_교통사고_묘사는_대시_접두가_없는_라벨_줄에서도_상황값을_원문_그대로_뽑는다")
    void extractsSituationFromLiveDescriptionWithoutDashPrefix() {
        String description = VlmKlidLiveFixtures.description(VlmKlidLiveFixtures.DESCRIBE_CAR_ACCIDENT);

        // 파서와 무관하게 원문 형식부터 고정한다 — 접두가 붙거나 라벨이 바뀌면 여기서 먼저 알린다.
        assertThat(description.split("\n", -1))
                .as("원문에 대시 접두 없는 「상황」 줄이 정확히 그 값으로 있어야 한다")
                .contains("상황: " + VlmKlidLiveFixtures.DESCRIBE_CAR_ACCIDENT_SITUATION);

        assertThat(DescriptionSituationExtractor.extract(description))
                .contains(VlmKlidLiveFixtures.DESCRIBE_CAR_ACCIDENT_SITUATION);
    }

    @Test
    @DisplayName("실응답_화재_묘사는_대시_접두가_있는_라벨_줄에서_상황값을_원문_그대로_뽑는다")
    void extractsSituationFromLiveDescriptionWithDashPrefix() {
        String description = VlmKlidLiveFixtures.description(VlmKlidLiveFixtures.DESCRIBE_FIRE);

        assertThat(description.split("\n", -1))
                .as("원문에 대시 접두가 붙은 「상황」 줄이 정확히 그 값으로 있어야 한다")
                .contains("- 상황: " + VlmKlidLiveFixtures.DESCRIBE_FIRE_SITUATION);

        assertThat(DescriptionSituationExtractor.extract(description))
                .contains(VlmKlidLiveFixtures.DESCRIBE_FIRE_SITUATION);
    }
}
