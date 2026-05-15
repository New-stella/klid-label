package kr.co.cudo.authoring.label.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 + Phase 6: LabelResponse.Item 의 trackId/lblSrcCd/autoLblYn 노출 검증.
 *
 * <p>Phase 6 부터 autoLblYn/confScore/lblSrcCd 는 LS_DATA_LBL_AI_INFO 에서 채워지며
 * AiInfo row 가 없는 라벨은 수동 라벨로 간주된다 (autoLblYn='N', lblSrcCd=null).
 */
class LabelResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LsDataLblAiInfo autoAiInfo(String lblSrcCd) {
        return LsDataLblAiInfo.create(1L, 0L, 100L, 1L, lblSrcCd, BigDecimal.valueOf(0.9), "batch");
    }

    @Test
    @DisplayName("Item_from_은_entity_trackId_를_DTO_로_매핑")
    void itemFromMapsTrackId() {
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, "person", "[[10,10],[20,20]]", BigDecimal.valueOf(0.9), "track-7");

        // Phase 6: AiInfo 동봉 (자동 라벨 응답)
        LabelResponse.Item item = LabelResponse.Item.from(entity, autoAiInfo(LsDataLblAiInfo.SRC_YOLO), objectMapper);

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

    // --- Phase 3: lblSrcCd 필드 ---

    @Test
    @DisplayName("Item_from_은_entity_lblSrcCd_INTERPOLATED_를_DTO_로_매핑")
    void itemFromMapsLblSrcCdInterpolated() {
        LsDataLbl entity = LsDataLbl.createAutoInterpolatedBbox(
                1L, "person", "[0.0,0.0,10.0,10.0]", BigDecimal.ZERO, "track-7");

        // Phase 6: AiInfo (SRC_INTERPOLATE) 동봉 — 응답 lblSrcCd 는 AiInfo 의 값을 사용
        LabelResponse.Item item = LabelResponse.Item.from(entity,
                autoAiInfo(LsDataLblAiInfo.SRC_INTERPOLATE), objectMapper);

        assertThat(item.lblSrcCd()).isEqualTo(LsDataLblAiInfo.SRC_INTERPOLATE);
        assertThat(item.trackId()).isEqualTo("track-7");
        assertThat(item.autoLblYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("Item_DETECTED_라벨은_lblSrcCd_null")
    void itemFromDetectedLblSrcCdNull() {
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, "person", "[[10,10],[20,20]]", BigDecimal.valueOf(0.9), "track-1");

        // Phase 6: 수동 라벨 (AiInfo null) — lblSrcCd 는 null
        LabelResponse.Item item = LabelResponse.Item.from(entity, null, objectMapper);

        assertThat(item.lblSrcCd()).isNull();
        assertThat(item.autoLblYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("Item_직렬화_JSON_에_lblSrcCd_필드_포함")
    void itemSerializesLblSrcCd() throws Exception {
        LsDataLbl entity = LsDataLbl.createAutoInterpolatedBbox(
                1L, "person", "[0.0,0.0,10.0,10.0]", BigDecimal.ZERO, "track-7");

        LabelResponse.Item item = LabelResponse.Item.from(entity,
                autoAiInfo(LsDataLblAiInfo.SRC_INTERPOLATE), objectMapper);
        String json = objectMapper.writeValueAsString(item);
        ObjectNode node = (ObjectNode) objectMapper.readTree(json);

        assertThat(node.has("lblSrcCd")).isTrue();
        assertThat(node.get("lblSrcCd").asText()).isEqualTo(LsDataLblAiInfo.SRC_INTERPOLATE);
    }

    // --- Phase 2: labelId / labelName / color 필드 ---

    @Test
    @DisplayName("Item_from_은_LsLabel_있을_때_labelId_labelName_color_매핑")
    void itemFromMapsLsLabel() {
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, 7L, "person", "[[1,1],[2,2]]", BigDecimal.valueOf(0.9), "track-1");
        kr.co.cudo.authoring.label.entity.LsLabel master =
                kr.co.cudo.authoring.label.entity.LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed");

        LabelResponse.Item item = LabelResponse.Item.from(entity,
                autoAiInfo(LsDataLblAiInfo.SRC_YOLO), master, objectMapper);

        assertThat(item.labelId()).isEqualTo(7L);
        assertThat(item.labelName()).isEqualTo("person");
        assertThat(item.color()).isEqualTo("#E74C3C");
        assertThat(item.label()).isEqualTo("person");  // 호환 필드
    }

    @Test
    @DisplayName("Item_from_은_LsLabel_null_일_때_labelName_color_null")
    void itemFromLsLabelNull() {
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, null, "person", "[[1,1],[2,2]]", BigDecimal.valueOf(0.9), null);

        LabelResponse.Item item = LabelResponse.Item.from(entity, null, null, objectMapper);

        assertThat(item.labelId()).isNull();
        assertThat(item.labelName()).isNull();
        assertThat(item.color()).isNull();
        assertThat(item.label()).isEqualTo("person");
    }

    @Test
    @DisplayName("Item_직렬화_JSON_에_labelId_labelName_color_필드_포함")
    void itemSerializesNewFields() throws Exception {
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, 7L, "person", "[[1,1]]", BigDecimal.valueOf(0.9), null);
        kr.co.cudo.authoring.label.entity.LsLabel master =
                kr.co.cudo.authoring.label.entity.LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed");

        LabelResponse.Item item = LabelResponse.Item.from(entity,
                autoAiInfo(LsDataLblAiInfo.SRC_YOLO), master, objectMapper);
        String json = objectMapper.writeValueAsString(item);
        ObjectNode node = (ObjectNode) objectMapper.readTree(json);

        assertThat(node.has("labelId")).isTrue();
        assertThat(node.has("labelName")).isTrue();
        assertThat(node.has("color")).isTrue();
        assertThat(node.get("labelName").asText()).isEqualTo("person");
        assertThat(node.get("color").asText()).isEqualTo("#E74C3C");
    }
}
