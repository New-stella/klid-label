package kr.co.cudo.authoring.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 — Point 좌표를 [[x,y],[x,y],...] 의 JSON 배열로 양방향 직렬화하는 헬퍼 테스트.
 * - LS_DATA_LBL.POINTS_JSON 컬럼에 저장될 정규형은 중첩 배열 형식.
 * - Jackson 의 안전한 기본 모드만 사용 (enableDefaultTyping 금지 — CWE-502 방어).
 */
class LabelPointSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("LabelPointSerializer_Point_리스트_JSON_배열_직렬화")
    void serialize() {
        List<Point> points = List.of(new Point(1.5, 2.5), new Point(3.0, 4.0));

        String json = LabelPointSerializer.toJson(points, objectMapper);

        assertThat(json).isEqualTo("[[1.5,2.5],[3.0,4.0]]");
    }

    @Test
    @DisplayName("LabelPointSerializer_JSON_배열_Point_리스트_역직렬화")
    void deserialize() {
        String json = "[[10.0,20.0],[30.5,40.5]]";

        List<Point> points = LabelPointSerializer.fromJson(json, objectMapper);

        assertThat(points).containsExactly(new Point(10.0, 20.0), new Point(30.5, 40.5));
    }

    @Test
    @DisplayName("LabelPointSerializer_빈_배열_역직렬화시_빈_리스트")
    void deserializeEmpty() {
        assertThat(LabelPointSerializer.fromJson("[]", objectMapper)).isEmpty();
    }

    @Test
    @DisplayName("LabelPointSerializer_null_입력시_각각_null_빈_리스트")
    void nullSafe() {
        assertThat(LabelPointSerializer.toJson(null, objectMapper)).isEqualTo("[]");
        assertThat(LabelPointSerializer.fromJson(null, objectMapper)).isEmpty();
    }

    @Test
    @DisplayName("LabelPointSerializer_객체배열_x_y_레거시_포맷_호환_역직렬화")
    void deserializeObjectArrayLegacy() {
        String json = "[{\"x\":120,\"y\":80},{\"x\":340,\"y\":280}]";

        List<Point> points = LabelPointSerializer.fromJson(json, objectMapper);

        assertThat(points).containsExactly(new Point(120.0, 80.0), new Point(340.0, 280.0));
    }

    @Test
    @DisplayName("LabelPointSerializer_평탄_1차원_BBOX_4요소_호환_역직렬화")
    void deserializeFlatBBoxLegacy() {
        String json = "[165.0,210.0,385.0,430.0]";

        List<Point> points = LabelPointSerializer.fromJson(json, objectMapper);

        assertThat(points).containsExactly(new Point(165.0, 210.0), new Point(385.0, 430.0));
    }

    @Test
    @DisplayName("LabelPointSerializer_평탄_1차원_홀수_길이_예외")
    void deserializeFlatOddLengthThrows() {
        String json = "[1.0,2.0,3.0]";

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> LabelPointSerializer.fromJson(json, objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("짝수");
    }
}
