package kr.co.cudo.authoring.label.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3: LabelResponse.Item 의 trackId 필드 노출 검증.
 *
 * <p>API 응답이 trackId 를 포함해야 FE 가 자동 라벨링 트랙을 시각화할 수 있다.
 */
class LabelResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Item_from_은_entity_trackId_를_DTO_로_매핑")
    void itemFromMapsTrackId() {
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, "person", "[[10,10],[20,20]]", BigDecimal.valueOf(0.9), "track-7");

        LabelResponse.Item item = LabelResponse.Item.from(entity, objectMapper);

        assertThat(item.trackId()).isEqualTo("track-7");
        assertThat(item.label()).isEqualTo("person");
        assertThat(item.autoLblYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("Item_trackId_null_엔티티는_DTO_trackId_도_null")
    void itemFromNullTrackId() {
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, "car", "[]", BigDecimal.valueOf(0.7));

        LabelResponse.Item item = LabelResponse.Item.from(entity, objectMapper);

        assertThat(item.trackId()).isNull();
    }

    @Test
    @DisplayName("Item_직렬화_JSON_에_trackId_필드_포함")
    void itemSerializesTrackId() throws Exception {
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, "person", "[[1,1],[2,2]]", BigDecimal.valueOf(0.8), "track-3");

        LabelResponse.Item item = LabelResponse.Item.from(entity, objectMapper);
        String json = objectMapper.writeValueAsString(item);
        ObjectNode node = (ObjectNode) objectMapper.readTree(json);

        assertThat(node.has("trackId")).isTrue();
        assertThat(node.get("trackId").asText()).isEqualTo("track-3");
    }

    @Test
    @DisplayName("LabelResponse_of_엔티티_리스트_경로_도_trackId_포함")
    void responseOfListIncludesTrackId() {
        LsDataLbl a = LsDataLbl.createAutoBbox(
                1L, "person", "[[1,1],[2,2]]", BigDecimal.valueOf(0.9), "track-1");
        LsDataLbl b = LsDataLbl.createAutoBbox(
                1L, "person", "[[3,3],[4,4]]", BigDecimal.valueOf(0.85), "track-2");

        LabelResponse resp = LabelResponse.of(List.of(a, b), objectMapper);

        assertThat(resp.items()).hasSize(2);
        assertThat(resp.items().get(0).trackId()).isEqualTo("track-1");
        assertThat(resp.items().get(1).trackId()).isEqualTo("track-2");
    }
}
