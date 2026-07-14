package kr.co.cudo.authoring.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 (키포인트) — SKELETON 삼중값 [[x,y,v],...] 전용 직렬화 헬퍼 테스트.
 * 기존 2-튜플 {@link LabelPointSerializer} 와 분리된 경로(회귀 격리)를 검증한다.
 */
class KeypointSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static List<KeypointPoint> sample17() {
        List<KeypointPoint> kps = new ArrayList<>(17);
        for (int i = 0; i < 17; i++) {
            kps.add(new KeypointPoint(i * 10.5, i * 20.0, i % 3)); // v 는 0/1/2 순환
        }
        return kps;
    }

    @Test
    @DisplayName("KeypointSerializer_라운드트립_toJson_fromJson_동일")
    void roundTrip() {
        List<KeypointPoint> original = sample17();

        String json = KeypointSerializer.toJson(original, objectMapper);
        List<KeypointPoint> parsed = KeypointSerializer.fromJson(json, objectMapper);

        assertThat(parsed).containsExactlyElementsOf(original);
    }

    @Test
    @DisplayName("KeypointSerializer_toJson_삼중값_형식")
    void toJsonTripletFormat() {
        String json = KeypointSerializer.toJson(
                List.of(new KeypointPoint(1.5, 2.5, 2)), objectMapper);

        assertThat(json).isEqualTo("[[1.5,2.5,2]]");
    }

    @Test
    @DisplayName("KeypointSerializer_null_빈입력은_빈배열")
    void emptyInputs() {
        assertThat(KeypointSerializer.toJson(null, objectMapper)).isEqualTo("[]");
        assertThat(KeypointSerializer.toJson(List.of(), objectMapper)).isEqualTo("[]");
        assertThat(KeypointSerializer.fromJson(null, objectMapper)).isEmpty();
        assertThat(KeypointSerializer.fromJson("[]", objectMapper)).isEmpty();
    }

    @Test
    @DisplayName("KeypointSerializer_2튜플_입력은_형식위반_거부")
    void rejectsTwoTuple() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> KeypointSerializer.fromJson("[[1.0,2.0]]", objectMapper))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
