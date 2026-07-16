package kr.co.cudo.authoring.common.converter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link YesNoConverter} 단위 검증 — Boolean ↔ CHAR(1) 'Y'/'N' 왕복 + null 안전성.
 *
 * <p>Phase 4 (여부 도메인 CHAR(1) 전환): BOOLEAN 컬럼(BBOX_ENABLED/POLYGON_ENABLED)을
 * 여부C1(CHAR(1))로 통일하면서 엔티티 boolean 필드를 유지하기 위한 어댑터의 매핑 규칙을 고정한다.
 */
class YesNoConverterTest {

    private final YesNoConverter converter = new YesNoConverter();

    @Test
    @DisplayName("BOOLEAN_ENABLED_CHAR1_전환후_TRUE_Y_FALSE_N_왕복")
    void BOOLEAN_ENABLED_CHAR1_전환후_TRUE_Y_FALSE_N_왕복() {
        // given / when / then — TRUE ↔ 'Y'
        assertThat(converter.convertToDatabaseColumn(Boolean.TRUE)).isEqualTo("Y");
        assertThat(converter.convertToEntityAttribute("Y")).isTrue();

        // FALSE ↔ 'N'
        assertThat(converter.convertToDatabaseColumn(Boolean.FALSE)).isEqualTo("N");
        assertThat(converter.convertToEntityAttribute("N")).isFalse();
    }

    @Test
    @DisplayName("여부_컨버터_왕복_라운드트립_항등성")
    void 여부_컨버터_왕복_라운드트립_항등성() {
        // given / when / then — DB→엔티티→DB, 엔티티→DB→엔티티 모두 원값 보존
        for (boolean v : new boolean[] {true, false}) {
            String db = converter.convertToDatabaseColumn(v);
            assertThat(converter.convertToEntityAttribute(db)).isEqualTo(v);
        }
        for (String db : new String[] {"Y", "N"}) {
            Boolean attr = converter.convertToEntityAttribute(db);
            assertThat(converter.convertToDatabaseColumn(attr)).isEqualTo(db);
        }
    }

    @Test
    @DisplayName("여부_컨버터_null_은_양방향_null_로_통과")
    void 여부_컨버터_null_은_양방향_null_로_통과() {
        // given / when / then — NOT NULL 컬럼이라 정상경로 미발생이나, 미상값을 'N' 으로 왜곡하지 않음
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
