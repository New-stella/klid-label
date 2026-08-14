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
 * Phase 3 + Phase 6: LabelResponse.Item 의 trackId/lblSrcCd/autoLblYn 노출 검증.
 *
 * <p>V6 부터 autoLblYn/confScore/lblSrcCd 는 <b>라벨 행의 컬럼</b>에서 채워진다. 값이 없는
 * (= AI 가 만들지 않은) 라벨은 응답에서 autoLblYn='N'·lblSrcCd=null 로 나간다 — <b>응답 계약은
 * 흡수 전과 같고</b> 조달처만 바뀌었다.
 */
class LabelResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** V6 — 생산이력을 라벨 행에 부여한다(구 autoAiInfo(...) 동봉 대체). */
    private LsDataLbl withAiSource(LsDataLbl entity, String lblSrcCd) {
        entity.applyAiSource(lblSrcCd, BigDecimal.valueOf(0.9));
        return entity;
    }

    @Test
    @DisplayName("Item_from_은_entity_trackId_를_DTO_로_매핑")
    void itemFromMapsTrackId() {
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, null, "person", "[[10,10],[20,20]]", BigDecimal.valueOf(0.9), "track-7");

        // Phase 6: AiInfo 동봉 (자동 라벨 응답)
        LabelResponse.Item item = LabelResponse.Item.from(withAiSource(entity, LsDataLbl.SRC_YOLO), null, objectMapper);

        assertThat(item.trackId()).isEqualTo("track-7");
        assertThat(item.label()).isEqualTo("person");
        assertThat(item.autoLblYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("Item_trackId_null_엔티티는_DTO_trackId_도_null")
    void itemFromNullTrackId() {
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, null, "car", "[]", BigDecimal.valueOf(0.7), null);

        LabelResponse.Item item = LabelResponse.Item.from(entity, null, objectMapper);

        assertThat(item.trackId()).isNull();
    }

    @Test
    @DisplayName("Item_직렬화_JSON_에_trackId_필드_포함")
    void itemSerializesTrackId() throws Exception {
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, null, "person", "[[1,1],[2,2]]", BigDecimal.valueOf(0.8), "track-3");

        LabelResponse.Item item = LabelResponse.Item.from(entity, null, objectMapper);
        String json = objectMapper.writeValueAsString(item);
        ObjectNode node = (ObjectNode) objectMapper.readTree(json);

        assertThat(node.has("trackId")).isTrue();
        assertThat(node.get("trackId").asText()).isEqualTo("track-3");
    }

    @Test
    @DisplayName("LabelResponse_of_엔티티_리스트_경로_도_trackId_포함")
    void responseOfListIncludesTrackId() {
        LsDataLbl a = LsDataLbl.createAutoBbox(
                1L, null, "person", "[[1,1],[2,2]]", BigDecimal.valueOf(0.9), "track-1");
        LsDataLbl b = LsDataLbl.createAutoBbox(
                1L, null, "person", "[[3,3],[4,4]]", BigDecimal.valueOf(0.85), "track-2");

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
                1L, null, "person", "[0.0,0.0,10.0,10.0]", BigDecimal.ZERO, "track-7");

        // Phase 6: AiInfo (SRC_INTERPOLATE) 동봉 — 응답 lblSrcCd 는 AiInfo 의 값을 사용
        LabelResponse.Item item = LabelResponse.Item.from(withAiSource(entity, LsDataLbl.SRC_INTERPOLATE), null, objectMapper);

        assertThat(item.lblSrcCd()).isEqualTo(LsDataLbl.SRC_INTERPOLATE);
        assertThat(item.trackId()).isEqualTo("track-7");
        assertThat(item.autoLblYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("출처_미부여_자동라벨은_lblSrcCd_만_null_이고_autoLblYn_은_Y_다")
    void itemFromDetectedLblSrcCdNull() {
        // ⚠ V6 — 구 단언은 autoLblYn='N' 이었다. 흡수 전 이 경로는 <b>AiInfo 를 null 로 넘겼기 때문에</b>
        //   엔티티가 자동 라벨이어도 무조건 'N' 이 나왔다(값을 조달하지 않은 것이지 그 라벨이 수동이어서가
        //   아니다). 이제 라벨 행이 사실을 들고 있으므로 'Y' 가 맞다. "AI 가 만들지 않은 라벨은 응답이
        //   'N'" 이라는 계약은 아래 별도 케이스가 <수동 라벨>로 정확히 고정한다.
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, null, "person", "[[10,10],[20,20]]", BigDecimal.valueOf(0.9), "track-1");

        LabelResponse.Item item = LabelResponse.Item.from(entity, null, objectMapper);

        assertThat(item.lblSrcCd()).isNull();
        assertThat(item.autoLblYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("Item_직렬화_JSON_에_lblSrcCd_필드_포함")
    void itemSerializesLblSrcCd() throws Exception {
        LsDataLbl entity = LsDataLbl.createAutoInterpolatedBbox(
                1L, null, "person", "[0.0,0.0,10.0,10.0]", BigDecimal.ZERO, "track-7");

        LabelResponse.Item item = LabelResponse.Item.from(withAiSource(entity, LsDataLbl.SRC_INTERPOLATE), null, objectMapper);
        String json = objectMapper.writeValueAsString(item);
        ObjectNode node = (ObjectNode) objectMapper.readTree(json);

        assertThat(node.has("lblSrcCd")).isTrue();
        assertThat(node.get("lblSrcCd").asText()).isEqualTo(LsDataLbl.SRC_INTERPOLATE);
    }

    // --- Phase 2: labelId / labelName / color 필드 ---

    @Test
    @DisplayName("Item_from_은_LsLabel_있을_때_labelId_labelName_color_매핑")
    void itemFromMapsLsLabel() {
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, 7L, "person", "[[1,1],[2,2]]", BigDecimal.valueOf(0.9), "track-1");
        kr.co.cudo.authoring.label.entity.LsLabel master =
                kr.co.cudo.authoring.label.entity.LsLabel.create("person", "#E74C3C", "BBOX", 1, "seed");

        LabelResponse.Item item = LabelResponse.Item.from(withAiSource(entity, LsDataLbl.SRC_YOLO), master, objectMapper);

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

        LabelResponse.Item item = LabelResponse.Item.from(entity, null, objectMapper);

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

        LabelResponse.Item item = LabelResponse.Item.from(withAiSource(entity, LsDataLbl.SRC_YOLO), master, objectMapper);
        String json = objectMapper.writeValueAsString(item);
        ObjectNode node = (ObjectNode) objectMapper.readTree(json);

        assertThat(node.has("labelId")).isTrue();
        assertThat(node.has("labelName")).isTrue();
        assertThat(node.has("color")).isTrue();
        assertThat(node.get("labelName").asText()).isEqualTo("person");
        assertThat(node.get("color").asText()).isEqualTo("#E74C3C");
    }

    // --- V6: 흡수 후에도 응답 계약은 그대로다 (@req R4) ---

    @Test
    @DisplayName("라벨_응답_스키마가_변경되지_않는다")
    void itemSchemaIsUnchangedAfterAbsorption() {
        // 흡수는 <조달처>만 바꾼다 — 필드 이름·개수·순서·타입은 계약이며 외부 FE 팀이 쓴다.
        LsDataLbl entity = LsDataLbl.createAutoBbox(
                1L, 7L, "person", "[[1,1],[2,2]]", BigDecimal.valueOf(0.9), "track-1");
        LabelResponse.Item item = LabelResponse.Item.from(
                withAiSource(entity, LsDataLbl.SRC_YOLO), null, objectMapper);

        java.util.List<String> fields = new java.util.ArrayList<>();
        objectMapper.valueToTree(item).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly(
                "id", "lblTypeCd", "label", "labelId", "labelName", "color",
                "points", "autoLblYn", "confScore", "trackId", "lblSrcCd");
    }

    @Test
    @DisplayName("AI정보가_없는_라벨의_응답_자동라벨여부는_N이다")
    void manualLabelStillRespondsAutoNo() {
        // DB 축은 NULL 이고 응답 축은 'N' 이다 — 두 축을 각각 보존하는 것이 의도다.
        LsDataLbl manual = LsDataLbl.createManual(
                1L, LsDataLbl.TYPE_BBOX, null, "person", "[[1,1],[2,2]]", 100L);
        assertThat(manual.getAutoLblYn()).as("DB 축: 부재는 null 로 표현한다").isNull();
        assertThat(manual.getLblSrcCd()).isNull();

        LabelResponse.Item item = LabelResponse.Item.from(manual, null, objectMapper);
        assertThat(item.autoLblYn()).as("응답 축: 계약대로 N").isEqualTo("N");
        assertThat(item.confScore()).isNull();
        assertThat(item.lblSrcCd()).isNull();
    }

    @Test
    @DisplayName("보간_라벨의_출처는_INTERPOLATE_이다_구_INTERPOLATED_폐기")
    void interpolatedFactoryUsesPersistedSourceCode() {
        // 흡수 전 팩토리의 transient 값은 'INTERPOLATED'(과거분사)였으나 DB 에 닿지 않았고, 실제
        //   적재값·조회 술어는 'INTERPOLATE' 였다. 흡수로 그 transient 가 적재값이 되므로 값을 통일했다.
        LsDataLbl interpolated = LsDataLbl.createAutoInterpolatedBbox(
                1L, null, "person", "[0.0,0.0,10.0,10.0]", BigDecimal.ZERO, "track-7");
        assertThat(interpolated.getLblSrcCd()).isEqualTo("INTERPOLATE");
    }
}
